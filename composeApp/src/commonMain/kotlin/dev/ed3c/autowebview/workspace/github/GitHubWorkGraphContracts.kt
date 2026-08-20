package dev.ed3c.autowebview.workspace.github

import dev.ed3c.autowebview.workspace.contract.AuthorityKind
import dev.ed3c.autowebview.workspace.contract.AuthorityRef
import dev.ed3c.autowebview.workspace.contract.EdgeRelation
import dev.ed3c.autowebview.workspace.contract.EvidenceCeiling
import dev.ed3c.autowebview.workspace.contract.ExternalProvider
import dev.ed3c.autowebview.workspace.contract.ExternalRef
import dev.ed3c.autowebview.workspace.contract.FreshnessState
import dev.ed3c.autowebview.workspace.contract.PublicSubjectProjection
import dev.ed3c.autowebview.workspace.contract.SubjectDataClass
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectKind
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.SubjectVisibility
import dev.ed3c.autowebview.workspace.contract.TypedEdge
import dev.ed3c.autowebview.workspace.contract.toPublicProjection
import kotlinx.serialization.Serializable

private val GITHUB_NAME_PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?$")
private val COMMIT_SHA_PATTERN = Regex("^[0-9a-f]{40}$")

@Serializable
data class GitHubRepositorySlug(
    val owner: String,
    val name: String,
) {
    init {
        require(GITHUB_NAME_PATTERN.matches(owner)) { "GitHub owner is invalid" }
        require(GITHUB_NAME_PATTERN.matches(name)) { "GitHub repository name is invalid" }
    }

    val fullName: String
        get() = "$owner/$name"
}

@Serializable
enum class GitHubRepositoryVisibility {
    PUBLIC,
    PRIVATE,
}

@Serializable
data class GitHubRepositorySnapshot(
    val repositoryId: Long,
    val slug: GitHubRepositorySlug,
    val visibility: GitHubRepositoryVisibility,
    val defaultBranch: String,
    val archived: Boolean,
    val disabled: Boolean,
    val updatedRevision: String,
) {
    init {
        require(repositoryId > 0) { "GitHub repository id must be positive" }
        require(defaultBranch.isNotBlank()) { "GitHub default branch cannot be blank" }
        require(updatedRevision.isNotBlank()) { "GitHub repository revision cannot be blank" }
        requireNoNewline(updatedRevision, "GitHub repository revision")
    }
}

@Serializable
enum class GitHubIssueState {
    OPEN,
    CLOSED,
}

@Serializable
enum class GitHubIssueStateReason {
    NONE,
    COMPLETED,
    NOT_PLANNED,
    REOPENED,
    UNKNOWN,
}

@Serializable
data class GitHubIssueSnapshot(
    val repositoryId: Long,
    val issueId: Long,
    val number: Long,
    val title: String,
    val state: GitHubIssueState,
    val stateReason: GitHubIssueStateReason,
    val updatedRevision: String,
) {
    init {
        require(repositoryId > 0) { "GitHub repository id must be positive" }
        require(issueId > 0) { "GitHub issue id must be positive" }
        require(number > 0) { "GitHub issue number must be positive" }
        require(title.isNotBlank()) { "GitHub issue title cannot be blank" }
        require(title.length <= 4_096) { "GitHub issue title is too long" }
        require(updatedRevision.isNotBlank()) { "GitHub issue revision cannot be blank" }
        requireNoNewline(updatedRevision, "GitHub issue revision")
    }
}

@Serializable
enum class GitHubPullRequestState {
    OPEN,
    CLOSED,
    MERGED,
}

@Serializable
enum class GitHubBranchRefState {
    AVAILABLE,
    DELETED,
    UNKNOWN,
}

