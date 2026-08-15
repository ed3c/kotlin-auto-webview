package dev.ed3c.autowebview.dispatcher

import dev.ed3c.autowebview.domain.AgentAction
import dev.ed3c.autowebview.domain.ActionRisk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@kotlinx.serialization.Serializable
enum class DispatcherMode {
    READY,
    OBSERVING_USER,
    PROPOSING,
    WAITING_FOR_CONFIRMATION,
    EXECUTING,
    SUSPENDED,
}

data class DispatcherSnapshot(
    val mode: DispatcherMode = DispatcherMode.READY,
    val pendingAction: AgentAction? = null,
    val reason: String = "",
)

sealed interface DispatcherEvent {
    data object UserInteractionStarted : DispatcherEvent
    data object UserInteractionEnded : DispatcherEvent
    data class ActionProposed(val action: AgentAction, val confirmationRequired: Boolean) : DispatcherEvent
    data object ActionConfirmed : DispatcherEvent
    data object ActionRejected : DispatcherEvent
    data object ActionCompleted : DispatcherEvent
    data class ActionFailed(val message: String) : DispatcherEvent
    data class Suspend(val reason: String) : DispatcherEvent
    data object Resume : DispatcherEvent
}

class LocalDispatcher {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DispatcherSnapshot())
    val state: StateFlow<DispatcherSnapshot> = mutableState.asStateFlow()

    suspend fun dispatch(event: DispatcherEvent): DispatcherSnapshot = mutex.withLock {
        val next = transition(mutableState.value, event)
        mutableState.value = next
        next
    }

    companion object {
        fun transition(current: DispatcherSnapshot, event: DispatcherEvent): DispatcherSnapshot = when (event) {
            DispatcherEvent.UserInteractionStarted -> DispatcherSnapshot(
                mode = DispatcherMode.OBSERVING_USER,
                reason = "User input always has priority",
            )
            DispatcherEvent.UserInteractionEnded -> current.copy(
                mode = DispatcherMode.READY,
                pendingAction = null,
                reason = "User interaction ended",
            )
            is DispatcherEvent.ActionProposed -> if (current.mode == DispatcherMode.OBSERVING_USER) {
                current.copy(reason = "Proposal deferred while user is interacting")
            } else if (event.confirmationRequired || event.action.risk >= ActionRisk.MEDIUM) {
                DispatcherSnapshot(
                    mode = DispatcherMode.WAITING_FOR_CONFIRMATION,
                    pendingAction = event.action,
                    reason = "Human confirmation required",
                )
            } else {
                DispatcherSnapshot(
                    mode = DispatcherMode.PROPOSING,
                    pendingAction = event.action,
                    reason = "Low-risk proposal is ready",
                )
            }
            DispatcherEvent.ActionConfirmed -> if (current.pendingAction != null) {
                current.copy(mode = DispatcherMode.EXECUTING, reason = "Confirmed by user")
            } else current
            DispatcherEvent.ActionRejected -> DispatcherSnapshot(
                mode = DispatcherMode.READY,
                reason = "Rejected by user",
            )
            DispatcherEvent.ActionCompleted -> DispatcherSnapshot(
                mode = DispatcherMode.READY,
                reason = "Action completed",
            )
            is DispatcherEvent.ActionFailed -> DispatcherSnapshot(
                mode = DispatcherMode.READY,
                reason = "Action failed: ${event.message}",
            )
            is DispatcherEvent.Suspend -> DispatcherSnapshot(
                mode = DispatcherMode.SUSPENDED,
                reason = event.reason,
            )
            DispatcherEvent.Resume -> DispatcherSnapshot(
                mode = DispatcherMode.READY,
                reason = "Resumed",
            )
        }
    }
}
