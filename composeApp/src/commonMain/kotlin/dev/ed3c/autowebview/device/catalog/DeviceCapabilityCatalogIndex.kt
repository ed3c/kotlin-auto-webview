package dev.ed3c.autowebview.device.catalog

class DeviceCapabilityCatalog(
    descriptors: List<DeviceCapabilityDescriptor>,
) {
    private val byCapabilityId: Map<DeviceCapabilityId, DeviceCapabilityDescriptor>
    private val byCanonicalActionId: Map<String, DeviceCapabilityDescriptor>

    init {
        val capabilityIndex = descriptors.associateBy(DeviceCapabilityDescriptor::id)
        require(capabilityIndex.size == descriptors.size) { "Duplicate device capability id" }

        val actionPairs = descriptors.flatMap { descriptor ->
            descriptor.canonicalActionIds.map { actionId -> actionId to descriptor }
        }
        require(actionPairs.map { it.first }.toSet().size == actionPairs.size) {
            "Canonical action id must have exactly one capability owner"
        }

        byCapabilityId = capabilityIndex
        byCanonicalActionId = actionPairs.toMap()
    }

    fun capability(id: DeviceCapabilityId): DeviceCapabilityDescriptor? = byCapabilityId[id]

    fun capabilityForCanonicalAction(actionId: String): DeviceCapabilityDescriptor? =
        byCanonicalActionId[actionId]

    fun containsCanonicalAction(actionId: String): Boolean = actionId in byCanonicalActionId

    val capabilities: List<DeviceCapabilityDescriptor>
        get() = byCapabilityId.values.sortedBy { it.id.value }
}
