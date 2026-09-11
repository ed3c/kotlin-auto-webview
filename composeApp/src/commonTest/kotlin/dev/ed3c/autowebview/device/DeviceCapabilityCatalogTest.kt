package dev.ed3c.autowebview.device

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityCatalog
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityDescriptor
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityScope
import dev.ed3c.autowebview.device.catalog.DevicePrivilegeClass
import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeviceCapabilityCatalogTest {
    @Test
    fun canonical_action_has_exactly_one_owner_and_aliases_do_not_resolve() {
        val descriptor = descriptor("own-webview-actions", setOf("own-webview.click"))
        val catalog = DeviceCapabilityCatalog(listOf(descriptor))

        assertSame(descriptor, catalog.capabilityForCanonicalAction("own-webview.click"))
        assertTrue(catalog.containsCanonicalAction("own-webview.click"))
        assertNull(catalog.capabilityForCanonicalAction("click"))
        assertNull(catalog.capabilityForCanonicalAction("CLICK"))
        assertFalse(catalog.containsCanonicalAction("tap"))
    }

    @Test
    fun duplicate_capability_or_canonical_action_ids_fail_closed() {
        val first = descriptor("capability-a", setOf("own-webview.click"))
        val duplicateCapability = descriptor("capability-a", setOf("own-webview.fill-text"))
        assertFailsWith<IllegalArgumentException> {
            DeviceCapabilityCatalog(listOf(first, duplicateCapability))
        }

        val secondOwner = descriptor("capability-b", setOf("own-webview.click"))
        assertFailsWith<IllegalArgumentException> {
            DeviceCapabilityCatalog(listOf(first, secondOwner))
        }
    }

    @Test
    fun catalog_exposes_deterministic_capability_order() {
        val catalog = DeviceCapabilityCatalog(
            listOf(
                descriptor("z-capability", setOf("own-webview.select-option")),
                descriptor("a-capability", setOf("own-webview.click")),
            ),
        )
        assertEquals(listOf("a-capability", "z-capability"), catalog.capabilities.map { it.id.value })
    }

    private fun descriptor(id: String, actions: Set<String>) = DeviceCapabilityDescriptor(
        id = DeviceCapabilityId(id),
        canonicalActionIds = actions,
        actionKinds = setOf(DeviceActionKind.UI_CLICK),
        allowedProfiles = setOf(DistributionProfile.PLAY_SAFE),
        scope = DeviceCapabilityScope.OWN_WEBVIEW,
        privilegeClass = DevicePrivilegeClass.NONE,
        maximumRisk = DeviceActionRisk.MEDIUM,
        confirmationClass = DeviceConfirmationClass.USER_CONFIRMATION,
        verifierId = "webview-postcondition-v1",
        auditCategory = "device-action",
    )
}
