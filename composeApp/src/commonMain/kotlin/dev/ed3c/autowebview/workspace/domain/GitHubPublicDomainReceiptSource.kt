package dev.ed3c.autowebview.workspace.domain

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val DOMAIN_SOURCE_SHA40 = Regex("^[0-9a-f]{40}$")
private val DOMAIN_SOURCE_SHA256 = Regex("^[0-9a-f]{64}$")
private val DOMAIN_SOURCE_REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
private val DOMAIN_SOURCE_BRANCH = Regex("^[A-Za-z0-9._/-]{1,256}$")
private val DOMAIN_SOURCE_PATH = Regex("^[A-Za-z0-9._/-]{1,512}$")

@Serializable
data class DomainCanarySelector(
    val schema: String,
    val producer: DomainCanaryProducerSelector,
    val receipt: DomainCanaryReceiptExpectation,
    val transport: DomainCanaryTransport,
    @SerialName("evidence_boundary") val evidenceBoundary: DomainCanaryEvidenceBoundary,
) {
    init {
        require(schema == "kaw.workspace.live-domain-selector.v1") { "Domain canary selector schema mismatch" }
        require(evidenceBoundary.maximumClaim == "EXACT_PUBLIC_DOMAIN_RECEIPT_VALIDATION") {
            "Domain canary maximum claim is invalid"
        }
        require(evidenceBoundary.privateSourceAccess == "NOT_EXERCISED")
        require(evidenceBoundary.otherDomainAuthorities == "NOT_EXERCISED")
        require(evidenceBoundary.productionDeployment == "NOT_EXERCISED")
        require(evidenceBoundary.userOutcome == "ABSENT")
        require(evidenceBoundary.paidOutcome == "ABSENT")
        require(evidenceBoundary.mergeRelease == "NOT_AUTHORIZED")
    }
}

@Serializable
data class DomainCanaryProducerSelector(
    @SerialName("repository_full_name") val repositoryFullName: String,
    @SerialName("pull_request_number") val pullRequestNumber: Int,
    @SerialName("head_branch") val headBranch: String,
    @SerialName("base_branch") val baseBranch: String,
    @SerialName("receipt_path") val receiptPath: String,
    @SerialName("required_workflow_names") val requiredWorkflowNames: Set<String>,
) {
    init {
        require(DOMAIN_SOURCE_REPO.matches(repositoryFullName)) { "Producer repository is invalid" }
        require(pullRequestNumber > 0) { "Producer pull request must be exact" }
        require(DOMAIN_SOURCE_BRANCH.matches(headBranch) && !headBranch.contains(".."))
        require(DOMAIN_SOURCE_BRANCH.matches(baseBranch) && !baseBranch.contains(".."))
        require(DOMAIN_SOURCE_PATH.matches(receiptPath) && !receiptPath.contains(".."))
        require(requiredWorkflowNames.isNotEmpty()) { "Producer workflow denominator is empty" }
        require(requiredWorkflowNames.all { it.isNotBlank() && it.length <= 128 })
    }
}

@Serializable
data class DomainCanaryReceiptExpectation(
    val schema: String,
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("authority_owner") val authorityOwner: String,
    val lane: String,
    val environment: String,
    @SerialName("closure_engine_blob") val closureEngineBlob: String,
    @SerialName("semantic_verifier_schema_blob") val semanticVerifierSchemaBlob: String,
    @SerialName("claim_id") val claimId: String,
    @SerialName("claim_digest") val claimDigest: String,
    @SerialName("verdict_state") val verdictState: DomainVerdictState,
    @SerialName("evidence_ceiling") val evidenceCeiling: String,
) {
    init {
        require(schema == "tvl.kaw-domain-receipt.v1")
        require(receiptId == "TVL-KAW-PUBLIC-SYNTHETIC-1")
        require(authorityOwner == "truth-verify-loop")
        require(lane == "L5_LIVE_DOMAIN_AUTHORITY_RECEIPT")
        require(environment == "PUBLIC_SYNTHETIC_CI")
        require(DOMAIN_SOURCE_SHA40.matches(closureEngineBlob))
        require(DOMAIN_SOURCE_SHA40.matches(semanticVerifierSchemaBlob))
        require(claimId.isNotBlank())
        require(DOMAIN_SOURCE_SHA256.matches(claimDigest))
        require(evidenceCeiling == "DOMAIN_VERDICT")
    }
}

