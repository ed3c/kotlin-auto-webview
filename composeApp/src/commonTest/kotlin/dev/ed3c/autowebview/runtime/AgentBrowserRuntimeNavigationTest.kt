package dev.ed3c.autowebview.runtime

import dev.ed3c.autowebview.dispatcher.DispatcherMode
import dev.ed3c.autowebview.domain.PageContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AgentBrowserRuntimeNavigationTest {
    @Test
    fun confirmedNavigationDispatchesOnceAndCompletesOnlyAfterFreshExactObservation() = runTest {
        val runtime = AgentBrowserRuntime()
        val loaded = mutableListOf<String>()
        runtime.bindNavigationPort(BrowserNavigationPort(loaded::add))
        val proposal = runtime.proposeNavigation("https://fixture.test/next")

        assertEquals(NavigationActionState.WAITING_FOR_CONFIRMATION, runtime.navigationStatus(proposal.proposalId)?.state)
        runtime.confirmPendingAction()

        assertEquals(listOf("https://fixture.test/next"), loaded)
        assertEquals(NavigationActionState.EXECUTING, runtime.navigationStatus(proposal.proposalId)?.state)
        runtime.onPageContext(page("https://fixture.test/next"))
        assertEquals(NavigationActionState.APPLIED, runtime.navigationStatus(proposal.proposalId)?.state)
        assertEquals(DispatcherMode.READY, runtime.dispatcherState.value.mode)
    }

    @Test
    fun sameUrlGetsDistinctProposalIdentity() = runTest {
        val runtime = AgentBrowserRuntime()
        val first = runtime.proposeNavigation("https://fixture.test/same")
        runtime.rejectPendingAction()
        val second = runtime.proposeNavigation("https://fixture.test/same")

        assertNotEquals(first.proposalId, second.proposalId)
    }

    @Test
    fun missingOrStaleBindingCannotDispatch() = runTest {
        val runtime = AgentBrowserRuntime()
        var dispatches = 0
        val generation = runtime.bindNavigationPort(BrowserNavigationPort { dispatches += 1 })
        val proposal = runtime.proposeNavigation("https://fixture.test/unbound")
        runtime.unbindNavigationPort(generation)

        runtime.confirmPendingAction()

        assertEquals(0, dispatches)
        assertEquals(NavigationActionState.NONE, runtime.navigationStatus(proposal.proposalId)?.state)
    }

    @Test
    fun userInputPreemptsPendingNavigation() = runTest {
        val runtime = AgentBrowserRuntime()
        var dispatches = 0
        runtime.bindNavigationPort(BrowserNavigationPort { dispatches += 1 })
        val proposal = runtime.proposeNavigation("https://fixture.test/preempted")

        runtime.userInteractionStarted()
        runtime.confirmPendingAction()

        assertEquals(0, dispatches)
        assertEquals(NavigationActionState.REJECTED, runtime.navigationStatus(proposal.proposalId)?.state)
    }

    @Test
    fun wrongFreshObservationProducesUnknownWithoutRetry() = runTest {
        val runtime = AgentBrowserRuntime()
        var dispatches = 0
        runtime.bindNavigationPort(BrowserNavigationPort { dispatches += 1 })
        val proposal = runtime.proposeNavigation("https://fixture.test/expected")
        runtime.confirmPendingAction()

        runtime.onPageContext(page("https://fixture.test/unexpected"))

        assertEquals(1, dispatches)
        assertEquals(NavigationActionState.UNKNOWN, runtime.navigationStatus(proposal.proposalId)?.state)
        assertEquals(DispatcherMode.READY, runtime.dispatcherState.value.mode)
    }

    private fun page(url: String) = PageContext(
        url = url,
        title = "Fixture",
        markdown = "fixture",
        capturedAtEpochMs = Long.MAX_VALUE,
    )
}
