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
    /** Transport facts observed by the trusted host listener, never asserted by the client. */
    val transport: McpHttpTransportFacts = McpHttpTransportFacts(),
)

/**
 * What the host's own HTTP/TLS implementation observed about the immediate peer.
 *
 * Every field is filled in by the trusted listener adapter. A value that arrived inside a request
 * header never reaches this type; forwarding metadata is admitted separately and only when
 * [McpHttpTransportPolicy] names the immediate peer as a trusted proxy.
 */
data class McpHttpTransportFacts(
    /** Numeric address of the immediate peer as the host listener saw it. */
    val peerAddress: String? = null,
    /** Negotiated TLS protocol on this hop, or `null` when the hop was plaintext. */
    val tlsProtocol: String? = null,
    /** Exact validated client-certificate subject, or `null` when no client certificate was used. */
    val peerCertificateSubject: String? = null,
    /** True only when the listener's own trust manager validated the client chain. */
    val peerCertificateVerified: Boolean = false,
    /** Platform-issued workload identity assertion attached by the host, never by the client. */
    val workloadIdentityAssertion: String? = null,
)

/**
 * Transport identity requirements for one mounted route.
 *
 * [trustedProxies] is an exact numeric peer allowlist. Forwarding metadata from any other peer is
 * rejected rather than trusted, so a client cannot promote itself by sending `X-Forwarded-Proto`.
 */
data class McpHttpTransportPolicy(
    val requireDirectTls: Boolean = false,
    val admittedTlsProtocols: Set<String> = setOf("TLSv1.3"),
    val trustedProxies: Set<String> = emptySet(),
    val requireClientCertificate: Boolean = false,
) {
    init {
        require(admittedTlsProtocols.isNotEmpty()) { "At least one admitted TLS protocol is required" }
        // TLS 1.2 is the floor: an obsolete protocol name here would silently widen every listener
        // that reads this policy, so it is refused where the policy is built, not where it is used.
        require(admittedTlsProtocols.all { it in ADMITTED_TLS_PROTOCOLS }) {
            "Only TLS 1.2 or newer may be admitted"
        }
        require(trustedProxies.all { it.isNotBlank() && it.none(Char::isWhitespace) }) {
            "Trusted proxy peer addresses are invalid"
        }
        require(!requireClientCertificate || requireDirectTls) {
            "Client certificates can only be required on a directly terminated TLS hop"
        }
    }

    companion object {
        val ADMITTED_TLS_PROTOCOLS: Set<String> = setOf("TLSv1.2", "TLSv1.3")
    }
}

/** Exact endpoint identity and body budgets for one mounted MCP route. */
data class McpHttpEndpointPolicy(
    val scheme: String,
    val authority: String,
    val path: String = "/mcp",
    val allowedOrigins: Set<String> = emptySet(),
    val allowMissingOrigin: Boolean = true,
    val maxRequestBodyBytes: Int = 64 * 1024,
    val maxResponseBodyBytes: Int = 256 * 1024,
    val transportPolicy: McpHttpTransportPolicy = McpHttpTransportPolicy(),
    /**
     * Whether a client that prefers `text/event-stream` gets a request-scoped SSE response.
     *
     * The stateless JSON response stays the default admitted mode; enabling this never adds a GET
     * event stream or a protocol-level HTTP session.
     */
    val sseResponseEnabled: Boolean = false,
    val maxSseEvents: Int = 8,
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
        require(maxSseEvents in 1..64) { "MCP SSE event budget is outside the admitted range" }
        require(normalizedScheme == "https" || isMcpHttpLoopbackAuthority(normalizedAuthority)) {
            "Plain HTTP MCP endpoints are allowed only on explicit loopback authorities"
        }
        require(allowMissingOrigin || normalizedOrigins.isNotEmpty()) {
            "At least one exact Origin is required when missing Origin is denied"
        }
        require(
            normalizedScheme != "https" ||
                transportPolicy.requireDirectTls ||
                transportPolicy.trustedProxies.isNotEmpty(),
        ) {
            "An HTTPS endpoint must terminate TLS directly or name at least one trusted proxy"
        }
    }
}

data class McpHttpAuthenticationInput(
    val authorizationHeader: String?,
    val scheme: String,
    val authority: String,
    /** Host clock reading for the request, so expiry is decided against one shared timeline. */
    val nowEpochMs: Long,
    /** Transport facts observed by the host listener; used by mTLS and workload-identity profiles. */
    val transport: McpHttpTransportFacts = McpHttpTransportFacts(),
    /**
     * Admitted route path and method.
     *
     * The bridge has already rejected any other value by the time a verifier runs, so the defaults
     * are the only admitted values; they exist so a proof-of-possession profile can bind a
     * credential to the exact request without re-deriving route authority.
     */
    val path: String = "/mcp",
    val httpMethod: String = "POST",
    /** Second credential-bearing header (for example a DPoP proof), when the client sent one. */
    val proofHeader: String? = null,
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
    TLS_REQUIRED(403, -32600, "An admitted TLS transport is required"),
    TLS_VERSION_REJECTED(403, -32600, "Negotiated TLS protocol is not admitted"),
    CLIENT_CERTIFICATE_REQUIRED(403, -32600, "A validated client certificate is required"),
    FORWARDING_NOT_ADMITTED(403, -32600, "Forwarding metadata came from an untrusted peer"),
    FORWARDED_METADATA_REJECTED(400, -32600, "Forwarding metadata does not match the admitted route"),
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
    SSE_BUDGET_EXCEEDED(500, -32603, "MCP response exceeds the request-scoped event budget"),
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

/** How the admitted response is framed on the wire. */
enum class McpHttpResponseMode {
    /** One JSON-RPC response object, `application/json`, body closed. */
    JSON_SINGLE_RESPONSE,

    /**
     * One request-scoped `text/event-stream` response that ends when the request ends.
     *
     * This is not a standing GET event stream and carries no protocol-level session: the events
     * belong to exactly one POST and the stream closes with it.
     */
    SSE_REQUEST_SCOPED_RESPONSE,
}

/** One Server-Sent Event of a request-scoped stream. */
data class McpHttpSseEvent(val id: Int, val event: String, val data: String) {
    init {
        require(id >= 0) { "SSE event id must be non-negative" }
        require(event.isNotBlank() && event.none(Char::isISOControl)) { "SSE event name is invalid" }
        require('\r' !in data) { "SSE data cannot contain carriage returns" }
    }

    /** Wire framing for exactly this event, including its terminating blank line. */
    fun frame(): String = buildString {
        append("id: ").append(id).append('\n')
        append("event: ").append(event).append('\n')
        for (line in data.split('\n')) append("data: ").append(line).append('\n')
        append('\n')
    }
}

data class McpHttpBridgeResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String?,
    val errorCode: McpHttpBridgeErrorCode? = null,
    val mode: McpHttpResponseMode = McpHttpResponseMode.JSON_SINGLE_RESPONSE,
    /**
     * Events to write in order for [McpHttpResponseMode.SSE_REQUEST_SCOPED_RESPONSE].
     *
     * The list is fully materialised inside the response budget before the first byte is written,
     * so a slow or vanished consumer can never make the producer accumulate unbounded state; a
     * listener that fails mid-write simply stops writing the remainder.
     */
    val events: List<McpHttpSseEvent> = emptyList(),
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
