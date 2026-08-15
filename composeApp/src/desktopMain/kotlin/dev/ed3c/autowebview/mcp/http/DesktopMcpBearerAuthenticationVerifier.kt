package dev.ed3c.autowebview.mcp.http

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop host-side bearer verifier with a fixed-length digest comparison.
 *
 * The raw expected token is copied only long enough to compute a SHA-256 digest, then the copy is
 * zeroed. Candidate bytes and candidate digests are also zeroed after verification. The verifier
 * never renders the authority, token, subject, or credential epoch.
 */
internal class DesktopMcpBearerAuthenticationVerifier(
    expectedToken: ByteArray,
    private val expectedAuthority: String,
    private val subjectId: String,
    private val credentialEpoch: String,
) : McpHttpAuthenticationVerifier, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val expectedDigest: ByteArray

    init {
        validateExpectedToken(expectedToken)
        val tokenCopy = expectedToken.copyOf()
        expectedDigest = try {
            sha256(tokenCopy)
        } finally {
            tokenCopy.fill(0)
        }
    }

    override suspend fun verify(input: McpHttpAuthenticationInput): McpHttpAuthenticationDecision {
        if (closed.get()) {
            return rejected(McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS)
        }
        if (input.scheme != "http" || input.authority != expectedAuthority) {
            return rejected(McpHttpAuthenticationRejectionReason.INSUFFICIENT_SCOPE)
        }
        val header = input.authorizationHeader
            ?: return rejected(McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS)
        val candidate = parseBearerToken(header)
            ?: return rejected(McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS)
        val candidateDigest = try {
            sha256(candidate)
        } finally {
            candidate.fill(0)
        }

        return try {
            if (MessageDigest.isEqual(expectedDigest, candidateDigest)) {
                McpHttpAuthenticationDecision.Accepted(
                    subjectId = subjectId,
                    credentialEpoch = credentialEpoch,
                )
            } else {
                rejected(McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS)
            }
        } finally {
            candidateDigest.fill(0)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) expectedDigest.fill(0)
    }

    override fun toString(): String =
        "DesktopMcpBearerAuthenticationVerifier(authority=<redacted>, credential=<redacted>, subject=<redacted>)"

    private fun parseBearerToken(header: String): ByteArray? {
        if (header.any(Char::isISOControl)) return null
        val prefix = "Bearer "
        if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
        val value = header.substring(prefix.length)
        if (value.isEmpty() || value != value.trim()) return null
        if (value.length > MAXIMUM_BEARER_TOKEN_BYTES) return null
        if (value.any { character -> character.code !in PRINTABLE_ASCII_RANGE }) return null
        return value.encodeToByteArray().takeIf { bytes ->
            bytes.size in 1..MAXIMUM_BEARER_TOKEN_BYTES
        }
    }

    private fun rejected(
        reason: McpHttpAuthenticationRejectionReason,
    ): McpHttpAuthenticationDecision.Rejected = McpHttpAuthenticationDecision.Rejected(reason)

    private companion object {
        const val MINIMUM_BEARER_TOKEN_BYTES = 32
        const val MAXIMUM_BEARER_TOKEN_BYTES = 4_096
        const val MINIMUM_DISTINCT_TOKEN_BYTES = 8
        val PRINTABLE_ASCII_RANGE = 0x21..0x7e

        fun validateExpectedToken(token: ByteArray) {
            require(token.size in MINIMUM_BEARER_TOKEN_BYTES..MAXIMUM_BEARER_TOKEN_BYTES) {
                "Desktop MCP bearer token length is outside the admitted range"
            }
            require(token.all { byte -> (byte.toInt() and 0xff) in PRINTABLE_ASCII_RANGE }) {
                "Desktop MCP bearer token must contain printable non-whitespace ASCII only"
            }
            require(token.toSet().size >= MINIMUM_DISTINCT_TOKEN_BYTES) {
                "Desktop MCP bearer token has insufficient byte diversity"
            }
        }

        fun sha256(value: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value)
    }
}
