package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class McpHttpTransportAdmissionTest {
    @Test
    fun anHttpsEndpointMustTerminateTlsOrNameATrustedProxy() {
        assertFailsWith<IllegalArgumentException> {
            McpHttpEndpointPolicy(scheme = "https", authority = "mcp.example.test:8443")
        }

        // Either arrangement is admissible on its own.
        McpHttpEndpointPolicy(
            scheme = "https",
            authority = "mcp.example.test:8443",
            transportPolicy = McpHttpTransportPolicy(requireDirectTls = true),
        )
        McpHttpEndpointPolicy(
            scheme = "https",
            authority = "mcp.example.test:8443",
            transportPolicy = McpHttpTransportPolicy(trustedProxies = setOf("10.0.0.1")),
        )
    }

    @Test
    fun aClientCertificateCanOnlyBeRequiredOnADirectlyTerminatedHop() {
        assertFailsWith<IllegalArgumentException> {
            McpHttpTransportPolicy(requireDirectTls = false, requireClientCertificate = true)
        }
    }

    @Test
    fun aPlaintextHopIsRejectedOnAnHttpsEndpoint() = runTest {
        val response = directTlsBridge().handle(request(transport = McpHttpTransportFacts(peerAddress = PEER)), NOW)

        assertEquals(403, response.status)
        assertEquals(McpHttpBridgeErrorCode.TLS_REQUIRED, response.errorCode)
    }

    @Test
    fun anUnadmittedTlsVersionIsRejected() = runTest {
        val response = directTlsBridge().handle(
            request(transport = McpHttpTransportFacts(peerAddress = PEER, tlsProtocol = "TLSv1.2")),
            NOW,
        )

        assertEquals(403, response.status)
        assertEquals(McpHttpBridgeErrorCode.TLS_VERSION_REJECTED, response.errorCode)
    }

    @Test
    fun anAdmittedTlsHopReachesTheGateway() = runTest {
        val response = directTlsBridge().handle(request(transport = tls()), NOW)

        assertEquals(200, response.status)
        assertNull(response.errorCode)
    }

    @Test
    fun aMissingClientCertificateIsRejectedWhenTheProfileRequiresOne() = runTest {
        val bridge = directTlsBridge(requireClientCertificate = true)

        assertEquals(
            McpHttpBridgeErrorCode.CLIENT_CERTIFICATE_REQUIRED,
            bridge.handle(request(transport = tls()), NOW).errorCode,
        )
        // A subject the trust manager did not validate is not a certificate.
        assertEquals(
            McpHttpBridgeErrorCode.CLIENT_CERTIFICATE_REQUIRED,
            bridge.handle(
                request(transport = tls().copy(peerCertificateSubject = "CN=client")),
                NOW,
            ).errorCode,
        )
        assertEquals(
            200,
            bridge.handle(
                request(
                    transport = tls().copy(
                        peerCertificateSubject = "CN=client",
                        peerCertificateVerified = true,
                    ),
                ),
                NOW,
            ).status,
        )
    }

    @Test
    fun forwardingMetadataFromAnUntrustedPeerIsARejectionNotAHint() = runTest {
        for (header in listOf("Forwarded", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-For")) {
            val response = directTlsBridge().handle(
                request(
                    transport = tls(),
                    extraHeaders = mapOf(header to listOf("proto=https")),
                ),
                NOW,
            )
            assertEquals(
                McpHttpBridgeErrorCode.FORWARDING_NOT_ADMITTED,
                response.errorCode,
                "$header was trusted from an unlisted peer",
            )
        }
    }

    @Test
    fun aTrustedProxyMayTerminateTlsButItsMetadataMustStillMatchTheRoute() = runTest {
        val bridge = proxiedBridge()

        // Plaintext from the trusted proxy is admitted: the proxy terminated TLS.
        assertEquals(
            200,
            bridge.handle(
                request(
                    transport = McpHttpTransportFacts(peerAddress = TRUSTED_PROXY),
                    extraHeaders = mapOf("X-Forwarded-Proto" to listOf("https")),
                ),
                NOW,
            ).status,
        )
        // The same proxy claiming a different scheme is rejected.
        assertEquals(
            McpHttpBridgeErrorCode.FORWARDED_METADATA_REJECTED,
            bridge.handle(
                request(
                    transport = McpHttpTransportFacts(peerAddress = TRUSTED_PROXY),
                    extraHeaders = mapOf("X-Forwarded-Proto" to listOf("http")),
                ),
                NOW,
            ).errorCode,
        )
        // ... or a different host.
        assertEquals(
            McpHttpBridgeErrorCode.FORWARDED_METADATA_REJECTED,
            bridge.handle(
                request(
                    transport = McpHttpTransportFacts(peerAddress = TRUSTED_PROXY),
                    extraHeaders = mapOf(
                        "X-Forwarded-Proto" to listOf("https"),
                        "X-Forwarded-Host" to listOf("attacker.test"),
                    ),
                ),
                NOW,
            ).errorCode,
        )
        // RFC 7239 syntax is read with the same rule.
        assertEquals(
            McpHttpBridgeErrorCode.FORWARDED_METADATA_REJECTED,
            bridge.handle(
                request(
                    transport = McpHttpTransportFacts(peerAddress = TRUSTED_PROXY),
                    extraHeaders = mapOf("Forwarded" to listOf("""for=203.0.113.9;proto="http"""")),
                ),
                NOW,
            ).errorCode,
        )
    }

    @Test
    fun aPeerThatIsNotTheProxyCannotBorrowTheProxyBoundary() = runTest {
        val response = proxiedBridge().handle(
            request(
                transport = McpHttpTransportFacts(peerAddress = "203.0.113.9"),
                extraHeaders = mapOf("X-Forwarded-Proto" to listOf("https")),
            ),
            NOW,
        )

        assertEquals(McpHttpBridgeErrorCode.FORWARDING_NOT_ADMITTED, response.errorCode)
    }

    private fun tls() = McpHttpTransportFacts(peerAddress = PEER, tlsProtocol = "TLSv1.3")

    private fun directTlsBridge(requireClientCertificate: Boolean = false) = bridge(
        McpHttpTransportPolicy(
            requireDirectTls = true,
            requireClientCertificate = requireClientCertificate,
        ),
    )

    private fun proxiedBridge() = bridge(
        McpHttpTransportPolicy(trustedProxies = setOf(TRUSTED_PROXY)),
    )

    private fun bridge(transportPolicy: McpHttpTransportPolicy) = McpStreamableHttpBridge(
        gateway = { GATEWAY_RESPONSE },
        endpointPolicy = McpHttpEndpointPolicy(
            scheme = "https",
            authority = AUTHORITY,
            path = "/mcp",
            transportPolicy = transportPolicy,
        ),
        authenticationVerifier = {
            McpHttpAuthenticationDecision.Accepted(subjectId = "test", credentialEpoch = "epoch-1")
        },
    )

    private fun request(
        transport: McpHttpTransportFacts,
        extraHeaders: Map<String, List<String>> = emptyMap(),
    ) = McpHttpBridgeRequest(
        method = "POST",
        scheme = "https",
        authority = AUTHORITY,
        path = "/mcp",
        headers = mapOf(
            "Content-Type" to listOf("application/json"),
            "Accept" to listOf("application/json, text/event-stream"),
            "Authorization" to listOf("Bearer test"),
        ) + extraHeaders,
        body = PING_BODY,
        transport = transport,
    )

    private companion object {
        const val AUTHORITY = "mcp.example.test:8443"
        const val PEER = "203.0.113.7"
        const val TRUSTED_PROXY = "10.0.0.1"
        const val NOW = 1_700_000_000_000L
        const val PING_BODY = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        const val GATEWAY_RESPONSE = """{"jsonrpc":"2.0","id":1,"result":{}}"""
    }
}