@Serializable
data class GitHubPullRequestSnapshot(
    val repositoryId: Long,
    val pullRequestId: Long,
    val number: Long,
    val title: String,
    val state: GitHubPullRequestState,
    val draft: Boolean,
    val baseRef: String,
    val baseSha: String,
    val headRef: String,
    val headSha: String,
    val headRefState: GitHubBranchRefState,
    val mergeCommitSha: String? = null,
    val updatedRevision: String,
) {
    init {
        require(repositoryId > 0) { "GitHub repository id must be positive" }
        require(pullRequestId > 0) { "GitHub pull request id must be positive" }
        require(number > 0) { "GitHub pull request number must be positive" }
        require(title.isNotBlank()) { "GitHub pull request title cannot be blank" }
        require(title.length <= 4_096) { "GitHub pull request title is too long" }
        require(baseRef.isNotBlank()) { "GitHub pull request base ref cannot be blank" }
        require(headRef.isNotBlank()) { "GitHub pull request head ref cannot be blank" }
        requireCommitSha(baseSha, "GitHub pull request base SHA")
        requireCommitSha(headSha, "GitHub pull request head SHA")
        if (mergeCommitSha != null) {
            requireCommitSha(mergeCommitSha, "GitHub pull request merge commit SHA")
        }
        require(updatedRevision.isNotBlank()) { "GitHub pull request revision cannot be blank" }
        requireNoNewline(updatedRevision, "GitHub pull request revision")
    }
}

@Serializable
data class GitHubCommitSnapshot(
    val repositoryId: Long,
    val sha: String,
    val treeSha: String? = null,
    val committedRevision: String,
) {
    init {
        require(repositoryId > 0) { "GitHub repository id must be positive" }
        requireCommitSha(sha, "GitHub commit SHA")
        if (treeSha != null) {
            requireCommitSha(treeSha, "GitHub commit tree SHA")
        }
        require(committedRevision.isNotBlank()) { "GitHub commit revision cannot be blank" }
        requireNoNewline(committedRevision, "GitHub commit revision")
    }
}

@Serializable
enum class GitHubCheckStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    UNKNOWN,
}

@Serializable
enum class GitHubCheckConclusion {
    SUCCESS,
    FAILURE,
    NEUTRAL,
    CANCELLED,
    TIMED_OUT,
    ACTION_REQUIRED,
    SKIPPED,
    STALE,
    STARTUP_FAILURE,
    UNKNOWN,
}

@Serializable
data class GitHubCheckRunSnapshot(
    val repositoryId: Long,
    val checkRunId: Long,
    val name: String,
    val headSha: String,
    val status: GitHubCheckStatus,
    val conclusion: GitHubCheckConclusion,
    val completedRevision: String,
) {
    init {
        require(repositoryId > 0) { "GitHub repository id must be positive" }
        require(checkRunId > 0) { "GitHub check run id must be positive" }
        require(name.isNotBlank()) { "GitHub check run name cannot be blank" }
        require(name.length <= 512) { "GitHub check run name is too long" }
        requireCommitSha(headSha, "GitHub check run head SHA")
        require(completedRevision.isNotBlank()) { "GitHub check revision cannot be blank" }
        requireNoNewline(completedRevision, "GitHub check revision")
    }
}

@Serializable
data class GitHubWorkGraphRequest(
    val repository: GitHubRepositorySlug,
    val issueNumbers: Set<Long> = emptySet(),
    val pullRequestNumbers: Set<Long> = emptySet(),
    val commitShas: Set<String> = emptySet(),
    val linkedIssueNumbersByPullRequest: Map<Long, Set<Long>> = emptyMap(),
    val includeChecksForPullRequests: Boolean = true,
    val observationSequence: Long,
) {
    init {
        require(observationSequence >= 0) { "GitHub observation sequence cannot be negative" }
        require(issueNumbers.all { it > 0 }) { "GitHub issue numbers must be positive" }
        require(pullRequestNumbers.all { it > 0 }) { "GitHub pull request numbers must be positive" }
        require(commitShas.all(COMMIT_SHA_PATTERN::matches)) { "GitHub commit SHAs must be lowercase SHA-1" }
        require(linkedIssueNumbersByPullRequest.keys.all { it in pullRequestNumbers }) {
            "GitHub issue links must reference a requested pull request"
        }
        require(linkedIssueNumbersByPullRequest.values.flatten().all { it > 0 }) {
            "GitHub linked issue numbers must be positive"
        }
        val requestedSubjects = issueNumbers.size + pullRequestNumbers.size + commitShas.size
        require(requestedSubjects <= 100) { "GitHub work graph request is too large" }
    }
}

