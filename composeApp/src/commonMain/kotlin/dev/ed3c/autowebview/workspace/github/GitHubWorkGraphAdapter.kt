package dev.ed3c.autowebview.workspace.github

import dev.ed3c.autowebview.workspace.contract.ConfidenceLevel
import dev.ed3c.autowebview.workspace.contract.EdgeRelation
import dev.ed3c.autowebview.workspace.contract.EvidenceCeiling
import dev.ed3c.autowebview.workspace.contract.EvidenceClass
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.TypedEdge
import dev.ed3c.autowebview.workspace.registry.SqlDelightWorkspaceRegistry
import kotlinx.serialization.Serializable

class GitHubWorkGraphMapper {
    fun map(snapshot: GitHubWorkGraphSnapshot): GitHubWorkGraphProjection {
        val warnings = linkedSetOf<GitHubProjectionWarning>()
        val issues = dedupe(
            items = snapshot.issues,
            key = GitHubIssueSnapshot::issueId,
            conflictLabel = "GitHub issue id",
            warnings = warnings,
        )
        rejectNumberAliases(issues, GitHubIssueSnapshot::number, GitHubIssueSnapshot::issueId, "issue")

        val pullRequests = dedupe(
            items = snapshot.pullRequests,
            key = GitHubPullRequestSnapshot::pullRequestId,
            conflictLabel = "GitHub pull request id",
            warnings = warnings,
        )
        rejectNumberAliases(
            pullRequests,
            GitHubPullRequestSnapshot::number,
            GitHubPullRequestSnapshot::pullRequestId,
            "pull request",
        )

        val commits = dedupe(
            items = snapshot.commits,
            key = GitHubCommitSnapshot::sha,
            conflictLabel = "GitHub commit SHA",
            warnings = warnings,
        )
        val checkRuns = dedupe(
            items = snapshot.checkRuns,
            key = GitHubCheckRunSnapshot::checkRunId,
            conflictLabel = "GitHub check run id",
            warnings = warnings,
        )

        val repositoryKey = GitHubSubjectKeys.repository(snapshot.repository.repositoryId)
        val authority = githubAuthority(snapshot.repository.slug)
        val visibility = githubVisibility(snapshot.repository)
        val dataClass = githubDataClass(snapshot.repository)
        val nodeByKey = linkedMapOf<String, GitHubProjectedNode>()
        val edgeById = linkedMapOf<String, TypedEdge>()
        val assessments = mutableListOf<GitHubCheckAssessment>()

        fun addNode(node: GitHubProjectedNode) {
            val id = node.subject.key.logicalId
            val existing = nodeByKey[id]
            if (existing == null) {
                nodeByKey[id] = node
            } else if (existing != node) {
                throw GitHubWorkGraphMappingException(
                    GitHubProjectionRejectionReason.DUPLICATE_IDENTITY_CONFLICT,
                    "Conflicting projected node identity: $id",
                )
            } else {
                warnings += GitHubProjectionWarning.DUPLICATE_ALIAS_COLLAPSED
            }
        }

        fun addEdge(edge: TypedEdge) {
            val existing = edgeById[edge.edgeId]
            if (existing == null) {
                edgeById[edge.edgeId] = edge
            } else if (existing != edge) {
                throw GitHubWorkGraphMappingException(
                    GitHubProjectionRejectionReason.DUPLICATE_IDENTITY_CONFLICT,
                    "Conflicting projected edge identity: ${edge.edgeId}",
                )
            }
        }

        val repositoryState = when {
            snapshot.repository.disabled -> GitHubWorkState.REPOSITORY_DISABLED
            snapshot.repository.archived -> GitHubWorkState.REPOSITORY_ARCHIVED
            else -> GitHubWorkState.REPOSITORY_ACTIVE
        }
        addNode(
            GitHubProjectedNode(
                subject = SubjectRef(
                    key = repositoryKey,
                    canonicalAuthority = authority,
                    version = snapshot.repository.updatedRevision,
                    visibility = visibility,
                    dataClass = dataClass,
                ),
                externalRef = githubExternalRef(
                    externalId = "repository:${snapshot.repository.repositoryId}",
                    revision = snapshot.repository.updatedRevision,
                    canonicalUrl = GitHubCanonicalUrls.repository(snapshot.repository.slug),
                    observationSequence = snapshot.observationSequence,
                ),
                state = repositoryState,
                evidenceCeiling = EvidenceCeiling.SOURCE_ONLY,
            ),
        )

        val issueByNumber = linkedMapOf<Long, GitHubIssueSnapshot>()
        for (issue in issues.sortedBy(GitHubIssueSnapshot::number)) {
            issueByNumber[issue.number] = issue
            val key = GitHubSubjectKeys.issue(issue.issueId)
            val state = when {
                issue.state == GitHubIssueState.OPEN -> GitHubWorkState.ISSUE_OPEN
                issue.stateReason == GitHubIssueStateReason.COMPLETED ->
                    GitHubWorkState.ISSUE_CLOSED_COMPLETED
                issue.stateReason == GitHubIssueStateReason.NOT_PLANNED ->
                    GitHubWorkState.ISSUE_CLOSED_NOT_PLANNED
                else -> GitHubWorkState.ISSUE_CLOSED_OTHER
            }
            addNode(
                GitHubProjectedNode(
                    subject = SubjectRef(
                        key = key,
                        canonicalAuthority = authority,
                        version = "${issue.state.name}:${issue.stateReason.name}:${issue.updatedRevision}",
                        visibility = visibility,
                        dataClass = dataClass,
                    ),
                    externalRef = githubExternalRef(
                        externalId = "issue:${issue.issueId}",
                        revision = issue.updatedRevision,
                        canonicalUrl = GitHubCanonicalUrls.issue(snapshot.repository.slug, issue.number),
                        observationSequence = snapshot.observationSequence,
                    ),
                    state = state,
                    evidenceCeiling = EvidenceCeiling.SOURCE_ONLY,
                ),
            )
            addEdge(
                sourceEdge(
                    from = key,
                    relation = EdgeRelation.DERIVED_FROM,
                    to = repositoryKey,
                    authority = authority,
                ),
            )
        }

        val commitBySha = linkedMapOf<String, GitHubCommitSnapshot>()
        commits.forEach { commitBySha[it.sha] = it }
        val referencedShas = linkedSetOf<String>()
        pullRequests.forEach { pullRequest ->
            referencedShas += pullRequest.baseSha
            referencedShas += pullRequest.headSha
            pullRequest.mergeCommitSha?.let(referencedShas::add)
        }
        checkRuns.forEach { referencedShas += it.headSha }

        for (sha in (commitBySha.keys + referencedShas).sorted()) {
            val commit = commitBySha[sha]
            val key = GitHubSubjectKeys.commit(sha)
            val referenceOnly = commit == null
            if (referenceOnly) warnings += GitHubProjectionWarning.REFERENCE_ONLY_COMMIT
            addNode(
                GitHubProjectedNode(
                    subject = SubjectRef(
                        key = key,
                        canonicalAuthority = authority,
                        version = commit?.committedRevision ?: sha,
                        visibility = visibility,
                        dataClass = dataClass,
                    ),
                    externalRef = githubExternalRef(
                        externalId = "commit:$sha",
                        revision = sha,
                        canonicalUrl = GitHubCanonicalUrls.commit(snapshot.repository.slug, sha),
                        observationSequence = snapshot.observationSequence,
                    ),
                    state = if (referenceOnly) {
                        GitHubWorkState.COMMIT_REFERENCE_ONLY
                    } else {
                        GitHubWorkState.COMMIT_PRESENT
                    },
                    evidenceCeiling = EvidenceCeiling.SOURCE_ONLY,
                ),
            )
            addEdge(
                sourceEdge(
                    from = key,
                    relation = EdgeRelation.DERIVED_FROM,
                    to = repositoryKey,
                    authority = authority,
                ),
            )
        }

        for (pullRequest in pullRequests.sortedBy(GitHubPullRequestSnapshot::number)) {
            val key = GitHubSubjectKeys.pullRequest(pullRequest.pullRequestId)
            val state = when {
                pullRequest.state == GitHubPullRequestState.MERGED -> GitHubWorkState.PULL_REQUEST_MERGED
                pullRequest.state == GitHubPullRequestState.CLOSED -> GitHubWorkState.PULL_REQUEST_CLOSED
                pullRequest.draft -> GitHubWorkState.PULL_REQUEST_DRAFT
                else -> GitHubWorkState.PULL_REQUEST_OPEN
            }
            if (pullRequest.headRefState == GitHubBranchRefState.DELETED) {
                warnings += GitHubProjectionWarning.HEAD_BRANCH_DELETED
            }
            addNode(
                GitHubProjectedNode(
                    subject = SubjectRef(
                        key = key,
                        canonicalAuthority = authority,
                        version = buildString {
                            append(pullRequest.state.name)
                            append(':')
                            append(if (pullRequest.draft) "DRAFT" else "READY")
                            append(':')
                            append(pullRequest.headSha)
                            append(':')
                            append(pullRequest.updatedRevision)
                        },
                        visibility = visibility,
                        dataClass = dataClass,
                    ),
                    externalRef = githubExternalRef(
                        externalId = "pull-request:${pullRequest.pullRequestId}",
                        revision = pullRequest.updatedRevision,
                        canonicalUrl = GitHubCanonicalUrls.pullRequest(
                            snapshot.repository.slug,
                            pullRequest.number,
                        ),
                        observationSequence = snapshot.observationSequence,
                    ),
                    state = state,
                    evidenceCeiling = EvidenceCeiling.SOURCE_ONLY,
                ),
            )
            addEdge(
                sourceEdge(
                    from = key,
                    relation = EdgeRelation.DERIVED_FROM,
                    to = repositoryKey,
                    authority = authority,
                ),
            )
            addEdge(
                sourceEdge(
                    from = key,
                    relation = EdgeRelation.DEPENDS_ON,
                    to = GitHubSubjectKeys.commit(pullRequest.baseSha),
                    authority = authority,
                ),
            )
            addEdge(
                sourceEdge(
                    from = key,
                    relation = EdgeRelation.EVIDENCED_BY,
                    to = GitHubSubjectKeys.commit(pullRequest.headSha),
                    authority = authority,
                ),
            )

            for (issueNumber in snapshot.linkedIssueNumbersByPullRequest[pullRequest.number].orEmpty()) {
                val issue = issueByNumber[issueNumber]
                if (issue == null) {
                    warnings += GitHubProjectionWarning.LINKED_ISSUE_NOT_REQUESTED
                    continue
                }
                addEdge(
                    TypedEdge(
                        edgeId = edgeId(
                            relation = EdgeRelation.IMPLEMENTS,
                            from = key,
                            to = GitHubSubjectKeys.issue(issue.issueId),
                        ),
                        from = key,
                        relation = EdgeRelation.IMPLEMENTS,
                        to = GitHubSubjectKeys.issue(issue.issueId),
                        owner = authority,
                        evidenceClass = EvidenceClass.SOURCE_STATEMENT,
                        confidence = ConfidenceLevel.HIGH,
                    ),
                )
            }
        }

        for (check in checkRuns.sortedBy(GitHubCheckRunSnapshot::checkRunId)) {
            val key = GitHubSubjectKeys.check(check.checkRunId)
            val matchingPullRequests = pullRequests.filter { it.headSha == check.headSha }
            val assessmentState = when {
                matchingPullRequests.isEmpty() && pullRequests.isEmpty() ->
                    GitHubCheckEvidenceState.ORPHANED_CHECK
                matchingPullRequests.isEmpty() -> GitHubCheckEvidenceState.STALE_HEAD
                check.status != GitHubCheckStatus.COMPLETED ->
                    GitHubCheckEvidenceState.EXACT_HEAD_PENDING
                check.conclusion == GitHubCheckConclusion.SUCCESS ->
                    GitHubCheckEvidenceState.EXACT_HEAD_SUCCESS
                else -> GitHubCheckEvidenceState.EXACT_HEAD_NON_SUCCESS
            }
            val evidenceCeiling = if (assessmentState == GitHubCheckEvidenceState.EXACT_HEAD_SUCCESS) {
                EvidenceCeiling.TECHNICAL
            } else {
                EvidenceCeiling.SOURCE_ONLY
            }
            if (assessmentState == GitHubCheckEvidenceState.STALE_HEAD) {
                warnings += GitHubProjectionWarning.CHECK_NOT_EXACT_HEAD
            }
            val state = when {
                check.status == GitHubCheckStatus.QUEUED -> GitHubWorkState.CHECK_QUEUED
                check.status == GitHubCheckStatus.IN_PROGRESS -> GitHubWorkState.CHECK_IN_PROGRESS
                check.conclusion == GitHubCheckConclusion.SUCCESS ->
                    GitHubWorkState.CHECK_COMPLETED_SUCCESS
                else -> GitHubWorkState.CHECK_COMPLETED_NON_SUCCESS
            }
            addNode(
                GitHubProjectedNode(
                    subject = SubjectRef(
                        key = key,
                        canonicalAuthority = authority,
                        version = "${check.headSha}:${check.status.name}:${check.conclusion.name}:${check.completedRevision}",
                        visibility = visibility,
                        dataClass = dataClass,
                    ),
                    externalRef = githubExternalRef(
                        externalId = "check-run:${check.checkRunId}",
                        revision = check.completedRevision,
                        canonicalUrl = GitHubCanonicalUrls.check(
                            snapshot.repository.slug,
                            check.checkRunId,
                        ),
                        observationSequence = snapshot.observationSequence,
                    ),
                    state = state,
                    evidenceCeiling = evidenceCeiling,
                ),
            )
            addEdge(
                sourceEdge(
                    from = key,
                    relation = EdgeRelation.DERIVED_FROM,
                    to = GitHubSubjectKeys.commit(check.headSha),
                    authority = authority,
                ),
            )

            if (matchingPullRequests.isEmpty()) {
                assessments += GitHubCheckAssessment(
                    checkRunId = check.checkRunId,
                    pullRequestNumber = null,
                    state = assessmentState,
                    evidenceCeiling = evidenceCeiling,
                )
            } else {
                for (pullRequest in matchingPullRequests) {
                    assessments += GitHubCheckAssessment(
                        checkRunId = check.checkRunId,
                        pullRequestNumber = pullRequest.number,
                        state = assessmentState,
                        evidenceCeiling = evidenceCeiling,
                    )
                    if (assessmentState == GitHubCheckEvidenceState.EXACT_HEAD_SUCCESS) {
                        addEdge(
                            TypedEdge(
                                edgeId = edgeId(
                                    relation = EdgeRelation.EVIDENCED_BY,
                                    from = GitHubSubjectKeys.pullRequest(pullRequest.pullRequestId),
                                    to = key,
                                ),
                                from = GitHubSubjectKeys.pullRequest(pullRequest.pullRequestId),
                                relation = EdgeRelation.EVIDENCED_BY,
                                to = key,
                                owner = authority,
                                evidenceClass = EvidenceClass.TECHNICAL_RECEIPT,
                                confidence = ConfidenceLevel.HIGH,
                            ),
                        )
                    }
                }
            }
        }

        return GitHubWorkGraphProjection(
            repository = snapshot.repository,
            nodes = nodeByKey.values.sortedBy { it.subject.key.logicalId },
            edges = edgeById.values.sortedBy(TypedEdge::edgeId),
            checkAssessments = assessments.sortedWith(
                compareBy<GitHubCheckAssessment> { it.checkRunId }
                    .thenBy { it.pullRequestNumber ?: Long.MAX_VALUE },
            ),
            warnings = warnings,
            observationSequence = snapshot.observationSequence,
        )
    }

