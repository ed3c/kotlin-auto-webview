package dev.ed3c.autowebview.workspace.viewmodel

import dev.ed3c.autowebview.workspace.contract.AuthorityKind
import dev.ed3c.autowebview.workspace.contract.EvidenceCeiling
import dev.ed3c.autowebview.workspace.contract.ExternalRef
import dev.ed3c.autowebview.workspace.contract.FreshnessState
import dev.ed3c.autowebview.workspace.contract.ProjectionRef
import dev.ed3c.autowebview.workspace.contract.RouteDecision
import dev.ed3c.autowebview.workspace.contract.RouteDecisionState
import dev.ed3c.autowebview.workspace.contract.RouteRequest
import dev.ed3c.autowebview.workspace.contract.SubjectDataClass
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectKind
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.SubjectVisibility
import dev.ed3c.autowebview.workspace.contract.TypedEdge
import kotlinx.serialization.Serializable

@Serializable
enum class CapabilityWorkspaceSection {
    SUBJECTS,
    GRAPH,
    WORK,
    EVIDENCE,
    SKILLS,
    EXPERIMENTS,
    PROJECTIONS,
    ROUTES,
}

@Serializable
enum class CapabilityWorkspaceConnectionState {
    ONLINE,
    OFFLINE_CACHED,
    UNAVAILABLE,
}

@Serializable
enum class CapabilityWorkspaceQualificationState {
    NOT_APPLICABLE,
    CANDIDATE,
    QUALIFIED,
    NOT_QUALIFIED,
    UNKNOWN,
}

@Serializable
data class CapabilityWorkspaceSubjectRecord(
    val subject: SubjectRef,
    val externalRefs: List<ExternalRef> = emptyList(),
    val evidenceCeiling: EvidenceCeiling = EvidenceCeiling.SOURCE_ONLY,
    val freshness: FreshnessState = FreshnessState.UNKNOWN,
    val qualification: CapabilityWorkspaceQualificationState = CapabilityWorkspaceQualificationState.NOT_APPLICABLE,
    val blockerCodes: Set<String> = emptySet(),
) {
    init {
        require(blockerCodes.all(::isMachineCode)) { "Workspace blocker codes must be bounded machine codes" }
        if (qualification != CapabilityWorkspaceQualificationState.NOT_APPLICABLE) {
            require(subject.key.kind == SubjectKind.SKILL || subject.key.kind == SubjectKind.SKILL_CANDIDATE) {
                "Qualification state is only valid for Skill subjects"
            }
        }
    }
}

@Serializable
data class CapabilityWorkspaceProjectionRecord(
    val projection: ProjectionRef,
    val blockerCodes: Set<String> = emptySet(),
) {
    init {
        require(blockerCodes.all(::isMachineCode)) { "Projection blocker codes must be bounded machine codes" }
    }
}

@Serializable
data class CapabilityWorkspaceRouteAction(
    val actionId: String,
    val label: String,
    val request: RouteRequest,
    val requiredSubjectKeys: Set<SubjectKey>,
) {
    init {
        require(isStableUiId(actionId)) { "Route action id is invalid" }
        require(label.isNotBlank() && label.length <= 120) { "Route action label is invalid" }
        require(requiredSubjectKeys.isNotEmpty()) { "Route action requires exact subjects" }
        require(requiredSubjectKeys == request.exactSubjects) {
            "Route action subjects must exactly match RouteRequest subjects"
        }
    }
}

