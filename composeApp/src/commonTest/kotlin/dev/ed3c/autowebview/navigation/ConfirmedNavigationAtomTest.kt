package dev.ed3c.autowebview.navigation

import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfirmedNavigationAtomTest {
    @Test
    fun sameUrlTwiceYieldsDistinctProposalIds() = runTest {
        val runtime = AgentBrowserRuntime(
            sessionId = "test-session",
            observationTimeoutMs = 50,
        )
        val first = assertIs<AgentBrowserRuntime.ProposeNavigationResult.Accepted>(
            runtime.proposeNavigation("https://example.com/page"),
        )
        val second = assertIs<AgentBrowserRuntime.ProposeNavigationResult.Accepted>(
            runtime.proposeNavigation("https://example.com/page"),
        )
        assertNotEquals(first.proposalId, second.proposalId)
        assertEquals(first.normalizedUrl, second.normalizedUrl)
    }

    @Test
    fun wrongProposalConfirmDoesNotCallPort() = runTest {
        val port = CountingPort()
        val runtime = testRuntime()
        runtime.bindNavigationPort(port, urlObserver = { "https://example.com/done" })
        val accepted = assertIs<AgentBrowserRuntime.ProposeNavigationResult.Accepted>(
            runtime.proposeNavigation("https://example.com/done"),
        )
        runtime.confirmNavigation("not-the-pending-proposal")
        assertEquals(0, port.calls)
        assertEquals(BrowserActionStatus.WAITING_FOR_CONFIRMATION, runtime.actionStatus(accepted.proposalId))
        assertEquals(BrowserActionStatus.NONE, runtime.actionStatus("not-the-pending-proposal"))
    }

    @Test
    fun confirmThenReplaceWebViewDoesNotCallOldPort() = runTest {
        val oldPort = CountingPort()
        val newPort = CountingPort()
        val runtime = testRuntime()
        val oldGeneration = runtime.bindNavigationPort(
            oldPort,
            urlObserver = { "https://example.com/target" },
        )
        val accepted = assertIs<AgentBrowserRuntime.ProposeNavigationResult.Accepted>(
            runtime.proposeNavigation("https://example.com/target"),
        )
        runtime.unbindNavigationPort(oldGeneration)
        runtime.bindNavigationPort(
            newPort,
            urlObserver = { "https://example.com/target" },
        )
        runtime.confirmPendingAction()
        assertEquals(0, oldPort.calls)
        assertEquals(1, newPort.calls)
        assertEquals(BrowserActionStatus.APPLIED, runtime.actionStatus(accepted.proposalId))
    }

    @Test
    fun httpCredentialsAndControlCharsAreRejected() = runTest {
        val runtime = testRuntime()
        assertIs<AgentBrowserRuntime.ProposeNavigationResult.Invalid>(
            runtime.proposeNavigation("http://example.com"),
        )
        assertIs<AgentBrowserRuntime.ProposeNavigationResult.Invalid>(
            runtime.proposeNavigation("https://user:secret@example.com/"),
        )
        assertIs<AgentBrowserRuntime.ProposeNavigationResult.Invalid>(
            runtime.proposeNavigation("https://example.com/\u0001path"),
        )
    }

    @Test
    fun userPreemptionYieldsZeroSideEffects() = runTest {
        val port = CountingPort()
        val runtime = testRuntime()
        runtime.bindNavigationPort(port, urlObserver = { "https://example.com/x" })
        val accepted = assertIs<AgentBrowserRuntime.ProposeNavigationResult.Accepted>(
            runtime.proposeNavigation("https://example.com/x"),
        )
        runtime.userInteractionStarted()
        runtime.confirmNavigation(accepted.proposalId)
        assertEquals(0, port.calls)
        assertEquals(BrowserActionStatus.REJECTED, runtime.actionStatus(accepted.proposalId))
    }

    @Test
    fun dispatchExceptionIsUnknownNotNoneOrApplied() = runTest {
        val port = object : BrowserNavigationPort {
            override suspend fun navigate(command: NavigationCommand): NavigationDispatchResult {
                error("simulated dispatch crash")
            }
        }
        val runtime = testRuntime()
        runtime.bindNavigationPort(port, urlObserver = { "https://example.com/crash" })
        val accepted = assertIs<AgentBrowserRuntime.ProposeNavigationResult.Accepted>(
            runtime.proposeNavigation("https://example.com/crash"),
        )
        runtime.confirmNavigation(accepted.proposalId)
        val status = runtime.actionStatus(accepted.proposalId)
        assertEquals(BrowserActionStatus.UNKNOWN, status)
        assertTrue(status != BrowserActionStatus.NONE)
        assertTrue(status != BrowserActionStatus.APPLIED)
    }

    @Test
    fun loadUrlAcceptanceAloneIsNotAppliedWithoutObservation() = runTest {
        val port = CountingPort()
        val runtime = testRuntime(observationTimeoutMs = 40)
        runtime.bindNavigationPort(port, urlObserver = { null })
        val accepted = assertIs<AgentBrowserRuntime.ProposeNavigationResult.Accepted>(
            runtime.proposeNavigation("https://example.com/observe"),
        )
        runtime.confirmNavigation(accepted.proposalId)
        assertEquals(1, port.calls)
        assertEquals(BrowserActionStatus.UNKNOWN, runtime.actionStatus(accepted.proposalId))
    }

    @Test
    fun replacedBindingGenerationRejectsWithoutSideEffect() = runTest {
        var currentGen: Long? = 2L
        var loads = 0
        val port = BoundWebViewNavigationPort(
            bindingGeneration = 1L,
            currentBindingGeneration = { currentGen },
            loadUrl = { loads += 1 },
        )
        val rejected = port.navigate(
            NavigationCommand(
                proposalId = "p1",
                httpsUrl = "https://example.com/",
                bindingGeneration = 1L,
            ),
        )
        assertIs<NavigationDispatchResult.Rejected>(rejected)
        assertEquals(0, loads)
        assertEquals(0, port.sideEffectCount)
    }

    private fun testRuntime(observationTimeoutMs: Long = 200) = AgentBrowserRuntime(
        sessionId = "atom-test-session",
        observationTimeoutMs = observationTimeoutMs,
        observationPollMs = 5,
    )

    private class CountingPort(
        private val result: NavigationDispatchResult = NavigationDispatchResult.Accepted,
    ) : BrowserNavigationPort {
        var calls: Int = 0
            private set

        override suspend fun navigate(command: NavigationCommand): NavigationDispatchResult {
            calls += 1
            return result
        }
    }
}
