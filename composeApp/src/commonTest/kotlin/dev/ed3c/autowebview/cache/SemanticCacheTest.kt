package dev.ed3c.autowebview.cache

import dev.ed3c.autowebview.domain.SemanticCacheRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticCacheTest {
    @Test
    fun ranksStrongerMatchAbovePartialMatch() = runTest {
        val cache = InMemorySemanticCache()
        cache.put(record("kotlin", "Kotlin multiplatform webview browser context"))
        cache.put(record("partial", "Browser cooking catalog"))
        cache.put(record("unrelated", "Pasta tomato recipe kitchen"))

        val matches = cache.query("KMP browser webview")

        assertEquals(listOf("kotlin", "partial"), matches.map { it.record.id })
        assertTrue(matches.first().relevance > matches.last().relevance)
    }

    private fun record(id: String, content: String) = SemanticCacheRecord(
        id = id,
        sourceUrl = "https://example.com/$id",
        title = id,
        summary = content,
        content = content,
        createdAtEpochMs = 1,
    )
}
