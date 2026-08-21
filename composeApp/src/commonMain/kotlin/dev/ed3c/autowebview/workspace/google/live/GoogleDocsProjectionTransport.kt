package dev.ed3c.autowebview.workspace.google.live

import dev.ed3c.autowebview.workspace.contract.DigestRef
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.google.GoogleProjectionBinding
import dev.ed3c.autowebview.workspace.google.GoogleProjectionKind
import dev.ed3c.autowebview.workspace.google.GoogleProjectionReadResult
import dev.ed3c.autowebview.workspace.google.GoogleProjectionRemoteSnapshot
import dev.ed3c.autowebview.workspace.google.GoogleProjectionTransport
import dev.ed3c.autowebview.workspace.google.GoogleProjectionWriteCommand
import dev.ed3c.autowebview.workspace.google.GoogleProjectionWriteResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val MANAGED_DOCUMENT_SCHEMA = "kaw.google-docs-projection.v1"
private const val MANAGED_DOCUMENT_BEGIN = "[KAW_GOOGLE_DOCS_PROJECTION_V1]"
private const val MANAGED_DOCUMENT_END = "[/KAW_GOOGLE_DOCS_PROJECTION_V1]"

private const val REASON_ACCOUNT_ABSENT = "GOOGLE_ACCOUNT_CAPABILITY_ABSENT"
private const val REASON_SCOPE_NOT_ADMITTED = "GOOGLE_DOCS_SCOPE_NOT_ADMITTED"
private const val REASON_SHEETS_UNSUPPORTED = "GOOGLE_SHEETS_CONDITIONAL_WRITE_UNSUPPORTED"
private const val REASON_NETWORK_FAILURE = "GOOGLE_DOCS_NETWORK_FAILURE"
private const val REASON_RATE_LIMITED = "GOOGLE_DOCS_RATE_LIMITED"
private const val REASON_SERVER_FAILURE = "GOOGLE_DOCS_SERVER_FAILURE"
private const val REASON_UNAUTHORIZED = "GOOGLE_DOCS_UNAUTHORIZED"
private const val REASON_FORBIDDEN = "GOOGLE_DOCS_FORBIDDEN"
private const val REASON_FILE_NOT_FOUND = "GOOGLE_DOCS_FILE_NOT_FOUND"
private const val REASON_RESPONSE_INVALID = "GOOGLE_DOCS_RESPONSE_INVALID"
private const val REASON_WRITE_REJECTED = "GOOGLE_DOCS_WRITE_REJECTED"
private const val REASON_TARGET_FOREIGN = "GOOGLE_DOCS_TARGET_NOT_MANAGED"
private const val REASON_TARGET_CORRUPT = "GOOGLE_DOCS_TARGET_MANAGED_CONTENT_CORRUPT"
private const val REASON_TARGET_SUBJECT_MISMATCH = "GOOGLE_DOCS_TARGET_SUBJECT_MISMATCH"
private const val REASON_RENDERED_DIGEST_MISMATCH = "GOOGLE_DOCS_RENDERED_DIGEST_MISMATCH"