    private fun sourceEdge(
        from: dev.ed3c.autowebview.workspace.contract.SubjectKey,
        relation: EdgeRelation,
        to: dev.ed3c.autowebview.workspace.contract.SubjectKey,
        authority: dev.ed3c.autowebview.workspace.contract.AuthorityRef,
    ): TypedEdge = TypedEdge(
        edgeId = edgeId(relation, from, to),
        from = from,
        relation = relation,
        to = to,
        owner = authority,
        evidenceClass = EvidenceClass.SOURCE_OBSERVATION,
        confidence = ConfidenceLevel.HIGH,
    )

    private fun <T, K> dedupe(
        items: List<T>,
        key: (T) -> K,
        conflictLabel: String,
        warnings: MutableSet<GitHubProjectionWarning>,
    ): List<T> {
        val unique = linkedMapOf<K, T>()
        for (item in items) {
            val identity = key(item)
            val existing = unique[identity]
            if (existing == null) {
                unique[identity] = item
            } else if (existing == item) {
                warnings += GitHubProjectionWarning.DUPLICATE_ALIAS_COLLAPSED
            } else {
                throw GitHubWorkGraphMappingException(
                    GitHubProjectionRejectionReason.DUPLICATE_IDENTITY_CONFLICT,
                    "Conflicting $conflictLabel: $identity",
                )
            }
        }
        return unique.values.toList()
    }

