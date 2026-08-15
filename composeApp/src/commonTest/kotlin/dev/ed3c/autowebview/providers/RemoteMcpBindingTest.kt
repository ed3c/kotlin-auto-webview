package dev.ed3c.autowebview.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RemoteMcpBindingTest {
    private val policy = RemoteMcpBindingPolicy()

    @Test
    fun hermesAcceptsSecretFreeHttpsRemoteMcpBinding() {
        val accepted = policy.admit(
            RemoteMcpConsumerBinding(
                id = "kmp-browser",
                consumerProviderId = "hermes",
                endpoint = "https://agent.example.invalid/mcp",
                authBoundary = RemoteMcpAuthBoundary.OAUTH,
                toolAllowlist = setOf("browser_read_context", "browser_propose_navigation"),
            ),
        )

        val result = assertIs<RemoteMcpBindingAdmission.Accepted>(accepted)
        assertEquals("https://agent.example.invalid", result.endpointOrigin)
    }

    @Test
    fun nemoClawRequiresAuthenticatedRemoteMcpBoundary() {
        val rejected = policy.admit(
            RemoteMcpConsumerBinding(
                id = "managed-kmp-browser",
                consumerProviderId = "nemoclaw",
                endpoint = "https://mcp.example.invalid/mcp",
                authBoundary = RemoteMcpAuthBoundary.NONE,
            ),
        )

        assertEquals(
            RemoteMcpBindingRejectionReason.AUTHENTICATED_TRANSPORT_REQUIRED,
            assertIs<RemoteMcpBindingAdmission.Rejected>(rejected).reason,
        )
    }

    @Test
    fun openClawPrivateStreamProfileDoesNotPretendToSupportRemoteMcp() {
        val rejected = policy.admit(
            RemoteMcpConsumerBinding(
                id = "wrong-protocol",
                consumerProviderId = "openclaw",
                endpoint = "https://mcp.example.invalid/mcp",
                authBoundary = RemoteMcpAuthBoundary.MTLS,
            ),
        )

        assertEquals(
            RemoteMcpBindingRejectionReason.PROVIDER_DOES_NOT_SUPPORT_REMOTE_MCP,
            assertIs<RemoteMcpBindingAdmission.Rejected>(rejected).reason,
        )
    }

    @Test
    fun endpointCredentialsQueryAndFragmentFailClosed() {
        val credentialed = policy.admit(
            RemoteMcpConsumerBinding(
                id = "credentialed",
                consumerProviderId = "hermes",
                endpoint = "https://user:secret@agent.example.invalid/mcp",
                authBoundary = RemoteMcpAuthBoundary.OAUTH,
            ),
        )
        assertEquals(
            RemoteMcpBindingRejectionReason.URL_CREDENTIALS_FORBIDDEN,
            assertIs<RemoteMcpBindingAdmission.Rejected>(credentialed).reason,
        )

        val query = policy.admit(
            RemoteMcpConsumerBinding(
                id = "query",
                consumerProviderId = "hermes",
                endpoint = "https://agent.example.invalid/mcp?token=secret",
                authBoundary = RemoteMcpAuthBoundary.OAUTH,
            ),
        )
        assertEquals(
            RemoteMcpBindingRejectionReason.URL_QUERY_FORBIDDEN,
            assertIs<RemoteMcpBindingAdmission.Rejected>(query).reason,
        )

        val fragment = policy.admit(
            RemoteMcpConsumerBinding(
                id = "fragment",
                consumerProviderId = "hermes",
                endpoint = "https://agent.example.invalid/mcp#secret",
                authBoundary = RemoteMcpAuthBoundary.OAUTH,
            ),
        )
        assertEquals(
            RemoteMcpBindingRejectionReason.URL_FRAGMENT_FORBIDDEN,
            assertIs<RemoteMcpBindingAdmission.Rejected>(fragment).reason,
        )
    }

    @Test
    fun plaintextRemoteMcpFailsClosed() {
        val rejected = policy.admit(
            RemoteMcpConsumerBinding(
                id = "plaintext",
                consumerProviderId = "hermes",
                endpoint = "http://agent.example.invalid/mcp",
                authBoundary = RemoteMcpAuthBoundary.NONE,
            ),
        )

        assertEquals(
            RemoteMcpBindingRejectionReason.HTTPS_REQUIRED,
            assertIs<RemoteMcpBindingAdmission.Rejected>(rejected).reason,
        )
    }
}
