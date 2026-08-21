package dev.ed3c.autowebview.workspace.google.live

import dev.ed3c.autowebview.workspace.contract.AuthorityKind
import dev.ed3c.autowebview.workspace.contract.AuthorityRef
import dev.ed3c.autowebview.workspace.contract.DigestRef
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectKind
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.google.GoogleDestinationAdmission
import dev.ed3c.autowebview.workspace.google.GoogleProjectionBinding
import dev.ed3c.autowebview.workspace.google.GoogleProjectionKind
import dev.ed3c.autowebview.workspace.google.GoogleProjectionPayload
import dev.ed3c.autowebview.workspace.google.GoogleProjectionReadResult
import dev.ed3c.autowebview.workspace.google.GoogleProjectionWriteCommand
import dev.ed3c.autowebview.workspace.google.GoogleProjectionWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val FILE_ID = "DocFile_123"
private const val DOC_SCOPE = "https://www.googleapis.com/auth/documents"

class GoogleDocsProjectionTransportTest {
    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            googleProjectionSha256(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            googleProjectionSha256("abc"),
        )
    }

    @Test
    fun endpointAndCapabilityRejectUnsafeMaterial() {
        assertFailsWith<IllegalArgumentException> { GoogleDocsApiEndpoint("http://docs.googleapis.com") }
        assertFailsWith<IllegalArgumentException> { GoogleDocsApiEndpoint("https://example.com") }
        assertFailsWith<IllegalArgumentException> {
            GoogleDocsAccessCapability.create("CAP:google", setOf(DOC_SCOPE), "token with space")
        }
        val capability = capability()
        assertFalse(capability.toString().contains("test-token"))
        assertTrue(capability.toString().contains("<redacted>"))
    }

    @Test
    fun blankDocumentReadReturnsUnboundSnapshot() = runTest {
        val executor = FakeGoogleDocsApiExecutor(
            getResults = listOf(response(200, documentResponse(text = "\n", revision = "rev-1"))),
        )
        val found = assertIs<GoogleProjectionReadResult.Found>(transport(executor).read(binding()))
        assertEquals(FILE_ID, found.snapshot.fileId)
        assertEquals("rev-1", found.snapshot.revision)
        assertEquals(null, found.snapshot.canonicalSubject)
        assertEquals(null, found.snapshot.canonicalDigest)
        assertEquals(DigestRef(value = googleProjectionSha256("\n")), found.snapshot.renderedDigest)
    }

    @Test
    fun managedDocumentReadReturnsExactIdentityAndDigests() = runTest {
        val payload = payload("rendered content")
        val executor = FakeGoogleDocsApiExecutor(
            getResults = listOf(response(200, documentResponse(managedText(payload), revision = "rev-8"))),
        )
        val found = assertIs<GoogleProjectionReadResult.Found>(transport(executor).read(binding()))
        assertEquals(payload.subject.key, found.snapshot.canonicalSubject)
        assertEquals(payload.subject.digest, found.snapshot.canonicalDigest)
        assertEquals(payload.renderedDigest, found.snapshot.renderedDigest)
    }

    @Test
    fun writeUsesRequiredRevisionAndReturnsAcknowledgedRevision() = runTest {
        val payload = payload("new projection")
        val executor = FakeGoogleDocsApiExecutor(
            getResults = listOf(response(200, documentResponse("\n", revision = "rev-1"))),
            writeResults = listOf(
                response(
                    200,
                    """{"documentId":"$FILE_ID","writeControl":{"requiredRevisionId":"rev-2"}}""",
                ),
            ),
        )
        val result = assertIs<GoogleProjectionWriteResult.Acknowledged>(
            transport(executor).write(command(payload, revision = "rev-1")),
        )
        assertEquals("rev-2", result.revision)
        assertEquals(payload.renderedDigest, result.writtenDigest)
        val body = Json.parseToJsonElement(executor.requestBodies.single()).jsonObject
        assertEquals("rev-1", body["writeControl"]!!.jsonObject["requiredRevisionId"]!!.jsonPrimitive.content)
        val requests = body["requests"]!!.jsonArray
        assertEquals(1, requests.size)
        assertTrue(requests.single().jsonObject.containsKey("insertText"))
        assertTrue(executor.requestBodies.single().contains("KAW_GOOGLE_DOCS_PROJECTION_V1"))
    }

    @Test
    fun managedDocumentWriteDeletesOldManagedBodyAtomically() = runTest {
        val previous = payload("old")
        val next = payload("new")
        val executor = FakeGoogleDocsApiExecutor(
            getResults = listOf(response(200, documentResponse(managedText(previous), revision = "rev-2"))),
            writeResults = listOf(
                response(200, """{"documentId":"$FILE_ID","writeControl":{"requiredRevisionId":"rev-3"}}"""),
            ),
        )
        assertIs<GoogleProjectionWriteResult.Acknowledged>(
            transport(executor).write(command(next, revision = "rev-2")),
        )
        val requests = Json.parseToJsonElement(executor.requestBodies.single())
            .jsonObject["requests"]!!.jsonArray
        assertEquals(2, requests.size)
        assertTrue(requests[0].jsonObject.containsKey("deleteContentRange"))
        assertTrue(requests[1].jsonObject.containsKey("insertText"))
    }

    @Test
    fun foreignOrCorruptTargetsAreNeverOverwritten() = runTest {
        val foreignExecutor = FakeGoogleDocsApiExecutor(
            getResults = listOf(response(200, documentResponse("human notes\n", revision = "rev-1"))),
        )
        val foreign = assertIs<GoogleProjectionWriteResult.Blocked>(
            transport(foreignExecutor).write(command(payload("new"), revision = "rev-1")),
        )
        assertEquals("GOOGLE_DOCS_TARGET_NOT_MANAGED", foreign.reasonCode)
        assertTrue(foreignExecutor.requestBodies.isEmpty())

        val corrupt = "[KAW_GOOGLE_DOCS_PROJECTION_V1]\n{bad-json}\n[/KAW_GOOGLE_DOCS_PROJECTION_V1]\n"
        val corruptExecutor = FakeGoogleDocsApiExecutor(
            getResults = listOf(response(200, documentResponse(corrupt, revision = "rev-1"))),
        )
        val blocked = assertIs<GoogleProjectionWriteResult.Blocked>(
            transport(corruptExecutor).write(command(payload("new"), revision = "rev-1")),
        )
        assertEquals("GOOGLE_DOCS_TARGET_MANAGED_CONTENT_CORRUPT", blocked.reasonCode)
        assertTrue(corruptExecutor.requestBodies.isEmpty())
    }

    @Test
    fun managedTargetForAnotherSubjectIsBlocked() = runTest {
        val executor = FakeGoogleDocsApiExecutor(
            getResults = listOf(
                response(200, documentResponse(managedText(payload("old", "REQ:other")), revision = "rev-1")),
            ),
        )
        val blocked = assertIs<GoogleProjectionWriteResult.Blocked>(
            transport(executor).write(command(payload("new"), revision = "rev-1")),
        )
        assertEquals("GOOGLE_DOCS_TARGET_SUBJECT_MISMATCH", blocked.reasonCode)
    }

    @Test
    fun staleRevisionAndProviderPreconditionFailureRemainExplicit() = runTest {
        val staleExecutor = FakeGoogleDocsApiExecutor(
            getResults = listOf(response(200, documentResponse("\n", revision = "rev-2"))),
        )
        val stale = assertIs<GoogleProjectionWriteResult.RevisionChanged>(
            transport(staleExecutor).write(command(payload("new"), revision = "rev-1")),
        )
        assertEquals("rev-2", stale.actualRevision)

        val providerExecutor = FakeGoogleDocsApiExecutor(
            getResults = listOf(response(200, documentResponse("\n", revision = "rev-1"))),
            writeResults = listOf(
                response(400, """{"error":{"status":"FAILED_PRECONDITION","message":"revision is not latest"}}"""),
            ),
        )
        assertIs<GoogleProjectionWriteResult.RevisionChanged>(
            transport(providerExecutor).write(command(payload("new"), revision = "rev-1")),
        )
    }

    @Test
    fun renderedDigestMismatchIsBlockedBeforeNetwork() = runTest {
        val tampered = payload("new").copy(renderedContent = "tampered")
        val executor = FakeGoogleDocsApiExecutor()
        val blocked = assertIs<GoogleProjectionWriteResult.Blocked>(
            transport(executor).write(command(tampered, revision = "rev-1")),
        )
        assertEquals("GOOGLE_DOCS_RENDERED_DIGEST_MISMATCH", blocked.reasonCode)
        assertEquals(0, executor.getCalls)
    }

    @Test
    fun missingAccountOrScopeIsBlockedWithoutNetwork() = runTest {
        val executor = FakeGoogleDocsApiExecutor()
        val missing = GoogleDocsProjectionTransport(
            capabilityProvider = GoogleDocsAccessCapabilityProvider { null },
            executor = executor,
        )
        assertEquals(
            "GOOGLE_ACCOUNT_CAPABILITY_ABSENT",
            assertIs<GoogleProjectionReadResult.Blocked>(missing.read(binding())).reasonCode,
        )

        val wrongScope = GoogleDocsProjectionTransport(
            capabilityProvider = GoogleDocsAccessCapabilityProvider {
                GoogleDocsAccessCapability.create(
                    capabilityId = "CAP:google:readonly",
                    grantedScopes = setOf("https://www.googleapis.com/auth/drive.metadata.readonly"),
                    accessToken = "test-token",
                )
            },
            executor = executor,
        )
        assertEquals(
            "GOOGLE_DOCS_SCOPE_NOT_ADMITTED",
            assertIs<GoogleProjectionReadResult.Blocked>(wrongScope.read(binding())).reasonCode,
        )
        assertEquals(0, executor.getCalls)
    }

    @Test
    fun sheetsAreFailClosedUntilAProvablePreconditionExists() = runTest {
        val executor = FakeGoogleDocsApiExecutor()
        val sheetBinding = binding().copy(kind = GoogleProjectionKind.SHEET)
        assertEquals(
            "GOOGLE_SHEETS_CONDITIONAL_WRITE_UNSUPPORTED",
            assertIs<GoogleProjectionReadResult.Blocked>(transport(executor).read(sheetBinding)).reasonCode,
        )
        assertEquals(
            "GOOGLE_SHEETS_CONDITIONAL_WRITE_UNSUPPORTED",
            assertIs<GoogleProjectionWriteResult.Blocked>(
                transport(executor).write(command(payload("sheet"), revision = "rev-1", binding = sheetBinding)),
            ).reasonCode,
        )
        assertEquals(0, executor.getCalls)
    }

    @Test
    fun providerFailuresRemainBoundedAndDoNotLeakBodies() = runTest {
        for ((status, reason) in listOf(
            401 to "GOOGLE_DOCS_UNAUTHORIZED",
            403 to "GOOGLE_DOCS_FORBIDDEN",
            404 to "GOOGLE_DOCS_FILE_NOT_FOUND",
        )) {
            val result = transport(
                FakeGoogleDocsApiExecutor(getResults = listOf(response(status, "secret provider message"))),
            ).read(binding())
            assertEquals(reason, assertIs<GoogleProjectionReadResult.Blocked>(result).reasonCode)
        }
        assertEquals(
            "GOOGLE_DOCS_RATE_LIMITED",
            assertIs<GoogleProjectionReadResult.RetryableFailure>(
                transport(FakeGoogleDocsApiExecutor(getResults = listOf(response(429, "rate")))).read(binding()),
            ).reasonCode,
        )
        assertEquals(
            "GOOGLE_DOCS_SERVER_FAILURE",
            assertIs<GoogleProjectionReadResult.RetryableFailure>(
                transport(FakeGoogleDocsApiExecutor(getResults = listOf(response(503, "server")))).read(binding()),
            ).reasonCode,
        )
        assertEquals(
            "GOOGLE_DOCS_NETWORK_FAILURE",
            assertIs<GoogleProjectionReadResult.RetryableFailure>(
                transport(FakeGoogleDocsApiExecutor(getResults = listOf(GoogleDocsHttpResult.NetworkFailure))).read(binding()),
            ).reasonCode,
        )
    }

    @Test
    fun responseIdentityMismatchAndCancellationFailClosed() = runTest {
        val mismatch = transport(
            FakeGoogleDocsApiExecutor(
                getResults = listOf(response(200, documentResponse("\n", "rev-1", "OtherDoc_123"))),
            ),
        ).read(binding())
        assertEquals(
            "GOOGLE_DOCS_RESPONSE_INVALID",
            assertIs<GoogleProjectionReadResult.Blocked>(mismatch).reasonCode,
        )

        val cancelling = object : GoogleDocsApiExecutor {
            override suspend fun getDocument(
                fileId: String,
                capability: GoogleDocsAccessCapability,
            ): GoogleDocsHttpResult = throw CancellationException("cancel")

            override suspend fun batchUpdateDocument(
                fileId: String,
                capability: GoogleDocsAccessCapability,
                requestBody: String,
            ): GoogleDocsHttpResult = error("not called")
        }
        var cancelled = false
        try {
            transport(cancelling).read(binding())
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled, "Cancellation must propagate")
    }

    private fun transport(executor: GoogleDocsApiExecutor): GoogleDocsProjectionTransport =
        GoogleDocsProjectionTransport(
            capabilityProvider = GoogleDocsAccessCapabilityProvider { capability() },
            executor = executor,
        )

    private fun capability(): GoogleDocsAccessCapability = GoogleDocsAccessCapability.create(
        capabilityId = "CAP:google:docs:test",
        grantedScopes = setOf(DOC_SCOPE),
        accessToken = "test-token",
    )

    private fun binding(): GoogleProjectionBinding = GoogleProjectionBinding(
        projectionId = "GPROJ:docs:test",
        canonicalSubject = SubjectKey("REQ:google:test", SubjectKind.REQUIREMENT),
        kind = GoogleProjectionKind.DOC,
        fileId = FILE_ID,
        expectedRevision = "rev-1",
        destinationAdmission = GoogleDestinationAdmission.ADMITTED,
    )

    private fun payload(content: String, logicalId: String = "REQ:google:test"): GoogleProjectionPayload {
        val subject = SubjectRef(
            key = SubjectKey(logicalId, SubjectKind.REQUIREMENT),
            canonicalAuthority = AuthorityRef(AuthorityKind.GITHUB, "ed3c/kotlin-auto-webview"),
            version = "v1",
            digest = DigestRef(value = googleProjectionSha256("canonical:$logicalId")),
        )
        return GoogleProjectionPayload(
            subject = subject,
            renderedContent = content,
            renderedDigest = DigestRef(value = googleProjectionSha256(content)),
            destinationAdmission = GoogleDestinationAdmission.ADMITTED,
        )
    }

    private fun command(
        payload: GoogleProjectionPayload,
        revision: String,
        binding: GoogleProjectionBinding = binding(),
    ): GoogleProjectionWriteCommand = GoogleProjectionWriteCommand(
        eventId = "GPEVT:docs:test",
        binding = binding,
        payload = payload,
        ifRevisionMatches = revision,
    )

    private fun managedText(payload: GoogleProjectionPayload): String {
        val envelope = buildJsonObject {
            put("schema", "kaw.google-docs-projection.v1")
            putJsonObject("canonicalSubject") {
                put("logicalId", payload.subject.key.logicalId)
                put("kind", payload.subject.key.kind.name)
            }
            putJsonObject("canonicalDigest") {
                put("algorithm", payload.subject.digest!!.algorithm)
                put("value", payload.subject.digest!!.value)
            }
            putJsonObject("renderedDigest") {
                put("algorithm", payload.renderedDigest.algorithm)
                put("value", payload.renderedDigest.value)
            }
            put("renderedContent", payload.renderedContent)
        }
        return "[KAW_GOOGLE_DOCS_PROJECTION_V1]\n$envelope\n[/KAW_GOOGLE_DOCS_PROJECTION_V1]\n"
    }

    private fun documentResponse(
        text: String,
        revision: String,
        fileId: String = FILE_ID,
    ): String = buildJsonObject {
        put("documentId", fileId)
        put("revisionId", revision)
        putJsonObject("body") {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("startIndex", 1)
                        put("endIndex", text.length + 1)
                        putJsonObject("paragraph") {
                            putJsonArray("elements") {
                                add(
                                    buildJsonObject {
                                        put("startIndex", 1)
                                        put("endIndex", text.length + 1)
                                        putJsonObject("textRun") { put("content", text) }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }.toString()

    private fun response(status: Int, body: String): GoogleDocsHttpResult.Response =
        GoogleDocsHttpResult.Response(statusCode = status, body = body)
}

private class FakeGoogleDocsApiExecutor(
    getResults: List<GoogleDocsHttpResult> = emptyList(),
    writeResults: List<GoogleDocsHttpResult> = emptyList(),
) : GoogleDocsApiExecutor {
    private val gets = ArrayDeque(getResults)
    private val writes = ArrayDeque(writeResults)
    val requestBodies = mutableListOf<String>()
    var getCalls: Int = 0
        private set

    override suspend fun getDocument(
        fileId: String,
        capability: GoogleDocsAccessCapability,
    ): GoogleDocsHttpResult {
        getCalls += 1
        return gets.removeFirstOrNull() ?: error("No fake GET response configured")
    }

    override suspend fun batchUpdateDocument(
        fileId: String,
        capability: GoogleDocsAccessCapability,
        requestBody: String,
    ): GoogleDocsHttpResult {
        requestBodies += requestBody
        return writes.removeFirstOrNull() ?: error("No fake write response configured")
    }
}
