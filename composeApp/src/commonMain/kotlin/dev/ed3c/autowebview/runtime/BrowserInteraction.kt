package dev.ed3c.autowebview.runtime

import dev.ed3c.autowebview.capability.PolicyDecision

data class BrowserInteractionProposal(
    val proposalId: String,
    val decision: PolicyDecision,
)
