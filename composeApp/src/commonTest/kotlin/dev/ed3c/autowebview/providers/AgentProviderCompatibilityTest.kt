package dev.ed3c.autowebview.providers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentProviderCompatibilityTest {
    private val registry = AgentProviderRegistry()
    private val json = Json { encodeDefaults = true }

    @Test
    fun discoveryIsStableAndDoesNotGrantAuthority() {
        val discovered = registry.discover()

        assertEquals(listOf("hermes", "nemoclaw", "openclaw"), discovered.map { it.id })
        assertTrue(discovered.all { it.requiresLocalHitl })
        assertTrue(discovered.none { it.authorityCeiling == RemoteAuthorityCeiling.DIRECT_EXECUTION })
    }

    @Test
    fun hermesIsMcpFirstAndMayOnlyProposeTypedActions() {
        val accepted = registry.admit(
            ProviderCompatibilityRequest(
                providerId = "hermes",
                requiredProtocols = setOf(AgentProviderProtocol.MCP_CLIENT),
                requestedAuthority = RemoteAuthorityCeiling.PROPOSE_TYPED_ACTIONS,
            ),
        )

        val profile = assertIs<ProviderCompatibilityAdmission.Accepted>(accepted).profile
        assertEquals(AgentProviderKind.HERMES, profile.kind)
        assertEquals(AgentProviderRole.AGENT_RUNTIME, profile.role)
        assertTrue(AgentProviderProtocol.OPENAI_COMPATIBLE_API in profile.protocols)
        assertTrue(AgentProviderProtocol.MESSAGING_GATEWAY in profile.protocols)
    }

    @Test
    fun hermesDoesNotPretendToOwnNemoClawManagedMcp() {
        val rejected = registry.admit(
            ProviderCompatibilityRequest(
                providerId = "hermes",
                requiredProtocols = setOf(AgentProviderProtocol.MCP_STREAMABLE_HTTP_MANAGED),
                requestedAuthority = RemoteAuthorityCeiling.NONE,
            ),
        )

        assertEquals(
            ProviderCompatibilityRejectionReason.UNSUPPORTED_PROTOCOL,
            assertIs<ProviderCompatibilityAdmission.Rejected>(rejected).reason,
        )
    }

    @Test
    fun nemoClawIsAControlPlaneAndCannotAuthorizeApplicationActions() {
        val discovery = registry.admit(
            ProviderCompatibilityRequest(
                providerId = "nemoclaw",
                requiredProtocols = setOf(
                    AgentProviderProtocol.MCP_STREAMABLE_HTTP_MANAGED,
                    AgentProviderProtocol.NETWORK_POLICY,
                ),
                requestedAuthority = RemoteAuthorityCeiling.NONE,
            ),
        )
        assertIs<ProviderCompatibilityAdmission.Accepted>(discovery)

        val rejected = registry.admit(
            ProviderCompatibilityRequest(
                providerId = "nemoclaw",
                requiredProtocols = setOf(AgentProviderProtocol.SANDBOX_LIFECYCLE),
                requestedAuthority = RemoteAuthorityCeiling.PROPOSE_TYPED_ACTIONS,
            ),
        )
        assertEquals(
            ProviderCompatibilityRejectionReason.CONTROL_PLANE_CANNOT_AUTHORIZE_ACTIONS,
            assertIs<ProviderCompatibilityAdmission.Rejected>(rejected).reason,
        )
    }

    @Test
    fun directExecutionIsRejectedForEveryProvider() {
        for (provider in registry.discover()) {
            val rejected = registry.admit(
                ProviderCompatibilityRequest(
                    providerId = provider.id,
                    requiredProtocols = emptySet(),
                    requestedAuthority = RemoteAuthorityCeiling.DIRECT_EXECUTION,
                ),
            )
            assertEquals(
                ProviderCompatibilityRejectionReason.DIRECT_EXECUTION_FORBIDDEN,
                assertIs<ProviderCompatibilityAdmission.Rejected>(rejected).reason,
            )
        }
    }

    @Test
    fun unknownProviderFailsClosed() {
        val rejected = registry.admit(
            ProviderCompatibilityRequest(
                providerId = "unknown-runtime",
                requiredProtocols = emptySet(),
                requestedAuthority = RemoteAuthorityCeiling.NONE,
            ),
        )

        assertEquals(
            ProviderCompatibilityRejectionReason.UNKNOWN_PROVIDER,
            assertIs<ProviderCompatibilityAdmission.Rejected>(rejected).reason,
        )
    }

    @Test
    fun controlPlaneProfileCannotBeConstructedWithActionAuthority() {
        assertFailsWith<IllegalArgumentException> {
            AgentProviderProfile(
                id = "unsafe-control-plane",
                kind = AgentProviderKind.NEMOCLAW,
                role = AgentProviderRole.SANDBOX_CONTROL_PLANE,
                protocols = setOf(AgentProviderProtocol.SANDBOX_LIFECYCLE),
                authorityCeiling = RemoteAuthorityCeiling.PROPOSE_TYPED_ACTIONS,
                upstreamRepository = "example/unsafe",
            )
        }
    }

    @Test
    fun profilesRoundTripWithoutInventingMissingEvidence() {
        val openClaw = BuiltInAgentProviders.openClaw
        val encoded = json.encodeToString(openClaw)
        val decoded = json.decodeFromString(AgentProviderProfile.serializer(), encoded)

        assertEquals(openClaw, decoded)
        assertNull(decoded.observedUpstreamCommit)
    }
}