@Serializable
data class CapabilityWorkspaceSnapshot(
    val snapshotId: String,
    val capturedAtEpochMs: Long,
    val subjects: List<CapabilityWorkspaceSubjectRecord>,
    val edges: List<TypedEdge>,
    val projections: List<CapabilityWorkspaceProjectionRecord>,
    val routes: List<CapabilityWorkspaceRouteAction>,
) {
    init {
        require(isStableUiId(snapshotId)) { "Workspace snapshot id is invalid" }
        require(capturedAtEpochMs >= 0) { "Workspace snapshot time cannot be negative" }
        require(subjects.map { it.subject.key }.distinct().size == subjects.size) {
            "Workspace snapshot contains duplicate subject identities"
        }
        require(edges.map(TypedEdge::edgeId).distinct().size == edges.size) {
            "Workspace snapshot contains duplicate edge identities"
        }
        require(projections.map { it.projection.projectionId }.distinct().size == projections.size) {
            "Workspace snapshot contains duplicate projection identities"
        }
        require(routes.map(CapabilityWorkspaceRouteAction::actionId).distinct().size == routes.size) {
            "Workspace snapshot contains duplicate route action identities"
        }
    }
}

sealed interface CapabilityWorkspaceLoadResult {
    data class Loaded(val snapshot: CapabilityWorkspaceSnapshot) : CapabilityWorkspaceLoadResult
    data class OfflineCached(val snapshot: CapabilityWorkspaceSnapshot) : CapabilityWorkspaceLoadResult
    data class Unavailable(val reasonCode: String) : CapabilityWorkspaceLoadResult {
        init {
            require(isMachineCode(reasonCode)) { "Unavailable reason must be a bounded machine code" }
        }
    }
}

fun interface CapabilityWorkspaceSnapshotSource {
    suspend fun load(): CapabilityWorkspaceLoadResult
}

fun interface CapabilityWorkspaceRoutePort {
    suspend fun propose(request: RouteRequest): RouteDecision
}

@Serializable
data class CapabilityWorkspaceSubjectView(
    val key: SubjectKey,
    val authorityKind: AuthorityKind,
    val authorityLabel: String,
    val versionLabel: String?,
    val digestLabel: String?,
    val visibility: SubjectVisibility,
    val dataClass: SubjectDataClass,
    val evidenceCeiling: EvidenceCeiling,
    val freshness: FreshnessState,
    val qualification: CapabilityWorkspaceQualificationState,
    val blockerCodes: Set<String>,
    val externalProviders: Set<String>,
    val externalLocators: List<String>,
    val routeEnabled: Boolean,
)

@Serializable
data class CapabilityWorkspaceProjectionView(
    val projectionId: String,
    val canonicalSubject: SubjectKey,
    val kindLabel: String,
    val stateLabel: String,
    val externalProvider: String,
    val externalLocator: String?,
    val blockerCodes: Set<String>,
)

@Serializable
data class CapabilityWorkspaceRouteView(
    val actionId: String,
    val label: String,
    val capabilityId: String,
    val destinationAuthorityKind: AuthorityKind,
    val destinationLabel: String,
    val evidenceCeiling: EvidenceCeiling,
    val enabled: Boolean,
    val blockerCodes: Set<String>,
    val lastDecisionState: RouteDecisionState? = null,
    val lastReasonCode: String? = null,
)

@Serializable
data class CapabilityWorkspaceUiState(
    val selectedSection: CapabilityWorkspaceSection = CapabilityWorkspaceSection.SUBJECTS,
    val connectionState: CapabilityWorkspaceConnectionState = CapabilityWorkspaceConnectionState.UNAVAILABLE,
    val snapshotId: String? = null,
    val capturedAtEpochMs: Long? = null,
    val subjects: List<CapabilityWorkspaceSubjectView> = emptyList(),
    val edges: List<TypedEdge> = emptyList(),
    val projections: List<CapabilityWorkspaceProjectionView> = emptyList(),
    val routes: List<CapabilityWorkspaceRouteView> = emptyList(),
    val globalBlockerCodes: Set<String> = emptySet(),
) {
    init {
        require(globalBlockerCodes.all(::isMachineCode)) { "Global blocker codes must be bounded machine codes" }
    }
}

internal fun isMachineCode(value: String): Boolean =
    value.isNotBlank() && value.length <= 96 && value.all { it.isUpperCase() || it.isDigit() || it == '_' }

private fun isStableUiId(value: String): Boolean =
    value.length in 3..128 && value.first().isLetter() && value.all { it.isLetterOrDigit() || it in "._:-" }
