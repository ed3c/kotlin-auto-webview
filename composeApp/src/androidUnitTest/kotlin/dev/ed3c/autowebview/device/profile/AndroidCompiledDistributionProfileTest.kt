package dev.ed3c.autowebview.device.profile

import dev.ed3c.autowebview.BuildConfig
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidCompiledDistributionProfileTest {
    @Test
    fun generated_profile_id_maps_to_the_existing_closed_enum() {
        val expected = when (BuildConfig.DISTRIBUTION_PROFILE_ID) {
            "PLAY_SAFE" -> DistributionProfile.PLAY_SAFE
            "ENTERPRISE_SIDELOAD" -> DistributionProfile.ENTERPRISE_SIDELOAD
            else -> error("Unexpected generated profile id")
        }

        assertEquals(expected, AndroidCompiledDistributionProfile.current)
        assertEquals(BuildConfig.DISTRIBUTION_PROFILE_ID, AndroidCompiledDistributionProfile.profileId)
        assertNotEquals(DistributionProfile.ACCESSIBILITY_TOOL, AndroidCompiledDistributionProfile.current)
    }

    @Test
    fun package_identity_is_profile_specific_and_cannot_alias_the_stronger_artifact() {
        when (AndroidCompiledDistributionProfile.current) {
            DistributionProfile.PLAY_SAFE -> {
                assertEquals(
                    AndroidCompiledDistributionProfile.PRIMARY_APPLICATION_ID,
                    AndroidCompiledDistributionProfile.applicationId,
                )
                assertTrue(!AndroidCompiledDistributionProfile.applicationId.endsWith(".enterprise"))
            }
            DistributionProfile.ENTERPRISE_SIDELOAD -> {
                assertEquals(
                    AndroidCompiledDistributionProfile.ENTERPRISE_APPLICATION_ID,
                    AndroidCompiledDistributionProfile.applicationId,
                )
                assertTrue(AndroidCompiledDistributionProfile.applicationId.endsWith(".enterprise"))
            }
            DistributionProfile.ACCESSIBILITY_TOOL -> error("No distributable accessibility-tool variant exists")
        }
    }
}
