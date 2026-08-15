package dev.ed3c.autowebview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.coroutines.launch

@Composable
fun ContextRail(runtime: AgentBrowserRuntime, modifier: Modifier = Modifier) {
    val context by runtime.currentContext.collectAsState()
    val projections by runtime.projections.collectAsState()
    val dispatcher by runtime.dispatcherState.collectAsState()
    val audit by runtime.auditEvents.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier.background(Color(0xFFF1F5F9)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Agent Context Rail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Mode: ${dispatcher.mode}", style = MaterialTheme.typography.labelLarge)
        dispatcher.reason.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        Text(context?.title ?: "No page context yet", style = MaterialTheme.typography.titleSmall)
        Text(context?.url ?: "Load a page and press Context", style = MaterialTheme.typography.bodySmall)
        Text("Local-only capture · sensitive fields filtered", color = Color(0xFF047857), style = MaterialTheme.typography.labelMedium)

        if (dispatcher.pendingAction != null) {
            HorizontalDivider()
            Text(dispatcher.pendingAction!!.description, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { runtime.confirmPendingAction() } }) { Text("Approve") }
                OutlinedButton(onClick = { scope.launch { runtime.rejectPendingAction() } }) { Text("Reject") }
            }
        }

        HorizontalDivider()
        Text("Semantic cache projections", fontWeight = FontWeight.SemiBold)
        if (projections.isEmpty()) {
            Text("No prior related context yet. Revisit or compare another page to populate L1 cache.", style = MaterialTheme.typography.bodySmall)
        } else {
            projections.forEach { projection ->
                Column(Modifier.fillMaxWidth().background(Color.White, MaterialTheme.shapes.small).padding(10.dp)) {
                    Text("${(projection.relevance * 100).toInt()}% match", style = MaterialTheme.typography.labelSmall)
                    Text(projection.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider()
        Text("Audit trail", fontWeight = FontWeight.SemiBold)
        audit.takeLast(8).asReversed().forEach { event ->
            Text("${event.category}: ${event.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
