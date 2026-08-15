package dev.ed3c.autowebview.capability

import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import dev.ed3c.autowebview.domain.CapabilityDescriptor

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data class RequiresConfirmation(val reason: String) : PolicyDecision
    data class Denied(val reason: String) : PolicyDecision
}

class CapabilityRegistry(
    descriptors: List<CapabilityDescriptor> = emptyList(),
) {
    private val capabilities = descriptors.associateBy(CapabilityDescriptor::id).toMutableMap()
    private val enabled = descriptors.filter(CapabilityDescriptor::enabledByDefault).mapTo(mutableSetOf()) { it.id }

    fun register(descriptor: CapabilityDescriptor) {
        require(descriptor.id.isNotBlank()) { "Capability id cannot be blank" }
        require(descriptor.id !in capabilities) { "Duplicate capability: ${descriptor.id}" }
        capabilities[descriptor.id] = descriptor
        if (descriptor.enabledByDefault) enabled += descriptor.id
    }

    fun setEnabled(id: String, value: Boolean) {
        require(id in capabilities) { "Unknown capability: $id" }
        if (value) enabled += id else enabled -= id
    }

    fun all(): List<CapabilityDescriptor> = capabilities.values.sortedBy { it.id }

    fun evaluate(action: AgentAction, grantedPermissions: Set<String>): PolicyDecision {
        val capability = capabilities[action.capabilityId]
            ?: return PolicyDecision.Denied("Capability is not registered")
        if (action.capabilityId !in enabled) return PolicyDecision.Denied("Capability is disabled")
        val missing = capability.requiredPermissions - grantedPermissions
        if (missing.isNotEmpty()) return PolicyDecision.Denied("Missing permissions: ${missing.sorted().joinToString()}")
        if (action.risk > capability.maximumRisk) return PolicyDecision.Denied("Action exceeds capability risk ceiling")
        return when (action.risk) {
            ActionRisk.READ_ONLY, ActionRisk.LOW -> PolicyDecision.Allowed
            ActionRisk.MEDIUM -> PolicyDecision.RequiresConfirmation("This action changes browser state")
            ActionRisk.HIGH, ActionRisk.DESTRUCTIVE -> PolicyDecision.RequiresConfirmation("Sensitive action requires explicit approval")
        }
    }
}
