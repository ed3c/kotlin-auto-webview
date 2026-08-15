package dev.ed3c.autowebview.mcp.http

/**
 * Framework-neutral HTTP request presented to the MCP admission bridge.
 *
 * A concrete server adapter obtains [scheme], [authority], [path], [query], and headers from its
 * trusted HTTP implementation. The bridge never derives route authority from the JSON-RPC body.
 */
data class McpHttpBridgeRequest(
    val method: String,
    val scheme: String,
    val authority: String,
    val path: String,
    val query: String? = null,
    val headers: Map<String, List<String>>,
    val body: String,
    val declaredContentLength: Long? = null,
)

/** Exact endpoint identity and body budgets for one mounted MCP route. */
data class McpHttpEndpointPolicy(
    val scheme: String,
    val authority: String,
    val path: String = "/mcp",
    val allowedOrigins: Set<String> = emptySet(),
    val allowMissingOrigin: Boolean = true,
    val maxRequestBodyBytes: Int = 64 * 1024,
    val maxResponseBodyBytes: Int = 256 * 1024,
) {
    internal val normalizedScheme: String = normalizeMcpHttpScheme(scheme)
    internal val normalizedAuthority: String = normalizeMcpHttpAuthority(authority)
    internal val normalizedOrigins: Set<String> =
        allowedOrigins.mapTo(linkedSetOf(), ::normalizeMcpHttpOrigin)

    init {
        require(path.startsWith('/')) { "MCP endpoint path must be absolute" }
        require(path.none(Char::isISOControl)) { "MCP endpoint path contains control characters" }
        require('?' !in path && '#' !in path) {
            "MCP endpoint path cannot contain query or fragment data"
        }
        require(maxRequestBodyBytes > 0) { "MCP request body budget must be positive" }
        require(maxResponseBodyBytes > 0) { "MCP response body budget must be positive" }
        require(normalizedScheme == "https" || isMcpHttpLoopbackAuthority(normalizedAuthority)) {
            "Plain HTTP MCP endpoints are allowed only on explicit loopback authorities"
        }
        require(allowMissingOrigin || normalizedOrigins.isNotEmpty()) {
            "At least one exact Origin is required when missing Origin is denied"
        }
    }
}

data class McpHttpAuthenticationInput(
    val authorizationHeader: String?,
    val scheme: String,
    val authority: String,
)

enum class McpHttpAuthenticationRejectionReason {
    MISSING_CREDENTIALS,
    INVALID_CREDENTIALS,
    EXPIRED_CREDENTIALS,
    INSUFFICIENT_SCOPE,
}

sealed interface McpHttpAuthenticationDecision {
    data class Accepted(
        /** Opaque stable identifier; never copied into bridge receipts. */
        val subjectId: String,
        /** Changes when credentials rotate so old replay keys leave the active credential epoch. */
        val credentialEpoch: String,
    ) : McpHttpAuthenticationDecision {
        init {
            require(subjectId.isNotBlank() && subjectId.length <= 128)
            require(credentialEpoch.isNotBlank() && credentialEpoch.length <= 128)
            require(subjectId.none(Char::isISOControl))
            require(credentialEpoch.none(Char::isISOControl))
        }
    }

    data class Rejected(
        val reason: McpHttpAuthenticationRejectionReason,
    ) : McpHttpAuthenticationDecision
}

fun interface McpHttpAuthenticationVerifier {
    /** Verify credentials without retaining or logging the raw Authorization value. */
    suspend fun verify(input: McpHttpAuthenticationInput): McpHttpAuthenticationDecision
}

data class McpHttpReplayKey(val value: String)

enum class McpHttpReplayDecision {
    ACCEPTED,
    DUPLICATE,
    CAPACITY_EXHAUSTED,
}

fun interface McpHttpReplayGuard {
    suspend fun admit(key: McpHttpReplayKey, nowEpochMs: Long): McpHttpReplayDecision
}

fun interface McpJsonRpcGateway {
    suspend fun handle(payload: String): String
}

enum class McpHttpSideEffectState {
    NOT_STARTED,
    NOT_APPLICABLE,
    PROPOSAL_ONLY,
    UNKNOWN,
}

enum class McpHttpBridgeOutcome {
    REJECTED,
    NOTIFICATION_ACCEPTED,
    RESPONSE_RETURNED,
    GATEWAY_FAILURE,
    CANCELLED_OR_TIMED_OUT,
}

