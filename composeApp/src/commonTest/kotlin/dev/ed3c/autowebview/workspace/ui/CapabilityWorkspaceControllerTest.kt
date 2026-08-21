package dev.ed3c.autowebview.workspace.ui

import dev.ed3c.autowebview.workspace.contract.AuthorityKind
import dev.ed3c.autowebview.workspace.contract.AuthorityRef
import dev.ed3c.autowebview.workspace.contract.DigestRef
import dev.ed3c.autowebview.workspace.contract.EvidenceCeiling
import dev.ed3c.autowebview.workspace.contract.ExternalProvider
import dev.ed3c.autowebview.workspace.contract.ExternalRef
import dev.ed3c.autowebview.workspace.contract.FreshnessState
import dev.ed3c.autowebview.workspace.contract.ProjectionKind
import dev.ed3c.autowebview.workspace.contract.ProjectionRef
import dev.ed3c.autowebview.workspace.contract.ProjectionState
import dev.ed3c.autowebview.workspace.contract.RouteDecision
import dev.ed3c.autowebview.workspace.contract.RouteDecisionState
import dev.ed3c.autowebview.workspace.contract.RouteRequest
import dev.ed3c.autowebview.workspace.contract.SubjectDataClass
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectKind
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.SubjectVisibility
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceAccess
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceConnectionState
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceController
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceLoadResult
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceProjectionRecord
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceQualificationState
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceRouteAction
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceRoutePort
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceSection
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceSnapshot
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceSnapshotSource
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceSubjectRecord
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceUiState
import dev.ed3c.autowebview.workspace.viewmodel.toPublicState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityWorkspaceControllerTest {
    private val digest = DigestRef(value = "a".repeat(64))
    private val subjectKey = SubjectKey("REQ.workspace-001", SubjectKind.REQUIREMENT)
    private val routeOwner = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "truth-verify-loop")

    @Test
    fun privateSubjectIsVisibleOnlyInAuthorizedLocalRealmAndPublicStateRedactsAgain() = runTest {
        val private = subject(
            visibility = SubjectVisibility.PRIVATE,
            dataClass = SubjectDataClass.CONFIDENTIAL,
        )
        val projection = ProjectionRef(
            projectionId = "PROJ.private-001",
            canonicalSubject = private.key,
            kind = ProjectionKind.GOOGLE_DOC,
            externalRef = ExternalRef(
                provider = ExternalProvider.GOOGLE_DOCS,
                externalId = "private-google-file",
                revision = "private-google-revision",
                canonicalUrl = "https://docs.google.com/document/d/private-google-file",
                freshness = FreshnessState.CURRENT,
            ),
            state = ProjectionState.WRITTEN,
        )
        val snapshot = snapshot(
            subjectRecord = CapabilityWorkspaceSubjectRecord(
                subject = private,
                externalRefs = listOf(
                    ExternalRef(
                        provider = ExternalProvider.GITHUB,
                        externalId = "private-node-123",
                        revision = "private-revision",
                        canonicalUrl = "https://github.com/private-owner/private-repo/issues/7",
                        freshness = FreshnessState.CURRENT,
                    ),
                ),
                evidenceCeiling = EvidenceCeiling.TECHNICAL,
                freshness = FreshnessState.CURRENT,
            ),
            projection = CapabilityWorkspaceProjectionRecord(projection),
        )
        val publicController = controller(snapshot, CapabilityWorkspaceAccess.PublicSafe)
        val publicState = publicController.load()
        assertEquals("PRIVATE_AUTHORITY", publicState.subjects.single().authorityLabel)
        assertTrue(publicState.subjects.single().externalLocators.isEmpty())
        assertTrue("PRIVATE_REFS_REDACTED" in publicState.globalBlockerCodes)

        val localController = controller(snapshot, CapabilityWorkspaceAccess.AuthorizedLocal)
        val localState = localController.load()
        assertEquals("subject-owner", localState.subjects.single().authorityLabel)
        assertTrue(localState.subjects.single().externalLocators.single().contains("private-owner"))
        assertTrue(localState.projections.single().externalLocator!!.contains("private-google-file"))

        val exported = localState.toPublicState()
        assertNull(exported.snapshotId)
        assertEquals("PRIVATE_AUTHORITY", exported.subjects.single().authorityLabel)
        assertNull(exported.projections.single().externalLocator)
        assertTrue(exported.subjects.single().externalLocators.isEmpty())
        assertEquals("ROUTE_DESTINATION", exported.routes.single().destinationLabel)
        assertTrue(exported.routes.all { !it.enabled })
    }

    @Test
    fun staleNotQualifiedAndMissingReceiptSubjectsDisableRoutesWithoutChangingOwnerVerdicts() = runTest {
        val skillKey = SubjectKey("SKILL.candidate-001", SubjectKind.SKILL_CANDIDATE)
        val skill = SubjectRef(
            key = skillKey,
            canonicalAuthority = AuthorityRef(AuthorityKind.METHOD_REPOSITORY, "skills-shared"),
            version = "v3",
            digest = digest,
        )
        val request = RouteRequest(
            requestId = "ROUTE.skill-001",
            caller = AuthorityRef(AuthorityKind.USER, "workspace-user"),
            intent = "qualify exact skill candidate",
            requiredCapabilityId = "qualify.skill",
            exactSubjects = setOf(skillKey),
            destinationOwner = AuthorityRef(AuthorityKind.QUALIFIER, "Skill.md-native"),
            evidenceCeiling = EvidenceCeiling.TECHNICAL,
        )
        val snapshot = CapabilityWorkspaceSnapshot(
            snapshotId = "SNAP.skill-001",
            capturedAtEpochMs = 11,
            subjects = listOf(
                CapabilityWorkspaceSubjectRecord(
                    subject = skill,
                    freshness = FreshnessState.STALE,
                    qualification = CapabilityWorkspaceQualificationState.NOT_QUALIFIED,
                    blockerCodes = setOf("MISSING_RECEIPT"),
                ),
            ),
            edges = emptyList(),
            projections = emptyList(),
            routes = listOf(
                CapabilityWorkspaceRouteAction(
                    actionId = "ACTION.qualify-skill",
                    label = "Qualify skill",
                    request = request,
                    requiredSubjectKeys = request.exactSubjects,
                ),
            ),
        )
        var calls = 0
        val controller = CapabilityWorkspaceController(
            source = CapabilityWorkspaceSnapshotSource { CapabilityWorkspaceLoadResult.Loaded(snapshot) },
            routePort = CapabilityWorkspaceRoutePort {
                calls += 1
                decision(it)
            },
        )
        val state = controller.load()
        val route = state.routes.single()
        assertFalse(route.enabled)
        assertTrue("SUBJECT_NOT_CURRENT" in route.blockerCodes)
        assertTrue("SKILL_NOT_QUALIFIED" in route.blockerCodes)
        assertTrue("MISSING_RECEIPT" in route.blockerCodes)
        assertTrue("MISSING_RECEIPT" in state.globalBlockerCodes)

        controller.proposeRoute(state, route.actionId)
        assertEquals(0, calls)
        assertEquals(CapabilityWorkspaceQualificationState.NOT_QUALIFIED, state.subjects.single().qualification)
    }

    @Test
    fun externalAuthorityBlockerDisablesRouteWithoutGuessingAroundIt() = runTest {
        val snapshot = snapshot(
            subjectRecord = CapabilityWorkspaceSubjectRecord(
                subject = subject(),
                freshness = FreshnessState.CURRENT,
                blockerCodes = setOf("EXTERNAL_AUTHORITY_REQUIRED"),
            ),
        )
        val state = controller(snapshot).load()
        assertFalse(state.routes.single().enabled)
        assertTrue("EXTERNAL_AUTHORITY_REQUIRED" in state.routes.single().blockerCodes)
    }

    @Test
    fun projectionConflictIsDisplayedAsBlockerAndNeverChangesCanonicalSubject() = runTest {
        val canonical = subject()
        val projection = ProjectionRef(
            projectionId = "PROJ.google-001",
            canonicalSubject = canonical.key,
            kind = ProjectionKind.GOOGLE_DOC,
            externalRef = ExternalRef(
                provider = ExternalProvider.GOOGLE_DOCS,
                externalId = "google-file-123",
                revision = "rev-2",
                canonicalUrl = "https://docs.google.com/document/d/google-file-123",
                freshness = FreshnessState.CURRENT,
            ),
            state = ProjectionState.CONFLICT,
        )
        val state = controller(
            snapshot(
                subjectRecord = CapabilityWorkspaceSubjectRecord(
                    subject = canonical,
                    freshness = FreshnessState.CURRENT,
                ),
                projection = CapabilityWorkspaceProjectionRecord(projection),
            ),
        ).load()

        assertTrue("PROJECTION_CONFLICT" in state.globalBlockerCodes)
        assertTrue("PROJECTION_CONFLICT" in state.projections.single().blockerCodes)
        assertEquals(canonical.key, state.projections.single().canonicalSubject)
        assertEquals("subject-owner", state.subjects.single().authorityLabel)
    }

    @Test
    fun offlineCachedStatePreservesSelectedSectionAcrossSerializationAndCannotRoute() = runTest {
        val snapshot = snapshot(
            subjectRecord = CapabilityWorkspaceSubjectRecord(subject(), freshness = FreshnessState.CURRENT),
        )
        var routeCalls = 0
        val source = CapabilityWorkspaceSnapshotSource { CapabilityWorkspaceLoadResult.OfflineCached(snapshot) }
        val controller = CapabilityWorkspaceController(
            source = source,
            routePort = CapabilityWorkspaceRoutePort {
                routeCalls += 1
                decision(it)
            },
        )
        val initial = controller.load()
        val selected = controller.selectSection(initial, CapabilityWorkspaceSection.ROUTES)
        val encoded = Json.encodeToString(CapabilityWorkspaceUiState.serializer(), selected)
        val restored = Json.decodeFromString(CapabilityWorkspaceUiState.serializer(), encoded)
        val reloaded = controller.load(restored)

        assertEquals(CapabilityWorkspaceSection.ROUTES, reloaded.selectedSection)
        assertEquals(CapabilityWorkspaceConnectionState.OFFLINE_CACHED, reloaded.connectionState)
        assertTrue("OFFLINE_CACHED_VIEW" in reloaded.globalBlockerCodes)
        assertFalse(reloaded.routes.single().enabled)
        controller.proposeRoute(reloaded, reloaded.routes.single().actionId)
        assertEquals(0, routeCalls)
    }

    @Test
    fun onlineRouteOnlyRecordsProposalDecisionAndNeverExecutionAuthority() = runTest {
        val snapshot = snapshot(
            subjectRecord = CapabilityWorkspaceSubjectRecord(subject(), freshness = FreshnessState.CURRENT),
        )
        var calls = 0
        val controller = CapabilityWorkspaceController(
            source = CapabilityWorkspaceSnapshotSource { CapabilityWorkspaceLoadResult.Loaded(snapshot) },
            routePort = CapabilityWorkspaceRoutePort { request ->
                calls += 1
                RouteDecision(
                    requestId = request.requestId,
                    state = RouteDecisionState.ADMITTED,
                    destinationOwner = request.destinationOwner,
                    evidenceCeiling = request.evidenceCeiling,
                    reasonCode = "ROUTE_PROPOSAL_ACKNOWLEDGED",
                    executionAuthorityGranted = false,
                )
            },
        )
        val state = controller.load()
        assertTrue(state.routes.single().enabled)
        val next = controller.proposeRoute(state, state.routes.single().actionId)

        assertEquals(1, calls)
        assertEquals(RouteDecisionState.ADMITTED, next.routes.single().lastDecisionState)
        assertEquals("ROUTE_PROPOSAL_ACKNOWLEDGED", next.routes.single().lastReasonCode)
    }

    @Test
    fun unavailableWorkspaceShowsOnlyBoundedBlockerAndKeepsNavigationState() = runTest {
        val controller = CapabilityWorkspaceController(
            source = CapabilityWorkspaceSnapshotSource {
                CapabilityWorkspaceLoadResult.Unavailable("SOURCE_UNAVAILABLE")
            },
            routePort = CapabilityWorkspaceRoutePort(::decision),
        )
        val restored = CapabilityWorkspaceUiState(selectedSection = CapabilityWorkspaceSection.EVIDENCE)
        val state = controller.load(restored)

        assertEquals(CapabilityWorkspaceSection.EVIDENCE, state.selectedSection)
        assertEquals(CapabilityWorkspaceConnectionState.UNAVAILABLE, state.connectionState)
        assertEquals(setOf("SOURCE_UNAVAILABLE"), state.globalBlockerCodes)
        assertTrue(state.subjects.isEmpty())
    }

    private fun controller(
        snapshot: CapabilityWorkspaceSnapshot,
        access: CapabilityWorkspaceAccess = CapabilityWorkspaceAccess.PublicSafe,
    ) = CapabilityWorkspaceController(
        source = CapabilityWorkspaceSnapshotSource { CapabilityWorkspaceLoadResult.Loaded(snapshot) },
        routePort = CapabilityWorkspaceRoutePort(::decision),
        access = access,
    )

    private fun snapshot(
        subjectRecord: CapabilityWorkspaceSubjectRecord,
        projection: CapabilityWorkspaceProjectionRecord? = null,
    ): CapabilityWorkspaceSnapshot {
        val request = RouteRequest(
            requestId = "ROUTE.verify-001",
            caller = AuthorityRef(AuthorityKind.USER, "workspace-user"),
            intent = "verify exact requirement",
            requiredCapabilityId = "verify.claim",
            exactSubjects = setOf(subjectRecord.subject.key),
            destinationOwner = routeOwner,
            evidenceCeiling = EvidenceCeiling.TECHNICAL,
        )
        return CapabilityWorkspaceSnapshot(
            snapshotId = "SNAP.workspace-001",
            capturedAtEpochMs = 10,
            subjects = listOf(subjectRecord),
            edges = emptyList(),
            projections = listOfNotNull(projection),
            routes = listOf(
                CapabilityWorkspaceRouteAction(
                    actionId = "ACTION.verify-claim",
                    label = "Verify claim",
                    request = request,
                    requiredSubjectKeys = request.exactSubjects,
                ),
            ),
        )
    }

    private fun subject(
        visibility: SubjectVisibility = SubjectVisibility.PUBLIC,
        dataClass: SubjectDataClass = SubjectDataClass.PUBLIC,
    ) = SubjectRef(
        key = subjectKey,
        canonicalAuthority = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "subject-owner"),
        version = "v1",
        digest = digest,
        visibility = visibility,
        dataClass = dataClass,
    )

    private fun decision(request: RouteRequest) = RouteDecision(
        requestId = request.requestId,
        state = RouteDecisionState.DEFERRED,
        destinationOwner = request.destinationOwner,
        evidenceCeiling = request.evidenceCeiling,
        reasonCode = "DESTINATION_NOT_CONNECTED",
        executionAuthorityGranted = false,
    )
}
