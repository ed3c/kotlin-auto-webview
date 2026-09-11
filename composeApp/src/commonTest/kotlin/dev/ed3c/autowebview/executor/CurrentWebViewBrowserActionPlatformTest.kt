package dev.ed3c.autowebview.executor

import dev.ed3c.autowebview.domain.InteractiveElement
import dev.ed3c.autowebview.domain.PageContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CurrentWebViewBrowserActionPlatformTest {
    @Test
    fun exactCurrentTargetFillRequiresPlatformPostcondition() = runTest {
        var page = page()
        var script = ""
        val platform = CurrentWebViewBrowserActionPlatform(
            currentContext = { page },
            evaluator = BrowserScriptEvaluator {
                script = it
                """{"state":"APPLIED","code":""}"""
            },
        )
        val target = platform.resolve(BrowserTargetQuery(page.url, FINGERPRINT)).single()
        val result = platform.perform(
            BrowserActionCommand(
                proposalId = "proposal-1",
                pageUrl = page.url,
                targetExecutionToken = target.executionToken,
                targetFingerprint = FINGERPRINT,
                kind = BrowserActionKind.FILL_TEXT,
                payload = FillTextPayload("hello"),
            ),
            BrowserActionCancellationSignal { false },
        )

        assertIs<PlatformBrowserActionResult.Completed>(result)
        assertTrue("document.querySelectorAll" in script)
        assertTrue("\"kind\":\"FILL_TEXT\"" in script)
        assertTrue("querySelector(command" !in script)
    }

    @Test
    fun staleCaptureRejectsBeforeScriptEvaluation() = runTest {
        var page = page()
        var evaluations = 0
        val platform = CurrentWebViewBrowserActionPlatform(
            currentContext = { page },
            evaluator = BrowserScriptEvaluator {
                evaluations += 1
                """{"state":"APPLIED"}"""
            },
        )
        val target = platform.resolve(BrowserTargetQuery(page.url, FINGERPRINT)).single()
        page = page.copy(capturedAtEpochMs = page.capturedAtEpochMs + 1)

        val result = platform.perform(
            BrowserActionCommand(
                proposalId = "proposal-2",
                pageUrl = target.pageUrl,
                targetExecutionToken = target.executionToken,
                targetFingerprint = FINGERPRINT,
                kind = BrowserActionKind.SELECT_OPTION,
                payload = SelectOptionPayload("choice-b"),
            ),
            BrowserActionCancellationSignal { false },
        )

        assertIs<PlatformBrowserActionResult.Rejected>(result)
        assertEquals(0, evaluations)
    }

    @Test
    fun cancelledActionHasNoScriptSideEffect() = runTest {
        val page = page()
        var evaluations = 0
        val platform = CurrentWebViewBrowserActionPlatform(
            currentContext = { page },
            evaluator = BrowserScriptEvaluator {
                evaluations += 1
                """{"state":"APPLIED"}"""
            },
        )
        val target = platform.resolve(BrowserTargetQuery(page.url, FINGERPRINT)).single()

        val result = platform.perform(
            BrowserActionCommand(
                proposalId = "proposal-3",
                pageUrl = page.url,
                targetExecutionToken = target.executionToken,
                targetFingerprint = FINGERPRINT,
                kind = BrowserActionKind.FILL_TEXT,
                payload = FillTextPayload("never-dispatched"),
            ),
            BrowserActionCancellationSignal { true },
        )

        assertIs<PlatformBrowserActionResult.CancelledBeforeSideEffect>(result)
        assertEquals(0, evaluations)
    }

    @Test
    fun clickDispatchWaitsForFreshUrlObservation() = runTest {
        val page = page(tag = "a", role = "link")
        val platform = CurrentWebViewBrowserActionPlatform(
            currentContext = { page },
            evaluator = BrowserScriptEvaluator { """{"state":"DISPATCHED"}""" },
        )
        val target = platform.resolve(BrowserTargetQuery(page.url, FINGERPRINT)).single()

        val result = platform.perform(
            BrowserActionCommand(
                proposalId = "proposal-4",
                pageUrl = page.url,
                targetExecutionToken = target.executionToken,
                targetFingerprint = FINGERPRINT,
                kind = BrowserActionKind.CLICK,
                payload = ClickPayload,
                expectedUrl = "https://app.example.test/next",
            ),
            BrowserActionCancellationSignal { false },
        )

        assertIs<PlatformBrowserActionResult.DispatchedAwaitingObservation>(result)
    }

    private fun page(
        tag: String = "input",
        role: String? = "textbox",
    ) = PageContext(
        url = "https://app.example.test/form",
        title = "Fixture",
        markdown = "fixture",
        capturedAtEpochMs = 1_000,
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

    private companion object {
        const val FINGERPRINT = "deadbeef"
    }
}
