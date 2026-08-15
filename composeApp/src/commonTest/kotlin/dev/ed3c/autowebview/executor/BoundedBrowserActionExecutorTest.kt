package dev.ed3c.autowebview.executor

import dev.ed3c.autowebview.dispatcher.DispatcherMode
import dev.ed3c.autowebview.dispatcher.DispatcherSnapshot
import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import dev.ed3c.autowebview.domain.PageContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedBrowserActionExecutorTest {
    @Test
    fun exactConfirmedVisibleTargetExecutesTypedCommand() = runTest {
        val platform = FakePlatform()
        val result = BoundedBrowserActionExecutor(platform).execute(
            proposal = proposal(),
            context = context(),
        )

        assertIs<BrowserActionExecutionResult.Succeeded>(result)
        assertEquals(1, platform.performCalls)
        assertEquals("fingerprint-1", platform.lastCommand?.targetFingerprint)
        assertEquals(BrowserActionKind.CLICK, platform.lastCommand?.kind)
        assertEquals(BrowserExecutionState.SUCCEEDED, result.trace.last().state)
    }

    @Test
    fun dispatcherMustOwnTheExactExecutingAction() = runTest {
        val platform = FakePlatform()
        val executor = BoundedBrowserActionExecutor(platform)

        val notExecuting = executor.execute(
            proposal(),
            context(
                dispatcher = DispatcherSnapshot(
                    mode = DispatcherMode.WAITING_FOR_CONFIRMATION,
                    pendingAction = agentAction(),
                ),
            ),
        )
        assertEquals(
            BrowserExecutionFailureCode.DISPATCHER_NOT_EXECUTING,
            assertIs<BrowserActionExecutionResult.Rejected>(notExecuting).code,
        )

        val wrongAction = executor.execute(
            proposal(),
            context(
                dispatcher = executingDispatcher(
                    agentAction(id = "another-action"),
                ),
            ),
        )
        assertEquals(
            BrowserExecutionFailureCode.ACTION_ID_MISMATCH,
            assertIs<BrowserActionExecutionResult.Rejected>(wrongAction).code,
        )
        assertEquals(0, platform.resolveCalls)
    }

    @Test
    fun pageIdentityAndFreshnessAreRevalidated() = runTest {
        val executor = BoundedBrowserActionExecutor(FakePlatform())

        val wrongUrl = executor.execute(
            proposal(),
            context(page = page(url = "https://different.example")),
        )
        assertEquals(
            BrowserExecutionFailureCode.PAGE_URL_MISMATCH,
            assertIs<BrowserActionExecutionResult.Rejected>(wrongUrl).code,
        )

        val wrongSnapshot = executor.execute(
            proposal(),
            context(page = page(capturedAtEpochMs = 999)),
        )
        assertEquals(
            BrowserExecutionFailureCode.PAGE_SNAPSHOT_MISMATCH,
            assertIs<BrowserActionExecutionResult.Rejected>(wrongSnapshot).code,
        )

        val stale = executor.execute(
            proposal(maximumPageAgeMs = 50),
            context(nowEpochMs = 1_100),
        )
        assertEquals(
            BrowserExecutionFailureCode.PAGE_CONTEXT_STALE,
            assertIs<BrowserActionExecutionResult.Rejected>(stale).code,
        )
    }

    @Test
    fun confirmationMustMatchTheExactProposalAndRemainFresh() = runTest {
        val executor = BoundedBrowserActionExecutor(FakePlatform())

        val missing = executor.execute(
            proposal(),
            context(confirmation = null),
        )
        assertEquals(
            BrowserExecutionFailureCode.CONFIRMATION_REQUIRED,
            assertIs<BrowserActionExecutionResult.Rejected>(missing).code,
        )

        val mismatched = executor.execute(
            proposal(),
            context(confirmation = confirmation(proposalId = "other-proposal")),
        )
        assertEquals(
            BrowserExecutionFailureCode.CONFIRMATION_MISMATCH,
            assertIs<BrowserActionExecutionResult.Rejected>(mismatched).code,
        )

        val stale = executor.execute(
            proposal(maximumConfirmationAgeMs = 10),
            context(nowEpochMs = 1_100, confirmation = confirmation(confirmedAtEpochMs = 1_050)),
        )
        assertEquals(
            BrowserExecutionFailureCode.CONFIRMATION_STALE,
            assertIs<BrowserActionExecutionResult.Rejected>(stale).code,
        )
    }

    @Test
    fun unresolvedAndAmbiguousTargetsFailClosed() = runTest {
        val noTarget = BoundedBrowserActionExecutor(
            FakePlatform(resolved = emptyList()),
        ).execute(proposal(), context())
        assertEquals(
            BrowserExecutionFailureCode.TARGET_NOT_FOUND,
            assertIs<BrowserActionExecutionResult.Rejected>(noTarget).code,
        )

        val ambiguous = BoundedBrowserActionExecutor(
            FakePlatform(resolved = listOf(target(), target(executionToken = "target-2"))),
        ).execute(proposal(), context())
        assertEquals(
            BrowserExecutionFailureCode.TARGET_AMBIGUOUS,
            assertIs<BrowserActionExecutionResult.Rejected>(ambiguous).code,
        )
    }

    @Test
    fun targetSemanticsVisibilityAndEditabilityAreRevalidated() = runTest {
        suspend fun execute(target: ResolvedBrowserTarget, proposal: BrowserActionProposal = proposal()) =
            BoundedBrowserActionExecutor(FakePlatform(resolved = listOf(target))).execute(
                proposal,
                context(proposal = proposal),
            )

        assertEquals(
            BrowserExecutionFailureCode.TARGET_ROLE_MISMATCH,
            assertIs<BrowserActionExecutionResult.Rejected>(
                execute(target(role = "link")),
            ).code,
        )
        assertEquals(
            BrowserExecutionFailureCode.TARGET_NAME_MISMATCH,
            assertIs<BrowserActionExecutionResult.Rejected>(
                execute(target(accessibleName = "Different")),
            ).code,
        )
        assertEquals(
            BrowserExecutionFailureCode.TARGET_HIDDEN,
            assertIs<BrowserActionExecutionResult.Rejected>(
                execute(target(visible = false)),
            ).code,
        )
        assertEquals(
            BrowserExecutionFailureCode.TARGET_DISABLED,
            assertIs<BrowserActionExecutionResult.Rejected>(
                execute(target(enabled = false)),
            ).code,
        )

        val fillProposal = proposal(
            kind = BrowserActionKind.FILL_TEXT,
            payload = FillTextPayload("bounded text"),
        )
        assertEquals(
            BrowserExecutionFailureCode.TARGET_NOT_EDITABLE,
            assertIs<BrowserActionExecutionResult.Rejected>(
                execute(target(editable = false), fillProposal),
            ).code,
        )
    }

    @Test
    fun passwordPaymentSecretAndCrossOriginTargetsAreRejected() = runTest {
        val sensitivities = listOf(
            BrowserTargetSensitivity.PASSWORD,
            BrowserTargetSensitivity.PAYMENT,
            BrowserTargetSensitivity.SECRET,
            BrowserTargetSensitivity.CROSS_ORIGIN,
        )

        sensitivities.forEach { sensitivity ->
            val result = BoundedBrowserActionExecutor(
                FakePlatform(resolved = listOf(target(sensitivity = sensitivity))),
            ).execute(proposal(), context())
            assertEquals(
                BrowserExecutionFailureCode.SENSITIVE_TARGET,
                assertIs<BrowserActionExecutionResult.Rejected>(result).code,
            )
        }

        val passwordInput = BoundedBrowserActionExecutor(
            FakePlatform(resolved = listOf(target(inputType = "password"))),
        ).execute(proposal(), context())
        assertEquals(
            BrowserExecutionFailureCode.SENSITIVE_TARGET,
            assertIs<BrowserActionExecutionResult.Rejected>(passwordInput).code,
        )
    }

    @Test
    fun userInputPreemptsBeforeResolutionAndBeforeSideEffect() = runTest {
        var interacting = true
        val platform = FakePlatform()
        val beforeResolve = BoundedBrowserActionExecutor(platform).execute(
            proposal(),
            context(userInteraction = UserInteractionProbe { interacting }),
        )
        assertEquals(
            BrowserExecutionFailureCode.USER_PREEMPTED,
            assertIs<BrowserActionExecutionResult.Rejected>(beforeResolve).code,
        )
        assertEquals(0, platform.resolveCalls)

        interacting = false
        val afterResolvePlatform = FakePlatform(
            onResolve = { interacting = true },
        )
        val afterResolve = BoundedBrowserActionExecutor(afterResolvePlatform).execute(
            proposal(),
            context(userInteraction = UserInteractionProbe { interacting }),
        )
        assertIs<BrowserActionExecutionResult.Cancelled>(afterResolve)
        assertEquals(0, afterResolvePlatform.performCalls)
    }

    @Test
    fun platformCancellationSignalStopsBeforeSideEffect() = runTest {
        var interacting = false
        val platform = FakePlatform(
            onPerform = { signal ->
                interacting = true
                if (signal.isCancellationRequested()) {
                    PlatformBrowserActionResult.CancelledBeforeSideEffect
                } else {
                    PlatformBrowserActionResult.Completed
                }
            },
        )
        val result = BoundedBrowserActionExecutor(platform).execute(
            proposal(),
            context(userInteraction = UserInteractionProbe { interacting }),
        )

        assertIs<BrowserActionExecutionResult.Cancelled>(result)
        assertEquals(1, platform.performCalls)
    }

    @Test
    fun timeoutPreservesUnknownSideEffectStateAfterExecutionBegins() = runTest {
        val platform = FakePlatform(
            onPerform = {
                delay(10_000)
                PlatformBrowserActionResult.Completed
            },
        )
        val result = BoundedBrowserActionExecutor(platform).execute(
            proposal(timeoutMs = 1),
            context(),
        )

        val timeout = assertIs<BrowserActionExecutionResult.TimedOut>(result)
        assertEquals(BrowserSideEffectState.UNKNOWN, timeout.sideEffectState)
        assertEquals(BrowserExecutionState.TIMED_OUT, timeout.trace.last().state)
    }

    @Test
    fun platformFailureDoesNotLeakPlatformMessage() = runTest {
        val secretText = "private payload and endpoint"
        val platform = FakePlatform(
            performResult = PlatformBrowserActionResult.Failed(
                code = "renderer_failed",
                message = secretText,
                retryable = false,
                sideEffectState = BrowserSideEffectState.UNKNOWN,
            ),
        )
        val result = assertIs<BrowserActionExecutionResult.Failed>(
            BoundedBrowserActionExecutor(platform).execute(proposal(), context()),
        )

        assertTrue(result.message.contains("renderer_failed"))
        assertFalse(result.message.contains(secretText))
        assertEquals(BrowserSideEffectState.UNKNOWN, result.sideEffectState)
    }

    @Test
    fun proposalsReceiptsCommandsAndResultsRoundTripThroughSerialization() = runTest {
        val json = Json {
            classDiscriminator = "kindType"
            encodeDefaults = true
        }
        val proposal = proposal(
            kind = BrowserActionKind.FILL_TEXT,
            payload = FillTextPayload("hello"),
        )
        assertEquals(
            proposal,
            json.decodeFromString<BrowserActionProposal>(json.encodeToString(proposal)),
        )

        val receipt = confirmation()
        assertEquals(
            receipt,
            json.decodeFromString<BrowserActionConfirmationReceipt>(json.encodeToString(receipt)),
        )

        val result: BrowserActionExecutionResult = BoundedBrowserActionExecutor(
            FakePlatform(),
        ).execute(proposal(), context())
        assertEquals(
            result,
            json.decodeFromString<BrowserActionExecutionResult>(json.encodeToString(result)),
        )
    }

    private fun proposal(
        kind: BrowserActionKind = BrowserActionKind.CLICK,
        payload: BrowserActionPayload = ClickPayload,
        maximumPageAgeMs: Long = 500,
        maximumConfirmationAgeMs: Long = 500,
        timeoutMs: Long = 5_000,
    ) = BrowserActionProposal(
        id = "proposal-1",
        agentActionId = "agent-action-1",
        pageUrl = PAGE_URL,
        pageCapturedAtEpochMs = 1_000,
        targetFingerprint = "fingerprint-1",
        expectedRole = "button",
        expectedAccessibleName = "Continue",
        kind = kind,
        payload = payload,
        risk = ActionRisk.HIGH,
        createdAtEpochMs = 1_020,
        maximumPageAgeMs = maximumPageAgeMs,
        maximumConfirmationAgeMs = maximumConfirmationAgeMs,
        timeoutMs = timeoutMs,
    )

    private fun context(
        proposal: BrowserActionProposal = proposal(),
        page: PageContext = page(capturedAtEpochMs = proposal.pageCapturedAtEpochMs),
        dispatcher: DispatcherSnapshot = executingDispatcher(agentAction(id = proposal.agentActionId)),
        confirmation: BrowserActionConfirmationReceipt? = confirmation(
            proposalId = proposal.id,
            agentActionId = proposal.agentActionId,
            pageUrl = proposal.pageUrl,
            targetFingerprint = proposal.targetFingerprint,
        ),
        nowEpochMs: Long = 1_100,
        userInteraction: UserInteractionProbe = UserInteractionProbe { false },
    ) = BrowserActionExecutionContext(
        page = page,
        dispatcher = dispatcher,
        confirmation = confirmation,
        nowEpochMs = nowEpochMs,
        userInteraction = userInteraction,
    )

    private fun page(
        url: String = PAGE_URL,
        capturedAtEpochMs: Long = 1_000,
    ) = PageContext(
        url = url,
        title = "Fixture",
        markdown = "Fixture page",
        capturedAtEpochMs = capturedAtEpochMs,
    )

    private fun confirmation(
        proposalId: String = "proposal-1",
        agentActionId: String = "agent-action-1",
        pageUrl: String = PAGE_URL,
        targetFingerprint: String = "fingerprint-1",
        confirmedAtEpochMs: Long = 1_050,
    ) = BrowserActionConfirmationReceipt(
        proposalId = proposalId,
        agentActionId = agentActionId,
        pageUrl = pageUrl,
        targetFingerprint = targetFingerprint,
        confirmedAtEpochMs = confirmedAtEpochMs,
    )

    private fun agentAction(
        id: String = "agent-action-1",
    ) = AgentAction(
        id = id,
        capabilityId = "browser.interact",
        name = "Interact",
        description = "Fixture action",
        risk = ActionRisk.HIGH,
    )

    private fun executingDispatcher(
        action: AgentAction,
    ) = DispatcherSnapshot(
        mode = DispatcherMode.EXECUTING,
        pendingAction = action,
        reason = "Confirmed by user",
    )

    private fun target(
        executionToken: String = "target-1",
        pageUrl: String = PAGE_URL,
        fingerprint: String = "fingerprint-1",
        role: String? = "button",
        accessibleName: String = "Continue",
        inputType: String? = null,
        visible: Boolean = true,
        enabled: Boolean = true,
        editable: Boolean = true,
        sensitivity: BrowserTargetSensitivity = BrowserTargetSensitivity.NONE,
    ) = ResolvedBrowserTarget(
        executionToken = executionToken,
        pageUrl = pageUrl,
        fingerprint = fingerprint,
        role = role,
        accessibleName = accessibleName,
        tag = "BUTTON",
        inputType = inputType,
        visible = visible,
        enabled = enabled,
        editable = editable,
        sensitivity = sensitivity,
    )

    private class FakePlatform(
        var resolved: List<ResolvedBrowserTarget> = listOf(target()),
        var performResult: PlatformBrowserActionResult = PlatformBrowserActionResult.Completed,
        val onResolve: (() -> Unit)? = null,
        val onPerform: (suspend (BrowserActionCancellationSignal) -> PlatformBrowserActionResult)? = null,
    ) : BrowserActionPlatform {
        var resolveCalls: Int = 0
        var performCalls: Int = 0
        var lastCommand: BrowserActionCommand? = null

        override suspend fun resolve(query: BrowserTargetQuery): List<ResolvedBrowserTarget> {
            resolveCalls += 1
            onResolve?.invoke()
            return resolved
        }

        override suspend fun perform(
            command: BrowserActionCommand,
            cancellationSignal: BrowserActionCancellationSignal,
        ): PlatformBrowserActionResult {
            performCalls += 1
            lastCommand = command
            return onPerform?.invoke(cancellationSignal) ?: performResult
        }
    }

    private companion object {
        const val PAGE_URL = "https://example.com/checkout"

        fun target(
            executionToken: String = "target-1",
        ) = ResolvedBrowserTarget(
            executionToken = executionToken,
            pageUrl = PAGE_URL,
            fingerprint = "fingerprint-1",
            role = "button",
            accessibleName = "Continue",
            tag = "BUTTON",
            visible = true,
            enabled = true,
            editable = true,
        )
    }
}
