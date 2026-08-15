package dev.ed3c.autowebview.capability

import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlin.test.Test
import kotlin.test.assertIs

class CapabilityRegistryTest {
    @Test
    fun navigationRequiresConfirmation() {
        val registry = AgentBrowserRuntime.defaultCapabilities()
        val decision = registry.evaluate(
            AgentAction("1", "browser.navigate", "Navigate", "Go", risk = ActionRisk.MEDIUM),
            emptySet(),
        )
        assertIs<PolicyDecision.RequiresConfirmation>(decision)
    }

    @Test
    fun disabledInteractionIsDenied() {
        val registry = AgentBrowserRuntime.defaultCapabilities()
        val decision = registry.evaluate(
            AgentAction("2", "browser.interact", "Click", "Click buy", risk = ActionRisk.HIGH),
            emptySet(),
        )
        assertIs<PolicyDecision.Denied>(decision)
    }
}
