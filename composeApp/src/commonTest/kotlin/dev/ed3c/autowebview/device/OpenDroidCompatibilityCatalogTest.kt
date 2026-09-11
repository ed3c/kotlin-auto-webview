package dev.ed3c.autowebview.device

import dev.ed3c.autowebview.device.catalog.CapabilityAdmissionDecision
import dev.ed3c.autowebview.device.catalog.OpenDroidCompatibilityCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenDroidCompatibilityCatalogTest {
    @Test
    fun catalog_is_exactly_the_twelve_source_admission_records() {
        val expected = setOf(
            "action-taxonomy",
            "action-auto-mapping",
            "accessibility-observation",
            "accessibility-action-service",
            "generic-app-automation",
            "postcondition-verification-pattern",
            "multi-step-call-flow",
            "sms-and-communications",
            "calendar-actions",
            "raw-coordinate-or-gesture-authority",
            "direct-mcp-execution",
            "privileged-shell-root-terminal",
        )
        assertEquals(expected, OpenDroidCompatibilityCatalog.records.map { it.id.value }.toSet())
        assertEquals(12, OpenDroidCompatibilityCatalog.records.size)
        assertEquals("0e9e5898f0e0dcc679d99e5f4518e19310e96775", OpenDroidCompatibilityCatalog.UPSTREAM_COMMIT)
        assertEquals("4c9d1d5f644fc69d9a0a5e658b51d1753fd2ac32", OpenDroidCompatibilityCatalog.UPSTREAM_TREE)
    }

    @Test
    fun architecture_denials_are_preserved_as_non_executable_data() {
        val denied = OpenDroidCompatibilityCatalog.records.filter {
            it.decision == CapabilityAdmissionDecision.DENIED_BY_ARCHITECTURE
        }
        assertEquals(
            setOf("raw-coordinate-or-gesture-authority", "direct-mcp-execution", "privileged-shell-root-terminal"),
            denied.map { it.id.value }.toSet(),
        )
        assertTrue(denied.all { !it.executableCandidate })
        assertFalse(
            OpenDroidCompatibilityCatalog.records.first { it.id.value == "action-auto-mapping" }.executableCandidate,
        )
    }
}
