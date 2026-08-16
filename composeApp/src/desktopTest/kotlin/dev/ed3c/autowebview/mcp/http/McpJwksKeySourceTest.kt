package dev.ed3c.autowebview.mcp.http

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.Certificate
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
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
 * Real HTTPS evidence for the JWKS key source: a local TLS endpoint serves a key document, and the
 * source is driven across rotation, retirement, outage, and an attacker-chosen `kid`.
 *
 * Key material is generated at test time with the JDK's own `keytool`; nothing is committed.
 */
class McpJwksKeySourceTest {
    private val workspace: Path = Files.createTempDirectory("mcp-jwks")
    private val storePassword = "test-store-password"
    private val keyStorePath: Path = workspace.resolve("jwks.p12")

    private val signingKeys: KeyPair = rsa()
    private val rotatedKeys: KeyPair = rsa()

    private var served: String = jwksDocument("key-1" to signingKeys)
    private val requests = AtomicInteger(0)
    private var failRequests = false

    private var now: Long = 1_770_000_000_000L

    private val server: HttpsServer by lazy { startJwksServer() }
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().sslContext(clientTrustContext()).connectTimeout(Duration.ofSeconds(5)).build()
    }

    @AfterTest
    fun cleanUp() {
        runCatching { server.stop(0) }
        Files.walk(workspace).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun aPlaintextEndpointIsRefusedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            McpJwksKeySource(URI.create("http://issuer.example.test/jwks.json"))
        }
    }

    @Test
    fun retrievesAKeyThatVerifiesARealSignature() {
        val source = source()

        val key = assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
        assertTrue(verifies(key, signingKeys), "the retrieved key must verify its own signature")
        assertEquals(1, requests.get())
    }

    @Test
    fun servesFromCacheWithinTheAdmittedLifetimeAndRefreshesAfterIt() {
        val source = source()

        assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
        assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
        assertEquals(1, requests.get(), "a fresh cache must not refetch")

        now += CACHE_LIFETIME_MS
        assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
        assertEquals(2, requests.get(), "an expired cache must refetch")
    }

    @Test
    fun picksUpARotatedKeyAndStopsServingTheRetiredOne() {
        val source = source()
        assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))

        // The issuer rotates: a new kid appears and the old one is withdrawn.
        served = jwksDocument("key-2" to rotatedKeys)
        now += CACHE_LIFETIME_MS

        val rotated = assertNotNull(source.publicKey("key-2", McpJsonWebToken.ALG_RS256))
        assertTrue(verifies(rotated, rotatedKeys))
        // Retirement is the mechanism revocation actually reaches this process.
        assertNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
    }

    @Test
    fun anUnreachableIssuerRejectsRatherThanServingAnExpiredKey() {
        val source = source()
        assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))

        failRequests = true

        // Still inside the admitted lifetime: the cached key remains legitimate.
        now += CACHE_LIFETIME_MS / 2
        assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))

        // Past it: "cannot check" must not become "accept".
        now += CACHE_LIFETIME_MS
        assertNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
    }

    @Test
    fun anAttackerChosenKeyIdCannotDriveUnboundedRefreshes() {
        val source = source()
        assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
        val afterFirst = requests.get()

        // A caller inventing key ids must not become a request amplifier against the issuer.
        repeat(50) { index -> assertNull(source.publicKey("invented-$index", McpJsonWebToken.ALG_RS256)) }

        assertEquals(afterFirst, requests.get(), "unknown kids refetched inside the cooldown")

        // Once the cooldown passes, exactly one more attempt is allowed.
        now += REFRESH_INTERVAL_MS
        assertNull(source.publicKey("invented-again", McpJsonWebToken.ALG_RS256))
        assertEquals(afterFirst + 1, requests.get())
    }

    @Test
    fun aKeyIsOnlyUsableForItsOwnAlgorithm() {
        val source = source()

        assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
        // The document holds an RSA key; asking for it as ES256 must not produce a key.
        assertNull(source.publicKey("key-1", McpJsonWebToken.ALG_ES256))
    }

    @Test
    fun aDocumentBeyondTheBudgetIsRejected() {
        served = jwksDocument("key-1" to signingKeys, padding = 8 * 1_024)
        val source = McpJwksKeySource(
            jwksUrl = URI.create("https://127.0.0.1:${server.address.port}/jwks.json"),
            httpClient = client,
            cacheLifetimeMillis = CACHE_LIFETIME_MS,
            minimumRefreshIntervalMillis = REFRESH_INTERVAL_MS,
            maxDocumentBytes = 1_024,
            clock = { now },
        )

        assertNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
    }

    @Test
    fun aDuplicateKeyIdCannotBeDisplacedByAnAppendedEntry() {
        served = """{"keys":[${jwk("key-1", signingKeys)},${jwk("key-1", rotatedKeys)}]}"""
        val source = source()

        val key = assertNotNull(source.publicKey("key-1", McpJsonWebToken.ALG_RS256))
        assertTrue(verifies(key, signingKeys), "the first declaration must win")
    }

    // ---------- fixtures ----------

    private fun source() = McpJwksKeySource(
        jwksUrl = URI.create("https://127.0.0.1:${server.address.port}/jwks.json"),
        httpClient = client,
        cacheLifetimeMillis = CACHE_LIFETIME_MS,
        minimumRefreshIntervalMillis = REFRESH_INTERVAL_MS,
        clock = { now },
    )

    private fun rsa(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()

    private fun verifies(key: java.security.PublicKey, pair: KeyPair): Boolean {
        val payload = "kotlin-auto-webview".encodeToByteArray()
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(payload)
        }.sign()
        return Signature.getInstance("SHA256withRSA").apply {
            initVerify(key)
            update(payload)
        }.verify(signature)
    }

    private fun jwk(keyId: String, pair: KeyPair): String {
        val rsa = pair.public as java.security.interfaces.RSAPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun component(value: java.math.BigInteger): String =
            encoder.encodeToString(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        return """{"kty":"RSA","kid":"$keyId","alg":"RS256","use":"sig",""" +
            """"n":"${component(rsa.modulus)}","e":"${component(rsa.publicExponent)}"}"""
    }

    private fun jwksDocument(vararg keys: Pair<String, KeyPair>, padding: Int = 0): String {
        val entries = keys.joinToString(",") { (keyId, pair) -> jwk(keyId, pair) }
        val filler = if (padding > 0) ""","padding":"${"p".repeat(padding)}"""" else ""
        return """{"keys":[$entries]$filler}"""
    }

    private fun startJwksServer(): HttpsServer {
        val context = serverContext()
        val https = HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
        https.httpsConfigurator = object : HttpsConfigurator(context) {
            override fun configure(parameters: HttpsParameters) {
                parameters.setSSLParameters(context.defaultSSLParameters)
            }
        }
        https.createContext("/jwks.json") { exchange ->
            requests.incrementAndGet()
            if (failRequests) {
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
                return@createContext
            }
            val bytes = served.encodeToByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        https.start()
        return https
    }

    private fun serverContext(): SSLContext {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "jwks", "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA", "-dname", "CN=localhost", "-ext", "SAN=IP:127.0.0.1",
            "-validity", "1", "-keystore", keyStorePath.toString(), "-storetype", "PKCS12",
            "-storepass", storePassword, "-keypass", storePassword,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "keytool failed: $output" }

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(loadKeyStore(), storePassword.toCharArray()) }
        return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
    }

    private fun clientTrustContext(): SSLContext {
        val certificate: Certificate = loadKeyStore().getCertificate("jwks")
        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("jwks", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trustStore) }
        return SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance("PKCS12").apply {
        Files.newInputStream(keyStorePath).use { load(it, storePassword.toCharArray()) }
    }

    private companion object {
        const val CACHE_LIFETIME_MS = 600_000L
        const val REFRESH_INTERVAL_MS = 60_000L
    }
}
