package dev.ed3c.autowebview.toolmaker

import dev.ed3c.autowebview.domain.ActionRisk
import kotlinx.serialization.Serializable

@Serializable
data class NativeToolPolicySnapshot(
    val enabledCapabilityIds: Set<String> = emptySet(),
    val grantedPermissions: Set<NativePermission> = emptySet(),
    val availabilityByCapability: Map<String, NativeCapabilityAvailability> = emptyMap(),
    val allowedDeepLinkSchemes: Set<String> = emptySet(),
)

@Serializable
enum class NativeAdmissionReason {
    UNKNOWN_CAPABILITY,
    DISABLED_BY_POLICY,
    OPERATION_MISMATCH,
    AVAILABILITY_UNKNOWN,
    UNSUPPORTED,
    TEMPORARILY_UNAVAILABLE,
    MISSING_PERMISSION,
    RISK_EXCEEDS_CEILING,
    INVALID_INPUT,
    SCHEME_NOT_ALLOWED,
}

data class AdmittedNativeToolCall internal constructor(
    val request: NativeToolRequest,
    val descriptor: NativeToolDescriptor,
)

sealed interface NativeToolAdmission {
    data class Ready(
        val call: AdmittedNativeToolCall,
    ) : NativeToolAdmission

    data class RequiresConfirmation(
        val call: AdmittedNativeToolCall,
        val reason: String,
    ) : NativeToolAdmission

    data class Denied(
        val reason: NativeAdmissionReason,
        val message: String,
        val missingPermissions: Set<NativePermission> = emptySet(),
    ) : NativeToolAdmission
}

interface NativeToolExecutor {
    suspend fun execute(call: AdmittedNativeToolCall): NativeToolResult
}