class GoogleDocsProjectionTransport(
    private val capabilityProvider: GoogleDocsAccessCapabilityProvider,
    private val executor: GoogleDocsApiExecutor,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : GoogleProjectionTransport {
    override suspend fun read(binding: GoogleProjectionBinding): GoogleProjectionReadResult {
        if (binding.kind != GoogleProjectionKind.DOC) {
            return GoogleProjectionReadResult.Blocked(REASON_SHEETS_UNSUPPORTED)
        }
        val capability = when (val lookup = admittedCapability()) {
            is CapabilityLookup.Admitted -> lookup.capability
            is CapabilityLookup.Blocked -> return GoogleProjectionReadResult.Blocked(lookup.reasonCode)
        }
        return when (val lookup = lookup(binding.fileId, capability)) {
            is DocumentLookup.Blocked -> GoogleProjectionReadResult.Blocked(lookup.reasonCode)
            is DocumentLookup.Retryable -> GoogleProjectionReadResult.RetryableFailure(lookup.reasonCode)
            is DocumentLookup.Found -> GoogleProjectionReadResult.Found(lookup.document.toRemoteSnapshot())
        }
    }

    override suspend fun write(command: GoogleProjectionWriteCommand): GoogleProjectionWriteResult {
        if (command.binding.kind != GoogleProjectionKind.DOC) {
            return GoogleProjectionWriteResult.Blocked(REASON_SHEETS_UNSUPPORTED)
        }
        val capability = when (val lookup = admittedCapability()) {
            is CapabilityLookup.Admitted -> lookup.capability
            is CapabilityLookup.Blocked -> return GoogleProjectionWriteResult.Blocked(lookup.reasonCode)
        }
        val renderedDigest = googleProjectionSha256(command.payload.renderedContent)
        if (renderedDigest != command.payload.renderedDigest.value) {
            return GoogleProjectionWriteResult.Blocked(REASON_RENDERED_DIGEST_MISMATCH)
        }

        val before = when (val lookup = lookup(command.binding.fileId, capability)) {
            is DocumentLookup.Blocked -> return GoogleProjectionWriteResult.Blocked(lookup.reasonCode)
            is DocumentLookup.Retryable -> return GoogleProjectionWriteResult.RetryableFailure(lookup.reasonCode)
            is DocumentLookup.Found -> lookup.document
        }
        if (before.revision != command.ifRevisionMatches) {
            return GoogleProjectionWriteResult.RevisionChanged(before.revision)
        }
        when (val content = before.content) {
            ManagedDocumentContent.Blank -> Unit
            is ManagedDocumentContent.Managed -> {
                if (content.envelope.canonicalSubject != command.payload.subject.key) {
                    return GoogleProjectionWriteResult.Blocked(REASON_TARGET_SUBJECT_MISMATCH)
                }
            }
            ManagedDocumentContent.Foreign ->
                return GoogleProjectionWriteResult.Blocked(REASON_TARGET_FOREIGN)
            ManagedDocumentContent.Corrupt ->
                return GoogleProjectionWriteResult.Blocked(REASON_TARGET_CORRUPT)
        }

        val envelope = ManagedProjectionEnvelope(
            schema = MANAGED_DOCUMENT_SCHEMA,
            canonicalSubject = command.payload.subject.key,
            canonicalDigest = command.payload.subject.digest
                ?: return GoogleProjectionWriteResult.Blocked(REASON_RENDERED_DIGEST_MISMATCH),
            renderedDigest = command.payload.renderedDigest,
            renderedContent = command.payload.renderedContent,
        )
        val requestBody = buildBatchUpdateRequest(
            document = before,
            replacement = encodeManagedDocument(envelope),
            requiredRevision = command.ifRevisionMatches,
        )
        return when (
            val response = executor.batchUpdateDocument(
                fileId = command.binding.fileId,
                capability = capability,
                requestBody = requestBody,
            )
        ) {
            GoogleDocsHttpResult.NetworkFailure ->
                GoogleProjectionWriteResult.RetryableFailure(REASON_NETWORK_FAILURE)
            is GoogleDocsHttpResult.Response -> mapWriteResponse(
                response = response,
                expectedFileId = command.binding.fileId,
                writtenDigest = command.payload.renderedDigest,
            )
        }
    }

    private suspend fun admittedCapability(): CapabilityLookup {
        val capability = capabilityProvider.current()
            ?: return CapabilityLookup.Blocked(REASON_ACCOUNT_ABSENT)
        if (!capability.admitsDocsWrite()) {
            return CapabilityLookup.Blocked(REASON_SCOPE_NOT_ADMITTED)
        }
        return CapabilityLookup.Admitted(capability)
    }

    private suspend fun lookup(
        fileId: String,
        capability: GoogleDocsAccessCapability,
    ): DocumentLookup {
        return when (val response = executor.getDocument(fileId, capability)) {
            GoogleDocsHttpResult.NetworkFailure -> DocumentLookup.Retryable(REASON_NETWORK_FAILURE)
            is GoogleDocsHttpResult.Response -> when {
                response.statusCode == 200 -> {
                    val parsed = runCatching { parseDocument(response.body, fileId) }.getOrNull()
                    if (parsed == null) {
                        DocumentLookup.Blocked(REASON_RESPONSE_INVALID)
                    } else {
                        DocumentLookup.Found(parsed)
                    }
                }
                response.statusCode == 401 -> DocumentLookup.Blocked(REASON_UNAUTHORIZED)
                response.statusCode == 403 -> DocumentLookup.Blocked(REASON_FORBIDDEN)
                response.statusCode == 404 -> DocumentLookup.Blocked(REASON_FILE_NOT_FOUND)
                response.statusCode == 408 || response.statusCode == 429 ->
                    DocumentLookup.Retryable(REASON_RATE_LIMITED)
                response.statusCode in 500..599 -> DocumentLookup.Retryable(REASON_SERVER_FAILURE)
                else -> DocumentLookup.Blocked(REASON_RESPONSE_INVALID)
            }
        }
    }

    private fun mapWriteResponse(
        response: GoogleDocsHttpResult.Response,
        expectedFileId: String,
        writtenDigest: DigestRef,
    ): GoogleProjectionWriteResult {
        return when {
            response.statusCode == 200 -> {
                val root = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                    ?: return GoogleProjectionWriteResult.Blocked(REASON_RESPONSE_INVALID)
                val documentId = root.stringOrNull("documentId")
                val revision = root["writeControl"]
                    ?.jsonObject
                    ?.stringOrNull("requiredRevisionId")
                if (documentId != expectedFileId || revision.isNullOrBlank()) {
                    GoogleProjectionWriteResult.Blocked(REASON_RESPONSE_INVALID)
                } else {
                    GoogleProjectionWriteResult.Acknowledged(
                        fileId = documentId,
                        revision = revision,
                        writtenDigest = writtenDigest,
                    )
                }
            }
            response.statusCode == 400 && response.indicatesRevisionConflict() ->
                GoogleProjectionWriteResult.RevisionChanged()
            response.statusCode == 409 || response.statusCode == 412 ->
                GoogleProjectionWriteResult.RevisionChanged()
            response.statusCode == 401 -> GoogleProjectionWriteResult.Blocked(REASON_UNAUTHORIZED)
            response.statusCode == 403 -> GoogleProjectionWriteResult.Blocked(REASON_FORBIDDEN)
            response.statusCode == 404 -> GoogleProjectionWriteResult.Blocked(REASON_FILE_NOT_FOUND)
            response.statusCode == 408 || response.statusCode == 429 ->
                GoogleProjectionWriteResult.RetryableFailure(REASON_RATE_LIMITED)
            response.statusCode in 500..599 ->
                GoogleProjectionWriteResult.RetryableFailure(REASON_SERVER_FAILURE)
            else -> GoogleProjectionWriteResult.Blocked(REASON_WRITE_REJECTED)
        }
    }

    private fun parseDocument(body: String, expectedFileId: String): ParsedDocument {
        val root = Json.parseToJsonElement(body).jsonObject
        val fileId = root.requiredString("documentId")
        require(fileId == expectedFileId) { "Google Docs response file identity mismatch" }
        val revision = root.requiredString("revisionId")
        val bodyObject = root["body"]?.jsonObject
            ?: error("Google Docs response does not contain a body")
        val content = bodyObject["content"]?.jsonArray ?: JsonArray(emptyList())
        var endIndex = 1
        var unsupportedStructure = false
        val text = buildString {
            for (structural in content) {
                val element = structural.jsonObject
                endIndex = maxOf(endIndex, element["endIndex"]?.jsonPrimitive?.intOrNull ?: 1)
                val paragraph = element["paragraph"]?.jsonObject
                when {
                    paragraph != null -> {
                        val elements = paragraph["elements"]?.jsonArray ?: JsonArray(emptyList())
                        for (paragraphElementValue in elements) {
                            val paragraphElement = paragraphElementValue.jsonObject
                            val textRun = paragraphElement["textRun"]?.jsonObject
                            val runText = textRun?.get("content")?.jsonPrimitive?.contentOrNull
                            if (runText != null) {
                                append(runText)
                            } else if (
                                paragraphElement.keys.any { key ->
                                    key !in setOf("startIndex", "endIndex", "textRun")
                                }
                            ) {
                                unsupportedStructure = true
                            }
                        }
                    }
                    element.containsKey("sectionBreak") -> Unit
                    else -> unsupportedStructure = true
                }
            }
        }
        val contentState = classifyContent(text, unsupportedStructure)
        val digestInput = if (unsupportedStructure) bodyObject.toString() else text
        return ParsedDocument(
            fileId = fileId,
            revision = revision,
            endIndex = endIndex,
            rawText = text,
            rawDigest = DigestRef(value = googleProjectionSha256(digestInput)),
            content = contentState,
        )
    }

    private fun classifyContent(
        text: String,
        unsupportedStructure: Boolean,
    ): ManagedDocumentContent {
        if (unsupportedStructure) return ManagedDocumentContent.Foreign
        val normalized = text.trimEnd('\n', '\r')
        if (normalized.isBlank()) return ManagedDocumentContent.Blank
        val prefix = "$MANAGED_DOCUMENT_BEGIN\n"
        val suffix = "\n$MANAGED_DOCUMENT_END"
        if (!normalized.startsWith(prefix) || !normalized.endsWith(suffix)) {
            return ManagedDocumentContent.Foreign
        }
        val encoded = normalized.substring(prefix.length, normalized.length - suffix.length)
        val envelope = runCatching { json.decodeFromString<ManagedProjectionEnvelope>(encoded) }.getOrNull()
            ?: return ManagedDocumentContent.Corrupt
        if (envelope.schema != MANAGED_DOCUMENT_SCHEMA) return ManagedDocumentContent.Corrupt
        if (googleProjectionSha256(envelope.renderedContent) != envelope.renderedDigest.value) {
            return ManagedDocumentContent.Corrupt
        }
        return ManagedDocumentContent.Managed(envelope)
    }

    private fun encodeManagedDocument(envelope: ManagedProjectionEnvelope): String {
        val encoded = json.encodeToString(envelope)
        return "$MANAGED_DOCUMENT_BEGIN\n$encoded\n$MANAGED_DOCUMENT_END"
    }

    private fun buildBatchUpdateRequest(
        document: ParsedDocument,
        replacement: String,
        requiredRevision: String,
    ): String {
        val requests = buildJsonArray {
            if (document.endIndex > 2) {
                add(
                    buildJsonObject {
                        putJsonObject("deleteContentRange") {
                            putJsonObject("range") {
                                put("startIndex", 1)
                                put("endIndex", document.endIndex - 1)
                            }
                        }
                    },
                )
            }
            add(
                buildJsonObject {
                    putJsonObject("insertText") {
                        putJsonObject("location") { put("index", 1) }
                        put("text", replacement)
                    }
                },
            )
        }
        return buildJsonObject {
            put("requests", requests)
            putJsonObject("writeControl") {
                put("requiredRevisionId", requiredRevision)
            }
        }.toString()
    }
}

@Serializable
private data class ManagedProjectionEnvelope(
    val schema: String,
    val canonicalSubject: SubjectKey,
    val canonicalDigest: DigestRef,
    val renderedDigest: DigestRef,
    val renderedContent: String,
)

private data class ParsedDocument(
    val fileId: String,
    val revision: String,
    val endIndex: Int,
    val rawText: String,
    val rawDigest: DigestRef,
    val content: ManagedDocumentContent,
) {
    init {
        require(endIndex >= 1) { "Google Docs document end index is invalid" }
    }

    fun toRemoteSnapshot(): GoogleProjectionRemoteSnapshot {
        val managed = (content as? ManagedDocumentContent.Managed)?.envelope
        return GoogleProjectionRemoteSnapshot(
            fileId = fileId,
            revision = revision,
            canonicalSubject = managed?.canonicalSubject,
            canonicalDigest = managed?.canonicalDigest,
            renderedDigest = managed?.renderedDigest ?: rawDigest,
        )
    }
}

private sealed interface ManagedDocumentContent {
    data object Blank : ManagedDocumentContent
    data class Managed(val envelope: ManagedProjectionEnvelope) : ManagedDocumentContent
    data object Foreign : ManagedDocumentContent
    data object Corrupt : ManagedDocumentContent
}

private sealed interface CapabilityLookup {
    data class Admitted(val capability: GoogleDocsAccessCapability) : CapabilityLookup
    data class Blocked(val reasonCode: String) : CapabilityLookup
}

private sealed interface DocumentLookup {
    data class Found(val document: ParsedDocument) : DocumentLookup
    data class Retryable(val reasonCode: String) : DocumentLookup
    data class Blocked(val reasonCode: String) : DocumentLookup
}

private fun JsonObject.requiredString(name: String): String =
    stringOrNull(name)?.takeIf { it.isNotBlank() } ?: error("Missing string: $name")

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun GoogleDocsHttpResult.Response.indicatesRevisionConflict(): Boolean {
    val normalized = body.lowercase()
    return normalized.contains("requiredrevisionid") ||
        normalized.contains("failed_precondition") ||
        (normalized.contains("revision") && normalized.contains("latest"))
}
