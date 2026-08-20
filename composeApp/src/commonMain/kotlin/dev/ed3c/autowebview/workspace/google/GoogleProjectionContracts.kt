package dev.ed3c.autowebview.workspace.google

import dev.ed3c.autowebview.workspace.contract.ChangeProposal
import dev.ed3c.autowebview.workspace.contract.DigestRef
import dev.ed3c.autowebview.workspace.contract.ExternalProvider
import dev.ed3c.autowebview.workspace.contract.ExternalRef
import dev.ed3c.autowebview.workspace.contract.FreshnessState
import dev.ed3c.autowebview.workspace.contract.SubjectDataClass
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.SubjectVisibility
import dev.ed3c.autowebview.workspace.contract.SyncReceipt
import kotlinx.serialization.Serializable

private val W3_ID_PATTERN = Regex("^[A-Za-z][A-Za-z0-9._:-]{2,127}$")
private val GOOGLE_FILE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{6,256}$")

@Serializable
enum class GoogleProjectionKind {
    DOC,
    SHEET,
}

@Serializable
enum class GoogleDestinationAdmission {
    ADMITTED,
    LOCAL_ONLY,
    EXTERNAL_AUTHORITY_REQUIRED,
}

@Serializable
data class GoogleProjectionBinding(
    val projectionId: String,
    val canonicalSubject: SubjectKey,
    val kind: GoogleProjectionKind,
    val fileId: String,
    val expectedRevision: String? = null,
    val displayName: String? = null,
    val destinationAdmission: GoogleDestinationAdmission,
) {
    init {
        require(W3_ID_PATTERN.matches(projectionId)) { "Google projection id is invalid" }
        require(GOOGLE_FILE_ID_PATTERN.matches(fileId)) { "Google Drive file id is invalid" }
        require(expectedRevision == null || expectedRevision.isNotBlank()) {
            "Google target revision cannot be blank"
        }
        require(expectedRevision == null || !expectedRevision.contains('\n') && !expectedRevision.contains('\r')) {
            "Google target revision cannot contain newlines"
        }
        require(displayName == null || displayName.isNotBlank()) {
            "Google projection display name cannot be blank"
        }
        require(displayName == null || displayName.length <= 512) {
            "Google projection display name is too long"
        }
    }

    fun toTarget(observedAtEpochMs: Long): ExternalRef {
        require(observedAtEpochMs >= 0) { "Google target observation time cannot be negative" }
        return ExternalRef(
            provider = kind.externalProvider(),
            externalId = fileId,
            revision = expectedRevision,
            canonicalUrl = null,
            freshness = FreshnessState.UNKNOWN,
            observedAtEpochMs = observedAtEpochMs,
        )
    }
}

@Serializable
data class GoogleProjectionPayload(
    val subject: SubjectRef,
    val renderedContent: String,
    val renderedDigest: DigestRef,
    val destinationAdmission: GoogleDestinationAdmission,
) {
    init {
        require(subject.digest != null) {
            "Google projection requires an exact canonical subject digest"
        }
        require(renderedContent.isNotBlank()) { "Google projection payload cannot be blank" }
        require(renderedContent.length <= 2_000_000) { "Google projection payload is too large" }
    }
}

@Serializable
data class GoogleProjectionRemoteSnapshot(
    val fileId: String,
    val revision: String,
    val canonicalSubject: SubjectKey?,
    val canonicalDigest: DigestRef?,
    val renderedDigest: DigestRef,
) {
    init {
        require(GOOGLE_FILE_ID_PATTERN.matches(fileId)) { "Google read-back file id is invalid" }
        require(revision.isNotBlank()) { "Google read-back revision cannot be blank" }
        require(!revision.contains('\n') && !revision.contains('\r')) {
            "Google read-back revision cannot contain newlines"
        }
    }
}

