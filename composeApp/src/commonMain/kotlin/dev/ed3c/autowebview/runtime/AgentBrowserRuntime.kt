package dev.ed3c.autowebview.runtime

import dev.ed3c.autowebview.cache.InMemorySemanticCache
import dev.ed3c.autowebview.cache.SemanticCache
import dev.ed3c.autowebview.capability.CapabilityRegistry
import dev.ed3c.autowebview.capability.PolicyDecision
import dev.ed3c.autowebview.dispatcher.DispatcherEvent
import dev.ed3c.autowebview.dispatcher.DispatcherSnapshot
import dev.ed3c.autowebview.dispatcher.LocalDispatcher
import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import dev.ed3c.autowebview.domain.AuditEvent
import dev.ed3c.autowebview.domain.CapabilityDescriptor
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.domain.ProjectionHint
import dev.ed3c.autowebview.domain.SemanticCacheRecord
import dev.ed3c.autowebview.domain.StableIds
import dev.ed3c.autowebview.privacy.PrivacyGuard
import dev.ed3c.autowebview.projection.ProjectionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class AgentBrowserRuntime(
    private val cache: SemanticCache = InMemorySemanticCache(),
    private val privacyGuard: PrivacyGuard = PrivacyGuard(),
    private val projectionEngine: ProjectionEngine = ProjectionEngine(),
    val dispatcher: LocalDispatcher = LocalDispatcher(),
    val capabilities: CapabilityRegistry = defaultCapabilities(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val mutableContext = MutableStateFlow<PageContext?>(null)
    val currentContext: StateFlow<PageContext?> = mutableContext.asStateFlow()

    private val mutableProjections = MutableStateFlow<List<ProjectionHint>>(emptyList())
    val projections: StateFlow<List<ProjectionHint>> = mutableProjections.asStateFlow()

    private val mutableAudit = MutableStateFlow<List<AuditEvent>>(emptyList())
    val auditEvents: StateFlow<List<AuditEvent>> = mutableAudit.asStateFlow()

    val dispatcherState: StateFlow<DispatcherSnapshot> = dispatcher.state

    suspend fun onPageContext(raw: PageContext) {
        val sanitized = privacyGuard.sanitize(raw)
        val matches = cache.query(sanitized.markdown + " " + sanitized.selection)
        mutableContext.value = sanitized
        mutableProjections.value = projectionEngine.project(sanitized, matches)
        cache.put(
            SemanticCacheRecord(
                id = StableIds.from(sanitized.url, sanitized.title, sanitized.markdown.take(256)),
                sourceUrl = sanitized.url,
                title = sanitized.title,
                summary = summarize(sanitized),
                content = sanitized.markdown,
                createdAtEpochMs = now(),
                tags = keywords(sanitized.markdown),
            ),
        )
        audit("context", "Captured and locally sanitized page context", mapOf("url" to sanitized.url))
    }

    suspend fun propose(action: AgentAction, grantedPermissions: Set<String> = emptySet()): PolicyDecision {
        val decision = capabilities.evaluate(action, grantedPermissions)
        when (decision) {
            PolicyDecision.Allowed -> dispatcher.dispatch(DispatcherEvent.ActionProposed(action, confirmationRequired = false))
            is PolicyDecision.RequiresConfirmation -> dispatcher.dispatch(DispatcherEvent.ActionProposed(action, confirmationRequired = true))
            is PolicyDecision.Denied -> audit("policy", "Denied ${action.name}", mapOf("reason" to decision.reason))
        }
        return decision
    }

    suspend fun confirmPendingAction() {
        dispatcher.dispatch(DispatcherEvent.ActionConfirmed)
        audit("hitl", "User confirmed pending action")
    }

    suspend fun rejectPendingAction() {
        dispatcher.dispatch(DispatcherEvent.ActionRejected)
        audit("hitl", "User rejected pending action")
    }

    suspend fun userInteractionStarted() {
        dispatcher.dispatch(DispatcherEvent.UserInteractionStarted)
    }

    suspend fun userInteractionEnded() {
        dispatcher.dispatch(DispatcherEvent.UserInteractionEnded)
    }

    fun currentContextJson(): String = mutableContext.value?.let(json::encodeToString) ?: "{}"

    private fun summarize(context: PageContext): String {
        val source = context.selection.ifBlank { context.markdown }
        return source.replace(Regex("""\s+"""), " ").trim().take(240).ifBlank { context.title }
    }

    private fun keywords(text: String): Set<String> = Regex("""[\p{L}\p{N}_-]{4,}""")
        .findAll(text.lowercase())
        .map { it.value }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(12)
        .mapTo(linkedSetOf()) { it.key }

    private fun audit(category: String, message: String, metadata: Map<String, String> = emptyMap()) {
        mutableAudit.value = (mutableAudit.value + AuditEvent(now(), category, message, metadata)).takeLast(100)
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        fun defaultCapabilities() = CapabilityRegistry(
            listOf(
                CapabilityDescriptor(
                    id = "browser.read_context",
                    displayName = "Read page context",
                    description = "Capture sanitized DOM-derived context",
                    maximumRisk = ActionRisk.READ_ONLY,
                    enabledByDefault = true,
                ),
                CapabilityDescriptor(
                    id = "browser.navigate",
                    displayName = "Navigate browser",
                    description = "Navigate the embedded browser to an approved URL",
                    maximumRisk = ActionRisk.MEDIUM,
                    enabledByDefault = true,
                ),
                CapabilityDescriptor(
                    id = "browser.interact",
                    displayName = "Interact with page",
                    description = "Click or fill a non-sensitive element after approval",
                    maximumRisk = ActionRisk.HIGH,
                    enabledByDefault = false,
                ),
            ),
        )
    }
}
