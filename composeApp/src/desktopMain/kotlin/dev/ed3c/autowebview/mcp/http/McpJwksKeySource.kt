package dev.ed3c.autowebview.mcp.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PublicKey
import java.time.Duration

/**
 * A [McpJwtKeySource] that retrieves signing keys from an issuer's JWKS document.
 *
 * #48 left key distribution behind the [McpJwtKeySource] interface on purpose — verification logic
 * should not own network policy. The cost of leaving it there is that every deployment re-solves
 * retrieval, caching, refresh, and failure semantics, and those are exactly the places where a
 * plausible-looking implementation stops being safe.
 *
 * Three properties are the reason this class exists rather than a `fun interface` lambda:
 *
 *  - **An unknown `kid` cannot drive outbound requests.** Refreshes are rate-bounded, so a caller
 *    inventing key ids cannot make this endpoint fetch on demand.
 *  - **A failed fetch never widens trust.** A cached key is served only while the cache is still
 *    inside its admitted lifetime; once stale, an unreachable issuer means "reject", never "accept
 *    because we cannot check".
 *  - **Retirement is honoured.** A key absent from the current document stops verifying once the
 *    cache refreshes, which is how revocation and rotation actually reach this process.
 *
 * The lookup is synchronous because [McpJwtKeySource] is, so a refresh blocks the calling worker
 * for at most [requestTimeout]. That is bounded and deliberate: an unbounded wait here would be a
 * denial-of-service surface on the listener's small worker pool.
 */
class McpJwksKeySource(
    private val jwksUrl: URI,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val cacheLifetimeMillis: Long = DEFAULT_CACHE_LIFETIME_MILLIS,
    private val minimumRefreshIntervalMillis: Long = DEFAULT_MINIMUM_REFRESH_INTERVAL_MILLIS,
    private val maxDocumentBytes: Int = DEFAULT_MAX_DOCUMENT_BYTES,
    private val requestTimeout: Duration = Duration.ofSeconds(5),
    private val clock: () -> Long = System::currentTimeMillis,
) : McpJwtKeySource {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    private var cached: Map<String, JsonObject> = emptyMap()
    private var cachedAtEpochMs: Long = NEVER
    private var lastAttemptEpochMs: Long = NEVER

    init {
        require(jwksUrl.scheme?.lowercase() == "https") {
            "A JWKS endpoint must be HTTPS; signing keys retrieved over plaintext are not trusted"
        }
        require(cacheLifetimeMillis in 1..MAX_CACHE_LIFETIME_MILLIS) {
            "JWKS cache lifetime is outside the admitted range"
        }
        require(minimumRefreshIntervalMillis in 0..cacheLifetimeMillis) {
            "JWKS refresh interval must be positive and no larger than the cache lifetime"
        }
        require(maxDocumentBytes in 1..MAX_DOCUMENT_CEILING_BYTES) {
            "JWKS document budget is outside the admitted range"
        }
    }

    /** Whether the cache currently holds keys inside their admitted lifetime. */
    val hasFreshKeys: Boolean
        get() = synchronized(lock) { isFresh(clock()) }

    override fun publicKey(keyId: String, algorithm: String): PublicKey? = synchronized(lock) {
        val now = clock()

        if (isFresh(now)) {
            cached[keyId]?.let { return McpJwkParser.publicKey(it, algorithm) }
        }

        // Either the cache expired or this `kid` is unknown. Both are refresh reasons, and both
        // are rate-bounded so an attacker-chosen `kid` cannot become a request amplifier.
        //
        // `NEVER` is compared explicitly rather than arithmetically: `now - Long.MIN_VALUE`
        // overflows to a negative value, which would read as "inside the cooldown" and stop the
        // very first retrieval from ever happening.
        if (lastAttemptEpochMs != NEVER && now - lastAttemptEpochMs < minimumRefreshIntervalMillis) {
            // Inside the cooldown: answer from a fresh cache if there is one, otherwise reject.
            return if (isFresh(now)) cached[keyId]?.let { McpJwkParser.publicKey(it, algorithm) } else null
        }
        lastAttemptEpochMs = now

        val retrieved = runCatching { retrieve() }.getOrNull()
        if (retrieved == null) {
            // A failed retrieval never extends trust past the admitted lifetime.
            return if (isFresh(now)) cached[keyId]?.let { McpJwkParser.publicKey(it, algorithm) } else null
        }

        cached = retrieved
        cachedAtEpochMs = now
        return cached[keyId]?.let { McpJwkParser.publicKey(it, algorithm) }
    }

    override fun toString(): String = "McpJwksKeySource(endpoint=<redacted>, keys=${cached.size})"

    private fun isFresh(now: Long): Boolean =
        cachedAtEpochMs != NEVER && now - cachedAtEpochMs < cacheLifetimeMillis

    /** Fetch and parse the document, or throw. An empty key set is a valid full revocation. */
    private fun retrieve(): Map<String, JsonObject> {
        val request = HttpRequest.newBuilder(jwksUrl)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 200) { "JWKS endpoint returned ${response.statusCode()}" }

        val body = response.body()
        check(body.size <= maxDocumentBytes) { "JWKS document exceeds the admitted budget" }

        val document = json.parseToJsonElement(body.decodeToString()) as? JsonObject
            ?: error("JWKS document is not a JSON object")
        val keys = document["keys"] as? JsonArray ?: error("JWKS document has no key array")

        val byKeyId = linkedMapOf<String, JsonObject>()
        for (entry in keys) {
            val jwk = entry as? JsonObject ?: continue
            val keyId = (jwk["kid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            // First declaration wins: a duplicate `kid` is ambiguous, and silently preferring the
            // last one would let an appended entry displace an existing key.
            byKeyId.putIfAbsent(keyId, jwk)
        }
        return byKeyId
    }

    private companion object {
        /** Sentinel for "no retrieval has happened"; never used in arithmetic. */
        const val NEVER = Long.MIN_VALUE
        const val DEFAULT_CACHE_LIFETIME_MILLIS = 10L * 60 * 1_000
        const val DEFAULT_MINIMUM_REFRESH_INTERVAL_MILLIS = 60L * 1_000
        const val DEFAULT_MAX_DOCUMENT_BYTES = 256 * 1_024
        const val MAX_CACHE_LIFETIME_MILLIS = 24L * 60 * 60 * 1_000
        const val MAX_DOCUMENT_CEILING_BYTES = 4 * 1_024 * 1_024
    }
}
