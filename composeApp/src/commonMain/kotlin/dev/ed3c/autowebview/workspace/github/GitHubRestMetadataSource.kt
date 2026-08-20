package dev.ed3c.autowebview.workspace.github

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

fun interface GitHubTokenProvider {
    suspend fun token(): String?
}

class GitHubApiEndpoint(
    value: String = "https://api.github.com",
) {
    val origin: String

    init {
        val url = Url(value)
        require(url.protocol.name == "https") { "GitHub API endpoint must use HTTPS" }
        require(url.host.equals("api.github.com", ignoreCase = true)) {
            "W2 admits only the public api.github.com endpoint"
        }
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) {
            "GitHub API endpoint cannot contain credentials"
        }
        require(url.parameters.isEmpty()) { "GitHub API endpoint cannot contain query parameters" }
        require(url.fragment.isEmpty()) { "GitHub API endpoint cannot contain a fragment" }
        require(url.encodedPath.isEmpty() || url.encodedPath == "/") {
            "GitHub API endpoint cannot contain a path"
        }
        origin = "https://${url.host}"
    }

    fun resolve(path: String): String {
        require(path.startsWith('/')) { "GitHub API path must be absolute" }
        require(!path.contains("..")) { "GitHub API path traversal is forbidden" }
        return origin + path
    }
}

