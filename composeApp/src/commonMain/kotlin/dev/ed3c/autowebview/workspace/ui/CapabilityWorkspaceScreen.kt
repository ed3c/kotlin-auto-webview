package dev.ed3c.autowebview.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ed3c.autowebview.workspace.contract.TypedEdge
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceConnectionState
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceProjectionView
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceRouteView
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceSection
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceSubjectView
import dev.ed3c.autowebview.workspace.viewmodel.CapabilityWorkspaceUiState
import dev.ed3c.autowebview.workspace.viewmodel.subjectsFor

@Composable
fun CapabilityWorkspaceScreen(
    state: CapabilityWorkspaceUiState,
    onSectionSelected: (CapabilityWorkspaceSection) -> Unit,
    onRouteRequested: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkspaceHeader(state)
            SectionRail(state.selectedSection, onSectionSelected)
            if (state.globalBlockerCodes.isNotEmpty()) {
                Text("Blockers: ${state.globalBlockerCodes.sorted().joinToString()}")
            }
            HorizontalDivider()
            WorkspaceSectionContent(state, onRouteRequested, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WorkspaceHeader(state: CapabilityWorkspaceUiState) {
    val connection = when (state.connectionState) {
        CapabilityWorkspaceConnectionState.ONLINE -> "ONLINE"
        CapabilityWorkspaceConnectionState.OFFLINE_CACHED -> "OFFLINE / CACHED"
        CapabilityWorkspaceConnectionState.UNAVAILABLE -> "UNAVAILABLE"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Capability Workspace")
        Text("State: $connection")
        state.snapshotId?.let { Text("Snapshot: $it") }
        state.capturedAtEpochMs?.let { Text("Observed: $it") }
        Text("Read-only owner projections. Route actions create proposals only.")
    }
}

@Composable
private fun SectionRail(
    selected: CapabilityWorkspaceSection,
    onSectionSelected: (CapabilityWorkspaceSection) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(CapabilityWorkspaceSection.entries) { section ->
            TextButton(onClick = { onSectionSelected(section) }) {
                Text(if (section == selected) "[${section.name}]" else section.name)
            }
        }
    }
}

@Composable
private fun WorkspaceSectionContent(
    state: CapabilityWorkspaceUiState,
    onRouteRequested: (String) -> Unit,
    modifier: Modifier,
) {
    when (state.selectedSection) {
        CapabilityWorkspaceSection.SUBJECTS,
        CapabilityWorkspaceSection.WORK,
        CapabilityWorkspaceSection.EVIDENCE,
        CapabilityWorkspaceSection.SKILLS,
        CapabilityWorkspaceSection.EXPERIMENTS,
        -> SubjectList(state.subjectsFor(state.selectedSection), modifier)

        CapabilityWorkspaceSection.GRAPH -> EdgeList(state.edges, modifier)
        CapabilityWorkspaceSection.PROJECTIONS -> ProjectionList(state.projections, modifier)
        CapabilityWorkspaceSection.ROUTES -> RouteList(state.routes, onRouteRequested, modifier)
    }
}

@Composable
private fun SubjectList(subjects: List<CapabilityWorkspaceSubjectView>, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(subjects, key = { "${it.key.kind}:${it.key.logicalId}" }) { subject ->
            SubjectCard(subject)
        }
        if (subjects.isEmpty()) item { Text("No subjects in this view.") }
    }
}

@Composable
private fun SubjectCard(subject: CapabilityWorkspaceSubjectView) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${subject.key.kind}: ${subject.key.logicalId}")
            Text("Authority: ${subject.authorityKind} / ${subject.authorityLabel}")
            Text("Version: ${subject.versionLabel ?: "—"}  Digest: ${subject.digestLabel ?: "—"}")
            Text("Evidence: ${subject.evidenceCeiling}  Freshness: ${subject.freshness}")
            Text("Privacy: ${subject.visibility} / ${subject.dataClass}")
            if (subject.qualification.name != "NOT_APPLICABLE") Text("Skill: ${subject.qualification}")
            if (subject.externalProviders.isNotEmpty()) {
                Text("Providers: ${subject.externalProviders.sorted().joinToString()}")
            }
            subject.externalLocators.forEach { Text("Locator: $it") }
            if (subject.blockerCodes.isNotEmpty()) {
                Text("Blockers: ${subject.blockerCodes.sorted().joinToString()}")
            }
        }
    }
}

@Composable
private fun EdgeList(edges: List<TypedEdge>, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(edges, key = TypedEdge::edgeId) { edge ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${edge.from.logicalId} ${edge.relation} ${edge.to.logicalId}")
                    Text("Evidence: ${edge.evidenceClass} / ${edge.confidence}")
                    Text("Owner: ${edge.owner.kind}")
                }
            }
        }
        if (edges.isEmpty()) item { Text("No typed edges available.") }
    }
}

@Composable
private fun ProjectionList(projections: List<CapabilityWorkspaceProjectionView>, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(projections, key = CapabilityWorkspaceProjectionView::projectionId) { projection ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Projection (not authority): ${projection.projectionId}")
                    Text("Subject: ${projection.canonicalSubject.kind}:${projection.canonicalSubject.logicalId}")
                    Text("Kind: ${projection.kindLabel}  State: ${projection.stateLabel}")
                    Text("Provider: ${projection.externalProvider}")
                    projection.externalLocator?.let { Text("Locator: $it") }
                    if (projection.blockerCodes.isNotEmpty()) {
                        Text("Blockers: ${projection.blockerCodes.sorted().joinToString()}")
                    }
                }
            }
        }
        if (projections.isEmpty()) item { Text("No human projections available.") }
    }
}

@Composable
private fun RouteList(
    routes: List<CapabilityWorkspaceRouteView>,
    onRouteRequested: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(routes, key = CapabilityWorkspaceRouteView::actionId) { route ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(route.label)
                    Text("Capability: ${route.capabilityId}")
                    Text("Destination: ${route.destinationAuthorityKind} / ${route.destinationLabel}")
                    Text("Evidence ceiling: ${route.evidenceCeiling}")
                    if (route.blockerCodes.isNotEmpty()) {
                        Text("Blockers: ${route.blockerCodes.sorted().joinToString()}")
                    }
                    route.lastDecisionState?.let { state ->
                        Text("Last proposal: $state / ${route.lastReasonCode ?: "NO_REASON"}")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { onRouteRequested(route.actionId) },
                            enabled = route.enabled,
                        ) {
                            Text("Propose route")
                        }
                    }
                }
            }
        }
        if (routes.isEmpty()) item { Text("No route proposals available.") }
    }
}
