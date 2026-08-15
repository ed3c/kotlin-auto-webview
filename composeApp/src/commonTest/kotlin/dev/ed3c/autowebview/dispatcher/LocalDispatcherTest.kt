package dev.ed3c.autowebview.dispatcher

import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDispatcherTest {
    @Test
    fun userInteractionPreemptsAgent() {
        val next = LocalDispatcher.transition(
            DispatcherSnapshot(mode = DispatcherMode.EXECUTING),
            DispatcherEvent.UserInteractionStarted,
        )
        assertEquals(DispatcherMode.OBSERVING_USER, next.mode)
        assertEquals(null, next.pendingAction)
    }

    @Test
    fun mediumRiskWaitsForConfirmation() {
        val action = AgentAction("1", "browser.navigate", "Navigate", "Go", risk = ActionRisk.MEDIUM)
        val next = LocalDispatcher.transition(
            DispatcherSnapshot(),
            DispatcherEvent.ActionProposed(action, confirmationRequired = true),
        )
        assertEquals(DispatcherMode.WAITING_FOR_CONFIRMATION, next.mode)
        assertEquals(action, next.pendingAction)
    }
}
