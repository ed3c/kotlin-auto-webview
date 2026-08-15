package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurableMcpHttpReplayGuardTest {
    private val journalDirectory: Path = Files.createTempDirectory("mcp-replay-journal")

    @AfterTest
    fun cleanUp() {
        Files.walk(journalDirectory).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun anAdmittedDigestStaysSuppressedAfterARestart() = runBlocking {
        val journal = journalDirectory.resolve("replay.journal")
        val key = McpHttpReplayKey("a".repeat(64))

        assertEquals(McpHttpReplayDecision.ACCEPTED, guard(journal).admit(key, T0))
        // A new guard instance is what a listener or application restart actually looks like.
        assertEquals(McpHttpReplayDecision.DUPLICATE, guard(journal).admit(key, T0 + 1))
    }

    @Test
    fun suppressionEndsWhenTheWindowEnds() = runBlocking {
        val journal = journalDirectory.resolve("replay.journal")
        val key = McpHttpReplayKey("b".repeat(64))

        assertEquals(McpHttpReplayDecision.ACCEPTED, guard(journal).admit(key, T0))
        assertEquals(McpHttpReplayDecision.DUPLICATE, guard(journal).admit(key, T0 + WINDOW_MS - 1))
        assertEquals(McpHttpReplayDecision.ACCEPTED, guard(journal).admit(key, T0 + WINDOW_MS))
    }

    @Test
    fun distinctDigestsAreIndependent() = runBlocking {
        val journal = journalDirectory.resolve("replay.journal")
        val guard = guard(journal)

        assertEquals(McpHttpReplayDecision.ACCEPTED, guard.admit(McpHttpReplayKey("c".repeat(64)), T0))
        assertEquals(McpHttpReplayDecision.ACCEPTED, guard.admit(McpHttpReplayKey("d".repeat(64)), T0))
        assertEquals(McpHttpReplayDecision.DUPLICATE, guard.admit(McpHttpReplayKey("c".repeat(64)), T0))
    }

    @Test
    fun capacityIsBoundedAndFailsClosedRatherThanEvictingALiveEntry() = runBlocking {
        val journal = journalDirectory.resolve("replay.journal")
        val guard = DurableMcpHttpReplayGuard(journal, windowMs = WINDOW_MS, maxEntries = 2)

        assertEquals(McpHttpReplayDecision.ACCEPTED, guard.admit(McpHttpReplayKey("1".repeat(64)), T0))
        assertEquals(McpHttpReplayDecision.ACCEPTED, guard.admit(McpHttpReplayKey("2".repeat(64)), T0))
        assertEquals(
            McpHttpReplayDecision.CAPACITY_EXHAUSTED,
            guard.admit(McpHttpReplayKey("3".repeat(64)), T0),
        )
        // The first entry is still suppressed: capacity pressure never releases a live digest.
        assertEquals(McpHttpReplayDecision.DUPLICATE, guard.admit(McpHttpReplayKey("1".repeat(64)), T0))
    }

    @Test
    fun anUnusableStoreFailsClosedByDefaultAndDegradesOnlyWhenAsked() = runBlocking {
        // A directory where the journal file should be makes every store operation fail.
        val unusable = journalDirectory.resolve("blocked")
        Files.createDirectories(unusable)
        val key = McpHttpReplayKey("e".repeat(64))

        assertEquals(
            McpHttpReplayDecision.CAPACITY_EXHAUSTED,
            DurableMcpHttpReplayGuard(unusable, windowMs = WINDOW_MS).admit(key, T0),
        )

        val degrading = DurableMcpHttpReplayGuard(
            journal = unusable,
            windowMs = WINDOW_MS,
            failureMode = DurableMcpHttpReplayGuard.ReplayStoreFailureMode.DEGRADE_TO_MEMORY,
        )
        assertEquals(McpHttpReplayDecision.ACCEPTED, degrading.admit(key, T0))
        assertEquals(McpHttpReplayDecision.DUPLICATE, degrading.admit(key, T0))
    }

    @Test
    fun aTruncatedOrMalformedJournalLineIsNotTreatedAsALiveEntry() = runBlocking {
        val journal = journalDirectory.resolve("replay.journal")
        Files.write(
            journal,
            listOf("not-a-record", "f".repeat(64), "g".repeat(64) + " not-a-number"),
        )

        assertEquals(
            McpHttpReplayDecision.ACCEPTED,
            guard(journal).admit(McpHttpReplayKey("f".repeat(64)), T0),
        )
    }

    @Test
    fun theJournalContainsOnlyOpaqueDigestsAndExpiries() = runBlocking {
        val journal = journalDirectory.resolve("replay.journal")
        val key = semanticActionReplayKey(
            subjectId = "subject-that-must-not-appear",
            credentialEpoch = "epoch-that-must-not-appear",
            scheme = "https",
            authority = "mcp.example.test:8443",
            path = "/mcp",
            method = "tools/call",
            toolName = "browser_propose_navigation",
            arguments = buildJsonObject { put("url", "https://example.test/secret-path") },
        )

        assertEquals(McpHttpReplayDecision.ACCEPTED, guard(journal).admit(key, T0))

        val written = Files.readString(journal)
        assertTrue(key.value in written)
        for (secret in listOf(
            "subject-that-must-not-appear",
            "epoch-that-must-not-appear",
            "mcp.example.test",
            "secret-path",
            "browser_propose_navigation",
        )) {
            assertFalse(secret in written, "journal leaked $secret")
        }
    }

    private fun guard(journal: Path) = DurableMcpHttpReplayGuard(journal, windowMs = WINDOW_MS)

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val WINDOW_MS = 60_000L
    }
}
