package dev.ed3c.autowebview.providers

import io.ktor.http.Url
import kotlinx.serialization.Serializable

@Serializable
enum class RemoteMcpAuthBoundary {
    NONE,
    OAUTH,
    MTLS,
    EXTERNAL_SECRET_HEADER,
}

/**
 * Secret-free description of how an external agent/control plane consumes the
 * application's future remote MCP transport.
 *
 * The descriptor intentionally cannot carry bearer tokens, OAuth tokens,
 * client certificates, private keys, or arbitrary headers. Those remain in
 * the external runtime's admitted secret store.
 */
@Serializable
data class RemoteMcpConsumerBinding(
    val id: String,
    val consumerProviderId: String,
    val endpoint: String,
    val authBoundary: RemoteMcpAuthBoundary,
    val toolAllowlist: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank())
        require(consumerProviderId.isNotBlank())
        require(toolAllowlist.none(String::isBlank))
    }
}

@Serializable
enum class RemoteMcpBindingRejectionReason {
    UNKNOWN_PROVIDER,
    PROVIDER_DOES_NOT_SUPPORT_REMOTE_MCP,
    HTTPS_REQUIRED,
    URL_CREDENTIALS_FORBIDDEN,
    URL_QUERY_FORBIDDEN,
    URL_FRAGMENT_FORBIDDEN,
    AUTHENTICATED_TRANSPORT_REQUIRED,
}

sealed interface RemoteMcpBindingAdmission {
    data class Accepted(
        val binding: RemoteMcpConsumerBinding,
        val endpointOrigin: String,
    ) : RemoteMcpBindingAdmission

    data class Rejected(val reason: RemoteMcpBindingRejectionReason) : RemoteMcpBindingAdmission
}

class RemoteMcpBindingPolicy(
    private val providers: AgentProviderRegistry = AgentProviderRegistry(),
) {
    fun admit(binding: RemoteMcpConsumerBinding): RemoteMcpBindingAdmission {
        val profile = providers.find(binding.consumerProviderId)
            ?: return RemoteMcpBindingAdmission.Rejected(RemoteMcpBindingRejectionReason.UNKNOWN_PROVIDER)

        val supportsRemoteMcp =
            AgentProviderProtocol.MCP_CLIENT in profile.protocols ||
                AgentProviderProtocol.MCP_STREAMABLE_HTTP_MANAGED in profile.protocols
        if (!supportsRemoteMcp) {
            return RemoteMcpBindingAdmission.Rejected(
                RemoteMcpBindingRejectionReason.PROVIDER_DOES_NOT_SUPPORT_REMOTE_MCP,
            )
        }

        val url = Url(binding.endpoint)
        if (url.protocol.name != "https") {
            return RemoteMcpBindingAdmission.Rejected(RemoteMcpBindingRejectionReason.HTTPS_REQUIRED)
        }
        if (!url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) {
            return RemoteMcpBindingAdmission.Rejected(
                RemoteMcpBindingRejectionReason.URL_CREDENTIALS_FORBIDDEN,
            )
        }
        if (!url.parameters.isEmpty()) {
            return RemoteMcpBindingAdmission.Rejected(RemoteMcpBindingRejectionReason.URL_QUERY_FORBIDDEN)
        }
        if (url.fragment.isNotEmpty()) {
            return RemoteMcpBindingAdmission.Rejected(RemoteMcpBindingRejectionReason.URL_FRAGMENT_FORBIDDEN)
        }
        if (
            profile.kind == AgentProviderKind.NEMOCLAW &&
            binding.authBoundary == RemoteMcpAuthBoundary.NONE
        ) {
            return RemoteMcpBindingAdmission.Rejected(
                RemoteMcpBindingRejectionReason.AUTHENTICATED_TRANSPORT_REQUIRED,
            )
        }

        val defaultPort = when (url.protocol.name) {
            "https" -> 443
            else -> url.port
        }
        val explicitPort = if (url.port == defaultPort) "" else ":${url.port}"
        return RemoteMcpBindingAdmission.Accepted(
            binding = binding,
            endpointOrigin = "${url.protocol.name}://${url.host}$explicitPort",
        )
    }
}
