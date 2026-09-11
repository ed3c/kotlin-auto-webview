package dev.ed3c.autowebview.device.privacy

data class AccessibilityNodeSensitivityMetadata(
    val password: Boolean,
    val editable: Boolean,
    val className: String? = null,
    val viewIdResourceName: String? = null,
)

class AccessibilityNodeSensitivityClassifier {
    fun classify(metadata: AccessibilityNodeSensitivityMetadata): AccessibilitySensitivity {
        if (metadata.password) return AccessibilitySensitivity.PASSWORD
        val hint = listOfNotNull(metadata.className, metadata.viewIdResourceName)
            .joinToString(" ")
            .lowercase()
        return when {
            containsAny(hint, "card", "payment", "credit", "debit", "cvv", "cvc") ->
                AccessibilitySensitivity.PAYMENT
            containsAny(hint, "otp", "one-time", "one_time", "verification-code", "verification_code") ->
                AccessibilitySensitivity.OTP
            containsAny(hint, "secret", "token", "api-key", "api_key", "private-key", "private_key") ->
                AccessibilitySensitivity.SECRET
            containsAny(hint, "message", "chat", "conversation", "sms") ->
                AccessibilitySensitivity.PRIVATE_MESSAGE
            metadata.editable -> AccessibilitySensitivity.USER_CONTENT
            else -> AccessibilitySensitivity.PUBLIC_METADATA
        }
    }

    private fun containsAny(value: String, vararg needles: String): Boolean = needles.any(value::contains)
}
