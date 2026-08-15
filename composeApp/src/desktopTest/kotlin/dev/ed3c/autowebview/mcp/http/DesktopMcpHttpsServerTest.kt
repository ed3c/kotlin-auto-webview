package dev.ed3c.autowebview.mcp.http

import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.Certificate
import java.time.Duration
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real TLS evidence for the host-owned HTTPS endpoint.
 *
 * The key material is generated at test time with the JDK's own `keytool` and never leaves the
 * temporary directory, so no certificate or private key is committed to the repository.
 */
class DesktopMcpHttpsServerTest {
    private val workingDirectory: Path = Files.createTempDirectory("mcp-https")
    private val keyStorePath: Path = workingDirectory.resolve("listener.p12")
    private val storePassword = "test-store-password"

    private val serverContext: SSLContext by lazy { generateServerContext() }
    private val clientContext: SSLContext by lazy { clientTrustContext() }

    @AfterTest
    fun cleanUp() {
        Files.walk(workingDirectory).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun aDisabledConfigurationStartsNoRemoteListener() {
        val port = reserveLoopbackPort()

        assertNull(
            DesktopMcpHttpsServer.startIfEnabled(
                config = config(port, enabled = false),
                verifier = acceptingVerifier(port),
                gateway = McpJsonRpcGateway(BrowserMcpGateway(AgentBrowserRuntime())::handle),
            ),
        )
    }

    @Test
    fun aPlaintextEndpointCannotBeConfigured() {
        assertFailsWith<IllegalArgumentException> {
            McpHttpTransportPolicy(admittedTlsProtocols = setOf("SSLv3"))
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopMcpHttpsServerConfig(
                enabled = true,
                bindHost = "127.0.0.1",
                advertisedAuthority = "127.0.0.1:8443",
                port = 8_443,
                sslContext = serverContext,
                admittedTlsProtocols = setOf("TLSv1.1"),
            )
        }
    }

    @Test
    fun aRealTlsRequestReachesTheGatewayAndTheHopIsTls13() {
        val port = reserveLoopbackPort()
        val server = assertNotNull(
            DesktopMcpHttpsServer.startIfEnabled(
                config = config(port),
                verifier = acceptingVerifier(port),
                gateway = McpJsonRpcGateway(BrowserMcpGateway(AgentBrowserRuntime())::handle),
            ),
        )

        try {
            assertEquals("https://127.0.0.1:$port/mcp", server.endpoint)

            val response = post(server.endpoint)
            assertEquals(200, response.statusCode())
            assertTrue(""""result"""" in response.body())
            assertEquals(
                "TLSv1.3",
                response.sslSession().map { it.protocol }.orElse(null),
            )
        } finally {
            server.close()
            awaitPortReleased(port)
        }
    }

    @Test
    fun forwardingMetadataFromAnUntrustedPeerIsRejectedOverRealTls() {
        val port = reserveLoopbackPort()
        val server = assertNotNull(
            DesktopMcpHttpsServer.startIfEnabled(
                config = config(port),
                verifier = acceptingVerifier(port),
                gateway = McpJsonRpcGateway(BrowserMcpGateway(AgentBrowserRuntime())::handle),
            ),
        )

        try {
            val response = post(
                server.endpoint,
                extraHeaders = mapOf("X-Forwarded-Proto" to "https"),
            )
            assertEquals(403, response.statusCode())
        } finally {
            server.close()
            awaitPortReleased(port)
        }
    }

    @Test
    fun anUnauthenticatedRequestIsRejectedEvenOverTls() {
        val port = reserveLoopbackPort()
        val server = assertNotNull(
            DesktopMcpHttpsServer.startIfEnabled(
                config = config(port),
                verifier = McpHttpAuthenticationVerifier {
                    McpHttpAuthenticationDecision.Rejected(
                        McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS,
                    )
                },
                gateway = McpJsonRpcGateway(BrowserMcpGateway(AgentBrowserRuntime())::handle),
            ),
        )

        try {
            assertEquals(401, post(server.endpoint).statusCode())
        } finally {
            server.close()
            awaitPortReleased(port)
        }
    }

    @Test
    fun theListenerNeverRendersItsEndpointOrKeyMaterial() {
        val port = reserveLoopbackPort()
        val configuration = config(port)
        val server = assertNotNull(
            DesktopMcpHttpsServer.startIfEnabled(
                config = configuration,
                verifier = acceptingVerifier(port),
                gateway = McpJsonRpcGateway(BrowserMcpGateway(AgentBrowserRuntime())::handle),
            ),
        )

        try {
            for (rendered in listOf(configuration.toString(), server.toString())) {
                assertTrue(storePassword !in rendered)
                assertTrue("127.0.0.1" !in rendered)
            }
        } finally {
            server.close()
            awaitPortReleased(port)
        }
    }

    // ---------- helpers ----------

    private fun config(port: Int, enabled: Boolean = true) = DesktopMcpHttpsServerConfig(
        enabled = enabled,
        bindHost = "127.0.0.1",
        advertisedAuthority = "127.0.0.1:$port",
        port = port,
        sslContext = serverContext,
        allowMissingOrigin = true,
    )

    private fun acceptingVerifier(port: Int) = McpHttpAuthenticationVerifier { input ->
        if (input.scheme == "https" && input.authority == "127.0.0.1:$port") {
            McpHttpAuthenticationDecision.Accepted(subjectId = "https-test", credentialEpoch = "epoch-1")
        } else {
            McpHttpAuthenticationDecision.Rejected(
                McpHttpAuthenticationRejectionReason.INSUFFICIENT_SCOPE,
            )
        }
    }

    private fun post(
        endpoint: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer test-credential")
            .POST(HttpRequest.BodyPublishers.ofString(PING_BODY))
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        return HttpClient.newBuilder()
            .sslContext(clientContext)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun generateServerContext(): SSLContext {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool,
            "-genkeypair",
            "-alias", "mcp",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=localhost",
            "-ext", "SAN=IP:127.0.0.1",
            "-validity", "1",
            "-keystore", keyStorePath.toString(),
            "-storetype", "PKCS12",
            "-storepass", storePassword,
            "-keypass", storePassword,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "keytool failed: $output" }

        val keyStore = loadKeyStore()
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, storePassword.toCharArray())
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(keyManagers.keyManagers, null, null)
        }
    }

    private fun clientTrustContext(): SSLContext {
        val certificate: Certificate = loadKeyStore().getCertificate("mcp")
        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("mcp", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(null, trustManagers.trustManagers, null)
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance("PKCS12").apply {
        Files.newInputStream(keyStorePath).use { load(it, storePassword.toCharArray()) }
    }

    private fun reserveLoopbackPort(): Int =
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            socket.localPort
        }

    private fun awaitPortReleased(port: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
            } catch (_: IOException) {
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("Desktop MCP HTTPS port $port was not released")
    }

    private companion object {
        const val PING_BODY = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
    }
}
