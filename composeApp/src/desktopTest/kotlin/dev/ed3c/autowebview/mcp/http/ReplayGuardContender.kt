package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * One competing node, run as a separate JVM.
 *
 * A second process is not a convenience here, it is the only way to observe the mechanism:
 * two `FileChannel.lock()` calls on one file inside a single JVM raise
 * `OverlappingFileLockException` immediately rather than blocking, so an in-process test exercises
 * the error path and never the coordination path.
 *
 * Contenders spin until a shared wall-clock instant so they reach the journal together instead of
 * politely queueing.
 *
 * Arguments: `<journal> <digest> <nowEpochMs> <startAtEpochMs> <locked|unlocked>`
 * Prints the decision on stdout.
 */
object ReplayGuardContender {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 5) {
            "usage: <journal> <digest> <nowEpochMs> <startAtEpochMs> <locked|unlocked>"
        }
        val journal = Path.of(arguments[0])
        val digest = arguments[1]
        val nowEpochMs = arguments[2].toLong()
        val startAtEpochMs = arguments[3].toLong()
        val mode = arguments[4]

        while (System.currentTimeMillis() < startAtEpochMs) {
            Thread.onSpinWait()
        }

        val enteredAt = System.currentTimeMillis()
        val decision = when (mode) {
            "locked" -> runBlocking {
                DurableMcpHttpReplayGuard(journal, windowMs = WINDOW_MS)
                    .admit(McpHttpReplayKey(digest), nowEpochMs)
                    .name
            }
            "unlocked" -> admitWithoutCoordination(journal, digest, nowEpochMs)
            else -> error("unknown mode: $mode")
        }
        // Overlapping intervals across contenders mean the decisions were not serialised, which
        // distinguishes a coordination failure from contenders that simply never met.
        println("interval=$enteredAt..${System.currentTimeMillis()}")
        println(decision)
    }

    /**
     * The same decision without the exclusive lock — the negative control.
     *
     * This exists so the harness can be shown to detect double admission. The delay between read
     * and write makes the interleaving deterministic rather than merely likely: a control that
     * only sometimes goes red would leave the positive result resting on luck.
     */
    private fun admitWithoutCoordination(
        journal: Path,
        digest: String,
        nowEpochMs: Long,
    ): String {
        journal.parent?.let(Files::createDirectories)
        val live = if (Files.exists(journal)) {
            Files.readAllLines(journal).mapNotNull { line ->
                val recorded = line.substringBefore(' ', missingDelimiterValue = "")
                val expiresAt = line.substringAfter(' ', missingDelimiterValue = "").toLongOrNull()
                recorded.takeIf { it.isNotEmpty() && expiresAt != null && expiresAt > nowEpochMs }
            }.toSet()
        } else {
            emptySet()
        }
        if (digest in live) return McpHttpReplayDecision.DUPLICATE.name

        Thread.sleep(READ_WRITE_WINDOW_MS)

        RandomAccessFile(journal.toFile(), "rw").use { file ->
            file.seek(file.length())
            file.write("$digest ${nowEpochMs + WINDOW_MS}\n".encodeToByteArray())
        }
        return McpHttpReplayDecision.ACCEPTED.name
    }

    private const val WINDOW_MS = 600_000L
    private const val READ_WRITE_WINDOW_MS = 300L
}
