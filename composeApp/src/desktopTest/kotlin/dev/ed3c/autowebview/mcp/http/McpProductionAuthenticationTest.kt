package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class McpProductionAuthenticationTest {
    private val rsaKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
    private val ecKeys = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val rotatedKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()

    private val keySource = McpJwtKeySource { keyId, algorithm ->
        when {
            keyId == KEY_ID && algorithm == McpJsonWebToken.ALG_RS256 -> rsaKeys.public
            keyId == ROTATED_KEY_ID && algorithm == McpJsonWebToken.ALG_RS256 -> rotatedKeys.public
            keyId == EC_KEY_ID && algorithm == McpJsonWebToken.ALG_ES256 -> ecKeys.public
            else -> null
        }
    }

    // ---------- OAuth bearer ----------

    @Test
    fun anAdmittedAccessTokenAuthenticatesWithAnOpaqueSubject() = runBlocking {
        val decision = oauth().verify(input(bearer(accessToken())))

        val accepted = assertIs<McpHttpAuthenticationDecision.Accepted>(decision)
        assertEquals(KEY_ID, accepted.credentialEpoch)
        // The upstream account name must not survive into the bridge identity.
        assertNotEquals(SUBJECT, accepted.subjectId)
        assertFalse(SUBJECT in accepted.subjectId)
    }

    @Test
    fun es256IsAdmittedAlongsideRs256() = runBlocking {
        val token = signedToken(
            keyId = EC_KEY_ID,
            algorithm = McpJsonWebToken.ALG_ES256,
            keys = ecKeys,
            claims = defaultClaims(),
        )

        assertIs<McpHttpAuthenticationDecision.Accepted>(oauth().verify(input(bearer(token))))
        Unit
    }

    @Test
    fun keyRotationChangesTheCredentialEpochAndRetiresTheOldKey() = runBlocking {
        val rotatedSource = McpJwtKeySource { keyId, algorithm ->
            rotatedKeys.public.takeIf { keyId == ROTATED_KEY_ID && algorithm == McpJsonWebToken.ALG_RS256 }
        }
        val verifier = McpOAuthBearerVerifier(profile(), rotatedSource, SCHEME, AUTHORITY)

        val rotatedToken = signedToken(ROTATED_KEY_ID, McpJsonWebToken.ALG_RS256, rotatedKeys, defaultClaims())
        assertEquals(
            ROTATED_KEY_ID,
            assertIs<McpHttpAuthenticationDecision.Accepted>(
                verifier.verify(input(bearer(rotatedToken))),
            ).credentialEpoch,
        )
        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(verifier.verify(input(bearer(accessToken())))),
        )
    }

    @Test
    fun aTokenSignedByAnUnknownKeyIsRejected() = runBlocking {
        val forged = signedToken(KEY_ID, McpJsonWebToken.ALG_RS256, rotatedKeys, defaultClaims())

        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(oauth().verify(input(bearer(forged)))),
        )
    }

    @Test
    fun issuerAudienceScopeAndExpiryAreAllEnforced() = runBlocking {
        val wrongIssuer = accessToken { put("iss", "https://attacker.test") }
        val wrongAudience = accessToken { put("aud", "https://other.test/mcp") }
        val missingScope = accessToken { put("scope", "mcp:read") }
        val expired = accessToken {
            put("iat", (T0 / 1_000) - 7_200)
            put("exp", (T0 / 1_000) - 3_600)
        }
        val notYetValid = accessToken { put("nbf", (T0 / 1_000) + 3_600) }
        val unboundedLifetime = accessToken { put("exp", (T0 / 1_000) + 86_400) }
        val noExpiry = buildJsonObject {
            put("iss", ISSUER); put("sub", SUBJECT); put("aud", ENDPOINT)
            put("iat", T0 / 1_000); put("scope", "mcp:read mcp:call")
        }.let { signedToken(KEY_ID, McpJsonWebToken.ALG_RS256, rsaKeys, it) }

        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(oauth().verify(input(bearer(wrongIssuer)))),
        )
        assertEquals(
            McpHttpAuthenticationRejectionReason.INSUFFICIENT_SCOPE,
            rejection(oauth().verify(input(bearer(wrongAudience)))),
        )
        assertEquals(
            McpHttpAuthenticationRejectionReason.INSUFFICIENT_SCOPE,
            rejection(oauth().verify(input(bearer(missingScope)))),
        )
        assertEquals(
            McpHttpAuthenticationRejectionReason.EXPIRED_CREDENTIALS,
            rejection(oauth().verify(input(bearer(expired)))),
        )
        assertEquals(
            McpHttpAuthenticationRejectionReason.EXPIRED_CREDENTIALS,
            rejection(oauth().verify(input(bearer(notYetValid)))),
        )
        // A credential with no bounded lifetime is malformed, not valid forever.
        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(oauth().verify(input(bearer(unboundedLifetime)))),
        )
        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(oauth().verify(input(bearer(noExpiry)))),
        )
    }

    @Test
    fun missingOrWronglyScopedCredentialsAreDistinguishable() = runBlocking {
        assertEquals(
            McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS,
            rejection(oauth().verify(input(null))),
        )
        assertEquals(
            McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS,
            rejection(oauth().verify(input("Basic ${accessToken()}"))),
        )
        assertEquals(
            McpHttpAuthenticationRejectionReason.INSUFFICIENT_SCOPE,
            rejection(oauth().verify(input(bearer(accessToken()), authority = "other.test:8443"))),
        )
    }

    // ---------- Proof of possession ----------

    @Test
    fun proofOfPossessionBindsTheTokenToThisExactRequest() = runBlocking {
        val verifier = McpOAuthBearerVerifier(
            profile(requireProofOfPossession = true),
            keySource,
            SCHEME,
            AUTHORITY,
        )
        val thumbprint = requireNotNull(McpJsonWebToken.jwkThumbprint(ecKeys.public))
        val token = accessToken { putJsonObject("cnf") { put("jkt", thumbprint) } }

        val accepted = verifier.verify(
            McpHttpAuthenticationInput(
                authorizationHeader = "DPoP $token",
                scheme = SCHEME,
                authority = AUTHORITY,
                nowEpochMs = T0,
                proofHeader = dpopProof(token),
            ),
        )
        assertIs<McpHttpAuthenticationDecision.Accepted>(accepted)

        // Same access token, proof for a different endpoint: the binding must fail.
        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(
                verifier.verify(
                    McpHttpAuthenticationInput(
                        authorizationHeader = "DPoP $token",
                        scheme = SCHEME,
                        authority = AUTHORITY,
                        nowEpochMs = T0,
                        proofHeader = dpopProof(token, targetUrl = "https://other.test/mcp"),
                    ),
                ),
            ),
        )
        // No proof at all.
        assertEquals(
            McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS,
            rejection(
                verifier.verify(
                    McpHttpAuthenticationInput(
                        authorizationHeader = "DPoP $token",
                        scheme = SCHEME,
                        authority = AUTHORITY,
                        nowEpochMs = T0,
                    ),
                ),
            ),
        )
        // The access token itself replayed into the proof slot: the two token types are signed the
        // same way, so only the declared `typ` keeps them from being swapped.
        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(
                verifier.verify(
                    McpHttpAuthenticationInput(
                        authorizationHeader = "DPoP $token",
                        scheme = SCHEME,
                        authority = AUTHORITY,
                        nowEpochMs = T0,
                        proofHeader = token,
                    ),
                ),
            ),
        )
        // A proof signed by a key the access token never pinned.
        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(
                verifier.verify(
                    McpHttpAuthenticationInput(
                        authorizationHeader = "DPoP $token",
                        scheme = SCHEME,
                        authority = AUTHORITY,
                        nowEpochMs = T0,
                        proofHeader = dpopProof(token, signingKeys = otherEcKeys),
                    ),
                ),
            ),
        )
    }

    @Test
    fun aStaleProofIsRejectedEvenWithAValidAccessToken() = runBlocking {
        val verifier = McpOAuthBearerVerifier(
            profile(requireProofOfPossession = true),
            keySource,
            SCHEME,
            AUTHORITY,
        )
        val thumbprint = requireNotNull(McpJsonWebToken.jwkThumbprint(ecKeys.public))
        val token = accessToken { putJsonObject("cnf") { put("jkt", thumbprint) } }

        assertEquals(
            McpHttpAuthenticationRejectionReason.EXPIRED_CREDENTIALS,
            rejection(
                verifier.verify(
                    McpHttpAuthenticationInput(
                        authorizationHeader = "DPoP $token",
                        scheme = SCHEME,
                        authority = AUTHORITY,
                        nowEpochMs = T0 + 600_000,
                        proofHeader = dpopProof(token),
                    ),
                ),
            ),
        )
    }

    // ---------- mTLS ----------

    @Test
    fun mutualTlsAdmitsOnlyValidatedAndExactlyListedSubjects() = runBlocking {
        val verifier = McpMutualTlsVerifier(setOf(CERT_SUBJECT), SCHEME, AUTHORITY)
        val validated = McpHttpTransportFacts(
            peerAddress = "10.0.0.7",
            tlsProtocol = "TLSv1.3",
            peerCertificateSubject = CERT_SUBJECT,
            peerCertificateVerified = true,
        )

        assertIs<McpHttpAuthenticationDecision.Accepted>(
            verifier.verify(input(null, transport = validated)),
        )
        // Same subject string, but the host's trust manager did not validate the chain.
        assertEquals(
            McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS,
            rejection(
                verifier.verify(input(null, transport = validated.copy(peerCertificateVerified = false))),
            ),
        )
        // Validated, but a different workload than the one admitted.
        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(
                verifier.verify(
                    input(null, transport = validated.copy(peerCertificateSubject = "CN=other")),
                ),
            ),
        )
        // No TLS hop at all.
        assertEquals(
            McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS,
            rejection(verifier.verify(input(null, transport = McpHttpTransportFacts()))),
        )
    }

    // ---------- Workload identity ----------

    @Test
    fun workloadIdentityIsReadFromTheHostChannelNotTheClientHeader() = runBlocking {
        val verifier = McpWorkloadIdentityVerifier(
            issuer = ISSUER,
            admittedWorkloads = setOf(WORKLOAD),
            keys = keySource,
            expectedScheme = SCHEME,
            expectedAuthority = AUTHORITY,
        )
        val assertion = signedToken(
            KEY_ID,
            McpJsonWebToken.ALG_RS256,
            rsaKeys,
            buildJsonObject {
                put("iss", ISSUER); put("sub", WORKLOAD); put("aud", ENDPOINT)
                put("iat", T0 / 1_000); put("exp", (T0 / 1_000) + 300)
            },
        )

        assertIs<McpHttpAuthenticationDecision.Accepted>(
            verifier.verify(
                input(null, transport = McpHttpTransportFacts(workloadIdentityAssertion = assertion)),
            ),
        )
        // The very same assertion presented as a client Authorization header proves nothing.
        assertEquals(
            McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS,
            rejection(verifier.verify(input("Bearer $assertion"))),
        )

        val otherWorkload = signedToken(
            KEY_ID,
            McpJsonWebToken.ALG_RS256,
            rsaKeys,
            buildJsonObject {
                put("iss", ISSUER); put("sub", "spiffe://other"); put("aud", ENDPOINT)
                put("iat", T0 / 1_000); put("exp", (T0 / 1_000) + 300)
            },
        )
        assertEquals(
            McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
            rejection(
                verifier.verify(
                    input(
                        null,
                        transport = McpHttpTransportFacts(workloadIdentityAssertion = otherWorkload),
                    ),
                ),
            ),
        )
    }

    // ---------- helpers ----------

    private val otherEcKeys = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun profile(requireProofOfPossession: Boolean = false) = McpOAuthProfile(
        issuer = ISSUER,
        requiredScopes = setOf("mcp:call"),
        requireProofOfPossession = requireProofOfPossession,
    )

    private fun oauth() = McpOAuthBearerVerifier(profile(), keySource, SCHEME, AUTHORITY)

    private fun input(
        authorizationHeader: String?,
        authority: String = AUTHORITY,
        transport: McpHttpTransportFacts = McpHttpTransportFacts(),
    ) = McpHttpAuthenticationInput(
        authorizationHeader = authorizationHeader,
        scheme = SCHEME,
        authority = authority,
        nowEpochMs = T0,
        transport = transport,
    )

    private fun bearer(token: String) = "Bearer $token"

    private fun rejection(decision: McpHttpAuthenticationDecision) =
        assertIs<McpHttpAuthenticationDecision.Rejected>(decision).reason

    private fun defaultClaims(): JsonObject = buildJsonObject {
        put("iss", ISSUER)
        put("sub", SUBJECT)
        put("aud", ENDPOINT)
        put("iat", T0 / 1_000)
        put("exp", (T0 / 1_000) + 600)
        put("scope", "mcp:read mcp:call")
    }

    private fun accessToken(override: JsonObjectBuilder.() -> Unit = {}): String {
        val base = defaultClaims()
        val claims = buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            override()
        }
        return signedToken(KEY_ID, McpJsonWebToken.ALG_RS256, rsaKeys, claims)
    }

    private fun dpopProof(
        accessToken: String,
        targetUrl: String = ENDPOINT,
        signingKeys: KeyPair = ecKeys,
        issuedAtSeconds: Long = T0 / 1_000,
    ): String {
        val header = buildJsonObject {
            put("alg", McpJsonWebToken.ALG_ES256)
            put("typ", "dpop+jwt")
            put("kid", "dpop")
            put("jwk", publicJwk(signingKeys.public))
        }
        val claims = buildJsonObject {
            put("htm", "POST")
            put("htu", targetUrl)
            put("iat", issuedAtSeconds)
            put("jti", "proof-1")
            put(
                "ath",
                base64Url(MessageDigest.getInstance("SHA-256").digest(accessToken.encodeToByteArray())),
            )
        }
        return sign(header, claims, signingKeys, McpJsonWebToken.ALG_ES256)
    }

    private fun publicJwk(key: PublicKey): JsonObject {
        val ec = key as java.security.interfaces.ECPublicKey
        val size = (ec.params.curve.field.fieldSize + 7) / 8
        return buildJsonObject {
            put("crv", "P-256")
            put("kty", "EC")
            put("x", base64Url(fixedWidth(ec.w.affineX.toByteArray(), size)))
            put("y", base64Url(fixedWidth(ec.w.affineY.toByteArray(), size)))
        }
    }

    private fun signedToken(
        keyId: String,
        algorithm: String,
        keys: KeyPair,
        claims: JsonObject,
    ): String = sign(
        header = buildJsonObject {
            put("alg", algorithm)
            put("typ", "JWT")
            put("kid", keyId)
        },
        claims = claims,
        keys = keys,
        algorithm = algorithm,
    )

    private fun sign(
        header: JsonObject,
        claims: JsonObject,
        keys: KeyPair,
        algorithm: String,
    ): String {
        val signingInput =
            "${base64Url(header.toString().encodeToByteArray())}.${base64Url(claims.toString().encodeToByteArray())}"
        val jdkAlgorithm =
            if (algorithm == McpJsonWebToken.ALG_RS256) "SHA256withRSA" else "SHA256withECDSAinP1363Format"
        val signature = Signature.getInstance(jdkAlgorithm).apply {
            initSign(keys.private)
            update(signingInput.encodeToByteArray())
        }.sign()
        return "$signingInput.${base64Url(signature)}"
    }

    private fun fixedWidth(value: ByteArray, size: Int): ByteArray {
        val magnitude = value.dropWhile { it == 0.toByte() }.toByteArray()
        return if (magnitude.size >= size) magnitude else ByteArray(size - magnitude.size) + magnitude
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val SCHEME = "https"
        const val AUTHORITY = "mcp.example.test:8443"
        const val ENDPOINT = "https://mcp.example.test:8443/mcp"
        const val ISSUER = "https://issuer.example.test"
        const val SUBJECT = "account-1234567890"
        const val WORKLOAD = "spiffe://cluster/ns/default/sa/mcp-client"
        const val CERT_SUBJECT = "CN=mcp-client,OU=agents,O=ed3c"
        const val KEY_ID = "key-2026-08"
        const val ROTATED_KEY_ID = "key-2026-09"
        const val EC_KEY_ID = "key-ec-2026-08"
        const val T0 = 1_770_000_000_000L
    }
}