@Serializable
data class DomainCanaryTransport(
    val origin: String,
    @SerialName("credential_mode") val credentialMode: String,
    @SerialName("timeout_seconds") val timeoutSeconds: Long,
) {
    init {
        val url = Url(origin)
        require(url.protocol.name == "https" && url.host == "api.github.com") {
            "Only public api.github.com is admitted for the L5 canary"
        }
        require(url.user.isNullOrBlank() && url.password.isNullOrBlank())
        require(url.parameters.isEmpty() && url.fragment.isEmpty())
        require(url.encodedPath.isEmpty() || url.encodedPath == "/")
        require(credentialMode == "NONE") { "The public L5 canary is credential-free" }
        require(timeoutSeconds in 1..120)
    }
}

@Serializable
data class DomainCanaryEvidenceBoundary(
    @SerialName("maximum_claim") val maximumClaim: String,
    @SerialName("private_source_access") val privateSourceAccess: String,
    @SerialName("other_domain_authorities") val otherDomainAuthorities: String,
    @SerialName("production_deployment") val productionDeployment: String,
    @SerialName("user_outcome") val userOutcome: String,
    @SerialName("paid_outcome") val paidOutcome: String,
    @SerialName("merge_release") val mergeRelease: String,
)

fun interface DomainReceiptContentHasher {
    fun sha256(bytes: ByteArray): String
}

@Serializable
data class DomainProducerWorkflow(
    val name: String,
    val runId: Long,
    val conclusion: String,
)

data class ExactDomainReceipt(
    val reference: DomainReceiptReference,
    val rawReceipt: String,
    val repositoryId: Long,
    val producerPullRequestNumber: Int,
    val producerWorkflows: List<DomainProducerWorkflow>,
)

sealed interface DomainReceiptReadResult {
    data class Found(val receipt: ExactDomainReceipt) : DomainReceiptReadResult
    data class Unavailable(val reasonCode: String) : DomainReceiptReadResult
}

