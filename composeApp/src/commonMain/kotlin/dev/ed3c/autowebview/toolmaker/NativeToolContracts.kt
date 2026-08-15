package dev.ed3c.autowebview.toolmaker

import dev.ed3c.autowebview.domain.ActionRisk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object NativeCapabilityIds {
    const val CAMERA_CAPTURE = "native.camera.capture"
    const val CURRENT_LOCATION = "native.location.current"
    const val DEVICE_STATE = "native.device.state"
    const val OPEN_DEEP_LINK = "native.deeplink.open"
}

@Serializable
enum class NativePermission {
    CAMERA,
    LOCATION_APPROXIMATE,
    LOCATION_PRECISE,
}

@Serializable
enum class NativeCapabilityAvailability {
    UNKNOWN,
    AVAILABLE,
    UNSUPPORTED,
    TEMPORARILY_UNAVAILABLE,
}

@Serializable
enum class NativeToolOperation {
    CAPTURE_PHOTO,
    GET_CURRENT_LOCATION,
    READ_DEVICE_STATE,
    OPEN_DEEP_LINK,
}

@Serializable
data class NativeToolDescriptor(
    val id: String,
    val operation: NativeToolOperation,
    val displayName: String,
    val description: String,
    val requiredPermissions: Set<NativePermission> = emptySet(),
    val maximumRisk: ActionRisk,
    val auditCategory: String,
) {
    init {
        require(id.isNotBlank()) { "Capability id cannot be blank" }
        require(displayName.isNotBlank()) { "Capability display name cannot be blank" }
        require(description.isNotBlank()) { "Capability description cannot be blank" }
        require(auditCategory.isNotBlank()) { "Capability audit category cannot be blank" }
    }
}

@Serializable
enum class CameraLens {
    SYSTEM_DEFAULT,
    FRONT,
    BACK,
}

@Serializable
enum class LocationAccuracy {
    APPROXIMATE,
    PRECISE,
}

@Serializable
enum class DeviceStateField {
    BATTERY_PERCENT,
    IS_CHARGING,
    NETWORK_CONNECTED,
    LOCALE,
    TIME_ZONE,
}

@Serializable
sealed interface NativeToolRequest {
    val requestId: String
    val capabilityId: String
    val operation: NativeToolOperation
    val risk: ActionRisk
}

@Serializable
@SerialName("capture_photo")
data class CapturePhotoRequest(
    override val requestId: String,
    override val capabilityId: String = NativeCapabilityIds.CAMERA_CAPTURE,
    val preferredLens: CameraLens = CameraLens.SYSTEM_DEFAULT,
    val includeMetadata: Boolean = false,
    override val operation: NativeToolOperation = NativeToolOperation.CAPTURE_PHOTO,
    override val risk: ActionRisk = ActionRisk.HIGH,
) : NativeToolRequest {
    init {
        require(requestId.isNotBlank()) { "Request id cannot be blank" }
    }
}

@Serializable
@SerialName("get_current_location")
data class GetCurrentLocationRequest(
    override val requestId: String,
    override val capabilityId: String = NativeCapabilityIds.CURRENT_LOCATION,
    val accuracy: LocationAccuracy = LocationAccuracy.APPROXIMATE,
    val maximumAgeMs: Long = 30_000,
    override val operation: NativeToolOperation = NativeToolOperation.GET_CURRENT_LOCATION,
    override val risk: ActionRisk = ActionRisk.MEDIUM,
) : NativeToolRequest {
    init {
        require(requestId.isNotBlank()) { "Request id cannot be blank" }
        require(maximumAgeMs >= 0) { "Maximum age cannot be negative" }
    }
}

@Serializable
@SerialName("read_device_state")
data class ReadDeviceStateRequest(
    override val requestId: String,
    override val capabilityId: String = NativeCapabilityIds.DEVICE_STATE,
    val fields: Set<DeviceStateField> = DeviceStateField.entries.toSet(),
    override val operation: NativeToolOperation = NativeToolOperation.READ_DEVICE_STATE,
    override val risk: ActionRisk = ActionRisk.READ_ONLY,
) : NativeToolRequest {
    init {
        require(requestId.isNotBlank()) { "Request id cannot be blank" }
    }
}

@Serializable
@SerialName("open_deep_link")
data class OpenDeepLinkRequest(
    override val requestId: String,
    override val capabilityId: String = NativeCapabilityIds.OPEN_DEEP_LINK,
    val uri: String,
    val expectedScheme: String? = null,
    override val operation: NativeToolOperation = NativeToolOperation.OPEN_DEEP_LINK,
    override val risk: ActionRisk = ActionRisk.MEDIUM,
) : NativeToolRequest {
    init {
        require(requestId.isNotBlank()) { "Request id cannot be blank" }
        require(uri.isNotBlank()) { "Deep-link URI cannot be blank" }
        require(uri.none(Char::isISOControl)) { "Deep-link URI cannot contain control characters" }
    }
}

@Serializable
sealed interface NativeToolResult {
    val requestId: String
    val capabilityId: String
}

@Serializable
@SerialName("photo_captured")
data class PhotoCapturedResult(
    override val requestId: String,
    override val capabilityId: String = NativeCapabilityIds.CAMERA_CAPTURE,
    val contentUri: String,
    val mediaType: String,
    val width: Int? = null,
    val height: Int? = null,
) : NativeToolResult

@Serializable
@SerialName("location_resolved")
data class LocationResolvedResult(
    override val requestId: String,
    override val capabilityId: String = NativeCapabilityIds.CURRENT_LOCATION,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val capturedAtEpochMs: Long,
) : NativeToolResult

@Serializable
data class DeviceStateSnapshot(
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val networkConnected: Boolean? = null,
    val locale: String? = null,
    val timeZone: String? = null,
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100) {
            "Battery percentage must be between 0 and 100"
        }
    }
}

@Serializable
@SerialName("device_state_resolved")
data class DeviceStateResolvedResult(
    override val requestId: String,
    override val capabilityId: String = NativeCapabilityIds.DEVICE_STATE,
    val snapshot: DeviceStateSnapshot,
) : NativeToolResult

@Serializable
@SerialName("deep_link_opened")
data class DeepLinkOpenedResult(
    override val requestId: String,
    override val capabilityId: String = NativeCapabilityIds.OPEN_DEEP_LINK,
    val opened: Boolean,
    val resolvedScheme: String? = null,
) : NativeToolResult

@Serializable
enum class NativeToolFailureCode {
    CANCELLED,
    PERMISSION_REVOKED,
    PLATFORM_UNAVAILABLE,
    INVALID_INPUT,
    TIMEOUT,
    EXECUTION_FAILED,
}

@Serializable
@SerialName("native_tool_failure")
data class NativeToolFailure(
    override val requestId: String,
    override val capabilityId: String,
    val code: NativeToolFailureCode,
    val message: String,
    val retryable: Boolean = false,
) : NativeToolResult
