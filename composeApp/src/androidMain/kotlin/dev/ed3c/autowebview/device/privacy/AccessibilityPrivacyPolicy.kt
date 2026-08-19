package dev.ed3c.autowebview.device.privacy

import dev.ed3c.autowebview.device.contract.DeviceUiPrivacyClass

enum class AccessibilitySensitivity {
    PUBLIC_METADATA,
    USER_CONTENT,
    PASSWORD,
    PAYMENT,
    SECRET,
    OTP,
    PRIVATE_MESSAGE,
    POLICY_DENIED,
}

data class SanitizedAccessibilityText(
    val value: String,
    val privacyClass: DeviceUiPrivacyClass,
)

class AccessibilityPrivacyPolicy(
    private val maximumRetainedTextLength: Int = 256,
) {
    init {
        require(maximumRetainedTextLength in 1..1_024) { "Retained accessibility text bound is invalid" }
    }

    fun sanitize(
        rawValue: String,
        sensitivity: AccessibilitySensitivity,
    ): SanitizedAccessibilityText = when (sensitivity) {
        AccessibilitySensitivity.PUBLIC_METADATA -> SanitizedAccessibilityText(
            value = bounded(rawValue),
            privacyClass = DeviceUiPrivacyClass.PUBLIC_METADATA,
        )

        AccessibilitySensitivity.USER_CONTENT -> SanitizedAccessibilityText(
            value = bounded(rawValue),
            privacyClass = DeviceUiPrivacyClass.USER_CONTENT,
        )

        AccessibilitySensitivity.PASSWORD,
        AccessibilitySensitivity.PAYMENT,
        AccessibilitySensitivity.SECRET,
        AccessibilitySensitivity.OTP,
        AccessibilitySensitivity.PRIVATE_MESSAGE,
        AccessibilitySensitivity.POLICY_DENIED,
        -> SanitizedAccessibilityText(
            value = "",
            privacyClass = DeviceUiPrivacyClass.SENSITIVE_REDACTED,
        )
    }

    private fun bounded(value: String): String {
        require(value.none { it.isISOControl() }) {
            "Accessibility text contains unsupported control characters"
        }
        return value.take(maximumRetainedTextLength)
    }
}
