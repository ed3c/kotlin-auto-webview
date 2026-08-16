package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * At-rest custody for the listener credential.
 *
 * The store is an in-memory double here so the property under test — that a restart reuses the
 * issued value instead of minting a new one — is exercised on every platform. The real platform
 * backends and their absence semantics are covered by `McpHostKeyStoreTest`.
 */
class DesktopMcpCredentialPersistenceTest {
    private class RecordingKeyStore : McpHostKeyStore {
        val entries = linkedMapOf<String, ByteArray>()
        var deletions = 0

        override fun store(account: String, value: ByteArray) {
            entries[account] = value.copyOf()
        }

        override fun retrieve(account: String): ByteArray? = entries[account]?.copyOf()

        override fun delete(account: String) {
            deletions++
            entries.remove(account)
        }
    }

    @Test
    fun aRestartReusesTheIssuedValueInsteadOfMintingANewOne() = runBlocking {
        val store = RecordingKeyStore()

        val issued = lifecycle(store).use { it.issue(T0).use { bytes -> bytes.decodeToString() } }

        // A new lifecycle is what a restarted process actually looks like.
        lifecycle(store).use { restarted ->
            val restored = assertIs<DesktopMcpCredentialMaterial>(restarted.restore(T0))
            assertEquals("epoch-1", restored.epoch)
            assertEquals(issued, restored.use { it.decodeToString() })

            // And it verifies: the digest was rebuilt from the restored value.
            assertIs<McpHttpAuthenticationDecision.Accepted>(
                restarted.verify(input("Bearer $issued")),
            )
            Unit
        }
    }

    @Test
    fun theStoredEpochSurvivesSoReplayDomainsDoNotShiftAcrossARestart() = runBlocking {
        val store = RecordingKeyStore()

        lifecycle(store).use { first ->
            first.issue(T0).use { }
            first.rotate(T0).use { }
            assertEquals("epoch-2", first.activeEpoch)
        }

        lifecycle(store).use { restarted ->
            restarted.restore(T0)
            assertEquals("epoch-2", restarted.activeEpoch, "the restored epoch must be the stored one")

            // A later rotation continues past the restored ordinal rather than reusing it.
            restarted.rotate(T0).use { }
            assertEquals("epoch-3", restarted.activeEpoch)
        }
    }

    @Test
    fun revocationRemovesTheValueFromStorageSoARestartCannotUndoIt() = runBlocking {
        val store = RecordingKeyStore()

        val issued = lifecycle(store).use { live ->
            val value = live.issue(T0).use { it.decodeToString() }
            live.revoke()
            assertTrue(store.deletions > 0, "revocation must reach at-rest custody")
            value
        }

        lifecycle(store).use { restarted ->
            assertNull(restarted.restore(T0), "a revoked credential must not come back on restart")

            // Nothing is active, so the previously issued value is refused outright.
            assertEquals(
                McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
                assertIs<McpHttpAuthenticationDecision.Rejected>(
                    restarted.verify(input("Bearer $issued")),
                ).reason,
            )
        }
    }

    @Test
    fun nothingStoredIsADistinctAnswerFromACredentialBeingAvailable() = runBlocking {
        lifecycle(RecordingKeyStore()).use { fresh ->
            // `null` says "no credential was ever issued here", which is the caller's cue to
            // issue one. Returning a freshly minted credential instead would make a restart
            // indistinguishable from a first start.
            assertNull(fresh.restore(T0))
        }
    }

    @Test
    fun withoutAStoreCustodyStaysInProcessExactlyAsBefore() = runBlocking {
        lifecycle(keyStore = null).use { withoutStore ->
            assertNull(withoutStore.restore(T0), "no store means nothing to restore")
            val issued = withoutStore.issue(T0).use { it.decodeToString() }
            assertIs<McpHttpAuthenticationDecision.Accepted>(
                withoutStore.verify(input("Bearer $issued")),
            )
            Unit
        }
    }

    @Test
    fun aDamagedStoredRecordIsDiscardedRatherThanTrusted() = runBlocking {
        val store = RecordingKeyStore()
        store.store("$SUBJECT@$AUTHORITY", "not-a-valid-record".encodeToByteArray())

        lifecycle(store).use { restarted ->
            assertNull(restarted.restore(T0), "a damaged record is not a credential")
            assertTrue(store.deletions > 0, "the damaged record must be cleared, not left to fail again")

            // The next call is a clean issue rather than a repeated unexplained failure.
            val issued = restarted.issue(T0).use { it.decodeToString() }
            assertIs<McpHttpAuthenticationDecision.Accepted>(
                restarted.verify(input("Bearer $issued")),
            )
            Unit
        }
    }

    @Test
    fun aRotationReplacesTheStoredValueRatherThanAccumulating() = runBlocking {
        val store = RecordingKeyStore()

        lifecycle(store).use { live ->
            val first = live.issue(T0).use { it.decodeToString() }
            val second = live.rotate(T0).use { it.decodeToString() }
            assertNotEquals(first, second)
            assertEquals(1, store.entries.size, "one account must hold exactly one record")
            assertTrue(second in store.entries.values.single().decodeToString())
            assertTrue(first !in store.entries.values.single().decodeToString())
        }
    }

    private fun lifecycle(keyStore: McpHostKeyStore?) = DesktopMcpCredentialLifecycle(
        expectedScheme = "http",
        expectedAuthority = AUTHORITY,
        subjectId = SUBJECT,
        credentialLifetimeMillis = 600_000,
        handoverMillis = 5_000,
        keyStore = keyStore,
    )

    private fun input(authorizationHeader: String?) = McpHttpAuthenticationInput(
        authorizationHeader = authorizationHeader,
        scheme = "http",
        authority = AUTHORITY,
        nowEpochMs = T0,
    )

    private fun DesktopMcpCredentialMaterial.decodeToString(): String = use { it.decodeToString() }

    private companion object {
        const val AUTHORITY = "127.0.0.1:3090"
        const val SUBJECT = "desktop-persistence-test"
        const val T0 = 1_700_000_000_000L
    }
}
