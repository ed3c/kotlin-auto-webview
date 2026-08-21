package dev.ed3c.autowebview.workspace.domain.live

import dev.ed3c.autowebview.workspace.domain.DomainAuthorityReceiptValidator
import dev.ed3c.autowebview.workspace.domain.DomainCanarySelector
import dev.ed3c.autowebview.workspace.domain.DomainReceiptContentHasher
import dev.ed3c.autowebview.workspace.domain.DomainReceiptReadResult
import dev.ed3c.autowebview.workspace.domain.DomainReceiptValidationResult
import dev.ed3c.autowebview.workspace.domain.GitHubPublicDomainReceiptSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val LIVE_DOMAIN_FLAG = "KAW_LIVE_DOMAIN_CANARY"
private const val SELECTOR_PATH_ENV = "KAW_LIVE_DOMAIN_SELECTOR_PATH"
private const val RECEIPT_PATH_ENV = "KAW_LIVE_DOMAIN_RECEIPT_PATH"
private const val IMPLEMENTATION_HEAD_ENV = "KAW_IMPLEMENTATION_HEAD_SHA"
private val SHA40 = Regex("^[0-9a-f]{40}$")

class TruthVerifyLoopPublicCanaryTest {
    @Test
    fun exactDomainReceiptIsFetchedValidatedAndProjectedWithoutVerdictRecomputation() = runBlocking {
        if (System.getenv(LIVE_DOMAIN_FLAG) != "1") return@runBlocking

        require(System.getenv("GITHUB_TOKEN").isNullOrBlank()) {
            "The public domain canary must not receive GITHUB_TOKEN"
        }
        require(System.getenv("GH_TOKEN").isNullOrBlank()) {
            "The public domain canary must not receive GH_TOKEN"
        }

        val selectorPath = requiredPath(SELECTOR_PATH_ENV)
        val outputPath = requiredPath(RECEIPT_PATH_ENV)
        val implementationHead = requiredSha(IMPLEMENTATION_HEAD_ENV)
        val workflowRunId = requiredPositiveLong("GITHUB_RUN_ID")
        val workflowRunAttempt = requiredPositiveLong("GITHUB_RUN_ATTEMPT")
        val selector = Json { ignoreUnknownKeys = false }.decodeFromString<DomainCanarySelector>(
            Files.readString(selectorPath),
        )
        val startedAt = Instant.now().toString()
        val client = HttpClient(CIO) {
            expectSuccess = false
            engine { requestTimeout = selector.transport.timeoutSeconds * 1_000L }
        }

        val exactReceipt = try {
            val source = GitHubPublicDomainReceiptSource(
                client = client,
                hasher = DomainReceiptContentHasher(::sha256),
            )
            val result = withTimeout(selector.transport.timeoutSeconds * 1_000L) {
                source.read(selector)
            }
            assertIs<DomainReceiptReadResult.Found>(result).receipt
        } finally {
            client.close()
        }

        assertEquals(
            selector.producer.requiredWorkflowNames,
            exactReceipt.producerWorkflows.mapTo(linkedSetOf()) { it.name },
        )
        assertTrue(exactReceipt.producerWorkflows.all { it.conclusion == "success" })
        assertEquals(selector.receipt.claimId, exactReceipt.reference.claimId)
        assertEquals(selector.receipt.claimDigest, exactReceipt.reference.claimDigest)
        assertEquals(selector.receipt.verdictState, exactReceipt.reference.verdictState)

        val accepted = assertIs<DomainReceiptValidationResult.Accepted>(
            DomainAuthorityReceiptValidator().validate(
                reference = exactReceipt.reference,
                rawReceipt = exactReceipt.rawReceipt,
                observedContentSha256 = exactReceipt.reference.receiptContentSha256,
            ),
        )
        val projection = accepted.projection
        assertEquals(selector.receipt.authorityOwner, projection.authorityOwner)
        assertEquals(selector.receipt.verdictState, projection.state)
        assertEquals(selector.receipt.evidenceCeiling, projection.evidenceCeiling)
        assertEquals(exactReceipt.reference.commitSha, projection.commitSha)
        assertEquals(exactReceipt.reference.treeSha, projection.treeSha)
        assertEquals(exactReceipt.reference.receiptBlobSha, projection.receiptBlobSha)
        assertFalse(exactReceipt.rawReceipt.contains("authorization", ignoreCase = true))
        assertFalse(exactReceipt.rawReceipt.contains("cookie", ignoreCase = true))

        val receipt = buildJsonObject {
            put("schema", "kaw.workspace.live-domain-receipt.v1")
            put("lane", "L5_LIVE_DOMAIN_AUTHORITY_RECEIPT")
            put("status", "PASS")
            put("maximum_claim", "EXACT_PUBLIC_DOMAIN_RECEIPT_VALIDATION")
            putJsonObject("producer") {
                put("repository_full_name", exactReceipt.reference.repositoryFullName)
                put("repository_id", exactReceipt.repositoryId)
                put("pull_request_number", exactReceipt.producerPullRequestNumber)
                put("commit_sha", exactReceipt.reference.commitSha)
                put("tree_sha", exactReceipt.reference.treeSha)
                put("receipt_path", exactReceipt.reference.receiptPath)
                put("receipt_blob_sha", exactReceipt.reference.receiptBlobSha)
                put("receipt_content_sha256", exactReceipt.reference.receiptContentSha256)
                putJsonArray("workflow_runs") {
                    exactReceipt.producerWorkflows.sortedBy { it.name }.forEach { workflow ->
                        add(
                            buildJsonObject {
                                put("name", workflow.name)
                                put("run_id", workflow.runId)
                                put("conclusion", workflow.conclusion)
                            },
                        )
                    }
                }
            }
            putJsonObject("execution") {
                put("implementation_head_sha", implementationHead)
                put("workflow_run_id", workflowRunId)
                put("workflow_run_attempt", workflowRunAttempt)
                put("runner_os", System.getProperty("os.name"))
                put("started_at", startedAt)
                put("ended_at", Instant.now().toString())
            }
            putJsonObject("subject") {
                put("claim_id", projection.claimId)
                put("claim_digest", projection.claimDigest)
                put("receipt_id", projection.receiptId)
            }
            putJsonObject("authority") {
                put("owner", projection.authorityOwner)
                put("environment", exactReceipt.reference.environment)
                put("verdict_state", projection.state.name)
                put("closed", projection.closed)
                put("closure_digest", projection.closureDigest)
                put("source_freshness", projection.sourceFreshness)
                put("evidence_ceiling", projection.evidenceCeiling)
            }
            putJsonObject("validation") {
                put("exact_repository", true)
                put("exact_commit", true)
                put("exact_tree", true)
                put("exact_blob", true)
                put("exact_content_digest", true)
                put("producer_workflows_exact_head", true)
                put("verdict_preserved", true)
                put("raw_source_imported", false)
                put("raw_evidence_imported", false)
            }
            putJsonObject("disclosure") {
                put("credential_persisted", false)
                put("authorization_header_persisted", false)
                put("cookie_persisted", false)
                put("email_persisted", false)
                put("internal_reasoning_persisted", false)
                put("private_locator_persisted", false)
                put("raw_source_persisted", false)
                put("raw_evidence_persisted", false)
            }
            putJsonObject("evidence_boundary") {
                put("l2_public_github", "PASS_SEPARATE_RECEIPT")
                put("l3_google", "NOT_EXERCISED")
                put("l4_bettor", "NOT_EXERCISED")
                put("l5_domain_authority", "PASS")
                put("l6_physical_device", "NOT_EXERCISED")
                put("l7_user_outcome", "ABSENT")
                put("paid_outcome", "ABSENT")
                put("merge_release", "NOT_AUTHORIZED")
            }
        }
        outputPath.parent?.let(Files::createDirectories)
        Files.writeString(outputPath, receipt.toString() + "\n")
        assertTrue(Files.isRegularFile(outputPath))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun requiredPath(name: String): Path =
        System.getenv(name)?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: error("Missing required environment variable: $name")

    private fun requiredSha(name: String): String =
        System.getenv(name)?.lowercase()?.takeIf(SHA40::matches)
            ?: error("Missing or invalid SHA environment variable: $name")

    private fun requiredPositiveLong(name: String): Long =
        System.getenv(name)?.toLongOrNull()?.takeIf { it > 0 }
            ?: error("Missing or invalid positive integer environment variable: $name")
}
