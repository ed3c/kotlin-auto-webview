package dev.ed3c.autowebview.workspace.github.live

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ed3c.autowebview.persistence.db.AppDatabase
import dev.ed3c.autowebview.workspace.contract.EdgeRelation
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectKind
import dev.ed3c.autowebview.workspace.github.GitHubCheckConclusion
import dev.ed3c.autowebview.workspace.github.GitHubCheckEvidenceState
import dev.ed3c.autowebview.workspace.github.GitHubCheckStatus
import dev.ed3c.autowebview.workspace.github.GitHubMetadataSource
import dev.ed3c.autowebview.workspace.github.GitHubReadResult
import dev.ed3c.autowebview.workspace.github.GitHubRepositorySlug
import dev.ed3c.autowebview.workspace.github.GitHubRepositoryVisibility
import dev.ed3c.autowebview.workspace.github.GitHubRestMetadataSource
import dev.ed3c.autowebview.workspace.github.GitHubWorkGraphAdapter
import dev.ed3c.autowebview.workspace.github.GitHubWorkGraphProjection
import dev.ed3c.autowebview.workspace.github.GitHubWorkGraphRefreshResult
import dev.ed3c.autowebview.workspace.github.GitHubWorkGraphRequest
import dev.ed3c.autowebview.workspace.github.GitHubWorkGraphSnapshot
import dev.ed3c.autowebview.workspace.github.SqlDelightGitHubWorkGraphSink
import dev.ed3c.autowebview.workspace.registry.SqlDelightWorkspaceRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val LIVE_CANARY_FLAG = "KAW_LIVE_GITHUB_CANARY"
private const val SUBJECT_PATH_ENV = "KAW_LIVE_GITHUB_SUBJECT_PATH"
private const val RECEIPT_PATH_ENV = "KAW_LIVE_GITHUB_RECEIPT_PATH"
private const val IMPLEMENTATION_HEAD_ENV = "KAW_IMPLEMENTATION_HEAD_SHA"
private val SHA_PATTERN = Regex("^[0-9a-f]{40}$")

class GitHubPublicCanaryTest {
    @Test
    fun exactPublicSubjectSurvivesTransportMappingPersistenceAndReadBack() = runBlocking {
        if (System.getenv(LIVE_CANARY_FLAG) != "1") return@runBlocking

        require(System.getenv("GITHUB_TOKEN").isNullOrBlank()) {
            "The public canary must not receive GITHUB_TOKEN"
        }
        require(System.getenv("GH_TOKEN").isNullOrBlank()) {
            "The public canary must not receive GH_TOKEN"
        }

        val subjectPath = requiredPath(SUBJECT_PATH_ENV)
        val receiptPath = requiredPath(RECEIPT_PATH_ENV)
        val implementationHead = requiredSha(IMPLEMENTATION_HEAD_ENV)
        val workflowRunId = requiredPositiveLong("GITHUB_RUN_ID")
        val workflowRunAttempt = requiredPositiveLong("GITHUB_RUN_ATTEMPT")
        val subject = CanarySubject.load(subjectPath)
        val startedAt = Instant.now().toString()
        val observationSequence = System.currentTimeMillis()
        val databasePath = Files.createTempFile("kaw-live-github-canary", ".db")

        val outcome = try {
            executeCanary(subject, observationSequence, databasePath)
        } finally {
            Files.deleteIfExists(databasePath)
        }
        assertFalse(Files.exists(databasePath), "Temporary canary database must be removed")

        val receipt = outcome.toReceipt(
            subject = subject,
            implementationHead = implementationHead,
            workflowRunId = workflowRunId,
            workflowRunAttempt = workflowRunAttempt,
            startedAt = startedAt,
            endedAt = Instant.now().toString(),
            temporaryDatabaseRemoved = true,
        )
        receiptPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(receiptPath, receipt.toString() + "\n")
        assertTrue(Files.isRegularFile(receiptPath), "Sanitized L2 receipt was not written")
    }

