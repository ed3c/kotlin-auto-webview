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
import dev.ed3c.autowebview.executor.BoundedBrowserActionExecutor
import dev.ed3c.autowebview.executor.BrowserActionConfirmationReceipt
import dev.ed3c.autowebview.executor.BrowserActionExecutionContext
import dev.ed3c.autowebview.executor.BrowserActionExecutionResult
import dev.ed3c.autowebview.executor.BrowserActionKind
import dev.ed3c.autowebview.executor.BrowserActionPayload
import dev.ed3c.autowebview.executor.BrowserActionPlatform
import dev.ed3c.autowebview.executor.BrowserActionProposal
import dev.ed3c.autowebview.executor.BrowserSideEffectState
import dev.ed3c.autowebview.executor.ClickPayload
import dev.ed3c.autowebview.executor.FillTextPayload
import dev.ed3c.autowebview.executor.SelectOptionPayload
import dev.ed3c.autowebview.executor.UserInteractionProbe
import dev.ed3c.autowebview.privacy.PrivacyGuard
import dev.ed3c.autowebview.projection.ProjectionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val navigationMutex = Mutex()
    private var navigationSequence = 0L
    private var navigationBindingSequence = 0L
    private var navigationBinding: BoundNavigationPort? = null
    private var activeNavigation: ActiveNavigation? = null
    private val navigationStatuses = linkedMapOf<String, NavigationActionStatus>()
    private var interactionSequence = 0L
    private var interactionBindingSequence = 0L
    private var interactionBinding: BoundInteractionPlatform? = null
    private var activeInteraction: ActiveInteraction? = null
    private val interactionProposals = linkedMapOf<String, StoredInteractionProposal>()
    private val interactionStatuses = linkedMapOf<String, NavigationActionStatus>()

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
        completeObservedNavigation(sanitized)
        completeObservedInteraction(sanitized)
    }

    suspend fun proposeNavigation(url: String): NavigationProposal {
        val action = navigationMutex.withLock {
            navigationSequence += 1
            AgentAction(
                id = StableIds.from("navigate", navigationSequence.toString(), url),
                capabilityId = "browser.navigate",
                name = "Navigate",
                description = "Navigate to $url",
                arguments = mapOf("url" to url),
                risk = ActionRisk.MEDIUM,
            )
        }
        val decision = propose(action)
        navigationMutex.withLock {
            val status = when (decision) {
                PolicyDecision.Allowed -> NavigationActionStatus(
                    action.id,
                    NavigationActionState.EXECUTING,
                    "Allowed by policy but awaiting an execution binding",
                )
                is PolicyDecision.RequiresConfirmation -> NavigationActionStatus(
                    action.id,
                    NavigationActionState.WAITING_FOR_CONFIRMATION,
                    decision.reason,
                )
                is PolicyDecision.Denied -> NavigationActionStatus(
                    action.id,
                    NavigationActionState.REJECTED,
                    decision.reason,
                )
            }
            putNavigationStatus(status)
        }
        return NavigationProposal(action.id, decision)
    }

    fun bindNavigationPort(port: BrowserNavigationPort): Long {
        navigationBindingSequence += 1
        val generation = navigationBindingSequence
        navigationBinding = BoundNavigationPort(generation, port)
        return generation
    }

    fun unbindNavigationPort(generation: Long) {
        if (navigationBinding?.generation == generation) navigationBinding = null
    }

    suspend fun navigationStatus(proposalId: String): NavigationActionStatus? = navigationMutex.withLock {
        navigationStatuses[proposalId]
    }

    suspend fun actionStatus(proposalId: String): NavigationActionStatus? = navigationMutex.withLock {
        navigationStatuses[proposalId] ?: interactionStatuses[proposalId]
    }

    fun bindInteractionPlatform(platform: BrowserActionPlatform): Long {
        interactionBindingSequence += 1
        val generation = interactionBindingSequence
        interactionBinding = BoundInteractionPlatform(generation, platform)
        return generation
    }

    fun unbindInteractionPlatform(generation: Long) {
        if (interactionBinding?.generation == generation) interactionBinding = null
    }

    suspend fun proposeInteraction(
        kind: BrowserActionKind,
        targetFingerprint: String,
        value: String? = null,
        expectedUrl: String? = null,
    ): BrowserInteractionProposal {
        require(targetFingerprint.matches(Regex("[0-9a-f]{8,64}"))) {
            "targetFingerprint must be a bounded lowercase hexadecimal identity"
        }
        val page = currentContext.value ?: throw IllegalArgumentException("Current page context is required")
        require(page.interactiveElements.count { it.fingerprint == targetFingerprint } == 1) {
            "targetFingerprint must resolve exactly once in the current page context"
        }
        val payload: BrowserActionPayload = when (kind) {
            BrowserActionKind.CLICK -> {
                require(value == null) { "click does not accept a value" }
                val destination = expectedUrl ?: throw IllegalArgumentException("click requires expectedUrl")
                requireSameOriginHttps(page.url, destination)
                ClickPayload
            }
            BrowserActionKind.FILL_TEXT -> {
                require(expectedUrl == null) { "fill_text does not accept expectedUrl" }
                FillTextPayload(value ?: throw IllegalArgumentException("fill_text requires value"))
            }
            BrowserActionKind.SELECT_OPTION -> {
                require(expectedUrl == null) { "select_option does not accept expectedUrl" }
                SelectOptionPayload(value ?: throw IllegalArgumentException("select_option requires value"))
            }
        }
        val createdAt = now()
        val proposal = navigationMutex.withLock {
            interactionSequence += 1
            val id = StableIds.from(
                "interaction",
                interactionSequence.toString(),
                page.url,
                page.capturedAtEpochMs.toString(),
                targetFingerprint,
                kind.name,
            )
            BrowserActionProposal(
                id = id,
                agentActionId = id,
                pageUrl = page.url,
                pageCapturedAtEpochMs = page.capturedAtEpochMs,
                targetFingerprint = targetFingerprint,
                kind = kind,
                payload = payload,
                expectedUrl = expectedUrl,
                createdAtEpochMs = createdAt,
            )
        }
        val action = AgentAction(
            id = proposal.agentActionId,
            capabilityId = "browser.interact",
            name = "Interact with current page",
            description = "Execute one typed " + kind.name.lowercase() + " action after confirmation",
            arguments = buildMap {
                put("kind", kind.name)
                put("pageUrl", page.url)
                put("targetFingerprint", targetFingerprint)
                expectedUrl?.let { put("expectedUrl", it) }
            },
            risk = ActionRisk.HIGH,
        )
        val decision = propose(action)
        navigationMutex.withLock {
            val status = when (decision) {
                is PolicyDecision.RequiresConfirmation -> {
                    putStoredInteraction(
                        StoredInteractionProposal(
                            proposal = proposal,
                            bindingGeneration = interactionBinding?.generation,
                        ),
                    )
                    NavigationActionStatus(proposal.id, NavigationActionState.WAITING_FOR_CONFIRMATION, decision.reason)
                }
                PolicyDecision.Allowed -> NavigationActionStatus(
                    proposal.id,
                    NavigationActionState.EXECUTING,
                    "Allowed by policy but confirmation contract was not applied",
                )
                is PolicyDecision.Denied -> NavigationActionStatus(
                    proposal.id,
                    NavigationActionState.REJECTED,
                    decision.reason,
                )
            }
            putInteractionStatus(status)
        }
        return BrowserInteractionProposal(proposal.id, decision)
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
        val pending = dispatcherState.value.pendingAction
        if (pending?.capabilityId == "browser.navigate") {
            executeConfirmedNavigation(pending)
            return
        }
        if (pending?.capabilityId == "browser.interact") {
            executeConfirmedInteraction(pending)
            return
        }
        dispatcher.dispatch(DispatcherEvent.ActionConfirmed)
        audit("hitl", "User confirmed pending action")
    }

    suspend fun rejectPendingAction() {
        val pendingId = dispatcherState.value.pendingAction?.id
        dispatcher.dispatch(DispatcherEvent.ActionRejected)
        pendingId?.let {
            navigationMutex.withLock {
                if (navigationStatuses.containsKey(it)) {
                    putNavigationStatus(NavigationActionStatus(it, NavigationActionState.REJECTED, "Rejected by user"))
                }
                if (interactionStatuses.containsKey(it)) {
                    interactionProposals.remove(it)
                    putInteractionStatus(NavigationActionStatus(it, NavigationActionState.REJECTED, "Rejected by user"))
                }
            }
        }
        audit("hitl", "User rejected pending action")
    }

    suspend fun userInteractionStarted() {
        val pendingId = dispatcherState.value.pendingAction?.id
        dispatcher.dispatch(DispatcherEvent.UserInteractionStarted)
        pendingId?.let {
            navigationMutex.withLock {
                if (navigationStatuses.containsKey(it)) {
                    putNavigationStatus(NavigationActionStatus(it, NavigationActionState.REJECTED, "Preempted by user input"))
                }
                if (interactionStatuses.containsKey(it)) {
                    interactionProposals.remove(it)
                    putInteractionStatus(NavigationActionStatus(it, NavigationActionState.REJECTED, "Preempted by user input"))
                }
            }
        }
    }

    suspend fun userInteractionEnded() {
        dispatcher.dispatch(DispatcherEvent.UserInteractionEnded)
    }

    fun currentContextJson(): String = mutableContext.value?.let(json::encodeToString) ?: "{}"

    private suspend fun executeConfirmedNavigation(action: AgentAction) {
        val binding = navigationBinding
        val url = action.arguments["url"]
        if (binding == null || url == null) {
            dispatcher.dispatch(DispatcherEvent.ActionFailed("No current WebView navigation binding"))
            navigationMutex.withLock {
                putNavigationStatus(
                    NavigationActionStatus(action.id, NavigationActionState.NONE, "No current WebView navigation binding"),
                )
            }
            return
        }

        dispatcher.dispatch(DispatcherEvent.ActionConfirmed)
        val confirmedAt = now()
        navigationMutex.withLock {
            activeNavigation = ActiveNavigation(action.id, url, binding.generation, confirmedAt)
            putNavigationStatus(
                NavigationActionStatus(action.id, NavigationActionState.EXECUTING, "Confirmed and dispatched"),
            )
        }
        audit("hitl", "User confirmed pending navigation", mapOf("proposalId" to action.id))

        if (navigationBinding?.generation != binding.generation) {
            failActiveNavigation(action.id, NavigationActionState.NONE, "WebView binding changed before dispatch")
            return
        }
        try {
            binding.port.loadUrl(url)
        } catch (_: Throwable) {
            failActiveNavigation(action.id, NavigationActionState.UNKNOWN, "Navigation dispatch failed; effect is unknown")
        }
    }

    private suspend fun completeObservedNavigation(context: PageContext) {
        val active = navigationMutex.withLock { activeNavigation } ?: return
        if (context.capturedAtEpochMs < active.confirmedAtEpochMs) return
        if (navigationBinding?.generation != active.bindingGeneration) {
            failActiveNavigation(active.proposalId, NavigationActionState.UNKNOWN, "WebView binding changed after dispatch")
            return
        }
        if (context.url == active.expectedUrl) {
            navigationMutex.withLock {
                activeNavigation = null
                putNavigationStatus(
                    NavigationActionStatus(active.proposalId, NavigationActionState.APPLIED, "Observed exact fresh URL"),
                )
            }
            dispatcher.dispatch(DispatcherEvent.ActionCompleted)
        } else {
            failActiveNavigation(active.proposalId, NavigationActionState.UNKNOWN, "Observed a different fresh URL")
        }
    }

    private suspend fun executeConfirmedInteraction(action: AgentAction) {
        val stored = navigationMutex.withLock { interactionProposals.remove(action.id) }
        val binding = interactionBinding
        if (
            stored == null ||
            binding == null ||
            stored.bindingGeneration == null ||
            stored.bindingGeneration != binding.generation
        ) {
            dispatcher.dispatch(DispatcherEvent.ActionFailed("No matching current WebView action binding"))
            navigationMutex.withLock {
                putInteractionStatus(
                    NavigationActionStatus(action.id, NavigationActionState.NONE, "No matching current WebView action binding"),
                )
            }
            return
        }

        dispatcher.dispatch(DispatcherEvent.ActionConfirmed)
        val confirmedAt = now()
        navigationMutex.withLock {
            putInteractionStatus(
                NavigationActionStatus(action.id, NavigationActionState.EXECUTING, "Confirmed and executing typed action"),
            )
        }
        audit(
            "hitl",
            "User confirmed pending typed WebView action",
            mapOf("proposalId" to action.id, "kind" to stored.proposal.kind.name),
        )
        val page = currentContext.value
        if (page == null) {
            failInteraction(action.id, NavigationActionState.NONE, "Current page context is absent")
            return
        }
        val result = BoundedBrowserActionExecutor(binding.platform).execute(
            proposal = stored.proposal,
            context = BrowserActionExecutionContext(
                page = page,
                dispatcher = dispatcherState.value,
                confirmation = BrowserActionConfirmationReceipt(
                    proposalId = stored.proposal.id,
                    agentActionId = stored.proposal.agentActionId,
                    pageUrl = stored.proposal.pageUrl,
                    targetFingerprint = stored.proposal.targetFingerprint,
                    confirmedAtEpochMs = confirmedAt,
                ),
                nowEpochMs = confirmedAt,
                userInteraction = UserInteractionProbe {
                    dispatcherState.value.mode == dev.ed3c.autowebview.dispatcher.DispatcherMode.OBSERVING_USER
                },
            ),
        )
        when (result) {
            is BrowserActionExecutionResult.Succeeded -> {
                dispatcher.dispatch(DispatcherEvent.ActionCompleted)
                navigationMutex.withLock {
                    putInteractionStatus(
                        NavigationActionStatus(action.id, NavigationActionState.APPLIED, "Fresh exact DOM postcondition observed"),
                    )
                }
            }
            is BrowserActionExecutionResult.AwaitingObservation -> {
                val expected = stored.proposal.expectedUrl
                if (stored.proposal.kind != BrowserActionKind.CLICK || expected == null) {
                    failInteraction(action.id, NavigationActionState.UNKNOWN, "Action observation contract is absent")
                } else {
                    navigationMutex.withLock {
                        activeInteraction = ActiveInteraction(
                            proposalId = action.id,
                            expectedUrl = expected,
                            bindingGeneration = binding.generation,
                            confirmedAtEpochMs = confirmedAt,
                        )
                        putInteractionStatus(
                            NavigationActionStatus(action.id, NavigationActionState.EXECUTING, "Click dispatched; awaiting fresh URL"),
                        )
                    }
                }
            }
            is BrowserActionExecutionResult.Rejected,
            is BrowserActionExecutionResult.Cancelled -> {
                failInteraction(action.id, NavigationActionState.NONE, "Typed action rejected before a proven side effect")
            }
            is BrowserActionExecutionResult.TimedOut -> {
                failInteraction(action.id, result.sideEffectState.toNavigationState(), "Typed action timed out")
            }
            is BrowserActionExecutionResult.Failed -> {
                failInteraction(action.id, result.sideEffectState.toNavigationState(), "Typed action effect is unknown")
            }
        }
    }

    private suspend fun completeObservedInteraction(context: PageContext) {
        val active = navigationMutex.withLock { activeInteraction } ?: return
        if (context.capturedAtEpochMs < active.confirmedAtEpochMs) return
        if (interactionBinding?.generation != active.bindingGeneration) {
            failInteraction(active.proposalId, NavigationActionState.UNKNOWN, "WebView action binding changed after dispatch")
            return
        }
        if (context.url == active.expectedUrl) {
            navigationMutex.withLock {
                activeInteraction = null
                putInteractionStatus(
                    NavigationActionStatus(active.proposalId, NavigationActionState.APPLIED, "Observed exact fresh click URL"),
                )
            }
            dispatcher.dispatch(DispatcherEvent.ActionCompleted)
        } else {
            failInteraction(active.proposalId, NavigationActionState.UNKNOWN, "Observed a different fresh click URL")
        }
    }

    private suspend fun failInteraction(proposalId: String, state: NavigationActionState, reason: String) {
        navigationMutex.withLock {
            if (activeInteraction?.proposalId == proposalId) activeInteraction = null
            putInteractionStatus(NavigationActionStatus(proposalId, state, reason))
        }
        dispatcher.dispatch(DispatcherEvent.ActionFailed(reason))
    }

    private fun BrowserSideEffectState.toNavigationState(): NavigationActionState = when (this) {
        BrowserSideEffectState.NONE -> NavigationActionState.NONE
        BrowserSideEffectState.APPLIED -> NavigationActionState.APPLIED
        BrowserSideEffectState.UNKNOWN -> NavigationActionState.UNKNOWN
    }

    private suspend fun failActiveNavigation(
        proposalId: String,
        state: NavigationActionState,
        reason: String,
    ) {
        navigationMutex.withLock {
            if (activeNavigation?.proposalId == proposalId) activeNavigation = null
            putNavigationStatus(NavigationActionStatus(proposalId, state, reason))
        }
        dispatcher.dispatch(DispatcherEvent.ActionFailed(reason))
    }

    private fun putNavigationStatus(status: NavigationActionStatus) {
        navigationStatuses[status.proposalId] = status
        while (navigationStatuses.size > MAX_NAVIGATION_STATUSES) {
            navigationStatuses.remove(navigationStatuses.keys.first())
        }
    }

    private fun putStoredInteraction(stored: StoredInteractionProposal) {
        interactionProposals[stored.proposal.id] = stored
        while (interactionProposals.size > MAX_NAVIGATION_STATUSES) {
            interactionProposals.remove(interactionProposals.keys.first())
        }
    }

    private fun putInteractionStatus(status: NavigationActionStatus) {
        interactionStatuses[status.proposalId] = status
        while (interactionStatuses.size > MAX_NAVIGATION_STATUSES) {
            interactionStatuses.remove(interactionStatuses.keys.first())
        }
    }

    private fun requireSameOriginHttps(pageUrl: String, expectedUrl: String) {
        require(expectedUrl.length <= 2_048 && expectedUrl.none { it.code < 0x20 || it.code == 0x7f }) {
            "expectedUrl is outside the bounded URL contract"
        }
        require(httpsAuthority(pageUrl) == httpsAuthority(expectedUrl)) {
            "click expectedUrl must remain on the current HTTPS origin"
        }
    }

    private fun httpsAuthority(url: String): String {
        require(url.startsWith("https://")) { "only HTTPS action URLs are accepted" }
        val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        require(authority.isNotBlank() && '@' !in authority) { "URL host is required and credentials are forbidden" }
        return authority.lowercase()
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

    companion object {
        private const val MAX_NAVIGATION_STATUSES = 32
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
                    enabledByDefault = true,
                ),
            ),
        )
    }

    private data class BoundInteractionPlatform(
        val generation: Long,
        val platform: BrowserActionPlatform,
    )

    private data class StoredInteractionProposal(
        val proposal: BrowserActionProposal,
        val bindingGeneration: Long?,
    )

    private data class ActiveInteraction(
        val proposalId: String,
        val expectedUrl: String,
        val bindingGeneration: Long,
        val confirmedAtEpochMs: Long,
    )
}
