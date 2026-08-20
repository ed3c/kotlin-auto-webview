package dev.ed3c.autowebview.workspace.viewmodel

import dev.ed3c.autowebview.workspace.contract.FreshnessState
import dev.ed3c.autowebview.workspace.contract.RouteDecisionState
import dev.ed3c.autowebview.workspace.contract.SubjectDataClass
import dev.ed3c.autowebview.workspace.contract.SubjectKind
import dev.ed3c.autowebview.workspace.contract.SubjectVisibility

@JvmInline
value class CapabilityWorkspaceAccess private constructor(val privateLocatorsVisible: Boolean) {
    companion object {
        val PublicSafe = CapabilityWorkspaceAccess(false)
        val AuthorizedLocal = CapabilityWorkspaceAccess(true)
    }
}

class CapabilityWorkspaceController(
    private val source: CapabilityWorkspaceSnapshotSource,
    private val routePort: CapabilityWorkspaceRoutePort,
    private val access: CapabilityWorkspaceAccess = CapabilityWorkspaceAccess.PublicSafe,
) {
    private var loadedSnapshot: CapabilityWorkspaceSnapshot? = null

    suspend fun load(restored: CapabilityWorkspaceUiState? = null): CapabilityWorkspaceUiState {
        val selected = restored?.selectedSection ?: CapabilityWorkspaceSection.SUBJECTS
        return when (val result = source.load()) {
            is CapabilityWorkspaceLoadResult.Loaded -> {
                loadedSnapshot = result.snapshot
                render(result.snapshot, selected, CapabilityWorkspaceConnectionState.ONLINE)
            }
            is CapabilityWorkspaceLoadResult.OfflineCached -> {
                loadedSnapshot = result.snapshot
                render(result.snapshot, selected, CapabilityWorkspaceConnectionState.OFFLINE_CACHED)
            }
            is CapabilityWorkspaceLoadResult.Unavailable -> {
                loadedSnapshot = null
                CapabilityWorkspaceUiState(
                    selectedSection = selected,
                    connectionState = CapabilityWorkspaceConnectionState.UNAVAILABLE,
                    globalBlockerCodes = setOf(result.reasonCode),
                )
            }
        }
    }

    fun selectSection(
        state: CapabilityWorkspaceUiState,
        section: CapabilityWorkspaceSection,
    ): CapabilityWorkspaceUiState = state.copy(selectedSection = section)

    suspend fun proposeRoute(
        state: CapabilityWorkspaceUiState,
        actionId: String,
    ): CapabilityWorkspaceUiState {
        val routeView = state.routes.singleOrNull { it.actionId == actionId } ?: return state
        if (!routeView.enabled || state.connectionState != CapabilityWorkspaceConnectionState.ONLINE) return state
        val snapshot = loadedSnapshot ?: return state
        val action = snapshot.routes.singleOrNull { it.actionId == actionId } ?: return state
        val decision = routePort.propose(action.request)
        val safeReason = decision.reasonCode.takeIf(::isMachineCode) ?: "DESTINATION_REASON_REDACTED"
        return state.copy(
            routes = state.routes.map { route ->
                if (route.actionId == actionId) {
                    route.copy(
                        lastDecisionState = decision.state,
                        lastReasonCode = safeReason,
                    )
                } else {
                    route
                }
            },
        )
    }

    private fun render(
        snapshot: CapabilityWorkspaceSnapshot,
        selected: CapabilityWorkspaceSection,
        connection: CapabilityWorkspaceConnectionState,
    ): CapabilityWorkspaceUiState {
        val subjectByKey = snapshot.subjects.associateBy { it.subject.key }
        val subjectViews = snapshot.subjects.map { record ->
            val subject = record.subject
            val privateSubject = subject.visibility != SubjectVisibility.PUBLIC ||
                subject.dataClass != SubjectDataClass.PUBLIC
            val locatorsVisible = !privateSubject || access.privateLocatorsVisible
            val blockers = linkedSetOf<String>().apply {
                addAll(record.blockerCodes)
                if (record.freshness != FreshnessState.CURRENT) add("SUBJECT_NOT_CURRENT")
                if (record.qualification == CapabilityWorkspaceQualificationState.NOT_QUALIFIED) {
                    add("SKILL_NOT_QUALIFIED")
                }
            }
            CapabilityWorkspaceSubjectView(
                key = subject.key,
                authorityKind = subject.canonicalAuthority.kind,
                authorityLabel = if (locatorsVisible) subject.canonicalAuthority.ownerId else "PRIVATE_AUTHORITY",
                versionLabel = subject.version?.takeIf { locatorsVisible },
                digestLabel = subject.digest?.value?.take(12)?.takeIf { locatorsVisible },
                visibility = subject.visibility,
                dataClass = subject.dataClass,
                evidenceCeiling = record.evidenceCeiling,
                freshness = record.freshness,
                qualification = record.qualification,
                blockerCodes = blockers,
                externalProviders = record.externalRefs.mapTo(linkedSetOf()) { it.provider.name },
                externalLocators = if (locatorsVisible) {
                    record.externalRefs.map { ref -> ref.canonicalUrl ?: "${ref.provider.name}:${ref.externalId}" }
                } else {
                    emptyList()
                },
                routeEnabled = connection == CapabilityWorkspaceConnectionState.ONLINE && blockers.isEmpty(),
            )
        }

        val projectionViews = snapshot.projections.map { record ->
            val projection = record.projection
            val ownerRecord = subjectByKey[projection.canonicalSubject]
            val privateSubject = ownerRecord?.subject?.let { subject ->
                subject.visibility != SubjectVisibility.PUBLIC || subject.dataClass != SubjectDataClass.PUBLIC
            } ?: true
            val locatorsVisible = !privateSubject || access.privateLocatorsVisible
            val blockers = linkedSetOf<String>().apply {
                addAll(record.blockerCodes)
                if (projection.state.name == "CONFLICT") add("PROJECTION_CONFLICT")
                if (ownerRecord == null) add("PROJECTION_SUBJECT_MISSING")
            }
            CapabilityWorkspaceProjectionView(
                projectionId = projection.projectionId,
                canonicalSubject = projection.canonicalSubject,
                kindLabel = projection.kind.name,
                stateLabel = projection.state.name,
                externalProvider = projection.externalRef.provider.name,
                externalLocator = if (locatorsVisible) {
                    projection.externalRef.canonicalUrl
                        ?: "${projection.externalRef.provider.name}:${projection.externalRef.externalId}"
                } else {
                    null
                },
                blockerCodes = blockers,
            )
        }

        val routeViews = snapshot.routes.map { action ->
            val required = action.requiredSubjectKeys.mapNotNull(subjectByKey::get)
            val blockers = linkedSetOf<String>().apply {
                if (required.size != action.requiredSubjectKeys.size) add("ROUTE_SUBJECT_MISSING")
                required.forEach { record ->
                    addAll(record.blockerCodes)
                    if (record.freshness != FreshnessState.CURRENT) add("SUBJECT_NOT_CURRENT")
                    if (record.qualification == CapabilityWorkspaceQualificationState.NOT_QUALIFIED) {
                        add("SKILL_NOT_QUALIFIED")
                    }
                }
                if (connection != CapabilityWorkspaceConnectionState.ONLINE) add("ROUTE_OFFLINE")
            }
            CapabilityWorkspaceRouteView(
                actionId = action.actionId,
                label = action.label,
                capabilityId = action.request.requiredCapabilityId,
                destinationAuthorityKind = action.request.destinationOwner.kind,
                destinationLabel = action.request.destinationOwner.ownerId,
                evidenceCeiling = action.request.evidenceCeiling,
                enabled = blockers.isEmpty(),
                blockerCodes = blockers,
            )
        }

        val globalBlockers = linkedSetOf<String>().apply {
            if (connection == CapabilityWorkspaceConnectionState.OFFLINE_CACHED) add("OFFLINE_CACHED_VIEW")
            if (projectionViews.any { "PROJECTION_CONFLICT" in it.blockerCodes }) add("PROJECTION_CONFLICT")
            if (subjectViews.any { "MISSING_RECEIPT" in it.blockerCodes }) add("MISSING_RECEIPT")
            if (subjectViews.any { it.authorityLabel == "PRIVATE_AUTHORITY" }) add("PRIVATE_REFS_REDACTED")
        }

        return CapabilityWorkspaceUiState(
            selectedSection = selected,
            connectionState = connection,
            snapshotId = snapshot.snapshotId,
            capturedAtEpochMs = snapshot.capturedAtEpochMs,
            subjects = subjectViews,
            edges = snapshot.edges,
            projections = projectionViews,
            routes = routeViews,
            globalBlockerCodes = globalBlockers,
        )
    }
}

