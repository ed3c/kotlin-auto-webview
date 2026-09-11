package dev.ed3c.autowebview.device.runtime

import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.policy.DistributionProfile
import dev.ed3c.autowebview.dispatcher.DispatcherEvent
import dev.ed3c.autowebview.dispatcher.LocalDispatcher
import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalDispatcherDeviceRuntimeAuthoritySourceTest {
    @Test
    fun confirmed_existing_dispatcher_execution_is_admitted_but_waiting_confirmation_is_not() = runTest {
        val dispatcher = LocalDispatcher()
        val source = source(dispatcher)

        assertTrue(source.snapshot().platformAvailable)
        assertFalse(source.snapshot().userInteractionActive)

        dispatcher.dispatch(
            DispatcherEvent.ActionProposed(
                action = action(risk = ActionRisk.MEDIUM),
                confirmationRequired = true,
            ),
        )
        assertFalse(source.snapshot().platformAvailable)

        dispatcher.dispatch(DispatcherEvent.ActionConfirmed)
        assertTrue(source.snapshot().platformAvailable)
        assertFalse(source.snapshot().userInteractionActive)

        dispatcher.dispatch(DispatcherEvent.UserInteractionStarted)
        val preempted = source.snapshot()
        assertTrue(preempted.userInteractionActive)
        assertFalse(preempted.platformAvailable)
    }

    @Test
    fun proposing_and_suspended_existing_dispatcher_states_fail_closed() = runTest {
        val proposingDispatcher = LocalDispatcher()
        proposingDispatcher.dispatch(
            DispatcherEvent.ActionProposed(
                action = action(risk = ActionRisk.LOW),
                confirmationRequired = false,
            ),
        )
        assertFalse(source(proposingDispatcher).snapshot().platformAvailable)

        val suspendedDispatcher = LocalDispatcher()
        suspendedDispatcher.dispatch(DispatcherEvent.Suspend("test-suspension"))
        val suspended = source(suspendedDispatcher).snapshot()
        assertFalse(suspended.platformAvailable)
        assertFalse(suspended.userInteractionActive)
    }

    private fun source(dispatcher: LocalDispatcher): DeviceRuntimeAuthoritySource {
        val base = DeviceRuntimeAuthoritySnapshot(
            authorityEpoch = "authority-1",
            compiledProfile = DistributionProfile.ENTERPRISE_SIDELOAD,
            policyVersion = "policy-v1",
            workflowId = "workflow-1",
            workflowRevision = 1,
            workflowDigestSha256 = "a".repeat(64),
            currentSubject = DeviceSubjectRef(
                packageName = "dev.ed3c.fixture",
                windowId = "window-1",
                displayId = "display-0",
                snapshotVersion = 1,
                capturedAtEpochMs = 1_000,
            ),
            enabledCapabilityIds = emptySet(),
            userInteractionActive = false,
            screenLocked = false,
            platformAvailable = true,
        )
        return LocalDispatcherDeviceRuntimeAuthoritySource(
            dispatcher = dispatcher,
            delegate = DeviceRuntimeAuthoritySource { base },
        )
    }

    private fun action(risk: ActionRisk) = AgentAction(
        id = "device-action-1",
        capabilityId = "device.fixture",
        name = "Device fixture",
        description = "Dispatcher authority fixture",
        risk = risk,
    )
}
