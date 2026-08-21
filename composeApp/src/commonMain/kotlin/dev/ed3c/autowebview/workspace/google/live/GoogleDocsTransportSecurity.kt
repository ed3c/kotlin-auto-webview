package dev.ed3c.autowebview.workspace.google.live

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

private val CAPABILITY_ID_PATTERN = Regex("^[A-Za-z][A-Za-z0-9._:-]{2,127}$")
private val GOOGLE_SCOPE_PATTERN = Regex("^https://www\\.googleapis\\.com/auth/[A-Za-z0-9._/-]{1,128}$")
private val GOOGLE_FILE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{6,256}$")

internal val GOOGLE_DOCS_WRITE_SCOPES = setOf(
    "https://www.googleapis.com/auth/documents",
    "https://www.googleapis.com/auth/drive.file",
    "https://www.googleapis.com/auth/drive",
)

class GoogleDocsAccessCapability private constructor(
    val capabilityId: String,
    val grantedScopes: Set<String>,
    private val accessToken: String,
) {
    init {
        require(CAPABILITY_ID_PATTERN.matches(capabilityId)) {
            "Google capability id is invalid"
        }
        require(grantedScopes.isNotEmpty()) { "Google capability must bind at least one scope" }
        require(grantedScopes.all(GOOGLE_SCOPE_PATTERN::matches)) {
            "Google capability contains an invalid scope"
        }
        require(accessToken.isNotBlank()) { "Google access token cannot be blank" }
        require(accessToken.length <= 8_192) { "Google access token is too long" }
        require(accessToken.none { it.isWhitespace() }) {
            "Google access token cannot contain whitespace"
        }
    }

    internal fun authorizationHeader(): String = "Bearer $accessToken"

    internal fun admitsDocsWrite(): Boolean = grantedScopes.any(GOOGLE_DOCS_WRITE_SCOPES::contains)

    override fun toString(): String =
        "GoogleDocsAccessCapability(capabilityId=$capabilityId, grantedScopes=$grantedScopes, accessToken=<redacted>)"

    companion object {
        fun create(
            capabilityId: String,
            grantedScopes: Set<String>,
            accessToken: String,
        ): GoogleDocsAccessCapability = GoogleDocsAccessCapability(
            capabilityId = capabilityId,
            grantedScopes = grantedScopes.toSet(),
            accessToken = accessToken,
        )
    }
}

fun interface GoogleDocsAccessCapabilityProvider {
    suspend fun current(): GoogleDocsAccessCapability?
}

class GoogleDocsApiEndpoint(
    value: String = "https://docs.googleapis.com",
) {
    val origin: String

    init {
        val url = Url(value)
        require(url.protocol.name == "https") { "Google Docs API endpoint must use HTTPS" }
        require(url.host.equals("docs.googleapis.com", ignoreCase = true)) {
            "Google Docs transport admits only docs.googleapis.com"
        }
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) {
            "Google Docs API endpoint cannot contain credentials"
        }
        require(url.parameters.isEmpty()) {
            "Google Docs API endpoint cannot contain query parameters"
        }
        require(url.fragment.isEmpty()) { "Google Docs API endpoint cannot contain a fragment" }
        require(url.encodedPath.isEmpty() || url.encodedPath == "/") {
            "Google Docs API endpoint cannot contain a path"
        }
        origin = "https://${url.host}"
    }

    fun document(fileId: String): String {
        require(GOOGLE_FILE_ID_PATTERN.matches(fileId)) { "Google Docs file id is invalid" }
        return "$origin/v1/documents/$fileId"
    }

    fun batchUpdate(fileId: String): String = "${document(fileId)}:batchUpdate"
}

sealed interface GoogleDocsHttpResult {
    data class Response(
        val statusCode: Int,
        val body: String,
        val retryAfterSeconds: Long? = null,
    ) : GoogleDocsHttpResult {
        init {
            require(statusCode in 100..599) { "Google Docs HTTP status is invalid" }
            require(retryAfterSeconds == null || retryAfterSeconds >= 0) {
                "Google Docs retry-after cannot be negative"
            }
        }
    }

    data object NetworkFailure : GoogleDocsHttpResult
}

interface GoogleDocsApiExecutor {
    suspend fun getDocument(
        fileId: String,
        capability: GoogleDocsAccessCapability,
    ): GoogleDocsHttpResult

    suspend fun batchUpdateDocument(
        fileId: String,
        capability: GoogleDocsAccessCapability,
        requestBody: String,
    ): GoogleDocsHttpResult
}

class KtorGoogleDocsApiExecutor(
    private val client: HttpClient,
    private val endpoint: GoogleDocsApiEndpoint = GoogleDocsApiEndpoint(),
) : GoogleDocsApiExecutor {
    override suspend fun getDocument(
        fileId: String,
        capability: GoogleDocsAccessCapability,
    ): GoogleDocsHttpResult = execute {
        client.get(endpoint.document(fileId)) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.Authorization, capability.authorizationHeader())
            header(HttpHeaders.UserAgent, "kotlin-auto-webview")
        }
    }

    override suspend fun batchUpdateDocument(
        fileId: String,
        capability: GoogleDocsAccessCapability,
        requestBody: String,
    ): GoogleDocsHttpResult = execute {
        client.post(endpoint.batchUpdate(fileId)) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.Authorization, capability.authorizationHeader())
            header(HttpHeaders.UserAgent, "kotlin-auto-webview")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
    }

    private suspend fun execute(block: suspend () -> HttpResponse): GoogleDocsHttpResult {
        return try {
            val response = block()
            GoogleDocsHttpResult.Response(
                statusCode = response.status.value,
                body = response.bodyAsText(),
                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GoogleDocsHttpResult.NetworkFailure
        }
    }
}