fun CapabilityWorkspaceUiState.toPublicState(): CapabilityWorkspaceUiState = copy(
    subjects = subjects.map { subject ->
        val privateSubject = subject.visibility != SubjectVisibility.PUBLIC ||
            subject.dataClass != SubjectDataClass.PUBLIC
        if (!privateSubject) subject else subject.copy(
            authorityLabel = "PRIVATE_AUTHORITY",
            versionLabel = null,
            digestLabel = null,
            externalLocators = emptyList(),
            routeEnabled = false,
            blockerCodes = subject.blockerCodes + "PRIVATE_REFS_REDACTED",
        )
    },
    projections = projections.map { projection ->
        val privateSubject = subjects.firstOrNull { it.key == projection.canonicalSubject }?.let { subject ->
            subject.visibility != SubjectVisibility.PUBLIC || subject.dataClass != SubjectDataClass.PUBLIC
        } ?: true
        if (!privateSubject) projection else projection.copy(externalLocator = null)
    },
    routes = routes.map { route -> route.copy(enabled = false) },
    globalBlockerCodes = globalBlockerCodes + "PUBLIC_EXPORT_READ_ONLY",
)

internal fun CapabilityWorkspaceUiState.subjectsFor(section: CapabilityWorkspaceSection): List<CapabilityWorkspaceSubjectView> =
    when (section) {
        CapabilityWorkspaceSection.SUBJECTS -> subjects
        CapabilityWorkspaceSection.WORK -> subjects.filter { it.key.kind == SubjectKind.WORK_ITEM || it.key.kind == SubjectKind.IMPLEMENTATION }
        CapabilityWorkspaceSection.EVIDENCE -> subjects.filter { it.key.kind == SubjectKind.EVIDENCE }
        CapabilityWorkspaceSection.SKILLS -> subjects.filter { it.key.kind == SubjectKind.SKILL || it.key.kind == SubjectKind.SKILL_CANDIDATE }
        CapabilityWorkspaceSection.EXPERIMENTS -> subjects.filter { it.key.kind == SubjectKind.EXPERIMENT || it.key.kind == SubjectKind.OUTCOME }
        else -> emptyList()
    }
