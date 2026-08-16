package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Replay suppression that survives a restart and coordinates several admitted bridge nodes.
 *
 * The in-memory [BoundedMcpHttpReplayGuard] loses every admitted digest when the listener or the
 * application restarts, so a duplicate state-changing proposal replayed across that boundary was
 * indistinguishable from a first attempt. This guard keeps the same semantic digests from #43 in a
 * single append-only journal, and takes an exclusive file lock around every decision so two nodes
 * sharing that journal cannot both admit the same digest.
 *
 * Only the opaque digest, its expiry, and nothing else is written: the journal never contains a
 * subject, endpoint, credential, or tool argument.
 */
class DurableMcpHttpReplayGuard(
    private val journal: Path,
    private val windowMs: Long = 60_000,
    private val maxEntries: Int = 4_096,
    private val failureMode: ReplayStoreFailureMode = ReplayStoreFailureMode.FAIL_CLOSED,
) : McpHttpReplayGuard {
    private val mutex = Mutex()

    /** What to do when the durable store itself is unusable. */
    enum class ReplayStoreFailureMode {
        /**
         * Report capacity exhaustion, which the bridge turns into `503`.
         *
         * A store that cannot answer is not evidence that a proposal is new, so the request is
         * refused rather than admitted on an unverifiable assumption.
         */
        FAIL_CLOSED,

        /**
         * Fall back to in-process suppression only.
         *
         * Available for a deployment that would rather lose cross-restart suppression than
         * availability. It is never the default and it weakens the guarantee to that of
         * [BoundedMcpHttpReplayGuard].
         */
        DEGRADE_TO_MEMORY,
    }

    private val memoryFallback = BoundedMcpHttpReplayGuard(windowMs, maxEntries)

    init {
        require(windowMs > 0) { "Replay window must be positive" }
        require(maxEntries > 0) { "Replay capacity must be positive" }
    }

    override suspend fun admit(key: McpHttpReplayKey, nowEpochMs: Long): McpHttpReplayDecision =
        mutex.withLock {
            require(nowEpochMs >= 0) { "Replay time cannot be negative" }
            try {
                admitUnderFileLock(key, nowEpochMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Covers an unreadable journal, a full disk, and an overlapping in-process lock:
                // in every case this guard could not decide, which is not the same as "new".
                when (failureMode) {
                    ReplayStoreFailureMode.FAIL_CLOSED -> McpHttpReplayDecision.CAPACITY_EXHAUSTED
                    ReplayStoreFailureMode.DEGRADE_TO_MEMORY -> memoryFallback.admit(key, nowEpochMs)
                }
            }
        }

    override fun toString(): String =
        "DurableMcpHttpReplayGuard(journal=<redacted>, failureMode=$failureMode)"

    /**
     * One decision, taken while holding an exclusive OS lock on the journal.
     *
     * The lock — not the read — is what makes two nodes safe: a competing node blocks until this
     * decision has been durably appended, so the same digest can never be admitted twice.
     *
     * **Every access to the journal inside this block goes through [file].** POSIX record locks are
     * owned by the process and released when it closes *any* descriptor for the file, so a single
     * `Files.readAllLines(journal)` here would open a second descriptor, close it, and silently
     * drop the lock this function is holding — leaving every contender free to admit the same
     * digest while the code still reads as if it were serialised. That is not hypothetical: it is
     * how this guard originally shipped, and it is why the fix is a rule about descriptors rather
     * than a change of algorithm.
     */
    private fun admitUnderFileLock(key: McpHttpReplayKey, nowEpochMs: Long): McpHttpReplayDecision {
        journal.parent?.let(Files::createDirectories)
        RandomAccessFile(journal.toFile(), "rw").use { file ->
            file.channel.lock().use {
                val live = readLiveEntries(file, nowEpochMs)
                if (key.value in live) return McpHttpReplayDecision.DUPLICATE
                if (live.size >= maxEntries) return McpHttpReplayDecision.CAPACITY_EXHAUSTED

                val expiresAt = saturatingAdd(nowEpochMs, windowMs)
                // Compaction rewrites in place rather than swapping a temporary file in: replacing
                // the file would leave this lock attached to an unlinked inode, and a competing
                // node would then be locking a different file entirely.
                if (file.length() > COMPACTION_THRESHOLD_BYTES) {
                    val compacted = (live + (key.value to expiresAt))
                        .entries
                        .joinToString(separator = "") { (digest, entryExpiry) -> "$digest $entryExpiry\n" }
                    file.setLength(0)
                    file.seek(0)
                    file.write(compacted.encodeToByteArray())
                } else {
                    file.seek(file.length())
                    file.write("${key.value} $expiresAt\n".encodeToByteArray())
                }
                file.fd.sync()
                return McpHttpReplayDecision.ACCEPTED
            }
        }
    }

    /**
     * Read the journal through the already-locked descriptor.
     *
     * Taking [file] rather than the path is the whole point: see [admitUnderFileLock]. A malformed
     * or truncated line is dropped, never treated as a live entry.
     */
    private fun readLiveEntries(file: RandomAccessFile, nowEpochMs: Long): Map<String, Long> {
        val length = file.length()
        if (length == 0L) return emptyMap()
        check(length <= MAX_JOURNAL_BYTES) { "Replay journal is larger than the admitted ceiling" }

        val contents = ByteArray(length.toInt())
        file.seek(0)
        file.readFully(contents)

        val live = linkedMapOf<String, Long>()
        contents.decodeToString().lineSequence().forEach { line ->
            val digest = line.substringBefore(' ', missingDelimiterValue = "")
            val expiresAt = line.substringAfter(' ', missingDelimiterValue = "").toLongOrNull()
            if (digest.isNotEmpty() && expiresAt != null && expiresAt > nowEpochMs) {
                live[digest] = expiresAt
            }
        }
        return live
    }

    private companion object {
        const val COMPACTION_THRESHOLD_BYTES = 1L * 1_024 * 1_024

        /** Compaction keeps the journal near the threshold; well beyond it means something is wrong. */
        const val MAX_JOURNAL_BYTES = 64L * 1_024 * 1_024

        fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
