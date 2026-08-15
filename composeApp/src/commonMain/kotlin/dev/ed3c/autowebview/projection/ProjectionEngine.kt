package dev.ed3c.autowebview.projection

import dev.ed3c.autowebview.domain.CacheMatch
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.domain.ProjectionHint
import dev.ed3c.autowebview.domain.RenderingMode

class ProjectionEngine(
    private val minimumRelevance: Double = 0.12,
    private val maximumProjections: Int = 4,
) {
    fun project(context: PageContext, matches: List<CacheMatch>): List<ProjectionHint> {
        val eligible = matches
            .filter { it.relevance >= minimumRelevance }
            .take(maximumProjections)

        return eligible.mapIndexed { index, match ->
            val anchor = selectAnchor(context, match.record.tags, index)
            ProjectionHint(
                cacheId = match.record.id,
                anchorFingerprint = anchor?.fingerprint,
                anchorRect = anchor?.rect,
                summary = match.record.summary,
                relevance = match.relevance,
                renderingMode = if (anchor == null) RenderingMode.CONTEXT_RAIL else RenderingMode.BUBBLE,
            )
        }
    }

    private fun selectAnchor(context: PageContext, tags: Set<String>, fallbackIndex: Int) =
        context.interactiveElements.firstOrNull { element ->
            val haystack = (element.text + " " + element.accessibleName).lowercase()
            tags.any { it.lowercase() in haystack }
        } ?: context.interactiveElements.getOrNull(fallbackIndex % context.interactiveElements.size.coerceAtLeast(1))
}
