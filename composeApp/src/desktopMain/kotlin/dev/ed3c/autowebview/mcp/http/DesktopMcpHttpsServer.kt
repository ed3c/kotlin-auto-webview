package dev.ed3c.autowebview.mcp.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsExchange
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
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
import javax.net.ssl.SSLContext

/**
 * Default-off configuration for a host-owned remote/private HTTPS MCP endpoint.
 *
 * The host supplies the [SSLContext]; no key material, keystore path, or password is read from the
 * repository. [trustedProxies] is an exact numeric peer allowlist — the only peers whose forwarding
 * metadata the bridge will honour — and stays empty unless the deployment really terminates TLS in
 * front of this listener.
 */
class DesktopMcpHttpsServerConfig(
    val enabled: Boolean,
    val bindHost: String,
    val advertisedAuthority: String,
    val port: Int,
    val sslContext: SSLContext,
    val path: String = "/mcp",
    val requireClientCertificate: Boolean = false,
    val admittedTlsProtocols: Set<String> = setOf("TLSv1.3"),
    val trustedProxies: Set<String> = emptySet(),
    val allowedOrigins: Set<String> = emptySet(),
    val allowMissingOrigin: Boolean = false,
    val backlog: Int = 16,
    val workerThreads: Int = 4,
    val queueCapacity: Int = 64,
    val maxRequestBodyBytes: Int = 64 * 1_024,
    val maxResponseBodyBytes: Int = 256 * 1_024,
) {
    init {
        require(port in 1..65_535) { "Desktop MCP HTTPS port is outside the admitted range" }
        require(bindHost.isNotBlank() && bindHost.none(Char::isWhitespace)) {
            "Desktop MCP HTTPS bind host is invalid"
        }
        require(path.startsWith('/') && '?' !in path && '#' !in path) {
            "Desktop MCP HTTPS path must be an absolute path without query or fragment data"
        }
        require(backlog in 1..1_024) { "Desktop MCP HTTPS backlog is outside the admitted range" }
        require(workerThreads in 1..64) { "Desktop MCP HTTPS worker count is outside the admitted range" }
        require(queueCapacity in 1..4_096) { "Desktop MCP HTTPS queue capacity is outside the admitted range" }
        require(maxRequestBodyBytes in 1..MAX_BODY_BUDGET_BYTES) {
            "Desktop MCP HTTPS request budget is outside the admitted range"
        }
        require(maxResponseBodyBytes in 1..MAX_BODY_BUDGET_BYTES) {
            "Desktop MCP HTTPS response budget is outside the admitted range"
        }
        require(allowMissingOrigin || allowedOrigins.isNotEmpty()) {
            "A remote endpoint that denies a missing Origin needs at least one exact Origin"
        }
        // Building the endpoint policy here turns a malformed authority, path, Origin rule, or TLS
        // protocol into a construction failure rather than a request-time mismatch that reads like
        // a client error. It is the same policy the listener will use.
        endpointPolicy()
    }

    internal fun endpointPolicy(): McpHttpEndpointPolicy = McpHttpEndpointPolicy(
        scheme = "https",
        authority = advertisedAuthority,
        path = path,
        allowedOrigins = allowedOrigins,
        allowMissingOrigin = allowMissingOrigin,
        maxRequestBodyBytes = maxRequestBodyBytes,
        maxResponseBodyBytes = maxResponseBodyBytes,
        transportPolicy = McpHttpTransportPolicy(
            requireDirectTls = true,
            admittedTlsProtocols = admittedTlsProtocols,
            trustedProxies = trustedProxies,
            requireClientCertificate = requireClientCertificate,
        ),
    )

    override fun toString(): String =
        "DesktopMcpHttpsServerConfig(enabled=$enabled, tls=required, authority=<redacted>, path=<redacted>)"

    private companion object {
        const val MAX_BODY_BUDGET_BYTES = 4 * 1_024 * 1_024
    }
}

/**
 * Host-owned HTTPS termination for the portable MCP bridge.
 *
 * TLS is terminated here or by an exactly named trusted proxy; there is no plaintext remote mode.
 * The listener adds no session, event stream, or mobile inbound surface — it is the same POST
 * admission path as the loopback listener with a TLS hop and an explicit proxy boundary.
 */
