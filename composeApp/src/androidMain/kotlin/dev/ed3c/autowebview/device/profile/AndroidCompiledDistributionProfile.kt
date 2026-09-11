package dev.ed3c.autowebview.device.profile

import dev.ed3c.autowebview.BuildConfig
import dev.ed3c.autowebview.device.policy.DistributionProfile

/**
 * Compile/package-time Android distribution identity.
 *
 * The only input is the AGP-generated BuildConfig constant for the selected product flavor.
 * No intent, preference, environment, network, MCP or model value can widen this ceiling.
 */
object AndroidCompiledDistributionProfile {
    val current: DistributionProfile = when (BuildConfig.DISTRIBUTION_PROFILE_ID) {
        "PLAY_SAFE" -> DistributionProfile.PLAY_SAFE
        "ENTERPRISE_SIDELOAD" -> DistributionProfile.ENTERPRISE_SIDELOAD
        else -> error("Unknown compiled Android distribution profile")
    }

    val applicationId: String = BuildConfig.APPLICATION_ID
    val profileId: String = BuildConfig.DISTRIBUTION_PROFILE_ID

    init {
        require(current != DistributionProfile.ACCESSIBILITY_TOOL) {
            "ACCESSIBILITY_TOOL has no distributable Android variant without external admission"
        }
        when (current) {
            DistributionProfile.PLAY_SAFE -> require(applicationId == PRIMARY_APPLICATION_ID) {
                "PLAY_SAFE must retain the primary application identity"
            }
            DistributionProfile.ENTERPRISE_SIDELOAD -> require(applicationId == ENTERPRISE_APPLICATION_ID) {
                "ENTERPRISE_SIDELOAD must use the enterprise application identity"
            }
            DistributionProfile.ACCESSIBILITY_TOOL -> error("Unreachable distributable profile")
        }
    }

    const val PRIMARY_APPLICATION_ID: String = "dev.ed3c.autowebview"
    const val ENTERPRISE_APPLICATION_ID: String = "dev.ed3c.autowebview.enterprise"
}
