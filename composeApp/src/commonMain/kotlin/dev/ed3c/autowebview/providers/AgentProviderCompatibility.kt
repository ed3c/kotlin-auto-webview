package dev.ed3c.autowebview.providers

import kotlinx.serialization.Serializable

@Serializable
enum class AgentProviderKind {
    OPENCLAW,
    HERMES,
    NEMOCLAW,
    DEEPSEEK_HARNESS,
}

@Serializable
enum class AgentProviderRole {
    AGENT_RUNTIME,
    PLUGIN_HARNESS,
    SANDBOX_CONTROL_PLANE,
}

@Serializable
enum class AgentProviderProtocol {
    ORDERED_PRIVATE_STREAM,
    MCP_CLIENT,
    MCP_STREAMABLE_HTTP_CLIENT,
    MCP_STREAMABLE_HTTP_MANAGED,
    OPENAI_COMPATIBLE_API,
    MESSAGING_GATEWAY,
    AGENT_SKILLS,
    CORDIS_PLUGIN_COMPOSITION,
    DYNAMIC_TOOL_REGISTRY,
    DURABLE_SESSION_EVENT_LOG,
    SANDBOX_LIFECYCLE,
    MANAGED_INFERENCE,
    NETWORK_POLICY,
}

@Serializable
enum class RemoteAuthorityCeiling {
    NONE,
    OBSERVE_AND_PROJECT,
    PROPOSE_TYPED_ACTIONS,
    DIRECT_EXECUTION,
}

@Serializable
data class AgentProviderProfile(
    val id: String,
    val kind: AgentProviderKind,
    val role: AgentProviderRole,
    val protocols: Set<AgentProviderProtocol>,
    val authorityCeiling: RemoteAuthorityCeiling,
    val requiresLocalHitl: Boolean = true,
    val upstreamRepository: String,
    val observedUpstreamCommit: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(upstreamRepository.isNotBlank())
        require(observedUpstreamCommit == null || observedUpstreamCommit.length == 40)
        require(protocols.isNotEmpty())
        require(authorityCeiling != RemoteAuthorityCeiling.DIRECT_EXECUTION) {
            "Remote providers cannot receive direct application execution authority"
        }
        require(requiresLocalHitl) {
            "Provider compatibility cannot bypass local HITL"
        }
        if (role == AgentProviderRole.PLUGIN_HARNESS) {
            require(AgentProviderProtocol.CORDIS_PLUGIN_COMPOSITION in protocols) {
                "Plugin harness profiles must declare their composition protocol"
            }
        }
        if (role == AgentProviderRole.SANDBOX_CONTROL_PLANE) {
            require(authorityCeiling == RemoteAuthorityCeiling.NONE) {
                "Sandbox control planes cannot carry application action authority"
            }
        }
    }
}

@Serializable
data class ProviderCompatibilityRequest(
    val providerId: String,
    val requiredProtocols: Set<AgentProviderProtocol>,
    val requestedAuthority: RemoteAuthorityCeiling,
)

@Serializable
enum class ProviderCompatibilityRejectionReason {
    UNKNOWN_PROVIDER,
    UNSUPPORTED_PROTOCOL,
    AUTHORITY_EXCEEDS_PROVIDER_CEILING,
    DIRECT_EXECUTION_FORBIDDEN,
    CONTROL_PLANE_CANNOT_AUTHORIZE_ACTIONS,
}

sealed interface ProviderCompatibilityAdmission {
    data class Accepted(val profile: AgentProviderProfile) : ProviderCompatibilityAdmission
    data class Rejected(val reason: ProviderCompatibilityRejectionReason) : ProviderCompatibilityAdmission
}

