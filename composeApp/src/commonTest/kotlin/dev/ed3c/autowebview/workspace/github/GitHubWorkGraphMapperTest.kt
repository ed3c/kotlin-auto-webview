package dev.ed3c.autowebview.workspace.github

import dev.ed3c.autowebview.workspace.contract.EdgeRelation
import dev.ed3c.autowebview.workspace.contract.EvidenceCeiling
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.TypedEdge
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitHubWorkGraphMapperTest {
    private val mapper = GitHubWorkGraphMapper()
    private val baseSha = "a".repeat(40)
    private val headSha = "b".repeat(40)
    private val staleSha = "c".repeat(40)

    @Test
    fun issueAndPullRequestStatesNeverExceedSourceEvidence() {
        val snapshot = snapshot(
            issues = listOf(
                issue(id = 201, number = 1, state = GitHubIssueState.OPEN),
                issue(
                    id = 202,
                    number = 2,
                    state = GitHubIssueState.CLOSED,
                    reason = GitHubIssueStateReason.COMPLETED,
                ),
            ),
            pullRequests = listOf(
                pullRequest(id = 301, number = 3, draft = true),
                pullRequest(
                    id = 302,
                    number = 4,
                    state = GitHubPullRequestState.MERGED,
                    draft = false,
                ),
            ),
        )

        val projection = mapper.map(snapshot)
        assertTrue(projection.nodes.any { it.state == GitHubWorkState.ISSUE_OPEN })
        assertTrue(projection.nodes.any { it.state == GitHubWorkState.ISSUE_CLOSED_COMPLETED })
        assertTrue(projection.nodes.any { it.state == GitHubWorkState.PULL_REQUEST_DRAFT })
        assertTrue(projection.nodes.any { it.state == GitHubWorkState.PULL_REQUEST_MERGED })
        assertTrue(
            projection.nodes
                .filter {
                    it.state.name.startsWith("ISSUE_") ||
                        it.state.name.startsWith("PULL_REQUEST_")
                }
                .all { it.evidenceCeiling == EvidenceCeiling.SOURCE_ONLY },
        )
    }

    @Test
    fun onlySuccessfulExactHeadCheckCreatesTechnicalEvidenceEdge() {
        val exact = check(id = 401, sha = headSha, conclusion = GitHubCheckConclusion.SUCCESS)
        val stale = check(id = 402, sha = staleSha, conclusion = GitHubCheckConclusion.SUCCESS)
        val snapshot = snapshot(
            pullRequests = listOf(pullRequest(id = 301, number = 3)),
            checkRuns = listOf(exact, stale),
        )

        val projection = mapper.map(snapshot)
        assertEquals(
            GitHubCheckEvidenceState.EXACT_HEAD_SUCCESS,
            projection.checkAssessments.first { it.checkRunId == exact.checkRunId }.state,
        )
        assertEquals(
            EvidenceCeiling.TECHNICAL,
            projection.checkAssessments.first { it.checkRunId == exact.checkRunId }.evidenceCeiling,
        )
        assertEquals(
            GitHubCheckEvidenceState.STALE_HEAD,
            projection.checkAssessments.first { it.checkRunId == stale.checkRunId }.state,
        )
        assertEquals(
            EvidenceCeiling.SOURCE_ONLY,
            projection.checkAssessments.first { it.checkRunId == stale.checkRunId }.evidenceCeiling,
        )

        val pullRequestKey = GitHubSubjectKeys.pullRequest(301)
        val exactCheckKey = GitHubSubjectKeys.check(401)
        val staleCheckKey = GitHubSubjectKeys.check(402)
        assertTrue(
            projection.edges.any {
                it.from == pullRequestKey &&
                    it.to == exactCheckKey &&
                    it.relation == EdgeRelation.EVIDENCED_BY
            },
        )
        assertFalse(
            projection.edges.any {
                it.from == pullRequestKey &&
                    it.to == staleCheckKey &&
                    it.relation == EdgeRelation.EVIDENCED_BY
            },
        )
        assertTrue(GitHubProjectionWarning.CHECK_NOT_EXACT_HEAD in projection.warnings)
    }

    @Test
    fun deletedHeadBranchKeepsExactShaEvidenceButEmitsWarning() {
        val snapshot = snapshot(
            pullRequests = listOf(
                pullRequest(
                    id = 301,
                    number = 3,
                    headRefState = GitHubBranchRefState.DELETED,
                ),
            ),
            checkRuns = listOf(
                check(id = 401, sha = headSha, conclusion = GitHubCheckConclusion.SUCCESS),
            ),
        )

        val projection = mapper.map(snapshot)
        assertTrue(GitHubProjectionWarning.HEAD_BRANCH_DELETED in projection.warnings)
        assertEquals(
            GitHubCheckEvidenceState.EXACT_HEAD_SUCCESS,
            projection.checkAssessments.single().state,
        )
    }

    @Test
    fun duplicateAliasesCollapseButConflictingIdentityFailsClosed() {
        val duplicate = issue(id = 201, number = 1, state = GitHubIssueState.OPEN)
        val collapsed = mapper.map(snapshot(issues = listOf(duplicate, duplicate)))
        assertEquals(
            1,
            collapsed.nodes.count { it.subject.key == GitHubSubjectKeys.issue(duplicate.issueId) },
        )
        assertTrue(GitHubProjectionWarning.DUPLICATE_ALIAS_COLLAPSED in collapsed.warnings)

        val failure = assertFailsWith<GitHubWorkGraphMappingException> {
            mapper.map(
                snapshot(
                    issues = listOf(
                        duplicate,
                        duplicate.copy(title = "conflicting alias"),
                    ),
                ),
            )
        }
        assertEquals(GitHubProjectionRejectionReason.DUPLICATE_IDENTITY_CONFLICT, failure.reason)
    }

    @Test
    fun sameIssueNumberCannotResolveToTwoStableIds() {
        val failure = assertFailsWith<GitHubWorkGraphMappingException> {
            mapper.map(
                snapshot(
                    issues = listOf(
                        issue(id = 201, number = 1, state = GitHubIssueState.OPEN),
                        issue(id = 202, number = 1, state = GitHubIssueState.OPEN),
                    ),
                ),
            )
        }
        assertEquals(GitHubProjectionRejectionReason.NUMBER_ALIAS_CONFLICT, failure.reason)
    }

    @Test
    fun privateRepositoryPublicSummaryContainsNoPrivateIdentityOrUrls() {
        val repository = repository(
            id = 900,
            slug = GitHubRepositorySlug("fixture-private-owner", "fixture-private-repository"),
            visibility = GitHubRepositoryVisibility.PRIVATE,
        )
        val projection = mapper.map(
            snapshot(
                repository = repository,
                issues = listOf(
                    issue(
                        repositoryId = repository.repositoryId,
                        id = 901,
                        number = 1,
                        state = GitHubIssueState.OPEN,
                    ),
                ),
            ),
        )
        val encoded = Json.encodeToString(projection.toPublicSummary())

        assertTrue(encoded.contains("PRIVATE"))
        assertFalse(encoded.contains(repository.slug.owner))
        assertFalse(encoded.contains(repository.slug.name))
        assertFalse(encoded.contains("GHREPO"))
        assertFalse(encoded.contains("github.com"))
        assertTrue(projection.toPublicSummary().subjects.isEmpty())
    }

    @Test
    fun linkedIssueBuildsTypedImplementsEdgeWithoutChangingEvidenceCeiling() {
        val snapshot = snapshot(
            issues = listOf(issue(id = 201, number = 1, state = GitHubIssueState.OPEN)),
            pullRequests = listOf(pullRequest(id = 301, number = 3)),
            links = mapOf(3L to setOf(1L)),
        )
        val projection = mapper.map(snapshot)

        assertTrue(
            projection.edges.any {
                it.from == GitHubSubjectKeys.pullRequest(301) &&
                    it.to == GitHubSubjectKeys.issue(201) &&
                    it.relation == EdgeRelation.IMPLEMENTS
            },
        )
        assertEquals(
            EvidenceCeiling.SOURCE_ONLY,
            projection.nodes.first {
                it.subject.key == GitHubSubjectKeys.pullRequest(301)
            }.evidenceCeiling,
        )
    }

    @Test
    fun adapterFailsClosedOnUnavailableSourceWithoutWritingLocalState() = runTest {
        val sink = RecordingSink()
        val adapter = GitHubWorkGraphAdapter(
            source = GitHubMetadataSource {
                GitHubReadResult.Unavailable(GitHubReadFailureReason.REPOSITORY_NOT_FOUND)
            },
            sink = sink,
        )

        val result = adapter.refresh(request())
        assertIs<GitHubWorkGraphRefreshResult.Unavailable>(result)
        assertTrue(sink.subjects.isEmpty())
        assertTrue(sink.edges.isEmpty())
    }

    @Test
    fun adapterRejectsSequenceMismatchBeforeWritingLocalState() = runTest {
        val sink = RecordingSink()
        val request = request(observationSequence = 10)
        val adapter = GitHubWorkGraphAdapter(
            source = GitHubMetadataSource {
                GitHubReadResult.Success(snapshot(observationSequence = 9))
            },
            sink = sink,
        )

        val result = assertIs<GitHubWorkGraphRefreshResult.Rejected>(adapter.refresh(request))
        assertEquals(GitHubProjectionRejectionReason.OBSERVATION_SEQUENCE_MISMATCH, result.reason)
        assertTrue(sink.subjects.isEmpty())
        assertTrue(sink.edges.isEmpty())
    }

    @Test
    fun adapterAppliesSubjectsBeforeEdgesAndReportsLocalRejection() = runTest {
        val request = request(observationSequence = 10)
        val snapshot = snapshot(
            issues = listOf(issue(id = 201, number = 1, state = GitHubIssueState.OPEN)),
            observationSequence = 10,
        )
        val successSink = RecordingSink()
        val successAdapter = GitHubWorkGraphAdapter(
            source = GitHubMetadataSource { GitHubReadResult.Success(snapshot) },
            sink = successSink,
        )
        val applied = assertIs<GitHubWorkGraphRefreshResult.Applied>(successAdapter.refresh(request))
        assertEquals(applied.projection.nodes.size, successSink.subjects.size)
        assertEquals(applied.projection.edges.size, successSink.edges.size)

        val rejectingSink = RecordingSink(rejectFirstEdge = true)
        val rejectingAdapter = GitHubWorkGraphAdapter(
            source = GitHubMetadataSource { GitHubReadResult.Success(snapshot) },
            sink = rejectingSink,
        )
        val rejected = assertIs<GitHubWorkGraphRefreshResult.Rejected>(
            rejectingAdapter.refresh(request),
        )
        assertEquals(GitHubProjectionRejectionReason.LOCAL_EDGE_REJECTED, rejected.reason)
        assertEquals(rejected.subjectsApplied, rejectingSink.subjects.size)
        assertEquals(0, rejected.edgesApplied)
    }

    private fun request(observationSequence: Long = 10) = GitHubWorkGraphRequest(
        repository = GitHubRepositorySlug("example", "public-repository"),
        observationSequence = observationSequence,
    )

    private fun snapshot(
        repository: GitHubRepositorySnapshot = repository(),
        issues: List<GitHubIssueSnapshot> = emptyList(),
        pullRequests: List<GitHubPullRequestSnapshot> = emptyList(),
        commits: List<GitHubCommitSnapshot> = emptyList(),
        checkRuns: List<GitHubCheckRunSnapshot> = emptyList(),
        links: Map<Long, Set<Long>> = emptyMap(),
        observationSequence: Long = 10,
    ) = GitHubWorkGraphSnapshot(
        repository = repository,
        issues = issues,
        pullRequests = pullRequests,
        commits = commits,
        checkRuns = checkRuns,
        linkedIssueNumbersByPullRequest = links,
        observationSequence = observationSequence,
    )

    private fun repository(
        id: Long = 100,
        slug: GitHubRepositorySlug = GitHubRepositorySlug("example", "public-repository"),
        visibility: GitHubRepositoryVisibility = GitHubRepositoryVisibility.PUBLIC,
    ) = GitHubRepositorySnapshot(
        repositoryId = id,
        slug = slug,
        visibility = visibility,
        defaultBranch = "main",
        archived = false,
        disabled = false,
        updatedRevision = "2026-08-20T00:00:00Z",
    )

    private fun issue(
        repositoryId: Long = 100,
        id: Long,
        number: Long,
        state: GitHubIssueState,
        reason: GitHubIssueStateReason = GitHubIssueStateReason.NONE,
    ) = GitHubIssueSnapshot(
        repositoryId = repositoryId,
        issueId = id,
        number = number,
        title = "fixture issue $number",
        state = state,
        stateReason = reason,
        updatedRevision = "2026-08-20T00:00:00Z",
    )

    private fun pullRequest(
        repositoryId: Long = 100,
        id: Long,
        number: Long,
        state: GitHubPullRequestState = GitHubPullRequestState.OPEN,
        draft: Boolean = false,
        headRefState: GitHubBranchRefState = GitHubBranchRefState.AVAILABLE,
    ) = GitHubPullRequestSnapshot(
        repositoryId = repositoryId,
        pullRequestId = id,
        number = number,
        title = "fixture pull request $number",
        state = state,
        draft = draft,
        baseRef = "main",
        baseSha = baseSha,
        headRef = "feature-$number",
        headSha = headSha,
        headRefState = headRefState,
        mergeCommitSha = if (state == GitHubPullRequestState.MERGED) staleSha else null,
        updatedRevision = "2026-08-20T00:00:00Z",
    )

    private fun check(
        repositoryId: Long = 100,
        id: Long,
        sha: String,
        status: GitHubCheckStatus = GitHubCheckStatus.COMPLETED,
        conclusion: GitHubCheckConclusion,
    ) = GitHubCheckRunSnapshot(
        repositoryId = repositoryId,
        checkRunId = id,
        name = "fixture-check-$id",
        headSha = sha,
        status = status,
        conclusion = conclusion,
        completedRevision = "2026-08-20T00:00:00Z",
    )

    private class RecordingSink(
        private val rejectFirstEdge: Boolean = false,
    ) : GitHubWorkGraphSink {
        val subjects = mutableListOf<SubjectRef>()
        val edges = mutableListOf<TypedEdge>()

        override suspend fun putSubject(subject: SubjectRef, observationSequence: Long): Boolean {
            subjects += subject
            return true
        }

        override suspend fun putEdge(edge: TypedEdge, observationSequence: Long): Boolean {
            if (rejectFirstEdge && edges.isEmpty()) return false
            edges += edge
            return true
        }
    }
}
