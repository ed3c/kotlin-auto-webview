package dev.ed3c.autowebview.projection

import dev.ed3c.autowebview.domain.CacheMatch
import dev.ed3c.autowebview.domain.DomRect
import dev.ed3c.autowebview.domain.InteractiveElement
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.domain.SemanticCacheRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectionEngineTest {
    @Test
    fun anchorsCacheToMatchingElement() {
        val context = PageContext(
            url = "https://example.com",
            title = "KMP",
            markdown = "Kotlin browser",
            capturedAtEpochMs = 1,
            interactiveElements = listOf(
                InteractiveElement("kmp-button", "button", text = "Open Kotlin guide", rect = DomRect(10.0, 20.0, 100.0, 40.0)),
            ),
        )
        val record = SemanticCacheRecord("cache", "", "", "Prior Kotlin note", "Kotlin", 1, setOf("kotlin"))
        val result = ProjectionEngine().project(context, listOf(CacheMatch(record, 0.8)))
        assertEquals("kmp-button", result.single().anchorFingerprint)
    }
}