    private suspend fun executeCanary(
        subject: CanarySubject,
        observationSequence: Long,
        databasePath: Path,
    ): CanaryOutcome {
        val request = GitHubWorkGraphRequest(
            repository = GitHubRepositorySlug(subject.repositoryOwner, subject.repositoryName),
            issueNumbers = setOf(subject.issueNumber),
            pullRequestNumbers = setOf(subject.pullRequestNumber),
            commitShas = setOf(subject.headSha),
            linkedIssueNumbersByPullRequest = emptyMap(),
            includeChecksForPullRequests = true,
            observationSequence = observationSequence,
        )
        val client = HttpClient(CIO) {
            expectSuccess = false
            engine {
                requestTimeout = subject.timeoutSeconds * 1_000L
            }
        }
        val recordingSource = RecordingMetadataSource(
            GitHubRestMetadataSource(
                client = client,
                maxCheckPages = subject.maxCheckPages,
            ),
        )
        val jdbcUrl = "jdbc:sqlite:${databasePath.toAbsolutePath()}"

        try {
            var appliedResult: GitHubWorkGraphRefreshResult.Applied? = null
            JdbcSqliteDriver(jdbcUrl).use { driver ->
                AppDatabase.Schema.awaitCreate(driver)
                val registry = SqlDelightWorkspaceRegistry(driver)
                val adapter = GitHubWorkGraphAdapter(
                    source = recordingSource,
                    sink = SqlDelightGitHubWorkGraphSink(registry),
                )
                val result = withTimeout(subject.timeoutSeconds * 1_000L) {
                    adapter.refresh(request)
                }
                appliedResult = assertIs<GitHubWorkGraphRefreshResult.Applied>(result)
                verifySnapshot(subject, assertNotNull(recordingSource.snapshot))
                verifyProjection(subject, assertNotNull(appliedResult).projection)
            }

            val applied = assertNotNull(appliedResult)
            JdbcSqliteDriver(jdbcUrl).use { reopenedDriver ->
                val registry = SqlDelightWorkspaceRegistry(reopenedDriver)
                assertEquals(applied.projection.nodes.size.toLong(), registry.activeSubjectCount())
                for (node in applied.projection.nodes) {
                    assertEquals(
                        node.subject,
                        registry.subject(node.subject.key),
                        "W1 subject read-back mismatch for ${node.subject.key}",
                    )
                }
                for ((from, expectedEdges) in applied.projection.edges.groupBy { it.from }) {
                    assertEquals(
                        expectedEdges.sortedBy { it.edgeId },
                        registry.edgesFrom(from).sortedBy { it.edgeId },
                        "W1 edge read-back mismatch for $from",
                    )
                }
            }

            return CanaryOutcome(
                projection = applied.projection,
                subjectsApplied = applied.subjectsApplied,
                edgesApplied = applied.edgesApplied,
                observationSequence = observationSequence,
            )
        } finally {
            client.close()
        }
    }

    private fun verifySnapshot(subject: CanarySubject, snapshot: GitHubWorkGraphSnapshot) {
        assertEquals(subject.repositoryId, snapshot.repository.repositoryId)
        assertEquals(subject.repositoryFullName, snapshot.repository.slug.fullName)
        assertEquals(GitHubRepositoryVisibility.PUBLIC, snapshot.repository.visibility)

        val issue = snapshot.issues.single()
        assertEquals(subject.issueId, issue.issueId)
        assertEquals(subject.issueNumber, issue.number)

        val pullRequest = snapshot.pullRequests.single()
        assertEquals(subject.pullRequestId, pullRequest.pullRequestId)
        assertEquals(subject.pullRequestNumber, pullRequest.number)
        assertEquals(subject.headSha, pullRequest.headSha)
        assertEquals(subject.baseSha, pullRequest.baseSha)

        val commit = snapshot.commits.single()
        assertEquals(subject.headSha, commit.sha)
        assertEquals(subject.treeSha, commit.treeSha)

        val checksById = snapshot.checkRuns.associateBy { it.checkRunId }
        for (expected in subject.expectedChecks) {
            val check = assertNotNull(checksById[expected.id], "Missing expected check ${expected.id}")
            assertEquals(expected.name, check.name)
            assertEquals(subject.headSha, check.headSha)
            assertEquals(GitHubCheckStatus.COMPLETED, check.status)
            assertEquals(GitHubCheckConclusion.SUCCESS, check.conclusion)
        }
        assertTrue(snapshot.checkRuns.all { it.headSha == subject.headSha })
    }