class NativeCapabilityRegistry(
    descriptors: List<NativeToolDescriptor>,
) {
    private val descriptorsById: Map<String, NativeToolDescriptor>

    init {
        val duplicateIds = descriptors
            .groupingBy(NativeToolDescriptor::id)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Duplicate native capability descriptors: ${duplicateIds.sorted().joinToString()}"
        }
        descriptorsById = descriptors.associateBy(NativeToolDescriptor::id)
    }

    fun all(): List<NativeToolDescriptor> = descriptorsById.values.sortedBy(NativeToolDescriptor::id)

    fun evaluate(
        request: NativeToolRequest,
        policy: NativeToolPolicySnapshot,
    ): NativeToolAdmission {
        val descriptor = descriptorsById[request.capabilityId]
            ?: return denied(
                NativeAdmissionReason.UNKNOWN_CAPABILITY,
                "Capability is not registered",
            )

        if (request.operation != descriptor.operation) {
            return denied(
                NativeAdmissionReason.OPERATION_MISMATCH,
                "Request operation does not match the capability descriptor",
            )
        }

        if (request.capabilityId !in policy.enabledCapabilityIds) {
            return denied(
                NativeAdmissionReason.DISABLED_BY_POLICY,
                "Capability is disabled by repository or user policy",
            )
        }

        when (policy.availabilityByCapability[request.capabilityId] ?: NativeCapabilityAvailability.UNKNOWN) {
            NativeCapabilityAvailability.UNKNOWN -> return denied(
                NativeAdmissionReason.AVAILABILITY_UNKNOWN,
                "Platform availability has not been proven",
            )
            NativeCapabilityAvailability.UNSUPPORTED -> return denied(
                NativeAdmissionReason.UNSUPPORTED,
                "Capability is unsupported on this platform",
            )
            NativeCapabilityAvailability.TEMPORARILY_UNAVAILABLE -> return denied(
                NativeAdmissionReason.TEMPORARILY_UNAVAILABLE,
                "Capability is temporarily unavailable",
            )
            NativeCapabilityAvailability.AVAILABLE -> Unit
        }

        validateRequest(request, policy)?.let { return it }

        val requiredPermissions = requiredPermissionsFor(request, descriptor)
        val missingPermissions = requiredPermissions - policy.grantedPermissions
        if (missingPermissions.isNotEmpty()) {
            return NativeToolAdmission.Denied(
                reason = NativeAdmissionReason.MISSING_PERMISSION,
                message = "Required platform permission is not granted",
                missingPermissions = missingPermissions,
            )
        }

        if (request.risk.ordinal > descriptor.maximumRisk.ordinal) {
            return denied(
                NativeAdmissionReason.RISK_EXCEEDS_CEILING,
                "Request risk exceeds the capability risk ceiling",
            )
        }

        val admitted = AdmittedNativeToolCall(request, descriptor)
        return when (request.risk) {
            ActionRisk.READ_ONLY, ActionRisk.LOW -> NativeToolAdmission.Ready(admitted)
            ActionRisk.MEDIUM -> NativeToolAdmission.RequiresConfirmation(
                admitted,
                "This native action changes application or operating-system state",
            )
            ActionRisk.HIGH, ActionRisk.DESTRUCTIVE -> NativeToolAdmission.RequiresConfirmation(
                admitted,
                "Sensitive native action requires explicit human approval",
            )
        }
    }

    private fun requiredPermissionsFor(
        request: NativeToolRequest,
        descriptor: NativeToolDescriptor,
    ): Set<NativePermission> = descriptor.requiredPermissions + when (request) {
        is GetCurrentLocationRequest -> when (request.accuracy) {
            LocationAccuracy.APPROXIMATE -> setOf(NativePermission.LOCATION_APPROXIMATE)
            LocationAccuracy.PRECISE -> setOf(NativePermission.LOCATION_PRECISE)
        }
        else -> emptySet()
    }

    private fun validateRequest(
        request: NativeToolRequest,
        policy: NativeToolPolicySnapshot,
    ): NativeToolAdmission.Denied? = when (request) {
        is OpenDeepLinkRequest -> {
            val scheme = request.uri.substringBefore(':', missingDelimiterValue = "").lowercase()
            when {
                scheme.isBlank() || !SCHEME_REGEX.matches(scheme) -> denied(
                    NativeAdmissionReason.INVALID_INPUT,
                    "Deep-link URI must contain a valid explicit scheme",
                )
                request.expectedScheme != null &&
                    request.expectedScheme.lowercase() != scheme -> denied(
                    NativeAdmissionReason.INVALID_INPUT,
                    "Deep-link scheme does not match the request contract",
                )
                scheme !in policy.allowedDeepLinkSchemes.map(String::lowercase).toSet() -> denied(
                    NativeAdmissionReason.SCHEME_NOT_ALLOWED,
                    "Deep-link scheme is not allowlisted",
                )
                else -> null
            }
        }
        else -> null
    }

    private fun denied(
        reason: NativeAdmissionReason,
        message: String,
    ) = NativeToolAdmission.Denied(reason, message)

    private companion object {
        val SCHEME_REGEX = Regex("[a-z][a-z0-9+.-]*")
    }
}

object NativeToolCatalog {
    fun defaultDescriptors(): List<NativeToolDescriptor> = listOf(
        NativeToolDescriptor(
            id = NativeCapabilityIds.CAMERA_CAPTURE,
            operation = NativeToolOperation.CAPTURE_PHOTO,
            displayName = "Capture photo",
            description = "Request one user-visible camera capture",
            requiredPermissions = setOf(NativePermission.CAMERA),
            maximumRisk = ActionRisk.HIGH,
            auditCategory = "native.camera",
        ),
        NativeToolDescriptor(
            id = NativeCapabilityIds.CURRENT_LOCATION,
            operation = NativeToolOperation.GET_CURRENT_LOCATION,
            displayName = "Read current location",
            description = "Request a bounded location snapshot",
            maximumRisk = ActionRisk.MEDIUM,
            auditCategory = "native.location",
        ),
        NativeToolDescriptor(
            id = NativeCapabilityIds.DEVICE_STATE,
            operation = NativeToolOperation.READ_DEVICE_STATE,
            displayName = "Read device state",
            description = "Read a bounded non-secret device-state snapshot",
            maximumRisk = ActionRisk.READ_ONLY,
            auditCategory = "native.device_state",
        ),
        NativeToolDescriptor(
            id = NativeCapabilityIds.OPEN_DEEP_LINK,
            operation = NativeToolOperation.OPEN_DEEP_LINK,
            displayName = "Open deep link",
            description = "Open one allowlisted deep-link scheme after confirmation",
            maximumRisk = ActionRisk.MEDIUM,
            auditCategory = "native.deep_link",
        ),
    )
}
