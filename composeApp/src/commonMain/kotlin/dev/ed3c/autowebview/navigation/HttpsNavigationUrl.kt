package dev.ed3c.autowebview.navigation

import io.ktor.http.Url

/**
 * Validates and normalizes agent-proposed navigation URLs.
 * HTTPS only, non-empty host, no userinfo/credentials, no control characters.
 */
object HttpsNavigationUrl {
    private const val MAX_URL_CHARS = 2_048

    fun validateAndNormalize(raw: String): Result<String> {
        if (raw.isEmpty()) return Result.failure(IllegalArgumentException("url is empty"))
        if (raw.length > MAX_URL_CHARS) {
            return Result.failure(IllegalArgumentException("url exceeds $MAX_URL_CHARS characters"))
        }
        if (raw.any { it.code < 0x20 || it.code == 0x7f }) {
            return Result.failure(IllegalArgumentException("url contains control characters"))
        }
        if (!raw.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("only HTTPS navigation is accepted"))
        }

        val url = try {
            Url(raw)
        } catch (failure: Exception) {
            return Result.failure(IllegalArgumentException("url is not a valid URI", failure))
        }

        if (!url.protocol.name.equals("https", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("only HTTPS navigation is accepted"))
        }
        if (url.host.isBlank()) {
            return Result.failure(IllegalArgumentException("url host must be non-empty"))
        }
        if (!url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException("url must not contain userinfo/credentials"))
        }

        // Normalized form: scheme lowercase, host lowercase, preserve path/query/fragment encoding as Url string.
        val normalized = buildString {
            append("https://")
            append(url.host.lowercase())
            if (url.port != 443 && url.port > 0) {
                append(':')
                append(url.port)
            }
            val path = url.encodedPath
            if (path.isEmpty()) append('/') else append(path)
            if (url.encodedQuery.isNotEmpty()) {
                append('?')
                append(url.encodedQuery)
            }
            if (url.encodedFragment.isNotEmpty()) {
                append('#')
                append(url.encodedFragment)
            }
        }
        return Result.success(normalized)
    }

    fun mainFrameMatches(observed: String, expectedNormalized: String): Boolean {
        val observedNormalized = validateAndNormalize(observed).getOrNull() ?: return false
        return observedNormalized == expectedNormalized
    }
}
