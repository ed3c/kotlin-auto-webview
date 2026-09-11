package dev.ed3c.autowebview.runtime

import dev.ed3c.autowebview.capability.PolicyDecision

fun interface BrowserNavigationPort {
    fun loadUrl(url: String)
}

enum class NavigationActionState {
    WAITING_FOR_CONFIRMATION,
    EXECUTING,
    APPLIED,
    NONE,
    UNKNOWN,
    REJECTED,
}

data class NavigationProposal(
    val proposalId: String,
    val decision: PolicyDecision,
)

data class NavigationActionStatus(
    val proposalId: String,
    val state: NavigationActionState,
    val reason: String,
)

internal data class BoundNavigationPort(
    val generation: Long,
    val port: BrowserNavigationPort,
)

internal data class ActiveNavigation(
    val proposalId: String,
    val expectedUrl: String,
    val bindingGeneration: Long,
    val confirmedAtEpochMs: Long,
)