class DesktopMcpHttpsServer private constructor(
    private val server: HttpsServer,
    private val executor: ThreadPoolExecutor,
    private val path: String,
    private val advertisedAuthority: String,
    internal val workerThreadPrefix: String,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val port: Int get() = server.address.port

    val endpoint: String get() = "https://$advertisedAuthority$path"

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
        }
    }

    override fun toString(): String =
        "DesktopMcpHttpsServer(state=${if (closed.get()) "closed" else "running"}, endpoint=<redacted>)"

    companion object {
        private const val EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5L
        private val SERVER_SEQUENCE = AtomicInteger(0)

        fun startIfEnabled(
            config: DesktopMcpHttpsServerConfig,
            verifier: McpHttpAuthenticationVerifier,
            gateway: McpJsonRpcGateway,
            observer: McpHttpBridgeObserver = McpHttpBridgeObserver { },
        ): DesktopMcpHttpsServer? {
            if (!config.enabled) return null

            val bindAddress = InetSocketAddress(InetAddress.getByName(config.bindHost), config.port)
            val server = HttpsServer.create(bindAddress, config.backlog)
            server.httpsConfigurator = object : HttpsConfigurator(config.sslContext) {
                override fun configure(parameters: HttpsParameters) {
                    val engineParameters = config.sslContext.defaultSSLParameters
                    engineParameters.protocols = config.admittedTlsProtocols.toTypedArray()
                    engineParameters.needClientAuth = config.requireClientCertificate
                    parameters.setSSLParameters(engineParameters)
                }
            }

            val bridge = McpStreamableHttpBridge(
                gateway = gateway,
                endpointPolicy = config.endpointPolicy(),
                authenticationVerifier = verifier,
                observer = observer,
            )
            val threadPrefix =
                "desktop-mcp-https-${server.address.port}-${SERVER_SEQUENCE.incrementAndGet()}"
            val executor = ThreadPoolExecutor(
                config.workerThreads,
                config.workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(config.queueCapacity),
                HttpsDaemonThreadFactory(threadPrefix),
                ThreadPoolExecutor.AbortPolicy(),
            )
            server.executor = executor

            return try {
                server.createContext(config.path) { exchange ->
                    handleExchange(exchange, bridge, config)
                }
                server.start()
                DesktopMcpHttpsServer(
                    server = server,
                    executor = executor,
                    path = config.path,
                    advertisedAuthority = config.advertisedAuthority,
                    workerThreadPrefix = threadPrefix,
                )
            } catch (failure: Exception) {
                server.stop(0)
                executor.shutdownNow()
                throw failure
            }
        }

        private fun handleExchange(
            exchange: HttpExchange,
            bridge: McpStreamableHttpBridge,
            config: DesktopMcpHttpsServerConfig,
        ) {
            val response = try {
                val request = DesktopMcpHttpExchange.adaptRequest(
                    exchange = exchange,
                    scheme = "https",
                    expectedAuthority = config.advertisedAuthority,
                    expectedPath = config.path,
                    maxRequestBodyBytes = config.maxRequestBodyBytes,
                    transport = transportFacts(exchange),
                )
                runBlocking { bridge.handle(request, System.currentTimeMillis()) }
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

        /**
         * Read transport identity from the TLS session the JDK negotiated.
         *
         * A peer certificate subject is reported only when the JDK's own trust manager already
         * validated the chain, so an unverified certificate can never become an identity.
         */
        private fun transportFacts(exchange: HttpExchange): McpHttpTransportFacts {
            val peerAddress = exchange.remoteAddress.address?.hostAddress
            val session = (exchange as? HttpsExchange)?.sslSession
                ?: return McpHttpTransportFacts(peerAddress = peerAddress)
            val principal = runCatching { session.peerPrincipal }.getOrNull()
            return McpHttpTransportFacts(
                peerAddress = peerAddress,
                tlsProtocol = session.protocol,
                peerCertificateSubject = principal?.name,
                peerCertificateVerified = principal != null,
            )
        }
    }
}

private class HttpsDaemonThreadFactory(
    private val prefix: String,
) : ThreadFactory {
    private val sequence = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread = Thread(
        runnable,
        "$prefix-${sequence.incrementAndGet()}",
    ).apply { isDaemon = true }
}
