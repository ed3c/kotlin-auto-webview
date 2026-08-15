package dev.ed3c.autowebview.domain

import kotlinx.serialization.Serializable

@Serializable
data class DomRect(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
)

@Serializable
data class InteractiveElement(
    val fingerprint: String,
    val tag: String,
    val role: String? = null,
    val text: String = "",
    val accessibleName: String = "",
    val inputType: String? = null,
    val rect: DomRect = DomRect(),
)

@Serializable
data class PageContext(
    val url: String,
    val title: String,
    val markdown: String,
    val selection: String = "",
    val capturedAtEpochMs: Long,
    val viewportWidth: Double = 0.0,
    val viewportHeight: Double = 0.0,
    val scrollX: Double = 0.0,
    val scrollY: Double = 0.0,
    val interactiveElements: List<InteractiveElement> = emptyList(),
)

@Serializable
data class SemanticCacheRecord(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val summary: String,
    val content: String,
    val createdAtEpochMs: Long,
    val tags: Set<String> = emptySet(),
)

@Serializable
data class CacheMatch(
    val record: SemanticCacheRecord,
    val relevance: Double,
)

@Serializable
data class ProjectionHint(
    val cacheId: String,
    val anchorFingerprint: String?,
    val anchorRect: DomRect?,
    val summary: String,
    val relevance: Double,
    val renderingMode: RenderingMode,
)

@Serializable
enum class RenderingMode { BUBBLE, HIGHLIGHT, CONTEXT_RAIL }

@Serializable
enum class ActionRisk { READ_ONLY, LOW, MEDIUM, HIGH, DESTRUCTIVE }

@Serializable
data class AgentAction(
    val id: String,
    val capabilityId: String,
    val name: String,
    val description: String,
    val arguments: Map<String, String> = emptyMap(),
    val risk: ActionRisk = ActionRisk.LOW,
)

@Serializable
data class CapabilityDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val requiredPermissions: Set<String> = emptySet(),
    val maximumRisk: ActionRisk = ActionRisk.LOW,
    val enabledByDefault: Boolean = false,
)

@Serializable
data class AuditEvent(
    val atEpochMs: Long,
    val category: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
)
