package dev.ed3c.autowebview.mcp.http

/**
 * Default-off Desktop-only configuration for a real loopback MCP HTTP listener.
 *
 * The bind host is intentionally not configurable. Runtime profiles may bind only to numeric
 * IPv4 loopback. Port 0 is available only through [forTest], preventing an accidental ephemeral
 * runtime endpoint that cannot be reconciled with the DeepSeek Harness Cordis binding.
 */
class DesktopMcpLoopbackServerConfig private constructor(
    val enabled: Boolean,
    val port: Int,
    val path: String,
    val backlog: Int,
    val workerThreads: Int,
    val queueCapacity: Int,
    allowedOrigins: Set<String>,
    val allowMissingOrigin: Boolean,
    val maxRequestBodyBytes: Int,
    val maxResponseBodyBytes: Int,
    val subjectId: String,
    val credentialEpoch: String,
    private val ephemeralPortAllowed: Boolean,
) {
    val allowedOrigins: Set<String> = allowedOrigins.toSet()

    init {
        val admittedPorts = if (ephemeralPortAllowed) 0..65_535 else 1..65_535
        require(port in admittedPorts) { "Desktop MCP port is outside the admitted range" }
        require(path.startsWith('/')) { "Desktop MCP path must be absolute" }
        require(path.none(Char::isISOControl)) { "Desktop MCP path contains control characters" }
        require('?' !in path && '#' !in path) {
            "Desktop MCP path cannot contain query or fragment data"
        }
        require(backlog in 1..1_024) { "Desktop MCP backlog is outside the admitted range" }
        require(workerThreads in 1..16) { "Desktop MCP worker count is outside the admitted range" }
        require(queueCapacity in 1..4_096) { "Desktop MCP queue capacity is outside the admitted range" }
        require(maxRequestBodyBytes in 1..MAX_BODY_BUDGET_BYTES) {
            "Desktop MCP request budget is outside the admitted range"
        }
        require(maxResponseBodyBytes in 1..MAX_BODY_BUDGET_BYTES) {
            "Desktop MCP response budget is outside the admitted range"
        }
        require(this.allowedOrigins.size <= MAX_ALLOWED_ORIGINS) {
            "Desktop MCP Origin allowlist is outside the admitted range"
        }
        require(this.allowedOrigins.all { origin ->
            origin.isNotBlank() &&
                origin.length <= MAX_ORIGIN_CHARACTERS &&
                origin.none(Char::isISOControl)
        }) {
            "Desktop MCP Origin allowlist contains an invalid value"
        }
        require(subjectId.matches(OPAQUE_ID_PATTERN)) { "Desktop MCP subject ID is invalid" }
        require(credentialEpoch.matches(OPAQUE_ID_PATTERN)) {
            "Desktop MCP credential epoch is invalid"
        }
    }

    override fun toString(): String =
        "DesktopMcpLoopbackServerConfig(enabled=$enabled, bind=127.0.0.1-only, port=$port, path=<redacted>, origins=<redacted>)"

    companion object {
        private const val MAX_BODY_BUDGET_BYTES = 4 * 1_024 * 1_024
        private const val MAX_ALLOWED_ORIGINS = 32
        private const val MAX_ORIGIN_CHARACTERS = 2_048
        private val OPAQUE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")

        fun runtime(
            enabled: Boolean = false,
            port: Int = 3_090,
            path: String = "/mcp",
            backlog: Int = 16,
            workerThreads: Int = 2,
            queueCapacity: Int = 32,
            allowedOrigins: Set<String> = emptySet(),
            allowMissingOrigin: Boolean = true,
            maxRequestBodyBytes: Int = 64 * 1_024,
            maxResponseBodyBytes: Int = 256 * 1_024,
            subjectId: String = "deepseek-harness-desktop",
            credentialEpoch: String = "desktop-loopback-v1",
        ): DesktopMcpLoopbackServerConfig = DesktopMcpLoopbackServerConfig(
            enabled = enabled,
            port = port,
            path = path,
            backlog = backlog,
            workerThreads = workerThreads,
            queueCapacity = queueCapacity,
            allowedOrigins = allowedOrigins,
            allowMissingOrigin = allowMissingOrigin,
            maxRequestBodyBytes = maxRequestBodyBytes,
            maxResponseBodyBytes = maxResponseBodyBytes,
            subjectId = subjectId,
            credentialEpoch = credentialEpoch,
            ephemeralPortAllowed = false,
        )

        internal fun forTest(
            port: Int = 0,
            path: String = "/mcp",
            allowedOrigins: Set<String> = emptySet(),
            allowMissingOrigin: Boolean = true,
            maxRequestBodyBytes: Int = 64 * 1_024,
            maxResponseBodyBytes: Int = 256 * 1_024,
        ): DesktopMcpLoopbackServerConfig = DesktopMcpLoopbackServerConfig(
            enabled = true,
            port = port,
            path = path,
            backlog = 4,
            workerThreads = 2,
            queueCapacity = 8,
            allowedOrigins = allowedOrigins,
            allowMissingOrigin = allowMissingOrigin,
            maxRequestBodyBytes = maxRequestBodyBytes,
            maxResponseBodyBytes = maxResponseBodyBytes,
            subjectId = "desktop-listener-test",
            credentialEpoch = "test-epoch-v1",
            ephemeralPortAllowed = true,
        )
    }
}