    private fun verifyProjection(subject: CanarySubject, projection: GitHubWorkGraphProjection) {
        val repositoryKey = SubjectKey("GHREPO:${subject.repositoryId}", SubjectKind.OTHER)
        val issueKey = SubjectKey("GHISSUE:${subject.issueId}", SubjectKind.WORK_ITEM)
        val pullRequestKey = SubjectKey("GHPR:${subject.pullRequestId}", SubjectKind.WORK_ITEM)
        val commitKey = SubjectKey("GHCOMMIT:${subject.headSha}", SubjectKind.IMPLEMENTATION)
        val requiredKeys = buildSet {
            add(repositoryKey)
            add(issueKey)
            add(pullRequestKey)
            add(commitKey)
            subject.expectedChecks.forEach { add(SubjectKey("GHCHECK:${it.id}", SubjectKind.EVIDENCE)) }
        }
        val nodesByKey = projection.nodes.associateBy { it.subject.key }
        assertTrue(requiredKeys.all(nodesByKey::containsKey), "Projection is missing required exact subjects")

        assertEquals(
            "https://github.com/${subject.repositoryFullName}",
            nodesByKey.getValue(repositoryKey).externalRef.canonicalUrl,
        )
        assertEquals(
            "https://github.com/${subject.repositoryFullName}/issues/${subject.issueNumber}",
            nodesByKey.getValue(issueKey).externalRef.canonicalUrl,
        )
        assertEquals(
            "https://github.com/${subject.repositoryFullName}/pull/${subject.pullRequestNumber}",
            nodesByKey.getValue(pullRequestKey).externalRef.canonicalUrl,
        )
        assertEquals(
            "https://github.com/${subject.repositoryFullName}/commit/${subject.headSha}",
            nodesByKey.getValue(commitKey).externalRef.canonicalUrl,
        )

        assertTrue(
            projection.edges.any {
                it.from == issueKey && it.relation == EdgeRelation.DERIVED_FROM && it.to == repositoryKey
            },
        )
        assertTrue(
            projection.edges.any {
                it.from == pullRequestKey && it.relation == EdgeRelation.EVIDENCED_BY && it.to == commitKey
            },
        )
        for (expected in subject.expectedChecks) {
            val checkKey = SubjectKey("GHCHECK:${expected.id}", SubjectKind.EVIDENCE)
            assertTrue(
                projection.edges.any {
                    it.from == pullRequestKey && it.relation == EdgeRelation.EVIDENCED_BY && it.to == checkKey
                },
                "Exact successful check ${expected.id} did not evidence the PR",
            )
            assertTrue(
                projection.checkAssessments.any {
                    it.checkRunId == expected.id &&
                        it.pullRequestNumber == subject.pullRequestNumber &&
                        it.state == GitHubCheckEvidenceState.EXACT_HEAD_SUCCESS
                },
            )
        }
    }
}

private class RecordingMetadataSource(
    private val delegate: GitHubMetadataSource,
) : GitHubMetadataSource {
    var snapshot: GitHubWorkGraphSnapshot? = null
        private set

    override suspend fun read(request: GitHubWorkGraphRequest): GitHubReadResult<GitHubWorkGraphSnapshot> {
        return when (val result = delegate.read(request)) {
            is GitHubReadResult.Success -> result.also { snapshot = it.value }
            is GitHubReadResult.Unavailable -> result
        }
    }
}

private data class ExpectedCheck(
    val id: Long,
    val name: String,
    val runId: Long,
)

