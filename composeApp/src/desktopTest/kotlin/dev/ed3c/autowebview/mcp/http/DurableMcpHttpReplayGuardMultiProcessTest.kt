package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-process evidence for `DurableMcpHttpReplayGuard`.
 *
 * The exclusive file lock is the entire multi-node mechanism, and it cannot be observed from one
 * JVM: two `FileChannel.lock()` calls on the same file in one process raise
 * `OverlappingFileLockException` rather than blocking. These tests therefore launch real JVMs from
 * the test runtime classpath and make them contend for the same digest at the same instant.
 */
class DurableMcpHttpReplayGuardMultiProcessTest {
    private val workspace: Path = Files.createTempDirectory("mcp-replay-multiprocess")

    @AfterTest
    fun cleanUp() {
        Files.walk(workspace).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun exactlyOneOfManyProcessesAdmitsAContendedDigest() {
        val journal = workspace.resolve("contended/replay.journal")
        val digest = "a".repeat(64)

        val decisions = contend(journal, digest, mode = "locked")

        assertEquals(
            CONTENDERS - 1,
            decisions.count { it == "DUPLICATE" },
            "expected every loser to see DUPLICATE, got $decisions; $diagnostics",
        )
        assertEquals(
            1,
            decisions.count { it == "ACCEPTED" },
            "exactly one process may admit a contended digest, got $decisions",
        )
        assertEquals(
            1,
            recordsFor(journal, digest),
            "the journal must hold exactly one record for the contended digest",
        )
    }

    @Test
    fun compactionUnderContentionKeepsLiveDigests() = runBlocking {
        val journal = workspace.resolve("compaction/replay.journal")
        val liveDigests = listOf("b".repeat(64), "c".repeat(64))
        val now = System.currentTimeMillis()

        // Push the journal past the 1 MiB compaction threshold with records that are already
        // expired, so compaction has something to drop while the live digests must survive it.
        Files.createDirectories(journal.parent)
        Files.newBufferedWriter(journal).use { writer ->
            repeat(20_000) { index ->
                writer.write("%064x %d%n".format(index, now - 60_000))
            }
            for (digest in liveDigests) {
                writer.write("$digest ${now + 600_000}\n")
            }
        }
        assertTrue(
            Files.size(journal) > 1L * 1_024 * 1_024,
            "fixture must exceed the compaction threshold to exercise it",
        )

        val decisions = contend(journal, "d".repeat(64), mode = "locked", nowEpochMs = now)

        assertEquals(1, decisions.count { it == "ACCEPTED" }, "got $decisions")
        assertTrue(
            Files.size(journal) < 1L * 1_024 * 1_024,
            "compaction should have dropped the expired records",
        )

        // The point of compacting in place under the held lock: no live digest may be lost.
        val guard = DurableMcpHttpReplayGuard(journal, windowMs = 600_000)
        for (digest in liveDigests) {
            assertEquals(
                McpHttpReplayDecision.DUPLICATE,
                guard.admit(McpHttpReplayKey(digest), now),
                "compaction lost a live digest",
            )
        }
    }

    @Test
    fun withoutTheLockTheHarnessObservesDoubleAdmission() {
        val journal = workspace.resolve("unlocked/replay.journal")
        val digest = "e".repeat(64)

        val decisions = contend(journal, digest, mode = "unlocked")

        // The negative control. If this ever reports a single ACCEPTED, the two tests above prove
        // nothing: they would be passing because the harness cannot see double admission at all.
        assertTrue(
            decisions.count { it == "ACCEPTED" } > 1,
            "the harness must be able to observe uncoordinated double admission, got $decisions",
        )
    }

    /** Raw contender output from the most recent [contend] call, for failure messages. */
    private var diagnostics: String = ""

    /** Launch [CONTENDERS] JVMs that all reach the journal at one shared instant. */
    private fun contend(
        journal: Path,
        digest: String,
        mode: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val startAt = System.currentTimeMillis() + STARTUP_GRACE_MS

        val processes = (1..CONTENDERS).map {
            ProcessBuilder(
                java,
                "-cp",
                classpath,
                "dev.ed3c.autowebview.mcp.http.ReplayGuardContender",
                journal.toString(),
                digest,
                nowEpochMs.toString(),
                startAt.toString(),
                mode,
            ).redirectErrorStream(true).start()
        }

        val outputs = processes.map { process ->
            // Streams are merged, so reading one to EOF cannot deadlock against a full pipe on the
            // other.
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(CONTENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("a contender did not finish within ${CONTENDER_TIMEOUT_SECONDS}s: $output")
            }
            check(process.exitValue() == 0) { "contender failed (${process.exitValue()}): $output" }
            output
        }
        diagnostics = outputs.joinToString(" | ") { it.replace("\n", " ") }
        return outputs.map { it.lines().last() }
    }

    private fun recordsFor(journal: Path, digest: String): Int =
        Files.readAllLines(journal).count { it.startsWith("$digest ") }

    private companion object {
        const val CONTENDERS = 4
        const val STARTUP_GRACE_MS = 3_000L
        const val CONTENDER_TIMEOUT_SECONDS = 60L
    }
}