class GitHubPublicDomainReceiptSource(
    private val client: HttpClient,
    private val hasher: DomainReceiptContentHasher,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun read(selector: DomainCanarySelector): DomainReceiptReadResult = try {
        val (owner, repo) = selector.producer.repositoryFullName.split('/', limit = 2)
        val repository = getJson<RepositoryDto>(selector, "/repos/$owner/$repo")
        require(repository.fullName == selector.producer.repositoryFullName) {
            "Producer repository identity mismatch"
        }

        val pull = getJson<PullRequestDto>(
            selector,
            "/repos/$owner/$repo/pulls/${selector.producer.pullRequestNumber}",
        )
        require(pull.number == selector.producer.pullRequestNumber) { "Producer PR identity mismatch" }
        require(pull.head.ref == selector.producer.headBranch) { "Producer head branch mismatch" }
        require(pull.base.ref == selector.producer.baseBranch) { "Producer base branch mismatch" }
        val commitSha = pull.head.sha.lowercase()
        require(DOMAIN_SOURCE_SHA40.matches(commitSha)) { "Producer head SHA is invalid" }

        val commit = getJson<GitCommitDto>(selector, "/repos/$owner/$repo/git/commits/$commitSha")
        require(commit.sha.equals(commitSha, ignoreCase = true)) { "Producer commit mismatch" }
        val treeSha = commit.tree.sha.lowercase()
        require(DOMAIN_SOURCE_SHA40.matches(treeSha)) { "Producer tree SHA is invalid" }

        val content = getJson<ContentDto>(
            selector,
            "/repos/$owner/$repo/contents/${selector.producer.receiptPath}",
            mapOf("ref" to commitSha),
        )
        require(content.path == selector.producer.receiptPath) { "Receipt path mismatch" }
        require(DOMAIN_SOURCE_SHA40.matches(content.sha.lowercase())) { "Receipt blob SHA is invalid" }
        require(content.encoding == "base64") { "Receipt content must use GitHub base64 encoding" }
        val bytes = decodeBase64(content.content)
        val contentSha256 = hasher.sha256(bytes).lowercase()
        require(DOMAIN_SOURCE_SHA256.matches(contentSha256)) { "Receipt content digest is invalid" }
        val rawReceipt = bytes.decodeToString()

        val workflows = readWorkflowDenominator(selector, owner, repo, commitSha)
        val expectation = selector.receipt
        val reference = DomainReceiptReference(
            repositoryFullName = selector.producer.repositoryFullName,
            commitSha = commitSha,
            treeSha = treeSha,
            receiptPath = selector.producer.receiptPath,
            receiptBlobSha = content.sha.lowercase(),
            receiptContentSha256 = contentSha256,
            receiptSchema = expectation.schema,
            receiptId = expectation.receiptId,
            authorityOwner = expectation.authorityOwner,
            lane = expectation.lane,
            environment = expectation.environment,
            closureEngineBlob = expectation.closureEngineBlob,
            semanticVerifierSchemaBlob = expectation.semanticVerifierSchemaBlob,
            claimId = expectation.claimId,
            claimDigest = expectation.claimDigest,
            verdictState = expectation.verdictState,
            evidenceCeiling = expectation.evidenceCeiling,
        )
        DomainReceiptReadResult.Found(
            ExactDomainReceipt(
                reference = reference,
                rawReceipt = rawReceipt,
                repositoryId = repository.id,
                producerPullRequestNumber = pull.number,
                producerWorkflows = workflows,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (abort: DomainSourceAbort) {
        DomainReceiptReadResult.Unavailable(abort.reasonCode)
    } catch (_: Exception) {
        DomainReceiptReadResult.Unavailable("DOMAIN_RECEIPT_RESPONSE_MISMATCH")
    }

    private suspend fun readWorkflowDenominator(
        selector: DomainCanarySelector,
        owner: String,
        repo: String,
        commitSha: String,
    ): List<DomainProducerWorkflow> {
        val response = getJson<WorkflowRunsDto>(
            selector,
            "/repos/$owner/$repo/actions/runs",
            mapOf("head_sha" to commitSha, "per_page" to "100"),
        )
        val byName = response.workflowRuns
            .asSequence()
            .filter { it.headSha.equals(commitSha, ignoreCase = true) }
            .filter { it.status == "completed" && it.conclusion == "success" }
            .filter { it.name in selector.producer.requiredWorkflowNames }
            .groupBy(WorkflowRunDto::name)
        require(byName.keys == selector.producer.requiredWorkflowNames) {
            "Producer workflow denominator is incomplete"
        }
        return selector.producer.requiredWorkflowNames.sorted().map { name ->
            val run = byName.getValue(name).maxBy(WorkflowRunDto::id)
            DomainProducerWorkflow(name = name, runId = run.id, conclusion = run.conclusion ?: "")
        }
    }

    private suspend inline fun <reified T> getJson(
        selector: DomainCanarySelector,
        path: String,
        parameters: Map<String, String> = emptyMap(),
    ): T {
        require(path.startsWith('/') && !path.contains("..")) { "Unsafe GitHub API path" }
        val response = try {
            client.get(selector.transport.origin.trimEnd('/') + path) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "kotlin-auto-webview")
                header("X-GitHub-Api-Version", "2022-11-28")
                parameters.forEach { (key, value) -> parameter(key, value) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw DomainSourceAbort("DOMAIN_RECEIPT_NETWORK_FAILURE")
        }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.NotFound -> throw DomainSourceAbort("DOMAIN_RECEIPT_NOT_FOUND")
            HttpStatusCode.Unauthorized -> throw DomainSourceAbort("DOMAIN_RECEIPT_UNAUTHORIZED")
            HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests ->
                throw DomainSourceAbort("DOMAIN_RECEIPT_RATE_LIMITED")
            else -> if (response.status.value in 500..599) {
                throw DomainSourceAbort("DOMAIN_RECEIPT_SERVER_FAILURE")
            } else {
                throw DomainSourceAbort("DOMAIN_RECEIPT_RESPONSE_MISMATCH")
            }
        }
        return try {
            json.decodeFromString(response.bodyAsText())
        } catch (_: Exception) {
            throw DomainSourceAbort("DOMAIN_RECEIPT_RESPONSE_MISMATCH")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(value: String): ByteArray =
        try {
            Base64.decode(value.filterNot(Char::isWhitespace))
        } catch (_: IllegalArgumentException) {
            throw DomainSourceAbort("DOMAIN_RECEIPT_RESPONSE_MISMATCH")
        }
}

private class DomainSourceAbort(val reasonCode: String) : RuntimeException()

@Serializable
private data class RepositoryDto(
    val id: Long,
    @SerialName("full_name") val fullName: String,
)

@Serializable
private data class PullRequestDto(
    val number: Int,
    val state: String,
    val merged: Boolean = false,
    val base: GitRefDto,
    val head: GitRefDto,
)

@Serializable
private data class GitRefDto(val ref: String, val sha: String)

@Serializable
private data class GitCommitDto(val sha: String, val tree: GitTreeDto)

@Serializable
private data class GitTreeDto(val sha: String)

@Serializable
private data class ContentDto(
    val path: String,
    val sha: String,
    val content: String,
    val encoding: String,
)

@Serializable
private data class WorkflowRunsDto(
    @SerialName("workflow_runs") val workflowRuns: List<WorkflowRunDto>,
)

@Serializable
private data class WorkflowRunDto(
    val id: Long,
    val name: String,
    @SerialName("head_sha") val headSha: String,
    val status: String,
    val conclusion: String? = null,
)