    private fun <T> rejectNumberAliases(
        items: List<T>,
        number: (T) -> Long,
        identity: (T) -> Long,
        label: String,
    ) {
        val byNumber = linkedMapOf<Long, Long>()
        for (item in items) {
            val itemNumber = number(item)
            val itemIdentity = identity(item)
            val existing = byNumber[itemNumber]
            if (existing != null && existing != itemIdentity) {
                throw GitHubWorkGraphMappingException(
                    GitHubProjectionRejectionReason.NUMBER_ALIAS_CONFLICT,
                    "GitHub $label number $itemNumber maps to multiple identities",
                )
            }
            byNumber[itemNumber] = itemIdentity
        }
    }
}

interface GitHubWorkGraphSink {
    suspend fun putSubject(subject: SubjectRef, observationSequence: Long): Boolean

    suspend fun putEdge(edge: TypedEdge, observationSequence: Long): Boolean
}

class SqlDelightGitHubWorkGraphSink(
    private val registry: SqlDelightWorkspaceRegistry,
) : GitHubWorkGraphSink {
    override suspend fun putSubject(subject: SubjectRef, observationSequence: Long): Boolean =
        registry.putSubject(subject, observationSequence)

    override suspend fun putEdge(edge: TypedEdge, observationSequence: Long): Boolean =
        registry.putEdge(edge, observationSequence)
}

