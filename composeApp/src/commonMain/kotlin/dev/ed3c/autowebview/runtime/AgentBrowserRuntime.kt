package dev.ed3c.autowebview.runtime

import dev.ed3c.autowebview.cache.InMemorySemanticCache
import dev.ed3c.autowebview.cache.SemanticCache
import dev.ed3c.autowebview.capability.CapabilityRegistry
import dev.ed3c.autowebview.capability.PolicyDecision
import dev.ed3c.autowebview.dispatcher.DispatcherEvent
import dev.ed3c.autowebview.dispatcher.DispatcherMode
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
import dev.ed3c.autowebview.navigation.BrowserActionStatus
import dev.ed3c.autowebview.navigation.BrowserNavigationPort
import dev.ed3c.autowebview.navigation.HttpsNavigationUrl
import dev.ed3c.autowebview.navigation.MainFrameUrlObserver
import dev.ed3c.autowebview.navigation.NavigationCommand
import dev.ed3c.autowebview.navigation.NavigationDispatchResult
import dev.ed3c.autowebview.navigation.NavigationProposalLedger
import dev.ed3c.autowebview.privacy.PrivacyGuard
import dev.ed3c.autowebview.projection.ProjectionEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    sessionId: String = StableIds.from("session", Clock.System.now().toEpochMilliseconds().toString()),
    private val proposalLedger: NavigationProposalLedger = NavigationProposalLedger(sessionId),
    private val observationTimeoutMs: Long = DEFAULT_OBSERVATION_TIMEOUT_MS,
    private val observationPollMs: Long = DEFAULT_OBSERVATION_POLL_MS,
) {
    private val mutableContext = MutableStateFlow<PageContext?>(null)
    val currentContext: StateFlow<PageContext?> = mutableContext.asStateFlow()

    private val mutableProjections = MutableStateFlow<List<ProjectionHint>>(emptyList())
    val projections: StateFlow<List<ProjectionHint>> = mutableProjections.asStateFlow()

    private val mutableAudit = MutableStateFlow<List<AuditEvent>>(emptyList())
    val auditEvents: StateFlow<List<AuditEvent>> = mutableAudit.asStateFlow()

    val dispatcherState: StateFlow<DispatcherSnapshot> = dispatcher.state

    private val bindingMutex = Mutex()
    private var bindingGenerationCounter: Long = 0L
    private var boundPort: BrowserNavigationPort? = null
    private var boundGeneration: Long? = null
    private var mainFrameUrlObserver: MainFrameUrlObserver? = null
    private var userInteracting: Boolean = false

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

    /**
     * Bind a navigation port for the current WebView. Returns the binding generation.
     * [portFactory] receives the assigned generation so the port can enforce it.
     * Does not expose the raw navigator — only the typed port is retained.
     */
    suspend fun bindNavigationPort(
        portFactory: (generation: Long) -> BrowserNavigationPort,
        urlObserver: MainFrameUrlObserver? = null,
    ): Long = bindingMutex.withLock {
        bindingGenerationCounter += 1
        val generation = bindingGenerationCounter
        boundPort = portFactory(generation)
        boundGeneration = generation
        mainFrameUrlObserver = urlObserver
        generation
    }

    /** Test/helper overload that binds an already-constructed port to a fresh generation. */
    suspend fun bindNavigationPort(
        port: BrowserNavigationPort,
        urlObserver: MainFrameUrlObserver? = null,
    ): Long = bindNavigationPort(
        portFactory = { port },
        urlObserver = urlObserver,
    )

    /** Unbind only when [generation] matches the currently bound generation. */
    suspend fun unbindNavigationPort(generation: Long) = bindingMutex.withLock {
        if (boundGeneration == generation) {
            boundPort = null
            boundGeneration = null
            mainFrameUrlObserver = null
        }
    }

    fun currentBindingGeneration(): Long? = boundGeneration

    /**
     * Create a navigation proposal and return [proposalId] immediately without waiting on HITL.
     */
    suspend fun proposeNavigation(rawUrl: String): ProposeNavigationResult {
        val normalized = HttpsNavigationUrl.validateAndNormalize(rawUrl)
            .getOrElse {
                return ProposeNavigationResult.Invalid(it.message ?: "invalid url")
            }

        val entry = proposalLedger.propose(
            normalizedUrl = normalized,
            initialStatus = BrowserActionStatus.PROPOSED,
        )

        val action = AgentAction(
            id = entry.proposalId,
            capabilityId = "browser.navigate",
            name = "Navigate",
            description = "Navigate to $normalized",
            arguments = mapOf(
                "url" to normalized,
                "proposalId" to entry.proposalId,
            ),
            risk = ActionRisk.MEDIUM,
        )
        val decision = propose(action)
        val status = when (decision) {
            PolicyDecision.Allowed -> BrowserActionStatus.WAITING_FOR_CONFIRMATION
            is PolicyDecision.RequiresConfirmation -> BrowserActionStatus.WAITING_FOR_CONFIRMATION
            is PolicyDecision.Denied -> BrowserActionStatus.REJECTED
        }
        proposalLedger.updateStatus(entry.proposalId, status)
        return ProposeNavigationResult.Accepted(
            proposalId = entry.proposalId,
            normalizedUrl = normalized,
            status = status,
            decision = decision,
        )
    }

    suspend fun actionStatus(proposalId: String): BrowserActionStatus =
        proposalLedger.statusOf(proposalId)

    suspend fun propose(action: AgentAction, grantedPermissions: Set<String> = emptySet()): PolicyDecision {
        val decision = capabilities.evaluate(action, grantedPermissions)
        when (decision) {
            PolicyDecision.Allowed -> dispatcher.dispatch(DispatcherEvent.ActionProposed(action, confirmationRequired = false))
            is PolicyDecision.RequiresConfirmation -> dispatcher.dispatch(DispatcherEvent.ActionProposed(action, confirmationRequired = true))
            is PolicyDecision.Denied -> audit("policy", "Denied ${action.name}", mapOf("reason" to decision.reason))
        }
        return decision
    }

    /**
     * Confirm the current pending action (UI path). Executes once via the bound port.
     */
    suspend fun confirmPendingAction() {
        val pending = dispatcher.state.value.pendingAction ?: return
        val proposalId = pending.arguments["proposalId"] ?: pending.id
        confirmNavigation(proposalId)
    }

    /**
     * Confirm a specific [proposalId]. Wrong proposal IDs do not call the bound port.
     */
    suspend fun confirmNavigation(proposalId: String) {
        val pending = dispatcher.state.value.pendingAction
        val pendingProposalId = pending?.arguments?.get("proposalId") ?: pending?.id
        if (pending == null || pendingProposalId != proposalId) {
            // Wrong or missing proposal — never dispatch a side effect.
            val known = proposalLedger.get(proposalId)
            if (known != null && known.status != BrowserActionStatus.APPLIED) {
                proposalLedger.updateStatus(proposalId, BrowserActionStatus.REJECTED)
            }
            audit("hitl", "Confirmation ignored for non-pending proposal", mapOf("proposalId" to proposalId))
            return
        }

        val normalizedUrl = pending.arguments["url"]
            ?: proposalLedger.get(proposalId)?.normalizedUrl
            ?: run {
                proposalLedger.updateStatus(proposalId, BrowserActionStatus.REJECTED)
                dispatcher.dispatch(DispatcherEvent.ActionFailed("Missing navigation URL"))
                return
            }

        // User preemption before dispatch → reject, side-effect count 0.
        if (userInteracting || dispatcher.state.value.mode == DispatcherMode.OBSERVING_USER) {
            proposalLedger.updateStatus(proposalId, BrowserActionStatus.REJECTED)
            dispatcher.dispatch(DispatcherEvent.ActionFailed("User preemption"))
            audit("hitl", "Navigation rejected by user preemption", mapOf("proposalId" to proposalId))
            return
        }

        val (port, generation) = bindingMutex.withLock {
            val gen = boundGeneration
            val p = boundPort
            if (gen == null || p == null) null to null else p to gen
        }
        if (port == null || generation == null) {
            proposalLedger.updateStatus(proposalId, BrowserActionStatus.REJECTED)
            dispatcher.dispatch(DispatcherEvent.ActionFailed("No bound WebView navigation port"))
            audit("hitl", "Navigation rejected: no bound port", mapOf("proposalId" to proposalId))
            return
        }

        dispatcher.dispatch(DispatcherEvent.ActionConfirmed)
        proposalLedger.updateStatus(proposalId, BrowserActionStatus.EXECUTING)
        audit("hitl", "User confirmed pending action", mapOf("proposalId" to proposalId))

        // Re-check preemption and binding after moving to EXECUTING, before side effect.
        if (userInteracting) {
            proposalLedger.updateStatus(proposalId, BrowserActionStatus.REJECTED)
            dispatcher.dispatch(DispatcherEvent.ActionFailed("User preemption"))
            return
        }
        if (currentBindingGeneration() != generation) {
            proposalLedger.updateStatus(proposalId, BrowserActionStatus.REJECTED)
            dispatcher.dispatch(DispatcherEvent.ActionFailed("WebView binding replaced"))
            return
        }

        val command = NavigationCommand(
            proposalId = proposalId,
            httpsUrl = normalizedUrl,
            bindingGeneration = generation,
        )

        val dispatchResult = try {
            port.navigate(command)
        } catch (_: Exception) {
            NavigationDispatchResult.Unknown
        }

        when (dispatchResult) {
            NavigationDispatchResult.Accepted -> {
                val applied = observeApplied(normalizedUrl)
                if (applied) {
                    proposalLedger.updateStatus(proposalId, BrowserActionStatus.APPLIED)
                    dispatcher.dispatch(DispatcherEvent.ActionCompleted)
                    audit("navigation", "Navigation applied", mapOf("proposalId" to proposalId))
                } else {
                    proposalLedger.updateStatus(proposalId, BrowserActionStatus.UNKNOWN)
                    dispatcher.dispatch(DispatcherEvent.ActionFailed("Postcondition not observed"))
                    audit("navigation", "Navigation unknown after dispatch", mapOf("proposalId" to proposalId))
                }
            }
            is NavigationDispatchResult.Rejected -> {
                proposalLedger.updateStatus(proposalId, BrowserActionStatus.REJECTED)
                dispatcher.dispatch(DispatcherEvent.ActionFailed(dispatchResult.reason))
                audit("navigation", "Navigation rejected", mapOf("proposalId" to proposalId, "reason" to dispatchResult.reason))
            }
            NavigationDispatchResult.Unknown -> {
                proposalLedger.updateStatus(proposalId, BrowserActionStatus.UNKNOWN)
                dispatcher.dispatch(DispatcherEvent.ActionFailed("Navigation dispatch unknown"))
                audit("navigation", "Navigation unknown", mapOf("proposalId" to proposalId))
            }
        }
    }

    suspend fun rejectPendingAction() {
        val pending = dispatcher.state.value.pendingAction
        val proposalId = pending?.arguments?.get("proposalId") ?: pending?.id
        if (proposalId != null) {
            proposalLedger.updateStatus(proposalId, BrowserActionStatus.REJECTED)
        }
        dispatcher.dispatch(DispatcherEvent.ActionRejected)
        audit("hitl", "User rejected pending action")
    }

    suspend fun userInteractionStarted() {
        userInteracting = true
        dispatcher.dispatch(DispatcherEvent.UserInteractionStarted)
    }

    suspend fun userInteractionEnded() {
        userInteracting = false
        dispatcher.dispatch(DispatcherEvent.UserInteractionEnded)
    }

    fun currentContextJson(): String = mutableContext.value?.let(json::encodeToString) ?: "{}"

    private suspend fun observeApplied(expectedNormalizedUrl: String): Boolean {
        val observer = mainFrameUrlObserver ?: return false
        val matched = withTimeoutOrNull(observationTimeoutMs) {
            while (true) {
                val current = observer.currentMainFrameUrl()
                if (current != null && HttpsNavigationUrl.mainFrameMatches(current, expectedNormalizedUrl)) {
                    return@withTimeoutOrNull true
                }
                delay(observationPollMs)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        return matched == true
    }

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

    sealed interface ProposeNavigationResult {
        data class Accepted(
            val proposalId: String,
            val normalizedUrl: String,
            val status: BrowserActionStatus,
            val decision: PolicyDecision,
        ) : ProposeNavigationResult

        data class Invalid(val reason: String) : ProposeNavigationResult
    }

    companion object {
        const val DEFAULT_OBSERVATION_TIMEOUT_MS = 5_000L
        const val DEFAULT_OBSERVATION_POLL_MS = 25L

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