enum class McpHttpBridgeErrorCode(
    val status: Int,
    val jsonRpcCode: Int,
    val safeMessage: String,
) {
    METHOD_NOT_ALLOWED(405, -32600, "Only HTTP POST is supported"),
    ENDPOINT_MISMATCH(404, -32600, "MCP endpoint does not match the admitted route"),
    QUERY_FORBIDDEN(400, -32600, "MCP endpoint query data is forbidden"),
    HEADER_INVALID(400, -32600, "An HTTP header is invalid"),
    DUPLICATE_HEADER(400, -32600, "A singleton HTTP header was repeated"),
    ORIGIN_REJECTED(403, -32600, "Request Origin is not admitted"),
    BODY_LENGTH_INVALID(400, -32600, "Content length is invalid"),
    BODY_TOO_LARGE(413, -32600, "MCP request body exceeds the configured limit"),
    CONTENT_TYPE_REQUIRED(415, -32600, "Content-Type application/json is required"),
    ACCEPT_REQUIRED(406, -32600, "Accept must include application/json and text/event-stream"),
    SESSION_MODE_UNSUPPORTED(400, -32600, "Protocol-level HTTP sessions are not supported"),
    AUTHENTICATION_REQUIRED(401, -32001, "Authentication is required"),
    AUTHENTICATION_REJECTED(403, -32001, "Authentication was rejected"),
    AUTHENTICATION_UNAVAILABLE(503, -32603, "Authentication verifier is unavailable"),
    MALFORMED_JSON(400, -32700, "Malformed JSON"),
    INVALID_JSON_RPC(400, -32600, "Invalid JSON-RPC request"),
    RPC_METHOD_NOT_ADMITTED(400, -32601, "JSON-RPC method is not admitted"),
    TOOL_NOT_ADMITTED(403, -32602, "MCP tool is not admitted"),
    PROTOCOL_VERSION_UNSUPPORTED(400, -32600, "MCP protocol version is not supported"),
    REQUEST_HEADER_MISMATCH(400, -32600, "MCP request metadata headers do not match the body"),
    REPLAY_DETECTED(409, -32009, "Duplicate action-bearing MCP request rejected"),
    REPLAY_CAPACITY_EXHAUSTED(503, -32010, "Replay guard capacity is exhausted"),
    REPLAY_GUARD_UNAVAILABLE(503, -32603, "Replay guard is unavailable"),
    GATEWAY_RESPONSE_INVALID(502, -32603, "MCP gateway returned an invalid response"),
    GATEWAY_FAILURE(502, -32603, "MCP gateway failed"),
    INTERNAL_FAILURE(500, -32603, "MCP HTTP bridge failed"),
}

data class McpHttpBridgeReceipt(
    val outcome: McpHttpBridgeOutcome,
    val rpcMethod: String?,
    val httpStatus: Int?,
    val errorCode: McpHttpBridgeErrorCode?,
    val gatewayInvoked: Boolean,
    val sideEffectState: McpHttpSideEffectState,
)

fun interface McpHttpBridgeObserver {
    /** Receipts contain no endpoint, credentials, request body, arguments, or response body. */
    fun record(receipt: McpHttpBridgeReceipt)
}

data class McpHttpBridgeResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String?,
    val errorCode: McpHttpBridgeErrorCode? = null,
)

internal fun normalizeMcpHttpScheme(value: String): String {
    val normalized = value.trim().lowercase()
    require(normalized == "http" || normalized == "https") {
        "MCP endpoint scheme must be HTTP or HTTPS"
    }
    return normalized
}

internal fun normalizeMcpHttpAuthority(value: String): String {
    val normalized = value.trim().lowercase()
    require(normalized.isNotEmpty()) { "MCP endpoint authority is required" }
    require(value.none(Char::isWhitespace)) { "MCP endpoint authority contains whitespace" }
    require(normalized.none(Char::isISOControl)) { "MCP endpoint authority contains control characters" }
    require(normalized.none { it in "/?#@" }) {
        "MCP endpoint authority contains forbidden characters"
    }
    parseMcpHttpAuthority(normalized)
    return normalized
}

internal fun isMcpHttpLoopbackAuthority(authority: String): Boolean =
    parseMcpHttpAuthority(authority).first in setOf("localhost", "127.0.0.1", "[::1]")

private fun parseMcpHttpAuthority(authority: String): Pair<String, Int?> {
    val host: String
    val portText: String?
    if (authority.startsWith('[')) {
        val closingBracket = authority.indexOf(']')
        require(closingBracket > 1) { "Invalid bracketed MCP endpoint authority" }
        host = authority.substring(0, closingBracket + 1)
        require(host.drop(1).dropLast(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it in ":." }) {
            "Invalid bracketed MCP endpoint host"
        }
        val suffix = authority.substring(closingBracket + 1)
        require(suffix.isEmpty() || suffix.startsWith(':')) { "Invalid MCP endpoint authority suffix" }
        portText = suffix.takeIf(String::isNotEmpty)?.drop(1)
    } else {
        require(authority.count { it == ':' } <= 1) { "IPv6 MCP endpoint hosts must be bracketed" }
        host = authority.substringBefore(':')
        require(host.matches(Regex("[a-z0-9.-]+"))) { "Invalid MCP endpoint host" }
        portText = authority.substringAfter(':', missingDelimiterValue = "").takeIf { ':' in authority }
    }
    require(host.isNotEmpty()) { "MCP endpoint host is required" }
    val port = portText?.let {
        require(it.isNotEmpty() && it.all(Char::isDigit)) { "Invalid MCP endpoint port" }
        it.toIntOrNull()?.also { value -> require(value in 1..65_535) }
            ?: throw IllegalArgumentException("Invalid MCP endpoint port")
    }
    return host to port
}

internal fun normalizeMcpHttpOrigin(value: String): String {
    val match = MCP_HTTP_ORIGIN_PATTERN.matchEntire(value.trim())
        ?: throw IllegalArgumentException("Origin must contain only scheme and authority")
    val scheme = normalizeMcpHttpScheme(match.groupValues[1])
    val authority = normalizeMcpHttpAuthority(match.groupValues[2])
    return "$scheme://$authority"
}

private val MCP_HTTP_ORIGIN_PATTERN = Regex("([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]+)")
