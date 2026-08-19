package dev.ed3c.autowebview.executor.webview

import dev.ed3c.autowebview.executor.BrowserActionKind
import dev.ed3c.autowebview.executor.BrowserActionPayload
import dev.ed3c.autowebview.executor.FillTextPayload
import dev.ed3c.autowebview.executor.SelectOptionPayload
import java.net.URI
import java.security.MessageDigest

data class PlaySafeWebViewPolicy(
    val allowedOrigins: Set<String>,
) {
    val normalizedOrigins: Set<String> = allowedOrigins.map(::normalizeHttpsOrigin).toSet()

    init {
        require(allowedOrigins.isNotEmpty()) { "Play-safe WebView requires an explicit owned-origin allowlist" }
        require(allowedOrigins.size <= 16) { "Play-safe WebView origin allowlist is unbounded" }
    }

    fun admits(pageUrl: String): Boolean = runCatching {
        normalizeHttpsOrigin(pageUrl) in normalizedOrigins
    }.getOrDefault(false)
}

data class PlaySafeWebElementObservation(
    val pageUrl: String,
    val pageNonce: String,
    val localId: String,
    val token: String,
    val tag: String,
    val role: String?,
    val accessibleName: String,
    val inputType: String?,
    val visible: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val sensitivity: String,
    val documentDigestSha256: String,
    val valueDigestSha256: String?,
) {
    val fingerprint: String = playSafeFingerprint(
        pageUrl = pageUrl,
        pageNonce = pageNonce,
        localId = localId,
        tag = tag,
        role = role,
        accessibleName = accessibleName,
        inputType = inputType,
    )
}

enum class PlaySafePostconditionVerdict {
    VERIFIED_APPLIED,
    INCONCLUSIVE,
}

object PlaySafeWebPostconditionVerifier {
    fun verify(
        kind: BrowserActionKind,
        payload: BrowserActionPayload,
        expectedFingerprint: String,
        preDocumentDigestSha256: String,
        post: PlaySafeWebElementObservation?,
        pageUrlChanged: Boolean,
    ): PlaySafePostconditionVerdict {
        if (kind == BrowserActionKind.CLICK && pageUrlChanged) {
            return PlaySafePostconditionVerdict.VERIFIED_APPLIED
        }
        val exact = post ?: return PlaySafePostconditionVerdict.INCONCLUSIVE
        if (exact.fingerprint != expectedFingerprint) return PlaySafePostconditionVerdict.INCONCLUSIVE
        return when (kind) {
            BrowserActionKind.CLICK -> if (exact.documentDigestSha256 != preDocumentDigestSha256) {
                PlaySafePostconditionVerdict.VERIFIED_APPLIED
            } else {
                PlaySafePostconditionVerdict.INCONCLUSIVE
            }

            BrowserActionKind.FILL_TEXT -> {
                val value = (payload as? FillTextPayload)?.value
                    ?: return PlaySafePostconditionVerdict.INCONCLUSIVE
                if (exact.valueDigestSha256 == sha256(value)) {
                    PlaySafePostconditionVerdict.VERIFIED_APPLIED
                } else {
                    PlaySafePostconditionVerdict.INCONCLUSIVE
                }
            }

            BrowserActionKind.SELECT_OPTION -> {
                val value = (payload as? SelectOptionPayload)?.value
                    ?: return PlaySafePostconditionVerdict.INCONCLUSIVE
                if (exact.valueDigestSha256 == sha256(value)) {
                    PlaySafePostconditionVerdict.VERIFIED_APPLIED
                } else {
                    PlaySafePostconditionVerdict.INCONCLUSIVE
                }
            }
        }
    }
}

internal fun normalizeHttpsOrigin(value: String): String {
    val uri = URI(value)
    require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS owned origins are executable" }
    require(uri.host != null && uri.host.isNotBlank()) { "Executable WebView URL requires an exact host" }
    require(uri.userInfo == null) { "Executable WebView origin cannot contain user info" }
    val host = uri.host.lowercase()
    val port = uri.port
    require(port == -1 || port in 1..65535) { "Executable WebView origin has invalid port" }
    return if (port == -1 || port == 443) "https://$host" else "https://$host:$port"
}

internal fun playSafeFingerprint(
    pageUrl: String,
    pageNonce: String,
    localId: String,
    tag: String,
    role: String?,
    accessibleName: String,
    inputType: String?,
): String = sha256(
    listOf(
        pageUrl,
        pageNonce,
        localId,
        tag.lowercase(),
        role.orEmpty().lowercase(),
        accessibleName,
        inputType.orEmpty().lowercase(),
    ).joinToString("\u001f"),
)

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