@Serializable
data class GitHubWorkGraphSnapshot(
    val repository: GitHubRepositorySnapshot,
    val issues: List<GitHubIssueSnapshot>,
    val pullRequests: List<GitHubPullRequestSnapshot>,
    val commits: List<GitHubCommitSnapshot>,
    val checkRuns: List<GitHubCheckRunSnapshot>,
    val linkedIssueNumbersByPullRequest: Map<Long, Set<Long>>,
    val observationSequence: Long,
) {
    init {
        require(observationSequence >= 0) { "GitHub observation sequence cannot be negative" }
        val repositoryId = repository.repositoryId
        require(issues.all { it.repositoryId == repositoryId }) {
            "GitHub issue repository identity mismatch"
        }
        require(pullRequests.all { it.repositoryId == repositoryId }) {
            "GitHub pull request repository identity mismatch"
        }
        require(commits.all { it.repositoryId == repositoryId }) {
            "GitHub commit repository identity mismatch"
        }
        require(checkRuns.all { it.repositoryId == repositoryId }) {
            "GitHub check repository identity mismatch"
        }
    }
}

@Serializable
enum class GitHubReadFailureReason {
    INVALID_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    REPOSITORY_NOT_FOUND,
    RESOURCE_NOT_FOUND,
    RESOURCE_GONE,
    PAGE_LIMIT_EXCEEDED,
    RESPONSE_MISMATCH,
    DECODE_FAILURE,
    NETWORK_FAILURE,
    SERVER_FAILURE,
}

sealed interface GitHubReadResult<out T> {
    data class Success<T>(val value: T) : GitHubReadResult<T>

    data class Unavailable(
        val reason: GitHubReadFailureReason,
        val retryAfterSeconds: Long? = null,
    ) : GitHubReadResult<Nothing>
}

fun interface GitHubMetadataSource {
    suspend fun read(request: GitHubWorkGraphRequest): GitHubReadResult<GitHubWorkGraphSnapshot>
}

@Serializable
enum class GitHubWorkState {
    REPOSITORY_ACTIVE,
    REPOSITORY_ARCHIVED,
    REPOSITORY_DISABLED,
    ISSUE_OPEN,
    ISSUE_CLOSED_COMPLETED,
    ISSUE_CLOSED_NOT_PLANNED,
    ISSUE_CLOSED_OTHER,
    PULL_REQUEST_DRAFT,
    PULL_REQUEST_OPEN,
    PULL_REQUEST_CLOSED,
    PULL_REQUEST_MERGED,
    COMMIT_PRESENT,
    COMMIT_REFERENCE_ONLY,
    CHECK_QUEUED,
    CHECK_IN_PROGRESS,
    CHECK_COMPLETED_SUCCESS,
    CHECK_COMPLETED_NON_SUCCESS,
}

@Serializable
enum class GitHubCheckEvidenceState {
    EXACT_HEAD_SUCCESS,
    EXACT_HEAD_PENDING,
    EXACT_HEAD_NON_SUCCESS,
    STALE_HEAD,
    ORPHANED_CHECK,
}

@Serializable
data class GitHubCheckAssessment(
    val checkRunId: Long,
    val pullRequestNumber: Long?,
    val state: GitHubCheckEvidenceState,
    val evidenceCeiling: EvidenceCeiling,
)

@Serializable
enum class GitHubProjectionWarning {
    DUPLICATE_ALIAS_COLLAPSED,
    LINKED_ISSUE_NOT_REQUESTED,
    REFERENCE_ONLY_COMMIT,
    HEAD_BRANCH_DELETED,
    CHECK_NOT_EXACT_HEAD,
}

@Serializable
data class GitHubProjectedNode(
    val subject: SubjectRef,
    val externalRef: ExternalRef,
    val state: GitHubWorkState,
    val evidenceCeiling: EvidenceCeiling,
)

