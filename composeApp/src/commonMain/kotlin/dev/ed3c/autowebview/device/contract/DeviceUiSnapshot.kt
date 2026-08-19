package dev.ed3c.autowebview.device.contract

import kotlinx.serialization.Serializable

object DeviceUiSnapshotSchema {
    const val VERSION = "kotlin-auto-webview/device-ui-snapshot/v1"
    const val MAX_ELEMENTS = 2_048
}

@Serializable
enum class DeviceUiPrivacyClass {
    PUBLIC_METADATA,
    USER_CONTENT,
    SENSITIVE_REDACTED,
}

@Serializable
data class DeviceUiElementSnapshot(
    val fingerprint: String,
    val parentFingerprint: String? = null,
    val role: String? = null,
    val accessibleName: String = "",
    val visible: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val privacyClass: DeviceUiPrivacyClass,
    val structuralDigestSha256: String,
) {
    init {
        DeviceContractValidation.requireOpaqueToken(fingerprint, "element fingerprint")
        parentFingerprint?.let {
            DeviceContractValidation.requireOpaqueToken(it, "parent fingerprint")
            require(it != fingerprint) { "Element cannot be its own parent" }
        }
        role?.let { DeviceContractValidation.requireIdentifier(it, "element role") }
        DeviceContractValidation.requireBoundedText(
            accessibleName,
            "accessible name",
            allowNewline = false,
            maxLength = 256,
        )
        DeviceContractValidation.requireSha256(structuralDigestSha256, "structural digest")
        if (privacyClass == DeviceUiPrivacyClass.SENSITIVE_REDACTED) {
            require(accessibleName.isEmpty()) {
                "Sensitive UI metadata must be redacted before crossing the portable boundary"
            }
        }
    }
}

@Serializable
data class DeviceUiSnapshot(
    val schemaVersion: String = DeviceUiSnapshotSchema.VERSION,
    val subject: DeviceSubjectRef,
    val eventSequence: Long,
    val privacyPolicyVersion: String,
    val contentDigestSha256: String,
    val elements: List<DeviceUiElementSnapshot>,
) {
    init {
        require(schemaVersion == DeviceUiSnapshotSchema.VERSION) { "Unknown device UI snapshot schema" }
        require(eventSequence >= 0) { "Accessibility event sequence cannot be negative" }
        DeviceContractValidation.requireIdentifier(privacyPolicyVersion, "privacy policy version")
        DeviceContractValidation.requireSha256(contentDigestSha256, "snapshot content digest")
        require(elements.size <= DeviceUiSnapshotSchema.MAX_ELEMENTS) { "UI snapshot exceeds bounded element count" }
        val fingerprints = elements.map(DeviceUiElementSnapshot::fingerprint)
        require(fingerprints.toSet().size == fingerprints.size) { "UI snapshot contains duplicate fingerprints" }
        val known = fingerprints.toSet()
        require(elements.all { it.parentFingerprint == null || it.parentFingerprint in known }) {
            "UI snapshot contains a dangling parent fingerprint"
        }
    }

    fun isFresh(nowEpochMs: Long, maximumAgeMs: Long): Boolean {
        if (nowEpochMs < 0 || maximumAgeMs < 0) return false
        if (nowEpochMs < subject.capturedAtEpochMs) return false
        return nowEpochMs - subject.capturedAtEpochMs <= maximumAgeMs
    }

    fun exactTargetCandidates(target: DeviceTargetRef.UiTarget): List<DeviceUiElementSnapshot> {
        if (target.snapshotVersion != subject.snapshotVersion) return emptyList()
        return elements.filter { it.fingerprint == target.fingerprint }
    }
}
