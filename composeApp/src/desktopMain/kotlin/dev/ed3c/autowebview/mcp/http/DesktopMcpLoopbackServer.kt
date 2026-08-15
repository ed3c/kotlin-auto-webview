package dev.ed3c.autowebview.mcp.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real JDK HTTP listener hard-bound to numeric IPv4 loopback and delegated to the portable bridge.
 *
 * The listener has no remote/TLS mode and does not start unless the caller supplies an explicitly
 * enabled [DesktopMcpLoopbackServerConfig]. It never logs request or response payloads.
 */
class DesktopMcpLoopbackServer private constructor(
    private val server: HttpServer,
    private val executor: ThreadPoolExecutor,
    private val verifier: DesktopMcpBearerAuthenticationVerifier,
    private val path: String,
    internal val workerThreadPrefix: String,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val port: Int
        get() = server.address.port

    val endpoint: String
        get() = "http://$LOOPBACK_HOST:$port$path"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        executor.shutdown()
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        } finally {
            verifier.close()
        }
    }

    override fun toString(): String =
        "DesktopMcpLoopbackServer(state=${if (closed.get()) "closed" else "running"}, endpoint=<redacted>)"

    companion object {
        const val LOOPBACK_HOST: String = "127.0.0.1"
        private const val EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5L
        private val LOOPBACK_BYTES = byteArrayOf(127, 0, 0, 1)
        private val SERVER_SEQUENCE = AtomicInteger(0)

        fun startIfEnabled(
            config: DesktopMcpLoopbackServerConfig,
            bearerToken: ByteArray,
            gateway: BrowserMcpGateway,
            observer: McpHttpBridgeObserver = McpHttpBridgeObserver { },
        ): DesktopMcpLoopbackServer? = startIfEnabled(
            config = config,
            bearerToken = bearerToken,
            gateway = McpJsonRpcGateway(gateway::handle),
            observer = observer,
        )

        fun startIfEnabled(
            config: DesktopMcpLoopbackServerConfig,
            bearerToken: ByteArray,
            gateway: McpJsonRpcGateway,
            observer: McpHttpBridgeObserver = McpHttpBridgeObserver { },
        ): DesktopMcpLoopbackServer? {
            if (!config.enabled) return null

            val bindAddress = InetSocketAddress(InetAddress.getByAddress(LOOPBACK_BYTES), config.port)
            val server = HttpServer.create(bindAddress, config.backlog)
            val actualAddress = server.address.address?.address
            if (actualAddress == null || !actualAddress.contentEquals(LOOPBACK_BYTES)) {
                server.stop(0)
                throw IllegalStateException("Desktop MCP listener did not bind to numeric IPv4 loopback")
            }

            val authority = "$LOOPBACK_HOST:${server.address.port}"
            val verifier = DesktopMcpBearerAuthenticationVerifier(
                expectedToken = bearerToken,
                expectedAuthority = authority,
                subjectId = config.subjectId,
                credentialEpoch = config.credentialEpoch,
            )
            val bridge = McpStreamableHttpBridge(
                gateway = gateway,
                endpointPolicy = McpHttpEndpointPolicy(
                    scheme = "http",
                    authority = authority,
                    path = config.path,
                    allowedOrigins = config.allowedOrigins,
                    allowMissingOrigin = config.allowMissingOrigin,
                    maxRequestBodyBytes = config.maxRequestBodyBytes,
                    maxResponseBodyBytes = config.maxResponseBodyBytes,
                ),
                authenticationVerifier = verifier,
                observer = observer,
            )
            val threadPrefix =
                "desktop-mcp-loopback-${server.address.port}-${SERVER_SEQUENCE.incrementAndGet()}"
            val executor = ThreadPoolExecutor(
                config.workerThreads,
                config.workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(config.queueCapacity),
                DaemonThreadFactory(threadPrefix),
                ThreadPoolExecutor.AbortPolicy(),
            )
            server.executor = executor

            return try {
                server.createContext(config.path) { exchange ->
                    handleExchange(
                        exchange = exchange,
                        bridge = bridge,
                        expectedAuthority = authority,
                        expectedPath = config.path,
                        maxRequestBodyBytes = config.maxRequestBodyBytes,
                    )
                }
                server.start()
                DesktopMcpLoopbackServer(
                    server = server,
                    executor = executor,
                    verifier = verifier,
                    path = config.path,
                    workerThreadPrefix = threadPrefix,
                )
            } catch (failure: Exception) {
                server.stop(0)
                executor.shutdownNow()
                verifier.close()
                throw failure
            }
        }

        private fun handleExchange(
            exchange: HttpExchange,
            bridge: McpStreamableHttpBridge,
            expectedAuthority: String,
            expectedPath: String,
            maxRequestBodyBytes: Int,
        ) {
            val response = try {
                val request = adaptRequest(
                    exchange = exchange,
                    expectedAuthority = expectedAuthority,
                    expectedPath = expectedPath,
                    maxRequestBodyBytes = maxRequestBodyBytes,
                )
                runBlocking {
                    bridge.handle(request, System.currentTimeMillis())
                }
            } catch (rejection: DesktopMcpListenerRejection) {
                listenerErrorResponse(rejection.status, rejection.code)
            } catch (_: CancellationException) {
                listenerErrorResponse(503, "LISTENER_CANCELLED_OR_TIMED_OUT")
            } catch (_: Exception) {
                listenerErrorResponse(500, "LISTENER_FAILURE")
            }

            try {
                writeResponse(exchange, response)
            } catch (_: Exception) {
                // The peer may have disconnected. No payload or exception is logged.
            } finally {
                exchange.close()
            }
        }

        private fun adaptRequest(
            exchange: HttpExchange,
            expectedAuthority: String,
            expectedPath: String,
            maxRequestBodyBytes: Int,
        ): McpHttpBridgeRequest {
            val localAddress = exchange.localAddress.address?.address
            if (localAddress == null || !localAddress.contentEquals(LOOPBACK_BYTES)) {
                throw DesktopMcpListenerRejection(403, "LOCAL_BIND_MISMATCH")
            }
            val remoteAddress = exchange.remoteAddress.address
            if (remoteAddress == null || !remoteAddress.isLoopbackAddress) {
                throw DesktopMcpListenerRejection(403, "REMOTE_ADDRESS_REJECTED")
            }

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
                    scheme = "http",
                    authority = expectedAuthority,
                    path = rawPath,
                    query = rawQuery,
                    headers = headers,
                    body = "",
                    declaredContentLength = 0,
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
            val body = decodeUtf8(bodyBytes)

            return McpHttpBridgeRequest(
                method = exchange.requestMethod,
                scheme = "http",
                authority = expectedAuthority,
                path = rawPath,
                query = rawQuery,
                headers = headers,
                body = body,
                declaredContentLength = declaredContentLength,
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

        private fun writeResponse(exchange: HttpExchange, response: McpHttpBridgeResponse) {
            for ((name, value) in response.headers) {
                exchange.responseHeaders.set(name, value)
            }
            val bytes = response.body?.encodeToByteArray()
            if (bytes == null) {
                exchange.sendResponseHeaders(response.status, -1)
                return
            }
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { output -> output.write(bytes) }
        }

        private fun listenerErrorResponse(status: Int, code: String): McpHttpBridgeResponse {
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

        private val SECURITY_SINGLETON_HEADERS = setOf(
            "Host",
            "Content-Length",
            "Transfer-Encoding",
            "Authorization",
            "Content-Type",
            "Origin",
            "Mcp-Session-Id",
            "MCP-Protocol-Version",
            "Mcp-Method",
            "Mcp-Name",
        )
    }
}

private class DaemonThreadFactory(
    private val prefix: String,
) : ThreadFactory {
    private val sequence = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread = Thread(
        runnable,
        "$prefix-${sequence.incrementAndGet()}",
    ).apply {
        isDaemon = true
    }
}

private class DesktopMcpListenerRejection(
    val status: Int,
    val code: String,
) : Exception()