private data class CanarySubject(
    val repositoryFullName: String,
    val repositoryId: Long,
    val repositoryNodeId: String,
    val issueNumber: Long,
    val issueId: Long,
    val issueNodeId: String,
    val pullRequestNumber: Long,
    val pullRequestId: Long,
    val pullRequestNodeId: String,
    val headSha: String,
    val baseSha: String,
    val treeSha: String,
    val expectedChecks: List<ExpectedCheck>,
    val transportClass: String,
    val transportCommit: String,
    val transportBlob: String,
    val maxCheckPages: Int,
    val timeoutSeconds: Long,
) {
    val repositoryOwner: String = repositoryFullName.substringBefore('/')
    val repositoryName: String = repositoryFullName.substringAfter('/')

    init {
        require(repositoryOwner.isNotBlank() && repositoryName.isNotBlank())
        require(repositoryId > 0 && issueId > 0 && pullRequestId > 0)
        require(issueNumber > 0 && pullRequestNumber > 0)
        require(SHA_PATTERN.matches(headSha) && SHA_PATTERN.matches(baseSha) && SHA_PATTERN.matches(treeSha))
        require(SHA_PATTERN.matches(transportCommit) && SHA_PATTERN.matches(transportBlob))
        require(expectedChecks.isNotEmpty())
        require(expectedChecks.map { it.id }.distinct().size == expectedChecks.size)
        require(maxCheckPages in 1..100)
        require(timeoutSeconds in 1..300)
    }

    companion object {
        fun load(path: Path): CanarySubject {
            val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
            require(root.requiredString("schema") == "kaw.workspace.live-github-public-subject.v1")
            val repository = root.requiredObject("repository")
            val issue = root.requiredObject("issue")
            val pullRequest = root.requiredObject("pull_request")
            val commit = root.requiredObject("commit")
            val transport = root.requiredObject("transport")
            require(repository.requiredString("visibility") == "public")
            require(transport.requiredString("credential_mode") == "NONE")
            return CanarySubject(
                repositoryFullName = repository.requiredString("full_name"),
                repositoryId = repository.requiredLong("id"),
                repositoryNodeId = repository.requiredString("node_id"),
                issueNumber = issue.requiredLong("number"),
                issueId = issue.requiredLong("id"),
                issueNodeId = issue.requiredString("node_id"),
                pullRequestNumber = pullRequest.requiredLong("number"),
                pullRequestId = pullRequest.requiredLong("id"),
                pullRequestNodeId = pullRequest.requiredString("node_id"),
                headSha = pullRequest.requiredString("head_sha"),
                baseSha = pullRequest.requiredString("base_sha"),
                treeSha = commit.requiredString("tree_sha"),
                expectedChecks = root.requiredArray("expected_checks").map { element ->
                    val check = element.jsonObject
                    ExpectedCheck(
                        id = check.requiredLong("id"),
                        name = check.requiredString("name"),
                        runId = check.requiredLong("run_id"),
                    )
                },
                transportClass = transport.requiredString("class"),
                transportCommit = transport.requiredString("source_commit"),
                transportBlob = transport.requiredString("source_blob"),
                maxCheckPages = transport.requiredLong("max_check_pages").toInt(),
                timeoutSeconds = transport.requiredLong("timeout_seconds"),
            )
        }
    }
}