class GitHubRestMetadataSource(
    private val client: HttpClient,
    private val tokenProvider: GitHubTokenProvider = GitHubTokenProvider { null },
    private val endpoint: GitHubApiEndpoint = GitHubApiEndpoint(),
    private val decoder: GitHubRestPayloadDecoder = GitHubRestPayloadDecoder(),
    private val maxCheckPages: Int = 10,
) : GitHubMetadataSource {
    init {
        require(maxCheckPages in 1..100) { "GitHub check page limit must be bounded" }
    }

    override suspend fun read(request: GitHubWorkGraphRequest): GitHubReadResult<GitHubWorkGraphSnapshot> {
        return try {
            val slug = request.repository
            val repositoryBody = getBody(
                path = "/repos/${slug.owner}/${slug.name}",
                notFoundReason = GitHubReadFailureReason.REPOSITORY_NOT_FOUND,
            )
            val repository = decoder.decodeRepository(repositoryBody, slug)

            val issues = request.issueNumbers.sorted().map { number ->
                val body = getBody(
                    path = "/repos/${slug.owner}/${slug.name}/issues/$number",
                    notFoundReason = GitHubReadFailureReason.RESOURCE_NOT_FOUND,
                )
                decoder.decodeIssue(body, repository.repositoryId)
            }

            val pullRequests = request.pullRequestNumbers.sorted().map { number ->
                val body = getBody(
                    path = "/repos/${slug.owner}/${slug.name}/pulls/$number",
                    notFoundReason = GitHubReadFailureReason.RESOURCE_NOT_FOUND,
                )
                decoder.decodePullRequest(body, repository.repositoryId)
            }

            val commits = request.commitShas.sorted().map { sha ->
                val body = getBody(
                    path = "/repos/${slug.owner}/${slug.name}/commits/$sha",
                    notFoundReason = GitHubReadFailureReason.RESOURCE_NOT_FOUND,
                )
                decoder.decodeCommit(body, repository.repositoryId, expectedSha = sha)
            }

            val checkRuns = if (request.includeChecksForPullRequests) {
                pullRequests
                    .map(GitHubPullRequestSnapshot::headSha)
                    .distinct()
                    .sorted()
                    .flatMap { sha -> readCheckRuns(slug, repository.repositoryId, sha) }
                    .distinctBy(GitHubCheckRunSnapshot::checkRunId)
            } else {
                emptyList()
            }

            GitHubReadResult.Success(
                GitHubWorkGraphSnapshot(
                    repository = repository,
                    issues = issues,
                    pullRequests = pullRequests,
                    commits = commits,
                    checkRuns = checkRuns,
                    linkedIssueNumbersByPullRequest = request.linkedIssueNumbersByPullRequest,
                    observationSequence = request.observationSequence,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GitHubReadAbort) {
            GitHubReadResult.Unavailable(
                reason = failure.reason,
                retryAfterSeconds = failure.retryAfterSeconds,
            )
        } catch (_: IllegalArgumentException) {
            GitHubReadResult.Unavailable(GitHubReadFailureReason.RESPONSE_MISMATCH)
        } catch (_: Exception) {
            GitHubReadResult.Unavailable(GitHubReadFailureReason.NETWORK_FAILURE)
        }
    }

    private suspend fun readCheckRuns(
        slug: GitHubRepositorySlug,
        repositoryId: Long,
        sha: String,
    ): List<GitHubCheckRunSnapshot> {
        val runs = linkedMapOf<Long, GitHubCheckRunSnapshot>()
        var expectedTotal: Int? = null

        for (page in 1..maxCheckPages) {
            val body = getBody(
                path = "/repos/${slug.owner}/${slug.name}/commits/$sha/check-runs",
                notFoundReason = GitHubReadFailureReason.RESOURCE_NOT_FOUND,
                parameters = mapOf(
                    "per_page" to "100",
                    "page" to page.toString(),
                ),
            )
            val decoded = decoder.decodeCheckRuns(body, repositoryId, expectedHeadSha = sha)
            if (expectedTotal == null) expectedTotal = decoded.totalCount
            for (run in decoded.checkRuns) {
                val existing = runs[run.checkRunId]
                if (existing != null && existing != run) {
                    throw GitHubReadAbort(GitHubReadFailureReason.RESPONSE_MISMATCH)
                }
                runs[run.checkRunId] = run
            }
            if (runs.size >= (expectedTotal ?: 0)) return runs.values.toList()
            if (decoded.checkRuns.isEmpty()) break
        }

        if (runs.size < (expectedTotal ?: 0)) {
            throw GitHubReadAbort(GitHubReadFailureReason.PAGE_LIMIT_EXCEEDED)
        }
        return runs.values.toList()
    }

    private suspend fun getBody(
        path: String,
        notFoundReason: GitHubReadFailureReason,
        parameters: Map<String, String> = emptyMap(),
    ): String {
        val token = tokenProvider.token()
        if (token != null) {
            if (token.isBlank() || token.contains('\n') || token.contains('\r')) {
                throw GitHubReadAbort(GitHubReadFailureReason.INVALID_REQUEST)
            }
        }

        val response = try {
            client.get(endpoint.resolve(path)) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "kotlin-auto-webview")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                parameters.forEach { (name, value) -> parameter(name, value) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw GitHubReadAbort(GitHubReadFailureReason.NETWORK_FAILURE)
        }

        when (response.status) {
            HttpStatusCode.OK -> return response.bodyAsText()
            HttpStatusCode.Unauthorized -> throw GitHubReadAbort(GitHubReadFailureReason.UNAUTHORIZED)
            HttpStatusCode.NotFound -> throw GitHubReadAbort(notFoundReason)
            HttpStatusCode.Gone -> throw GitHubReadAbort(GitHubReadFailureReason.RESOURCE_GONE)
            HttpStatusCode.TooManyRequests -> throw GitHubReadAbort(
                reason = GitHubReadFailureReason.RATE_LIMITED,
                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
            )
            HttpStatusCode.Forbidden -> {
                val exhausted = response.headers["X-RateLimit-Remaining"] == "0"
                throw GitHubReadAbort(
                    reason = if (exhausted) {
                        GitHubReadFailureReason.RATE_LIMITED
                    } else {
                        GitHubReadFailureReason.FORBIDDEN
                    },
                    retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
                )
            }
            else -> {
                val reason = if (response.status.value in 500..599) {
                    GitHubReadFailureReason.SERVER_FAILURE
                } else {
                    GitHubReadFailureReason.RESPONSE_MISMATCH
                }
                throw GitHubReadAbort(reason)
            }
        }
    }
}

internal class GitHubRestPayloadDecoder(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    fun decodeRepository(payload: String, expectedSlug: GitHubRepositorySlug): GitHubRepositorySnapshot {
        val dto = decode<GitHubRepositoryDto>(payload)
        require(dto.fullName.equals(expectedSlug.fullName, ignoreCase = true)) {
            "GitHub repository response does not match the requested slug"
        }
        return GitHubRepositorySnapshot(
            repositoryId = dto.id,
            slug = expectedSlug,
            visibility = if (dto.privateRepository) {
                GitHubRepositoryVisibility.PRIVATE
            } else {
                GitHubRepositoryVisibility.PUBLIC
            },
            defaultBranch = dto.defaultBranch,
            archived = dto.archived,
            disabled = dto.disabled,
            updatedRevision = dto.updatedAt,
        )
    }

    fun decodeIssue(payload: String, repositoryId: Long): GitHubIssueSnapshot {
        val dto = decode<GitHubIssueDto>(payload)
        require(dto.pullRequest == null) { "Pull request aliases are not admitted as issues" }
        return GitHubIssueSnapshot(
            repositoryId = repositoryId,
            issueId = dto.id,
            number = dto.number,
            title = dto.title,
            state = when (dto.state.lowercase()) {
                "open" -> GitHubIssueState.OPEN
                "closed" -> GitHubIssueState.CLOSED
                else -> error("Unknown GitHub issue state")
            },
            stateReason = when (dto.stateReason?.lowercase()) {
                null -> GitHubIssueStateReason.NONE
                "completed" -> GitHubIssueStateReason.COMPLETED
                "not_planned" -> GitHubIssueStateReason.NOT_PLANNED
                "reopened" -> GitHubIssueStateReason.REOPENED
                else -> GitHubIssueStateReason.UNKNOWN
            },
            updatedRevision = dto.updatedAt,
        )
    }

    fun decodePullRequest(payload: String, repositoryId: Long): GitHubPullRequestSnapshot {
        val dto = decode<GitHubPullRequestDto>(payload)
        val state = when {
            dto.merged -> GitHubPullRequestState.MERGED
            dto.state.equals("open", ignoreCase = true) -> GitHubPullRequestState.OPEN
            dto.state.equals("closed", ignoreCase = true) -> GitHubPullRequestState.CLOSED
            else -> error("Unknown GitHub pull request state")
        }
        return GitHubPullRequestSnapshot(
            repositoryId = repositoryId,
            pullRequestId = dto.id,
            number = dto.number,
            title = dto.title,
            state = state,
            draft = dto.draft,
            baseRef = dto.base.ref,
            baseSha = dto.base.sha.lowercase(),
            headRef = dto.head.ref,
            headSha = dto.head.sha.lowercase(),
            headRefState = if (dto.head.repository == null) {
                GitHubBranchRefState.DELETED
            } else {
                GitHubBranchRefState.AVAILABLE
            },
            mergeCommitSha = dto.mergeCommitSha?.lowercase(),
            updatedRevision = dto.updatedAt,
        )
    }

    fun decodeCommit(
        payload: String,
        repositoryId: Long,
        expectedSha: String,
    ): GitHubCommitSnapshot {
        val dto = decode<GitHubCommitDto>(payload)
        require(dto.sha.equals(expectedSha, ignoreCase = true)) {
            "GitHub commit response does not match the requested SHA"
        }
        return GitHubCommitSnapshot(
            repositoryId = repositoryId,
            sha = dto.sha.lowercase(),
            treeSha = dto.commit.tree.sha.lowercase(),
            committedRevision = dto.commit.committer?.date
                ?: dto.commit.author?.date
                ?: dto.sha.lowercase(),
        )
    }

    fun decodeCheckRuns(
        payload: String,
        repositoryId: Long,
        expectedHeadSha: String,
    ): DecodedCheckRuns {
        val dto = decode<GitHubCheckRunsDto>(payload)
        val runs = dto.checkRuns.map { run ->
            require(run.headSha.equals(expectedHeadSha, ignoreCase = true)) {
                "GitHub check run response contains a different head SHA"
            }
            val status = when (run.status.lowercase()) {
                "queued" -> GitHubCheckStatus.QUEUED
                "in_progress" -> GitHubCheckStatus.IN_PROGRESS
                "completed" -> GitHubCheckStatus.COMPLETED
                else -> GitHubCheckStatus.UNKNOWN
            }
            val conclusion = when (run.conclusion?.lowercase()) {
                null -> GitHubCheckConclusion.UNKNOWN
                "success" -> GitHubCheckConclusion.SUCCESS
                "failure" -> GitHubCheckConclusion.FAILURE
                "neutral" -> GitHubCheckConclusion.NEUTRAL
                "cancelled" -> GitHubCheckConclusion.CANCELLED
                "timed_out" -> GitHubCheckConclusion.TIMED_OUT
                "action_required" -> GitHubCheckConclusion.ACTION_REQUIRED
                "skipped" -> GitHubCheckConclusion.SKIPPED
                "stale" -> GitHubCheckConclusion.STALE
                "startup_failure" -> GitHubCheckConclusion.STARTUP_FAILURE
                else -> GitHubCheckConclusion.UNKNOWN
            }
            GitHubCheckRunSnapshot(
                repositoryId = repositoryId,
                checkRunId = run.id,
                name = run.name,
                headSha = run.headSha.lowercase(),
                status = status,
                conclusion = conclusion,
                completedRevision = run.completedAt
                    ?: run.startedAt
                    ?: "${run.headSha.lowercase()}:${status.name}:${conclusion.name}",
            )
        }
        return DecodedCheckRuns(dto.totalCount, runs)
    }

    private inline fun <reified T> decode(payload: String): T =
        try {
            json.decodeFromString(payload)
        } catch (_: Exception) {
            throw GitHubReadAbort(GitHubReadFailureReason.DECODE_FAILURE)
        }
}

internal data class DecodedCheckRuns(
    val totalCount: Int,
    val checkRuns: List<GitHubCheckRunSnapshot>,
)

private class GitHubReadAbort(
    val reason: GitHubReadFailureReason,
    val retryAfterSeconds: Long? = null,
) : RuntimeException()

@Serializable
private data class GitHubRepositoryDto(
    val id: Long,
    @SerialName("full_name") val fullName: String,
    @SerialName("private") val privateRepository: Boolean,
    @SerialName("default_branch") val defaultBranch: String,
    val archived: Boolean = false,
    val disabled: Boolean = false,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class GitHubIssueDto(
    val id: Long,
    val number: Long,
    val title: String,
    val state: String,
    @SerialName("state_reason") val stateReason: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("pull_request") val pullRequest: JsonObject? = null,
)

@Serializable
private data class GitHubPullRequestDto(
    val id: Long,
    val number: Long,
    val title: String,
    val state: String,
    val draft: Boolean = false,
    val merged: Boolean = false,
    val base: GitHubGitRefDto,
    val head: GitHubGitRefDto,
    @SerialName("merge_commit_sha") val mergeCommitSha: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class GitHubGitRefDto(
    val ref: String,
    val sha: String,
    @SerialName("repo") val repository: JsonObject? = null,
)

@Serializable
private data class GitHubCommitDto(
    val sha: String,
    val commit: GitHubCommitMetadataDto,
)

@Serializable
private data class GitHubCommitMetadataDto(
    val author: GitHubCommitSignatureDto? = null,
    val committer: GitHubCommitSignatureDto? = null,
    val tree: GitHubTreeDto,
)

@Serializable
private data class GitHubCommitSignatureDto(
    val date: String? = null,
)

@Serializable
private data class GitHubTreeDto(
    val sha: String,
)

@Serializable
private data class GitHubCheckRunsDto(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("check_runs") val checkRuns: List<GitHubCheckRunDto>,
)

@Serializable
private data class GitHubCheckRunDto(
    val id: Long,
    val name: String,
    @SerialName("head_sha") val headSha: String,
    val status: String,
    val conclusion: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)
