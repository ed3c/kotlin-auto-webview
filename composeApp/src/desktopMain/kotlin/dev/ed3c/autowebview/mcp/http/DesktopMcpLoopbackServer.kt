package dev.ed3c.autowebview.mcp.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
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
    private val ownedVerifier: AutoCloseable?,
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
            ownedVerifier?.close()
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
        ): DesktopMcpLoopbackServer? = start(
            config = config,
            gateway = gateway,
            observer = observer,
        ) { authority ->
            val verifier = DesktopMcpBearerAuthenticationVerifier(
                expectedToken = bearerToken,
                expectedAuthority = authority,
                subjectId = config.subjectId,
                credentialEpoch = config.credentialEpoch,
            )
            verifier to verifier
        }

        /**
         * Start with a host-owned [DesktopMcpCredentialLifecycle] so credentials can rotate and be
         * revoked while the listener runs.
         *
         * The lifecycle is bound to an exact authority at construction time, so the config must use
         * a fixed port; an ephemeral port cannot be reconciled with an already-scoped credential.
         * The lifecycle stays owned by the caller and is not closed when the listener closes.
         */
        fun startIfEnabled(
            config: DesktopMcpLoopbackServerConfig,
            credentials: DesktopMcpCredentialLifecycle,
            gateway: McpJsonRpcGateway,
            observer: McpHttpBridgeObserver = McpHttpBridgeObserver { },
        ): DesktopMcpLoopbackServer? {
            require(config.port != 0) {
                "A rotating credential lifecycle requires an exact listener port"
            }
            return start(config = config, gateway = gateway, observer = observer) { credentials to null }
        }

        private fun start(
            config: DesktopMcpLoopbackServerConfig,
            gateway: McpJsonRpcGateway,
            observer: McpHttpBridgeObserver,
            verifierFactory: (authority: String) -> Pair<McpHttpAuthenticationVerifier, AutoCloseable?>,
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
            val (verifier, ownedVerifier) = try {
                verifierFactory(authority)
            } catch (failure: Exception) {
                server.stop(0)
                throw failure
            }
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
                    ownedVerifier = ownedVerifier,
                    path = config.path,
                    workerThreadPrefix = threadPrefix,
                )
            } catch (failure: Exception) {
                server.stop(0)
                executor.shutdownNow()
                ownedVerifier?.close()
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
                DesktopMcpHttpExchange.listenerErrorResponse(rejection.status, rejection.code)
            } catch (_: CancellationException) {
                DesktopMcpHttpExchange.listenerErrorResponse(503, "LISTENER_CANCELLED_OR_TIMED_OUT")
            } catch (_: Exception) {
                DesktopMcpHttpExchange.listenerErrorResponse(500, "LISTENER_FAILURE")
            }

            try {
                DesktopMcpHttpExchange.writeResponse(exchange, response)
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

            return DesktopMcpHttpExchange.adaptRequest(
                exchange = exchange,
                scheme = "http",
                expectedAuthority = expectedAuthority,
                expectedPath = expectedPath,
                maxRequestBodyBytes = maxRequestBodyBytes,
                // Transport facts come from the listener, never from a request header.
                transport = McpHttpTransportFacts(peerAddress = remoteAddress.hostAddress),
            )
        }

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
