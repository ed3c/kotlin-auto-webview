package dev.ed3c.autowebview.runtime

import dev.ed3c.autowebview.domain.InteractiveElement
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.executor.BrowserActionCancellationSignal
import dev.ed3c.autowebview.executor.BrowserActionCommand
import dev.ed3c.autowebview.executor.BrowserActionKind
import dev.ed3c.autowebview.executor.BrowserActionPlatform
import dev.ed3c.autowebview.executor.BrowserTargetQuery
import dev.ed3c.autowebview.executor.ClickPayload
import dev.ed3c.autowebview.executor.FillTextPayload
import dev.ed3c.autowebview.executor.PlatformBrowserActionResult
import dev.ed3c.autowebview.executor.ResolvedBrowserTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class AgentBrowserRuntimeInteractionTest {
    @Test
    fun confirmedFillExecutesOnceAndDoesNotExposeValue() = runTest {
        val runtime = AgentBrowserRuntime()
        val platform = FakePlatform(PlatformBrowserActionResult.Completed)
        runtime.bindInteractionPlatform(platform)
        runtime.onPageContext(page())

        val proposal = runtime.proposeInteraction(
            kind = BrowserActionKind.FILL_TEXT,
            targetFingerprint = FINGERPRINT,
            value = SECRET_VALUE,
        )
        assertEquals(NavigationActionState.WAITING_FOR_CONFIRMATION, runtime.actionStatus(proposal.proposalId)?.state)
        assertTrue(runtime.dispatcherState.value.pendingAction?.arguments?.values?.none { SECRET_VALUE in it } == true)

        runtime.confirmPendingAction()

        assertEquals(1, platform.performCount)
        assertIs<FillTextPayload>(platform.lastCommand?.payload)
        assertEquals(NavigationActionState.APPLIED, runtime.actionStatus(proposal.proposalId)?.state)
        assertTrue(runtime.auditEvents.value.none { event ->
            SECRET_VALUE in event.message || event.metadata.values.any { SECRET_VALUE in it }
        })
    }

    @Test
    fun userPreemptionBeforeConfirmationDispatchesNothing() = runTest {
        val runtime = AgentBrowserRuntime()
        val platform = FakePlatform(PlatformBrowserActionResult.Completed)
        runtime.bindInteractionPlatform(platform)
        runtime.onPageContext(page())
        val proposal = runtime.proposeInteraction(
            BrowserActionKind.SELECT_OPTION,
            FINGERPRINT,
            value = "choice-b",
        )

        runtime.userInteractionStarted()
        runtime.confirmPendingAction()

        assertEquals(0, platform.performCount)
        assertEquals(NavigationActionState.REJECTED, runtime.actionStatus(proposal.proposalId)?.state)
    }

    @Test
    fun clickNeedsSameOriginAndFreshExactUrlBeforeApplied() = runTest {
        val runtime = AgentBrowserRuntime()
        val platform = FakePlatform(PlatformBrowserActionResult.DispatchedAwaitingObservation)
        runtime.bindInteractionPlatform(platform)
        runtime.onPageContext(page(tag = "a", role = "link"))

        assertFailsWith<IllegalArgumentException> {
            runtime.proposeInteraction(
                BrowserActionKind.CLICK,
                FINGERPRINT,
                expectedUrl = "https://other.example.test/next",
            )
        }

        val proposal = runtime.proposeInteraction(
            BrowserActionKind.CLICK,
            FINGERPRINT,
            expectedUrl = "https://app.example.test/next",
        )
        runtime.confirmPendingAction()
        assertEquals(NavigationActionState.EXECUTING, runtime.actionStatus(proposal.proposalId)?.state)

        runtime.onPageContext(
            page(
                url = "https://app.example.test/next",
                tag = "a",
                role = "link",
                capturedAtEpochMs = Long.MAX_VALUE,
            ),
        )
        assertEquals(NavigationActionState.APPLIED, runtime.actionStatus(proposal.proposalId)?.state)
        assertIs<ClickPayload>(platform.lastCommand?.payload)
    }

    private fun page(
        url: String = "https://app.example.test/form",
        tag: String = "input",
        role: String? = "textbox",
        capturedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    ) = PageContext(
        url = url,
        title = "Fixture",
        markdown = "fixture",
        capturedAtEpochMs = capturedAtEpochMs,
        interactiveElements = listOf(
            InteractiveElement(
                fingerprint = FINGERPRINT,
                tag = tag,
                role = role,
                accessibleName = "Target",
                inputType = if (tag == "input") "text" else null,
            ),
        ),
    )

    private class FakePlatform(
        private val result: PlatformBrowserActionResult,
    ) : BrowserActionPlatform {
        var performCount = 0
        var lastCommand: BrowserActionCommand? = null

        override suspend fun resolve(query: BrowserTargetQuery) = listOf(
            ResolvedBrowserTarget(
                executionToken = "opaque-token",
                pageUrl = query.pageUrl,
                fingerprint = query.fingerprint,
                role = query.expectedRole,
                accessibleName = query.expectedAccessibleName.orEmpty(),
                tag = "input",
                inputType = "text",
                visible = true,
                enabled = true,
                editable = true,
            ),
        )

        override suspend fun perform(
            command: BrowserActionCommand,
            cancellationSignal: BrowserActionCancellationSignal,
        ): PlatformBrowserActionResult {
            performCount += 1
            lastCommand = command
            return result
        }
    }

    private companion object {
        const val FINGERPRINT = "deadbeef"
        const val SECRET_VALUE = "super-secret-fill"
    }
}
