package dev.ed3c.autowebview.providers

import io.ktor.http.Url
import kotlinx.serialization.Serializable

/**
 * How DeepSeek Harness reaches the application's future Streamable HTTP MCP endpoint.
 *
 * REMOTE_HTTPS and LOOPBACK_HTTP both require a bearer token whose value is resolved by
 * DeepSeek Harness from an environment variable at runtime. LOOPBACK_HTTP is additionally
 * limited to explicit loopback development endpoints; loopback classification never disables
 * authentication because another local process may still reach the listener.
 */
@Serializable
enum class DeepSeekHarnessEndpointClass {
    REMOTE_HTTPS,
    LOOPBACK_HTTP,
}

@Serializable
data class DeepSeekHarnessReconnectPolicy(
    val enabled: Boolean = true,
    val initialDelayMs: Long = 500,
    val maximumDelayMs: Long = 30_000,
    val maximumAttempts: Int = 10,
) {
    init {
        require(initialDelayMs > 0) { "Reconnect initial delay must be positive" }
        require(maximumDelayMs >= initialDelayMs) {
            "Reconnect maximum delay cannot be smaller than the initial delay"
        }
        require(maximumAttempts > 0) { "Reconnect attempt budget must be positive" }
    }
}

/**
 * Secret-free configuration contract for a DeepSeek Harness Cordis MCP client row.
 *
 * This object accepts only an environment-variable name for authentication. It has no
 * field that can contain a bearer token, OAuth token, arbitrary header, private key, or
 * certificate bytes.
 */
@Serializable
data class DeepSeekHarnessCordisBinding(
    val id: String = "kotlin-auto-webview-mcp",
    val serverName: String = "kotlin_auto_webview",
    val endpoint: String,
    val endpointClass: DeepSeekHarnessEndpointClass,
    val bearerTokenEnvironmentVariable: String? = null,
    val toolCallTimeoutMs: Long = 60_000,
    val failOnStartupError: Boolean = true,
    val reconnect: DeepSeekHarnessReconnectPolicy = DeepSeekHarnessReconnectPolicy(),
) {
    init {
        require(ID_PATTERN.matches(id)) {
            "Cordis row id must match ${ID_PATTERN.pattern}"
        }
        require(SERVER_NAME_PATTERN.matches(serverName)) {
            "DeepSeek Harness serverName must match ${SERVER_NAME_PATTERN.pattern}"
        }
        require(toolCallTimeoutMs > 0) { "Tool-call timeout must be positive" }
        require(endpoint.none(Char::isISOControl)) { "Endpoint contains control characters" }
        require(validEnvironmentVariable(bearerTokenEnvironmentVariable)) {
            "MCP authentication requires a valid bearer-token environment variable"
        }

        val url = parseEndpoint(endpoint)
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) {
            "Credentials are forbidden in MCP endpoint URLs"
        }
        require(url.parameters.isEmpty()) { "Query parameters are forbidden in MCP endpoint URLs" }
        require(url.fragment.isEmpty()) { "Fragments are forbidden in MCP endpoint URLs" }

        when (endpointClass) {
            DeepSeekHarnessEndpointClass.REMOTE_HTTPS -> {
                require(url.protocol.name == "https") { "Remote MCP endpoints require HTTPS" }
            }
            DeepSeekHarnessEndpointClass.LOOPBACK_HTTP -> {
                require(url.protocol.name == "http") { "Loopback development endpoints require HTTP" }
                require(url.host.lowercase() in LOOPBACK_HOSTS) {
                    "Insecure HTTP is allowed only for an explicit loopback host"
                }
            }
        }
    }

    /**
     * Names that DeepSeek Harness exposes after its MCP client registers the current KMP tools.
     *
     * DeepSeek Harness owns generic normalization and hashing for arbitrary upstream tool names.
     * This compatibility layer deliberately emits only the two exact tool names currently owned
     * by [dev.ed3c.autowebview.mcp.BrowserMcpGateway], both of which already satisfy the upstream
     * function-name contract without normalization.
     */
    fun expectedPublicToolNames(): Map<String, String> = RAW_TOOL_NAMES.associateWith(::publicToolName)

    fun publicToolName(rawName: String): String {
        require(rawName in RAW_TOOL_NAMES) { "Tool is not part of the admitted KMP MCP surface" }
        val name = "mcp__${serverName}__${rawName}"
        require(name.length <= MAX_PUBLIC_TOOL_NAME_LENGTH) {
            "Server-qualified tool name exceeds the DeepSeek Harness function-name budget"
        }
        return name
    }

    /**
     * Render one opt-in Cordis patch row for `@deepseek-ai/dsh-mcp-client`.
     *
     * The result is deterministic for the same value object. The bearer token itself is resolved
     * by the DeepSeek Harness host from the named environment variable and is never returned here.
     */
    fun renderCordisPatch(): String {
        val variable = requireNotNull(bearerTokenEnvironmentVariable)
        val authLines = listOf(
            "        headers:",
            "          Authorization: !!js >-",
            "            (() => { const token = process.env.$variable?.trim(); if (!token) throw new Error('$variable is required'); return `Bearer \${token}`; })()",
        )

        return buildList {
            add("# Default-off DeepSeek Harness compatibility row.")
            add("# Evidence subject: deepseek-ai/deepseek-harness@${BuiltInAgentProviders.deepSeekHarness.observedUpstreamCommit}")
            add("- insert:")
            add("    - id: ${yamlQuote(id)}")
            add("      name: '@deepseek-ai/dsh-mcp-client'")
            add("      config:")
            add("        serverName: ${yamlQuote(serverName)}")
            add("        transport: streamable-http")
            add("        url: ${yamlQuote(endpoint)}")
            addAll(authLines)
            add("        toolCallTimeoutMs: $toolCallTimeoutMs")
            add("        failOnStartupError: $failOnStartupError")
            add("        reconnect:")
            add("          enabled: ${reconnect.enabled}")
            add("          initialDelayMs: ${reconnect.initialDelayMs}")
            add("          maxDelayMs: ${reconnect.maximumDelayMs}")
            add("          maxAttempts: ${reconnect.maximumAttempts}")
        }.joinToString(separator = "\n", postfix = "\n")
    }

    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val SERVER_NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")
        private val ENVIRONMENT_VARIABLE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private val RAW_TOOL_NAMES = linkedSetOf(
            "browser_capture_context",
            "browser_propose_navigation",
        )
        private const val MAX_PUBLIC_TOOL_NAME_LENGTH = 64

        private fun parseEndpoint(endpoint: String): Url = runCatching { Url(endpoint) }
            .getOrElse { failure ->
                throw IllegalArgumentException("Invalid MCP endpoint URL", failure)
            }

        private fun validEnvironmentVariable(value: String?): Boolean =
            value != null && ENVIRONMENT_VARIABLE_PATTERN.matches(value)

        private fun yamlQuote(value: String): String = "'${value.replace("'", "''")}'"
    }
}