class GitHubWorkGraphAdapter(
    private val source: GitHubMetadataSource,
    private val sink: GitHubWorkGraphSink,
    private val mapper: GitHubWorkGraphMapper = GitHubWorkGraphMapper(),
) {
    suspend fun refresh(request: GitHubWorkGraphRequest): GitHubWorkGraphRefreshResult {
        return when (val read = source.read(request)) {
            is GitHubReadResult.Unavailable -> GitHubWorkGraphRefreshResult.Unavailable(
                reason = read.reason,
                retryAfterSeconds = read.retryAfterSeconds,
            )
            is GitHubReadResult.Success -> applySnapshot(request, read.value)
        }
    }

    private suspend fun applySnapshot(
        request: GitHubWorkGraphRequest,
        snapshot: GitHubWorkGraphSnapshot,
    ): GitHubWorkGraphRefreshResult {
        if (snapshot.observationSequence != request.observationSequence) {
            return GitHubWorkGraphRefreshResult.Rejected(
                GitHubProjectionRejectionReason.OBSERVATION_SEQUENCE_MISMATCH,
            )
        }
        if (!sameRepository(request.repository, snapshot.repository.slug)) {
            return GitHubWorkGraphRefreshResult.Rejected(
                GitHubProjectionRejectionReason.REPOSITORY_SLUG_MISMATCH,
            )
        }
        if (!matchesRequestedScope(request, snapshot)) {
            return GitHubWorkGraphRefreshResult.Rejected(
                GitHubProjectionRejectionReason.REQUEST_SCOPE_MISMATCH,
            )
        }

        val projection = try {
            mapper.map(snapshot)
        } catch (failure: GitHubWorkGraphMappingException) {
            return GitHubWorkGraphRefreshResult.Rejected(failure.reason)
        } catch (_: IllegalArgumentException) {
            return GitHubWorkGraphRefreshResult.Rejected(
                GitHubProjectionRejectionReason.SNAPSHOT_INVALID,
            )
        }

        var subjectsApplied = 0
        for (node in projection.nodes) {
            if (!sink.putSubject(node.subject, projection.observationSequence)) {
                return GitHubWorkGraphRefreshResult.Rejected(
                    GitHubProjectionRejectionReason.LOCAL_SUBJECT_REJECTED,
                    subjectsApplied = subjectsApplied,
                    edgesApplied = 0,
                )
            }
            subjectsApplied += 1
        }

        var edgesApplied = 0
        for (edge in projection.edges) {
            if (!sink.putEdge(edge, projection.observationSequence)) {
                return GitHubWorkGraphRefreshResult.Rejected(
                    GitHubProjectionRejectionReason.LOCAL_EDGE_REJECTED,
                    subjectsApplied = subjectsApplied,
                    edgesApplied = edgesApplied,
                )
            }
            edgesApplied += 1
        }

        return GitHubWorkGraphRefreshResult.Applied(
            projection = projection,
            subjectsApplied = subjectsApplied,
            edgesApplied = edgesApplied,
        )
    }

    private fun matchesRequestedScope(
        request: GitHubWorkGraphRequest,
        snapshot: GitHubWorkGraphSnapshot,
    ): Boolean {
        if (snapshot.issues.mapTo(linkedSetOf(), GitHubIssueSnapshot::number) != request.issueNumbers) {
            return false
        }
        if (
            snapshot.pullRequests.mapTo(linkedSetOf(), GitHubPullRequestSnapshot::number) !=
            request.pullRequestNumbers
        ) {
            return false
        }
        if (
            snapshot.commits.mapTo(linkedSetOf()) { it.sha.lowercase() } !=
            request.commitShas.mapTo(linkedSetOf()) { it.lowercase() }
        ) {
            return false
        }
        if (snapshot.linkedIssueNumbersByPullRequest != request.linkedIssueNumbersByPullRequest) {
            return false
        }
        if (!request.includeChecksForPullRequests) {
            return snapshot.checkRuns.isEmpty()
        }

        val requestedHeadShas = snapshot.pullRequests.mapTo(linkedSetOf()) { it.headSha }
        return snapshot.checkRuns.all { it.headSha in requestedHeadShas }
    }

    private fun sameRepository(left: GitHubRepositorySlug, right: GitHubRepositorySlug): Boolean =
        left.owner.equals(right.owner, ignoreCase = true) &&
            left.name.equals(right.name, ignoreCase = true)
}

@Serializable
enum class GitHubProjectionRejectionReason {
    OBSERVATION_SEQUENCE_MISMATCH,
    REPOSITORY_SLUG_MISMATCH,
    REQUEST_SCOPE_MISMATCH,
    DUPLICATE_IDENTITY_CONFLICT,
    NUMBER_ALIAS_CONFLICT,
    SNAPSHOT_INVALID,
    LOCAL_SUBJECT_REJECTED,
    LOCAL_EDGE_REJECTED,
}

class GitHubWorkGraphMappingException(
    val reason: GitHubProjectionRejectionReason,
    message: String,
) : IllegalArgumentException(message)

sealed interface GitHubWorkGraphRefreshResult {
    data class Applied(
        val projection: GitHubWorkGraphProjection,
        val subjectsApplied: Int,
        val edgesApplied: Int,
    ) : GitHubWorkGraphRefreshResult

    data class Unavailable(
        val reason: GitHubReadFailureReason,
        val retryAfterSeconds: Long? = null,
    ) : GitHubWorkGraphRefreshResult

    data class Rejected(
        val reason: GitHubProjectionRejectionReason,
        val subjectsApplied: Int = 0,
        val edgesApplied: Int = 0,
    ) : GitHubWorkGraphRefreshResult
}
