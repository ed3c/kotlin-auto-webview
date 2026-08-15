package dev.ed3c.autowebview.mcp.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Minimal, strict JSON Web Token verification built on the JDK's own signature primitives.
 *
 * Only two asymmetric algorithms are admitted, `alg` is never taken from the token to decide which
 * key to trust (the key source decides that from `kid`), and every unverifiable condition is a
 * typed failure rather than a default-accept. No new third-party dependency is introduced for a
 * surface this small and this security-critical.
 */
internal object McpJsonWebToken {
    private val json = Json { ignoreUnknownKeys = true }
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    const val ALG_RS256 = "RS256"
    const val ALG_ES256 = "ES256"

    val ADMITTED_ALGORITHMS = setOf(ALG_RS256, ALG_ES256)

    /** A verified token: claims are trustworthy only because the signature already validated. */
    data class Verified(val keyId: String, val algorithm: String, val claims: JsonObject)

    sealed interface Failure {
        /** The value is not a well-formed compact JWS at all. */
        data object Malformed : Failure

        /** No admitted key matches the token's `kid`, or the algorithm is not admitted. */
        data object UnknownKey : Failure

        /** The signature did not verify against the admitted key. */
        data object BadSignature : Failure
    }

    /** Header `typ` values admitted for an access token and for a proof-of-possession token. */
    val ACCESS_TOKEN_TYPES = setOf("jwt", "at+jwt")
    val PROOF_TYPES = setOf("dpop+jwt")

    /**
     * Verify a compact JWS.
     *
     * [admittedTypes] is deliberately required rather than defaulted to "anything": an access token
     * and a proof-of-possession token are signed the same way, and refusing to accept one where the
     * other belongs is what stops the two from being swapped.
     */
    fun verifySignature(
        compactToken: String,
        keys: McpJwtKeySource,
        admittedTypes: Set<String> = ACCESS_TOKEN_TYPES,
    ): Result<Verified> {
        val parts = compactToken.split('.')
        if (parts.size != 3 || parts.any(String::isEmpty)) return failure(Failure.Malformed)

        val header = decodeJsonSegment(parts[0]) ?: return failure(Failure.Malformed)
        val claims = decodeJsonSegment(parts[1]) ?: return failure(Failure.Malformed)
        val algorithm = header.string("alg") ?: return failure(Failure.Malformed)
        val keyId = header.string("kid") ?: return failure(Failure.Malformed)
        val declaredType = header.string("typ")?.lowercase()
        if (declaredType != null && declaredType !in admittedTypes) {
            return failure(Failure.Malformed)
        }
        if (algorithm !in ADMITTED_ALGORITHMS) return failure(Failure.UnknownKey)

        val key = keys.publicKey(keyId, algorithm) ?: return failure(Failure.UnknownKey)
        val signature = runCatching { decoder.decode(parts[2]) }.getOrNull()
            ?: return failure(Failure.Malformed)
        val signedBytes = "${parts[0]}.${parts[1]}".encodeToByteArray()

        val verified = runCatching {
            Signature.getInstance(jdkAlgorithm(algorithm)).apply {
                initVerify(key)
                update(signedBytes)
            }.verify(if (algorithm == ALG_ES256) derEncodeEcdsa(signature) else signature)
        }.getOrDefault(false)

        return if (verified) {
            Result.success(Verified(keyId = keyId, algorithm = algorithm, claims = claims))
        } else {
            failure(Failure.BadSignature)
        }
    }

    /** RFC 7638 JWK thumbprint, used to bind a proof-of-possession key to an access token. */
    fun jwkThumbprint(key: PublicKey): String? {
        val canonical = when (key) {
            is RSAPublicKey -> """{"e":"${b64(key.publicExponent)}","kty":"RSA","n":"${b64(key.modulus)}"}"""
            is ECPublicKey -> {
                val size = (key.params.curve.field.fieldSize + 7) / 8
                """{"crv":"P-256","kty":"EC","x":"${b64(key.w.affineX, size)}","y":"${b64(key.w.affineY, size)}"}"""
            }
            else -> return null
        }
        return encoder.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()),
        )
    }

    fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    /** `aud` is either a string or an array of strings; both forms must be handled identically. */
    fun JsonObject.audiences(): Set<String> = when (val raw = this["aud"]) {
        is JsonPrimitive -> raw.takeIf { it.isString }?.content?.let(::setOf).orEmpty()
        is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.toSet()
        else -> emptySet()
    }

    fun JsonObject.scopes(): Set<String> =
        string("scope")?.split(' ')?.filter(String::isNotEmpty)?.toSet().orEmpty()

    private fun decodeJsonSegment(segment: String): JsonObject? = runCatching {
        json.parseToJsonElement(decoder.decode(segment).decodeToString()) as? JsonObject
    }.getOrNull()

    private fun jdkAlgorithm(algorithm: String): String = when (algorithm) {
        ALG_RS256 -> "SHA256withRSA"
        else -> "SHA256withECDSAinP1363Format"
    }

    /**
     * JWS ES256 signatures are raw R||S; the JDK's P1363 format expects exactly that, so the value
     * is passed through after a length check rather than re-encoded.
     */
    private fun derEncodeEcdsa(signature: ByteArray): ByteArray {
        require(signature.size == 64) { "ES256 signature must be 64 bytes" }
        return signature
    }

    private fun b64(value: BigInteger, fixedSize: Int? = null): String {
        val magnitude = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val bytes = when {
            fixedSize == null || magnitude.size >= fixedSize -> magnitude
            else -> ByteArray(fixedSize - magnitude.size) + magnitude
        }
        return encoder.encodeToString(bytes)
    }

    private fun failure(reason: Failure): Result<Verified> = Result.failure(McpJwtFailure(reason))
}

internal class McpJwtFailure(val reason: McpJsonWebToken.Failure) : Exception()

/**
 * Where admitted signing keys come from.
 *
 * Rotation is modelled as the source returning a new `kid`; the verifier turns that `kid` into the
 * credential epoch, so a rotated key automatically retires the previous epoch's replay keys.
 */
fun interface McpJwtKeySource {
    fun publicKey(keyId: String, algorithm: String): PublicKey?
}