@Serializable
data class GoogleProjectionWriteCommand(
    val eventId: String,
    val binding: GoogleProjectionBinding,
    val payload: GoogleProjectionPayload,
    val ifRevisionMatches: String,
) {
    init {
        require(W3_ID_PATTERN.matches(eventId)) { "Google projection event id is invalid" }
        require(binding.canonicalSubject == payload.subject.key) {
            "Google projection binding and payload must reference the same canonical subject"
        }
        require(ifRevisionMatches.isNotBlank()) { "Google conditional write requires a revision" }
    }
}

sealed interface GoogleProjectionWriteResult {
    data class Acknowledged(
        val fileId: String,
        val revision: String,
        val writtenDigest: DigestRef,
    ) : GoogleProjectionWriteResult

    data class RevisionChanged(
        val actualRevision: String? = null,
    ) : GoogleProjectionWriteResult

    data class RetryableFailure(
        val reasonCode: String,
    ) : GoogleProjectionWriteResult

    data class Blocked(
        val reasonCode: String,
    ) : GoogleProjectionWriteResult
}

sealed interface GoogleProjectionReadResult {
    data class Found(
        val snapshot: GoogleProjectionRemoteSnapshot,
    ) : GoogleProjectionReadResult

    data class RetryableFailure(
        val reasonCode: String,
    ) : GoogleProjectionReadResult

    data class Blocked(
        val reasonCode: String,
    ) : GoogleProjectionReadResult
}

fun interface GoogleProjectionPayloadSource {
    suspend fun render(
        canonicalSubject: SubjectKey,
        kind: GoogleProjectionKind,
    ): GoogleProjectionPayload?
}

interface GoogleProjectionTransport {
    suspend fun read(binding: GoogleProjectionBinding): GoogleProjectionReadResult

    suspend fun write(command: GoogleProjectionWriteCommand): GoogleProjectionWriteResult
}

@Serializable
enum class GoogleProjectionDispatchState {
    SYNCED,
    RETRY,
    CONFLICT,
    BLOCKED,
}

@Serializable
data class GoogleProjectionPublicReceipt(
    val canonicalSubjectKind: String,
    val projectionKind: GoogleProjectionKind,
    val state: GoogleProjectionDispatchState,
    val attempts: Int,
    val reasonCode: String? = null,
) {
    init {
        require(canonicalSubjectKind.isNotBlank()) { "Public Google receipt subject kind cannot be blank" }
        require(attempts >= 0) { "Public Google receipt attempts cannot be negative" }
    }
}

@Serializable
data class GoogleProjectionDispatchResult(
    val state: GoogleProjectionDispatchState,
    val receipt: SyncReceipt,
    val reasonCode: String? = null,
    val changeProposal: ChangeProposal? = null,
) {
    fun toPublicReceipt(): GoogleProjectionPublicReceipt = GoogleProjectionPublicReceipt(
        canonicalSubjectKind = receipt.canonicalSubject.kind.name,
        projectionKind = receipt.target.provider.googleProjectionKind(),
        state = state,
        attempts = receipt.attempts,
        reasonCode = reasonCode,
    )
}

internal fun GoogleProjectionKind.externalProvider(): ExternalProvider = when (this) {
    GoogleProjectionKind.DOC -> ExternalProvider.GOOGLE_DOCS
    GoogleProjectionKind.SHEET -> ExternalProvider.GOOGLE_SHEETS
}

internal fun ExternalProvider.googleProjectionKind(): GoogleProjectionKind = when (this) {
    ExternalProvider.GOOGLE_DOCS -> GoogleProjectionKind.DOC
    ExternalProvider.GOOGLE_SHEETS -> GoogleProjectionKind.SHEET
    else -> error("External provider is not a Google Docs/Sheets projection")
}

internal fun GoogleProjectionBinding.isDestinationAdmitted(subject: SubjectRef): Boolean {
    if (destinationAdmission != GoogleDestinationAdmission.ADMITTED) return false
    if (subject.visibility == SubjectVisibility.PUBLIC && subject.dataClass == SubjectDataClass.PUBLIC) {
        return true
    }
    return destinationAdmission == GoogleDestinationAdmission.ADMITTED
}
