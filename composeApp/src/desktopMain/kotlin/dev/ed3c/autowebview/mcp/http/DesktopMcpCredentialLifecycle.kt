package dev.ed3c.autowebview.mcp.http

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-owned runtime credential lifecycle for the Desktop MCP listener.
 *
 * The lifecycle owns issuance, rotation, expiry, and revocation. Raw credential bytes exist only
 * inside a [DesktopMcpCredentialMaterial] handle that the host must consume exactly once; the
 * lifecycle itself retains a fixed-length SHA-256 digest and never renders, logs, or returns the
 * credential again. Rotation keeps the previous epoch verifiable for a bounded handover so an
 * already-launched child process is not severed mid-call, and revocation is immediate and final.
 */
class DesktopMcpCredentialLifecycle(
    private val expectedScheme: String,
    private val expectedAuthority: String,
    private val subjectId: String,
    private val credentialLifetimeMillis: Long = DEFAULT_CREDENTIAL_LIFETIME_MILLIS,
    private val handoverMillis: Long = DEFAULT_HANDOVER_MILLIS,
    private val random: SecureRandom = SecureRandom(),
    /**
     * Optional at-rest custody. When absent, custody stays in-process exactly as before and a
     * restart requires a fresh issuance — the caller decides that, this class never assumes it.
     */
    private val keyStore: McpHostKeyStore? = null,
) : McpHttpAuthenticationVerifier, AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)

    private var nextEpochOrdinal: Int = 1
    private var active: AdmittedCredential? = null
    private var retiring: AdmittedCredential? = null
    private var revoked: Boolean = false

    init {
        require(subjectId.matches(OPAQUE_ID_PATTERN)) { "MCP credential subject ID is invalid" }
        require(credentialLifetimeMillis in 1..MAX_LIFETIME_MILLIS) {
            "MCP credential lifetime is outside the admitted range"
        }
        require(handoverMillis in 0..MAX_HANDOVER_MILLIS) {
            "MCP credential handover window is outside the admitted range"
        }
        require(handoverMillis <= credentialLifetimeMillis) {
            "MCP credential handover window cannot exceed the credential lifetime"
        }
    }

    /** Current lifecycle state without disclosing any credential material. */
    val state: DesktopMcpCredentialState
        get() = synchronized(lock) {
            when {
                closed.get() -> DesktopMcpCredentialState.CLOSED
                revoked -> DesktopMcpCredentialState.REVOKED
                active == null -> DesktopMcpCredentialState.UNINITIALIZED
                retiring != null -> DesktopMcpCredentialState.ROTATING
                else -> DesktopMcpCredentialState.READY
            }
        }

    /** Ordinal of the active epoch, or `null` before first issuance. */
    val activeEpoch: String?
        get() = synchronized(lock) { active?.epoch }

    /**
     * Issue the first credential. Fails if a credential is already active; use [rotate] instead.
     */
    fun issue(nowEpochMs: Long): DesktopMcpCredentialMaterial = synchronized(lock) {
        check(!closed.get()) { "MCP credential lifecycle is closed" }
        check(!revoked) { "MCP credential lifecycle is revoked" }
        check(active == null) { "MCP credential is already issued; rotate instead" }
        mint(nowEpochMs)
    }

    /**
     * Rotate to a higher epoch. The prior epoch stays verifiable until `nowEpochMs + handover`,
     * after which it is rejected as expired. An in-flight prior handover is dropped immediately so
     * at most one superseded epoch is ever verifiable.
     */
    fun rotate(nowEpochMs: Long): DesktopMcpCredentialMaterial = synchronized(lock) {
        check(!closed.get()) { "MCP credential lifecycle is closed" }
        check(!revoked) { "MCP credential lifecycle is revoked" }
        val current = active ?: error("MCP credential has not been issued")
        retiring?.digest?.fill(0)
        retiring = current.copy(notAfterEpochMs = nowEpochMs + handoverMillis)
        mint(nowEpochMs)
    }

    /**
     * Immediately reject every previously issued credential, including the active epoch.
     *
     * Revocation removes the value from at-rest custody too. Clearing memory while leaving a
     * restorable copy on disk would make the next start silently un-revoke it.
     */
    fun revoke() = synchronized(lock) {
        revoked = true
        active?.digest?.fill(0)
        retiring?.digest?.fill(0)
        active = null
        retiring = null
        runCatching { keyStore?.delete(storeAccount()) }
    }

    override suspend fun verify(input: McpHttpAuthenticationInput): McpHttpAuthenticationDecision {
        val candidate = run {
            if (closed.get()) return rejected(McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS)
            if (input.scheme != expectedScheme || input.authority != expectedAuthority) {
                return rejected(McpHttpAuthenticationRejectionReason.INSUFFICIENT_SCOPE)
            }
            val header = input.authorizationHeader
                ?: return rejected(McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS)
            parseBearerToken(header)
                ?: return rejected(McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS)
        }
        val candidateDigest = try {
            sha256(candidate)
        } finally {
            candidate.fill(0)
        }

        return try {
            synchronized(lock) {
                if (revoked || closed.get()) {
                    return@synchronized rejected(McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS)
                }
                val match = listOfNotNull(active, retiring)
                    .firstOrNull { MessageDigest.isEqual(it.digest, candidateDigest) }
                    ?: return@synchronized rejected(
                        McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
                    )
                if (input.nowEpochMs > match.notAfterEpochMs) {
                    return@synchronized rejected(
                        McpHttpAuthenticationRejectionReason.EXPIRED_CREDENTIALS,
                    )
                }
                McpHttpAuthenticationDecision.Accepted(
                    subjectId = subjectId,
                    credentialEpoch = match.epoch,
                )
            }
        } finally {
            candidateDigest.fill(0)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            active?.digest?.fill(0)
            retiring?.digest?.fill(0)
            active = null
            retiring = null
        }
    }

    override fun toString(): String =
        "DesktopMcpCredentialLifecycle(state=$state, authority=<redacted>, credential=<redacted>)"

    private fun mint(nowEpochMs: Long): DesktopMcpCredentialMaterial {
        val epoch = "epoch-${nextEpochOrdinal++}"
        val token = generateToken()
        val digest = sha256(token)
        active = AdmittedCredential(
            epoch = epoch,
            digest = digest,
            notAfterEpochMs = nowEpochMs + credentialLifetimeMillis,
        )
        // The epoch travels with the value: a restored credential has to verify under the same
        // epoch it was issued with, or the semantic replay keys from #43 would silently change
        // domain across a restart.
        keyStore?.store(storeAccount(), "$epoch ${token.decodeToString()}".encodeToByteArray())
        return DesktopMcpCredentialMaterial(epoch = epoch, token = token)
    }

    /**
     * Re-establish the credential a previous process issued, without minting a new one.
     *
     * Returns `null` when nothing is stored, which is a different answer from "a credential is
     * available" and is the caller's cue to [issue] instead. The stored epoch is restored with the
     * value, and the ordinal continues past it so a later [rotate] cannot reuse a retired epoch.
     */
    fun restore(nowEpochMs: Long): DesktopMcpCredentialMaterial? = synchronized(lock) {
        check(!closed.get()) { "MCP credential lifecycle is closed" }
        check(!revoked) { "MCP credential lifecycle is revoked" }
        check(active == null) { "MCP credential is already issued" }

        val store = keyStore ?: return null
        val stored = store.retrieve(storeAccount()) ?: return null
        val text = stored.decodeToString()
        stored.fill(0)

        val epoch = text.substringBefore(' ', missingDelimiterValue = "")
        val value = text.substringAfter(' ', missingDelimiterValue = "")
        if (!epoch.matches(OPAQUE_ID_PATTERN) || value.isEmpty()) {
            // A damaged record is not a credential. Removing it makes the next call a clean issue
            // rather than an unexplained repeated failure.
            store.delete(storeAccount())
            return null
        }

        val token = value.encodeToByteArray()
        active = AdmittedCredential(
            epoch = epoch,
            digest = sha256(token),
            notAfterEpochMs = nowEpochMs + credentialLifetimeMillis,
        )
        nextEpochOrdinal = maxOf(nextEpochOrdinal, (epoch.substringAfterLast('-').toIntOrNull() ?: 0) + 1)
        return DesktopMcpCredentialMaterial(epoch = epoch, token = token)
    }

    private fun storeAccount(): String = "$subjectId@$expectedAuthority"

    private fun generateToken(): ByteArray {
        val entropy = ByteArray(CREDENTIAL_ENTROPY_BYTES)
        return try {
            random.nextBytes(entropy)
            BASE64_URL.encode(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    private fun rejected(
        reason: McpHttpAuthenticationRejectionReason,
    ): McpHttpAuthenticationDecision.Rejected = McpHttpAuthenticationDecision.Rejected(reason)

    private data class AdmittedCredential(
        val epoch: String,
        val digest: ByteArray,
        val notAfterEpochMs: Long,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    companion object {
        /** Environment variable name an approved child process reads the credential from. */
        const val CHILD_PROCESS_ENVIRONMENT_NAME: String = "DSH_MCP_BEARER_TOKEN"

        private const val CREDENTIAL_ENTROPY_BYTES = 48
        private const val MAX_LIFETIME_MILLIS = 30L * 24 * 60 * 60 * 1_000
        private const val MAX_HANDOVER_MILLIS = 60L * 60 * 1_000
        private const val DEFAULT_CREDENTIAL_LIFETIME_MILLIS = 12L * 60 * 60 * 1_000
        private const val DEFAULT_HANDOVER_MILLIS = 30L * 1_000
        private const val MAXIMUM_BEARER_TOKEN_BYTES = 4_096
        private val PRINTABLE_ASCII_RANGE = 0x21..0x7e
        private val OPAQUE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        private val BASE64_URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        private fun sha256(value: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value)

        private fun parseBearerToken(header: String): ByteArray? {
            if (header.any(Char::isISOControl)) return null
            val prefix = "Bearer "
            if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
            val value = header.substring(prefix.length)
            if (value.isEmpty() || value != value.trim()) return null
            if (value.length > MAXIMUM_BEARER_TOKEN_BYTES) return null
            if (value.any { character -> character.code !in PRINTABLE_ASCII_RANGE }) return null
            return value.encodeToByteArray()
        }
    }
}

enum class DesktopMcpCredentialState {
    UNINITIALIZED,
    READY,
    ROTATING,
    REVOKED,
    CLOSED,
}

/**
 * One-shot handle to freshly issued credential bytes.
 *
 * The host consumes the material exactly once — typically to start the listener and to inject the
 * value into one approved child process environment — and the bytes are zeroed on the way out.
 */
class DesktopMcpCredentialMaterial internal constructor(
    val epoch: String,
    private val token: ByteArray,
) {
    private val consumed = AtomicBoolean(false)

    /** Consume the raw bytes exactly once; the backing array is zeroed after [block] returns. */
    fun <T> use(block: (ByteArray) -> T): T {
        check(consumed.compareAndSet(false, true)) { "MCP credential material was already consumed" }
        return try {
            block(token)
        } finally {
            token.fill(0)
        }
    }

    override fun toString(): String =
        "DesktopMcpCredentialMaterial(epoch=$epoch, credential=<redacted>)"
}
