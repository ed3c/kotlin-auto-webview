package dev.ed3c.autowebview.edge

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.Url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json

/**
 * Security policy for the private OpenClaw WebSocket endpoint.
 *
 * The transport accepts only explicit `wss://` endpoints whose host is in the
 * repository/user supplied allowlist. Credentials, query parameters, and
 * fragments are rejected so secret-bearing endpoint strings cannot become
 * transport configuration or receipts accidentally.
 */
data class OpenClawWebSocketEndpointPolicy(
    val allowedHosts: Set<String>,
    val maximumFrameChars: Int = 64 * 1024,
) {
    init {
        require(allowedHosts.isNotEmpty())
        require(allowedHosts.none(String::isBlank))
        require(maximumFrameChars in 1..MAXIMUM_FRAME_CHARS)
    }

    fun admit(endpoint: String): Url {
        val url = Url(endpoint)
        require(url.protocol.name == "wss") { "OpenClaw transport requires wss://" }
        require(url.host in allowedHosts) { "OpenClaw endpoint host is not allowlisted" }
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) {
            "Credentials are forbidden in endpoint URLs"
        }
        require(url.parameters.isEmpty()) { "Query parameters are forbidden in endpoint URLs" }
        require(url.fragment.isEmpty()) { "Fragments are forbidden in endpoint URLs" }
        return url
    }

    private companion object {
        const val MAXIMUM_FRAME_CHARS = 1024 * 1024
    }
}

/**
 * Real Ktor WebSocket adapter for the portable [OpenClawTransport] boundary.
 *
 * Lifecycle ownership of [HttpClient] stays outside this adapter. This class
 * never logs endpoint strings, frame bodies, pairing keys, or credentials.
 * Authentication/pairing remains enforced by [OpenClawStreamSession] after
 * transport decoding; a successful WebSocket handshake never grants authority.
 */
class KtorOpenClawWebSocketTransport(
    private val client: HttpClient,
    endpoint: String,
    private val endpointPolicy: OpenClawWebSocketEndpointPolicy,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : OpenClawTransport {
    private val admittedUrl: Url = endpointPolicy.admit(endpoint)
    private var session: DefaultClientWebSocketSession? = null

    override suspend fun connect() {
        check(session == null) { "OpenClaw transport is already connected" }
        session = client.webSocketSession(urlString = admittedUrl.toString())
    }

    override suspend fun receive(): StreamChunk? {
        val active = checkNotNull(session) { "OpenClaw transport is not connected" }
        while (true) {
            val frame = active.incoming.receiveCatching().getOrNull() ?: return null
            when (frame) {
                is Frame.Text -> {
                    val payload = frame.readText()
                    require(payload.length <= endpointPolicy.maximumFrameChars) {
                        "OpenClaw frame exceeds the configured size bound"
                    }
                    return json.decodeFromString(StreamChunk.serializer(), payload)
                }
                is Frame.Close -> return null
                else -> Unit
            }
        }
    }

    override suspend fun close() {
        val active = session ?: return
        session = null
        active.close()
    }
}
