package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopMcpCredentialLifecycleTest {
    @Test
    fun issuesHighEntropyCredentialAndAcceptsOnlyThatCredential() = runBlocking {
        lifecycle().use { credentials ->
            val token = credentials.issue(T0).consume()

            assertEquals(DesktopMcpCredentialState.READY, credentials.state)
            assertTrue(token.length >= 32, "issued credential is too short")
            assertTrue(token.all { it.code in 0x21..0x7e }, "issued credential is not printable ASCII")

            val accepted = assertIs<McpHttpAuthenticationDecision.Accepted>(
                credentials.verify(input("Bearer $token")),
            )
            assertEquals("epoch-1", accepted.credentialEpoch)
            assertEquals(SUBJECT, accepted.subjectId)

            assertEquals(
                McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
                rejection(credentials.verify(input("Bearer ${token.reversed()}"))),
            )
            assertEquals(
                McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS,
                rejection(credentials.verify(input(null))),
            )
        }
    }

    @Test
    fun distinctIssuancesDoNotRepeatCredentialMaterial() = runBlocking {
        val first = lifecycle().use { it.issue(T0).consume() }
        val second = lifecycle().use { it.issue(T0).consume() }
        assertNotEquals(first, second)
    }

    @Test
    fun rotationKeepsThePriorEpochVerifiableOnlyInsideTheHandoverWindow() = runBlocking {
        lifecycle().use { credentials ->
            val previous = credentials.issue(T0).consume()
            val current = credentials.rotate(T0).consume()

            assertEquals(DesktopMcpCredentialState.ROTATING, credentials.state)
            assertEquals("epoch-2", credentials.activeEpoch)

            assertEquals(
                "epoch-1",
                assertIs<McpHttpAuthenticationDecision.Accepted>(
                    credentials.verify(input("Bearer $previous", now = T0 + HANDOVER_MS)),
                ).credentialEpoch,
            )
            assertEquals(
                McpHttpAuthenticationRejectionReason.EXPIRED_CREDENTIALS,
                rejection(credentials.verify(input("Bearer $previous", now = T0 + HANDOVER_MS + 1))),
            )
            assertEquals(
                "epoch-2",
                assertIs<McpHttpAuthenticationDecision.Accepted>(
                    credentials.verify(input("Bearer $current", now = T0 + HANDOVER_MS + 1)),
                ).credentialEpoch,
            )
        }
    }

    @Test
    fun onlyOneSupersededEpochStaysVerifiable() = runBlocking {
        lifecycle().use { credentials ->
            val first = credentials.issue(T0).consume()
            credentials.rotate(T0).consume()
            credentials.rotate(T0).consume()

            assertEquals(
                McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
                rejection(credentials.verify(input("Bearer $first"))),
            )
        }
    }

    @Test
    fun activeCredentialExpiresAtItsOwnLifetime() = runBlocking {
        lifecycle().use { credentials ->
            val token = credentials.issue(T0).consume()

            assertIs<McpHttpAuthenticationDecision.Accepted>(
                credentials.verify(input("Bearer $token", now = T0 + LIFETIME_MS)),
            )
            assertEquals(
                McpHttpAuthenticationRejectionReason.EXPIRED_CREDENTIALS,
                rejection(credentials.verify(input("Bearer $token", now = T0 + LIFETIME_MS + 1))),
            )
        }
    }

    @Test
    fun revocationImmediatelyRejectsEveryIssuedEpoch() = runBlocking {
        lifecycle().use { credentials ->
            val previous = credentials.issue(T0).consume()
            val current = credentials.rotate(T0).consume()

            credentials.revoke()

            assertEquals(DesktopMcpCredentialState.REVOKED, credentials.state)
            for (token in listOf(previous, current)) {
                assertEquals(
                    McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
                    rejection(credentials.verify(input("Bearer $token"))),
                )
            }
            assertFailsWith<IllegalStateException> { credentials.rotate(T0) }
            Unit
        }
    }

    @Test
    fun credentialIsScopedToTheExactAdmittedEndpoint() = runBlocking {
        lifecycle().use { credentials ->
            val token = credentials.issue(T0).consume()

            assertEquals(
                McpHttpAuthenticationRejectionReason.INSUFFICIENT_SCOPE,
                rejection(credentials.verify(input("Bearer $token", authority = "127.0.0.1:3091"))),
            )
            assertEquals(
                McpHttpAuthenticationRejectionReason.INSUFFICIENT_SCOPE,
                rejection(credentials.verify(input("Bearer $token", scheme = "https"))),
            )
        }
    }

    @Test
    fun materialIsConsumableOnceAndClosingErasesCustody() = runBlocking {
        val credentials = lifecycle()
        val material = credentials.issue(T0)
        val token = material.consume()

        assertFailsWith<IllegalStateException> { material.use { it } }
        assertFalse(token in material.toString())
        assertFalse(token in credentials.toString())

        credentials.close()

        assertEquals(DesktopMcpCredentialState.CLOSED, credentials.state)
        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(credentials.verify(input("Bearer $token"))),
        )
        assertFailsWith<IllegalStateException> { credentials.issue(T0) }
        Unit
    }

    @Test
    fun rotationRequiresAnIssuedCredentialFirst() {
        lifecycle().use { credentials ->
            assertEquals(DesktopMcpCredentialState.UNINITIALIZED, credentials.state)
            assertFailsWith<IllegalStateException> { credentials.rotate(T0) }
        }
    }

    private fun lifecycle() = DesktopMcpCredentialLifecycle(
        expectedScheme = "http",
        expectedAuthority = AUTHORITY,
        subjectId = SUBJECT,
        credentialLifetimeMillis = LIFETIME_MS,
        handoverMillis = HANDOVER_MS,
    )

    private fun DesktopMcpCredentialMaterial.consume(): String = use { it.decodeToString() }

    private fun input(
        authorizationHeader: String?,
        now: Long = T0,
        scheme: String = "http",
        authority: String = AUTHORITY,
    ) = McpHttpAuthenticationInput(
        authorizationHeader = authorizationHeader,
        scheme = scheme,
        authority = authority,
        nowEpochMs = now,
    )

    private fun rejection(decision: McpHttpAuthenticationDecision) =
        assertIs<McpHttpAuthenticationDecision.Rejected>(decision).reason

    private companion object {
        const val AUTHORITY = "127.0.0.1:3090"
        const val SUBJECT = "desktop-credential-test"
        const val T0 = 1_700_000_000_000L
        const val LIFETIME_MS = 60_000L
        const val HANDOVER_MS = 5_000L
    }
}
