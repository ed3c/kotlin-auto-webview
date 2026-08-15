package dev.ed3c.autowebview.mcp.http

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Shared JDK-`HttpExchange`-to-bridge adaptation for every Desktop MCP listener.
 *
 * Both the loopback listener and the host-owned HTTPS listener funnel through this object so a
 * hardening fix — body budgets, singleton headers, UTF-8 strictness — cannot land on one listener
 * and silently miss the other.
 */
internal object DesktopMcpHttpExchange {
    private val SECURITY_SINGLETON_HEADERS = setOf(
        "Host",
        "Content-Length",
        "Transfer-Encoding",
        "Authorization",
        "DPoP",
        "Content-Type",
        "Origin",
        "Mcp-Session-Id",
        "MCP-Protocol-Version",
        "Mcp-Method",
        "Mcp-Name",
    )

    fun adaptRequest(
        exchange: HttpExchange,
        scheme: String,
        expectedAuthority: String,
        expectedPath: String,
        maxRequestBodyBytes: Int,
        transport: McpHttpTransportFacts,
    ): McpHttpBridgeRequest {
        val headers = exchange.requestHeaders.entries.associate { (name, values) ->
            name to values.toList()
        }
        validateSecuritySingletonHeaders(headers)

        val host = singleHeader(headers, "Host")
            ?: throw DesktopMcpListenerRejection(400, "HOST_REQUIRED")
        val normalizedHost = runCatching { normalizeMcpHttpAuthority(host) }.getOrNull()
            ?: throw DesktopMcpListenerRejection(400, "HOST_INVALID")
        if (normalizedHost != expectedAuthority) {
            throw DesktopMcpListenerRejection(400, "HOST_MISMATCH")
        }

        val rawPath = exchange.requestURI.rawPath ?: ""
        val rawQuery = exchange.requestURI.rawQuery
        if (rawPath != expectedPath) {
            throw DesktopMcpListenerRejection(404, "PATH_MISMATCH")
        }
        if (!rawQuery.isNullOrEmpty()) {
            throw DesktopMcpListenerRejection(400, "QUERY_FORBIDDEN")
        }

        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            return McpHttpBridgeRequest(
                method = exchange.requestMethod,
                scheme = scheme,
                authority = expectedAuthority,
                path = rawPath,
                query = rawQuery,
                headers = headers,
                body = "",
                declaredContentLength = 0,
                transport = transport,
            )
        }

        val transferEncoding = singleHeader(headers, "Transfer-Encoding", optional = true)
        val declaredContentLength = parseContentLength(headers)
        if (transferEncoding != null && declaredContentLength != null) {
            throw DesktopMcpListenerRejection(400, "AMBIGUOUS_BODY_LENGTH")
        }
        if (transferEncoding != null && !transferEncoding.equals("chunked", ignoreCase = true)) {
            throw DesktopMcpListenerRejection(400, "TRANSFER_ENCODING_REJECTED")
        }
        if (declaredContentLength != null && declaredContentLength > maxRequestBodyBytes) {
            throw DesktopMcpListenerRejection(413, "BODY_TOO_LARGE")
        }

        val bodyBytes = readBoundedBody(exchange, maxRequestBodyBytes)
        if (declaredContentLength != null && declaredContentLength != bodyBytes.size.toLong()) {
            throw DesktopMcpListenerRejection(400, "BODY_LENGTH_MISMATCH")
        }

        return McpHttpBridgeRequest(
            method = exchange.requestMethod,
            scheme = scheme,
            authority = expectedAuthority,
            path = rawPath,
            query = rawQuery,
            headers = headers,
            body = decodeUtf8(bodyBytes),
            declaredContentLength = declaredContentLength,
            transport = transport,
        )
    }

    fun writeResponse(exchange: HttpExchange, response: McpHttpBridgeResponse) {
        for ((name, value) in response.headers) {
            exchange.responseHeaders.set(name, value)
        }
        if (response.mode == McpHttpResponseMode.SSE_REQUEST_SCOPED_RESPONSE) {
            writeRequestScopedEventStream(exchange, response)
            return
        }
        val bytes = response.body?.encodeToByteArray()
        if (bytes == null) {
            exchange.sendResponseHeaders(response.status, -1)
            return
        }
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    /**
     * Write a request-scoped event stream, flushing each event so a consumer sees it immediately.
     *
     * A write failure means the consumer disconnected: the remaining events are abandoned rather
     * than buffered or retried, and the stream ends with the request.
     */
    private fun writeRequestScopedEventStream(
        exchange: HttpExchange,
        response: McpHttpBridgeResponse,
    ) {
        // Length 0 selects chunked encoding, which is what an event stream needs.
        exchange.sendResponseHeaders(response.status, 0)
        exchange.responseBody.use { output ->
            for (event in response.events) {
                try {
                    output.write(event.frame().encodeToByteArray())
                    output.flush()
                } catch (_: Exception) {
                    return
                }
            }
        }
    }

    fun listenerErrorResponse(status: Int, code: String): McpHttpBridgeResponse {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", JsonNull)
            putJsonObject("error") {
                put("code", -32600)
                put("message", "Desktop MCP listener rejected request")
                put("data", code)
            }
        }.toString()
        return McpHttpBridgeResponse(
            status = status,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
            ),
            body = body,
        )
    }

    private fun validateSecuritySingletonHeaders(headers: Map<String, List<String>>) {
        for (name in SECURITY_SINGLETON_HEADERS) {
            val values = headerValues(headers, name)
            if (values.size > 1 || values.any { ',' in it }) {
                throw DesktopMcpListenerRejection(
                    400,
                    "DUPLICATE_${name.uppercase().replace('-', '_')}",
                )
            }
        }
    }

    private fun readBoundedBody(exchange: HttpExchange, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8 * 1_024))
        val buffer = ByteArray(8 * 1_024)
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > maximumBytes) {
                    throw DesktopMcpListenerRejection(413, "BODY_TOO_LARGE")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        throw DesktopMcpListenerRejection(400, "BODY_UTF8_INVALID")
    }

    private fun parseContentLength(headers: Map<String, List<String>>): Long? {
        val raw = singleHeader(headers, "Content-Length", optional = true) ?: return null
        val value = raw.toLongOrNull()
            ?: throw DesktopMcpListenerRejection(400, "CONTENT_LENGTH_INVALID")
        if (value < 0) throw DesktopMcpListenerRejection(400, "CONTENT_LENGTH_INVALID")
        return value
    }

    private fun headerValues(
        headers: Map<String, List<String>>,
        name: String,
    ): List<String> = headers.entries
        .filter { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        .flatMap { (_, values) -> values }
        .onEach { value ->
            if (value.any(Char::isISOControl)) {
                throw DesktopMcpListenerRejection(400, "HEADER_INVALID")
            }
        }

    private fun singleHeader(
        headers: Map<String, List<String>>,
        name: String,
        optional: Boolean = false,
    ): String? {
        val values = headerValues(headers, name)
        if (values.size > 1) {
            throw DesktopMcpListenerRejection(
                400,
                "DUPLICATE_${name.uppercase().replace('-', '_')}",
            )
        }
        val value = values.singleOrNull()?.trim()
        if (!optional && value.isNullOrEmpty()) {
            throw DesktopMcpListenerRejection(
                400,
                "${name.uppercase().replace('-', '_')}_REQUIRED",
            )
        }
        return value
    }
}

internal class DesktopMcpListenerRejection(
    val status: Int,
    val code: String,
) : Exception()