@Serializable
data class GitHubWorkGraphProjection(
    val repository: GitHubRepositorySnapshot,
    val nodes: List<GitHubProjectedNode>,
    val edges: List<TypedEdge>,
    val checkAssessments: List<GitHubCheckAssessment>,
    val warnings: Set<GitHubProjectionWarning>,
    val observationSequence: Long,
) {
    init {
        require(observationSequence >= 0) { "GitHub projection sequence cannot be negative" }
        require(nodes.map { it.subject.key }.distinct().size == nodes.size) {
            "GitHub projection contains duplicate subject identities"
        }
        require(edges.map(TypedEdge::edgeId).distinct().size == edges.size) {
            "GitHub projection contains duplicate edge identities"
        }
    }

    fun toPublicSummary(): GitHubPublicWorkGraphSummary {
        val privateRepository = repository.visibility == GitHubRepositoryVisibility.PRIVATE
        return GitHubPublicWorkGraphSummary(
            repositoryVisibility = repository.visibility,
            subjectCount = nodes.size,
            edgeCount = edges.size,
            subjectKinds = nodes.mapTo(linkedSetOf()) { it.subject.key.kind },
            evidenceStates = checkAssessments.mapTo(linkedSetOf()) { it.state },
            subjects = if (privateRepository) {
                emptyList()
            } else {
                nodes.map { node -> node.subject.toPublicProjection(listOf(node.externalRef)) }
            },
        )
    }
}

@Serializable
data class GitHubPublicWorkGraphSummary(
    val repositoryVisibility: GitHubRepositoryVisibility,
    val subjectCount: Int,
    val edgeCount: Int,
    val subjectKinds: Set<SubjectKind>,
    val evidenceStates: Set<GitHubCheckEvidenceState>,
    val subjects: List<PublicSubjectProjection>,
)

internal object GitHubSubjectKeys {
    fun repository(repositoryId: Long) = SubjectKey("GHREPO:$repositoryId", SubjectKind.OTHER)

    fun issue(issueId: Long) = SubjectKey("GHISSUE:$issueId", SubjectKind.WORK_ITEM)

    fun pullRequest(pullRequestId: Long) = SubjectKey("GHPR:$pullRequestId", SubjectKind.WORK_ITEM)

    fun commit(sha: String) = SubjectKey("GHCOMMIT:$sha", SubjectKind.IMPLEMENTATION)

    fun check(checkRunId: Long) = SubjectKey("GHCHECK:$checkRunId", SubjectKind.EVIDENCE)
}

internal object GitHubCanonicalUrls {
    fun repository(slug: GitHubRepositorySlug): String = "https://github.com/${slug.fullName}"

    fun issue(slug: GitHubRepositorySlug, number: Long): String =
        "${repository(slug)}/issues/$number"

    fun pullRequest(slug: GitHubRepositorySlug, number: Long): String =
        "${repository(slug)}/pull/$number"

    fun commit(slug: GitHubRepositorySlug, sha: String): String =
        "${repository(slug)}/commit/$sha"

    fun check(slug: GitHubRepositorySlug, checkRunId: Long): String =
        "${repository(slug)}/runs/$checkRunId"
}

internal fun githubAuthority(slug: GitHubRepositorySlug): AuthorityRef =
    AuthorityRef(AuthorityKind.GITHUB, slug.fullName)

internal fun githubVisibility(repository: GitHubRepositorySnapshot): SubjectVisibility =
    if (repository.visibility == GitHubRepositoryVisibility.PUBLIC) {
        SubjectVisibility.PUBLIC
    } else {
        SubjectVisibility.PRIVATE
    }

internal fun githubDataClass(repository: GitHubRepositorySnapshot): SubjectDataClass =
    if (repository.visibility == GitHubRepositoryVisibility.PUBLIC) {
        SubjectDataClass.PUBLIC
    } else {
        SubjectDataClass.CONFIDENTIAL
    }

internal fun githubExternalRef(
    externalId: String,
    revision: String,
    canonicalUrl: String,
    observationSequence: Long,
): ExternalRef = ExternalRef(
    provider = ExternalProvider.GITHUB,
    externalId = externalId,
    revision = revision,
    canonicalUrl = canonicalUrl,
    freshness = FreshnessState.CURRENT,
    observedAtEpochMs = observationSequence,
)

internal fun edgeId(
    relation: EdgeRelation,
    from: SubjectKey,
    to: SubjectKey,
): String = "GHEDGE:${relation.name}:${from.logicalId}:${to.logicalId}"

private fun requireCommitSha(value: String, label: String) {
    require(COMMIT_SHA_PATTERN.matches(value)) { "$label must be a lowercase SHA-1" }
}

private fun requireNoNewline(value: String, label: String) {
    require(!value.contains('\n') && !value.contains('\r')) { "$label cannot contain newlines" }
}