class AgentProviderRegistry(
    profiles: Collection<AgentProviderProfile> = BuiltInAgentProviders.all,
) {
    private val profilesById = profiles.associateBy { it.id }.also { indexed ->
        require(indexed.size == profiles.size) { "Provider IDs must be unique" }
    }

    fun discover(): List<AgentProviderProfile> = profilesById.values.sortedBy { it.id }

    fun find(providerId: String): AgentProviderProfile? = profilesById[providerId]

    fun admit(request: ProviderCompatibilityRequest): ProviderCompatibilityAdmission {
        if (request.requestedAuthority == RemoteAuthorityCeiling.DIRECT_EXECUTION) {
            return ProviderCompatibilityAdmission.Rejected(
                ProviderCompatibilityRejectionReason.DIRECT_EXECUTION_FORBIDDEN,
            )
        }

        val profile = profilesById[request.providerId]
            ?: return ProviderCompatibilityAdmission.Rejected(
                ProviderCompatibilityRejectionReason.UNKNOWN_PROVIDER,
            )

        if (!profile.protocols.containsAll(request.requiredProtocols)) {
            return ProviderCompatibilityAdmission.Rejected(
                ProviderCompatibilityRejectionReason.UNSUPPORTED_PROTOCOL,
            )
        }

        if (
            profile.role == AgentProviderRole.SANDBOX_CONTROL_PLANE &&
            request.requestedAuthority != RemoteAuthorityCeiling.NONE
        ) {
            return ProviderCompatibilityAdmission.Rejected(
                ProviderCompatibilityRejectionReason.CONTROL_PLANE_CANNOT_AUTHORIZE_ACTIONS,
            )
        }

        if (request.requestedAuthority.ordinal > profile.authorityCeiling.ordinal) {
            return ProviderCompatibilityAdmission.Rejected(
                ProviderCompatibilityRejectionReason.AUTHORITY_EXCEEDS_PROVIDER_CEILING,
            )
        }

        return ProviderCompatibilityAdmission.Accepted(profile)
    }
}

object BuiltInAgentProviders {
    val openClaw = AgentProviderProfile(
        id = "openclaw",
        kind = AgentProviderKind.OPENCLAW,
        role = AgentProviderRole.AGENT_RUNTIME,
        protocols = setOf(AgentProviderProtocol.ORDERED_PRIVATE_STREAM),
        authorityCeiling = RemoteAuthorityCeiling.PROPOSE_TYPED_ACTIONS,
        upstreamRepository = "openclaw/openclaw",
        observedUpstreamCommit = null,
    )

    val hermes = AgentProviderProfile(
        id = "hermes",
        kind = AgentProviderKind.HERMES,
        role = AgentProviderRole.AGENT_RUNTIME,
        protocols = setOf(
            AgentProviderProtocol.MCP_CLIENT,
            AgentProviderProtocol.OPENAI_COMPATIBLE_API,
            AgentProviderProtocol.MESSAGING_GATEWAY,
            AgentProviderProtocol.AGENT_SKILLS,
        ),
        authorityCeiling = RemoteAuthorityCeiling.PROPOSE_TYPED_ACTIONS,
        upstreamRepository = "NousResearch/hermes-agent",
        observedUpstreamCommit = "77be513de1da24610ebe8d1d4848228578c6bdf3",
    )

    val nemoClaw = AgentProviderProfile(
        id = "nemoclaw",
        kind = AgentProviderKind.NEMOCLAW,
        role = AgentProviderRole.SANDBOX_CONTROL_PLANE,
        protocols = setOf(
            AgentProviderProtocol.MCP_STREAMABLE_HTTP_MANAGED,
            AgentProviderProtocol.SANDBOX_LIFECYCLE,
            AgentProviderProtocol.MANAGED_INFERENCE,
            AgentProviderProtocol.NETWORK_POLICY,
        ),
        authorityCeiling = RemoteAuthorityCeiling.NONE,
        upstreamRepository = "NVIDIA/NemoClaw",
        observedUpstreamCommit = "815bdd563ce7c90f1144e6bdef3471a6f95b45c6",
    )

    val deepSeekHarness = AgentProviderProfile(
        id = "deepseek-harness",
        kind = AgentProviderKind.DEEPSEEK_HARNESS,
        role = AgentProviderRole.PLUGIN_HARNESS,
        protocols = setOf(
            AgentProviderProtocol.MCP_CLIENT,
            AgentProviderProtocol.MCP_STREAMABLE_HTTP_CLIENT,
            AgentProviderProtocol.CORDIS_PLUGIN_COMPOSITION,
            AgentProviderProtocol.DYNAMIC_TOOL_REGISTRY,
            AgentProviderProtocol.DURABLE_SESSION_EVENT_LOG,
        ),
        authorityCeiling = RemoteAuthorityCeiling.PROPOSE_TYPED_ACTIONS,
        upstreamRepository = "deepseek-ai/deepseek-harness",
        observedUpstreamCommit = "47f943859bef60e4160492346772ded9b24f765a",
    )

    val all: List<AgentProviderProfile> = listOf(
        openClaw,
        hermes,
        nemoClaw,
        deepSeekHarness,
    )
}
