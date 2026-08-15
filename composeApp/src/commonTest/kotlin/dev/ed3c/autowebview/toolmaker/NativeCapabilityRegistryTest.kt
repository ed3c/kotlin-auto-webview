package dev.ed3c.autowebview.toolmaker

import dev.ed3c.autowebview.domain.ActionRisk
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeCapabilityRegistryTest {
    private val registry = NativeCapabilityRegistry(NativeToolCatalog.defaultDescriptors())

    @Test
    fun unknownCapabilityFailsClosed() {
        val request = ReadDeviceStateRequest(
            requestId = "request-unknown",
            capabilityId = "native.unknown",
        )

        val denied = assertIs<NativeToolAdmission.Denied>(
            registry.evaluate(request, NativeToolPolicySnapshot()),
        )

        assertEquals(NativeAdmissionReason.UNKNOWN_CAPABILITY, denied.reason)
    }

    @Test
    fun capabilityListingDoesNotEnableExecution() {
        assertTrue(registry.all().isNotEmpty())

        val denied = assertIs<NativeToolAdmission.Denied>(
            registry.evaluate(
                ReadDeviceStateRequest("request-disabled"),
                NativeToolPolicySnapshot(
                    availabilityByCapability = mapOf(
                        NativeCapabilityIds.DEVICE_STATE to NativeCapabilityAvailability.AVAILABLE,
                    ),
                ),
            ),
        )

        assertEquals(NativeAdmissionReason.DISABLED_BY_POLICY, denied.reason)
    }

    @Test
    fun unprovenPlatformAvailabilityFailsClosed() {
        val denied = assertIs<NativeToolAdmission.Denied>(
            registry.evaluate(
                ReadDeviceStateRequest("request-availability"),
                enabledPolicy(NativeCapabilityIds.DEVICE_STATE),
            ),
        )

        assertEquals(NativeAdmissionReason.AVAILABILITY_UNKNOWN, denied.reason)
    }

    @Test
    fun cameraRequiresPlatformPermission() {
        val denied = assertIs<NativeToolAdmission.Denied>(
            registry.evaluate(
                CapturePhotoRequest("request-camera"),
                availablePolicy(NativeCapabilityIds.CAMERA_CAPTURE),
            ),
        )

        assertEquals(NativeAdmissionReason.MISSING_PERMISSION, denied.reason)
        assertEquals(setOf(NativePermission.CAMERA), denied.missingPermissions)
    }

    @Test
    fun preciseLocationRequiresPrecisePermission() {
        val denied = assertIs<NativeToolAdmission.Denied>(
            registry.evaluate(
                GetCurrentLocationRequest(
                    requestId = "request-location",
                    accuracy = LocationAccuracy.PRECISE,
                ),
                availablePolicy(
                    NativeCapabilityIds.CURRENT_LOCATION,
                    permissions = setOf(NativePermission.LOCATION_APPROXIMATE),
                ),
            ),
        )

        assertEquals(NativeAdmissionReason.MISSING_PERMISSION, denied.reason)
        assertEquals(setOf(NativePermission.LOCATION_PRECISE), denied.missingPermissions)
    }

    @Test
    fun mismatchedOperationFailsClosed() {
        val denied = assertIs<NativeToolAdmission.Denied>(
            registry.evaluate(
                ReadDeviceStateRequest(
                    requestId = "request-mismatch",
                    capabilityId = NativeCapabilityIds.CAMERA_CAPTURE,
                ),
                availablePolicy(NativeCapabilityIds.CAMERA_CAPTURE),
            ),
        )

        assertEquals(NativeAdmissionReason.OPERATION_MISMATCH, denied.reason)
    }

    @Test
    fun overRiskRequestFailsClosed() {
        val denied = assertIs<NativeToolAdmission.Denied>(
            registry.evaluate(
                CapturePhotoRequest(
                    requestId = "request-over-risk",
                    risk = ActionRisk.DESTRUCTIVE,
                ),
                availablePolicy(
                    NativeCapabilityIds.CAMERA_CAPTURE,
                    permissions = setOf(NativePermission.CAMERA),
                ),
            ),
        )

        assertEquals(NativeAdmissionReason.RISK_EXCEEDS_CEILING, denied.reason)
    }

    @Test
    fun readOnlyDeviceStateCanBeAdmittedWithoutConfirmation() {
        val ready = assertIs<NativeToolAdmission.Ready>(
            registry.evaluate(
                ReadDeviceStateRequest("request-state"),
                availablePolicy(NativeCapabilityIds.DEVICE_STATE),
            ),
        )

        assertEquals(NativeCapabilityIds.DEVICE_STATE, ready.call.descriptor.id)
    }

    @Test
    fun stateChangingLocationRequestRequiresConfirmation() {
        val confirmation = assertIs<NativeToolAdmission.RequiresConfirmation>(
            registry.evaluate(
                GetCurrentLocationRequest("request-location-confirm"),
                availablePolicy(
                    NativeCapabilityIds.CURRENT_LOCATION,
                    permissions = setOf(NativePermission.LOCATION_APPROXIMATE),
                ),
            ),
        )

        assertEquals(NativeCapabilityIds.CURRENT_LOCATION, confirmation.call.descriptor.id)
    }

    @Test
    fun deepLinkRequiresAnExplicitAllowlistedScheme() {
        val blockedScheme = assertIs<NativeToolAdmission.Denied>(
            registry.evaluate(
                OpenDeepLinkRequest(
                    requestId = "request-link",
                    uri = "https://example.com/path",
                ),
                availablePolicy(NativeCapabilityIds.OPEN_DEEP_LINK),
            ),
        )
        assertEquals(NativeAdmissionReason.SCHEME_NOT_ALLOWED, blockedScheme.reason)

        val confirmation = assertIs<NativeToolAdmission.RequiresConfirmation>(
            registry.evaluate(
                OpenDeepLinkRequest(
                    requestId = "request-link-allowed",
                    uri = "https://example.com/path",
                    expectedScheme = "https",
                ),
                availablePolicy(
                    NativeCapabilityIds.OPEN_DEEP_LINK,
                    allowedDeepLinkSchemes = setOf("https"),
                ),
            ),
        )
        assertEquals(NativeCapabilityIds.OPEN_DEEP_LINK, confirmation.call.descriptor.id)
    }

    @Test
    fun descriptorRequiresAnAuditCategoryAndUniqueId() {
        assertFailsWith<IllegalArgumentException> {
            NativeToolDescriptor(
                id = "native.invalid",
                operation = NativeToolOperation.READ_DEVICE_STATE,
                displayName = "Invalid",
                description = "Missing audit category",
                maximumRisk = ActionRisk.READ_ONLY,
                auditCategory = "",
            )
        }

        val descriptor = NativeToolCatalog.defaultDescriptors().first()
        assertFailsWith<IllegalArgumentException> {
            NativeCapabilityRegistry(listOf(descriptor, descriptor))
        }
    }

    @Test
    fun typedRequestsAndResultsRoundTripThroughSerialization() {
        val json = Json {
            classDiscriminator = "kind"
            encodeDefaults = true
        }
        val request: NativeToolRequest = GetCurrentLocationRequest(
            requestId = "request-round-trip",
            accuracy = LocationAccuracy.PRECISE,
            maximumAgeMs = 5_000,
        )
        val encodedRequest = json.encodeToString(request)
        assertEquals(request, json.decodeFromString<NativeToolRequest>(encodedRequest))

        val result: NativeToolResult = LocationResolvedResult(
            requestId = request.requestId,
            latitude = 25.033,
            longitude = 121.5654,
            accuracyMeters = 12.0,
            capturedAtEpochMs = 1_700_000_000_000,
        )
        val encodedResult = json.encodeToString(result)
        assertEquals(result, json.decodeFromString<NativeToolResult>(encodedResult))
    }

    private fun enabledPolicy(capabilityId: String) = NativeToolPolicySnapshot(
        enabledCapabilityIds = setOf(capabilityId),
    )

    private fun availablePolicy(
        capabilityId: String,
        permissions: Set<NativePermission> = emptySet(),
        allowedDeepLinkSchemes: Set<String> = emptySet(),
    ) = NativeToolPolicySnapshot(
        enabledCapabilityIds = setOf(capabilityId),
        grantedPermissions = permissions,
        availabilityByCapability = mapOf(
            capabilityId to NativeCapabilityAvailability.AVAILABLE,
        ),
        allowedDeepLinkSchemes = allowedDeepLinkSchemes,
    )
}