private data class CanaryOutcome(
    val projection: GitHubWorkGraphProjection,
    val subjectsApplied: Int,
    val edgesApplied: Int,
    val observationSequence: Long,
) {
    fun toReceipt(
        subject: CanarySubject,
        implementationHead: String,
        workflowRunId: Long,
        workflowRunAttempt: Long,
        startedAt: String,
        endedAt: String,
        temporaryDatabaseRemoved: Boolean,
    ): JsonObject = buildJsonObject {
        put("schema", "kaw.workspace.live-github-receipt.v1")
        put("lane", "L2_LIVE_GITHUB_CONNECTOR")
        put("status", "PASS")
        put("maximum_claim", "PUBLIC_EXACT_SUBJECT_READ_AND_LOCAL_PROJECTION")
        putJsonObject("transport") {
            put("class", subject.transportClass)
            put("source_commit", subject.transportCommit)
            put("source_blob", subject.transportBlob)
            put("credential_mode", "NONE")
            put("credential_provider_bound", false)
        }
        putJsonObject("subject") {
            put("repository_full_name", subject.repositoryFullName)
            put("repository_id", subject.repositoryId)
            put("repository_node_id", subject.repositoryNodeId)
            put("issue_number", subject.issueNumber)
            put("issue_id", subject.issueId)
            put("issue_node_id", subject.issueNodeId)
            put("pull_request_number", subject.pullRequestNumber)
            put("pull_request_id", subject.pullRequestId)
            put("pull_request_node_id", subject.pullRequestNodeId)
            put("head_sha", subject.headSha)
            put("tree_sha", subject.treeSha)
            put("runtime_node_id_validation", "PREP_BINDING_ONLY_W2_MODEL_ABSENT")
        }
        putJsonObject("execution") {
            put("implementation_head_sha", implementationHead)
            put("workflow_run_id", workflowRunId)
            put("workflow_run_attempt", workflowRunAttempt)
            put("runner_os", System.getenv("RUNNER_OS") ?: "UNKNOWN")
            put("started_at", startedAt)
            put("ended_at", endedAt)
            put("observation_sequence", observationSequence)
        }
        putJsonObject("result") {
            put("subjects_applied", subjectsApplied)
            put("edges_applied", edgesApplied)
            put("active_subjects_after_reopen", projection.nodes.size)
            put("w1_subject_read_back", true)
            put("w1_edge_read_back", true)
            put("all_returned_checks_exact_head", true)
            putJsonArray("exact_successful_check_ids") {
                subject.expectedChecks.sortedBy { it.id }.forEach { add(JsonPrimitive(it.id)) }
            }
            putJsonArray("exact_successful_check_names") {
                subject.expectedChecks.sortedBy { it.id }.forEach { add(JsonPrimitive(it.name)) }
            }
            putJsonArray("source_workflow_run_ids") {
                subject.expectedChecks.map { it.runId }.distinct().sorted().forEach { add(JsonPrimitive(it)) }
            }
        }
        putJsonObject("disclosure") {
            put("authorization_header_persisted", false)
            put("token_persisted", false)
            put("cookie_persisted", false)
            put("email_persisted", false)
            put("response_body_persisted", false)
            put("private_locator_persisted", false)
        }
        putJsonObject("cleanup") {
            put("temporary_database_removed", temporaryDatabaseRemoved)
            put("credential_cleanup_required", false)
            put("temporary_response_files_persisted", false)
        }
        putJsonObject("evidence_boundary") {
            put("public_repository", "PASS")
            put("private_repository", "NOT_EXERCISED")
            put("github_mutation", "NOT_IMPLEMENTED")
            put("merge_release", "NOT_AUTHORIZED")
            put("l3_to_l6", "NOT_EXERCISED")
            put("l7_user_outcome", "ABSENT")
        }
    }
}

private fun JsonObject.requiredObject(name: String): JsonObject =
    this[name]?.jsonObject ?: error("Missing object: $name")

private fun JsonObject.requiredArray(name: String): JsonArray =
    this[name]?.jsonArray ?: error("Missing array: $name")

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("Missing string: $name")

private fun JsonObject.requiredLong(name: String): Long =
    this[name]?.jsonPrimitive?.long ?: error("Missing integer: $name")

private fun requiredPath(name: String): Path {
    val value = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable: $name")
    return Path.of(value)
}

private fun requiredSha(name: String): String {
    return System.getenv(name)?.lowercase()?.takeIf(SHA_PATTERN::matches)
        ?: error("Missing or invalid SHA environment variable: $name")
}

private fun requiredPositiveLong(name: String): Long {
    return System.getenv(name)?.toLongOrNull()?.takeIf { it > 0 }
        ?: error("Missing or invalid positive integer environment variable: $name")
}
