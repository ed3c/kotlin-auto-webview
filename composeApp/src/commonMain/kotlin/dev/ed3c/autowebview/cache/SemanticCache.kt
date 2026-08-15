package dev.ed3c.autowebview.cache

import dev.ed3c.autowebview.domain.CacheMatch
import dev.ed3c.autowebview.domain.SemanticCacheRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

interface SemanticCache {
    suspend fun put(record: SemanticCacheRecord)
    suspend fun query(text: String, limit: Int = 5): List<CacheMatch>
    suspend fun remove(id: String)
    suspend fun clear()
}

class InMemorySemanticCache(
    private val capacity: Int = 256,
) : SemanticCache {
    private val mutex = Mutex()
    private val records = LinkedHashMap<String, SemanticCacheRecord>()

    override suspend fun put(record: SemanticCacheRecord) = mutex.withLock {
        records.remove(record.id)
        records[record.id] = record
        while (records.size > capacity) {
            records.remove(records.keys.first())
        }
    }

    override suspend fun query(text: String, limit: Int): List<CacheMatch> = mutex.withLock {
        records.values
            .map { CacheMatch(it, LexicalSimilarity.cosine(text, it.content + " " + it.summary)) }
            .filter { it.relevance > 0.0 }
            .sortedByDescending(CacheMatch::relevance)
            .take(limit.coerceAtLeast(0))
    }

    override suspend fun remove(id: String) = mutex.withLock {
        records.remove(id)
        Unit
    }

    override suspend fun clear() = mutex.withLock {
        records.clear()
    }
}

internal object LexicalSimilarity {
    private val tokenRegex = Regex("[\\p{L}\\p{N}_-]{2,}")

    fun cosine(left: String, right: String): Double {
        val a = vector(left)
        val b = vector(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0
        for ((token, weight) in a) dot += weight * (b[token] ?: 0.0)
        val normA = sqrt(a.values.sumOf { it * it })
        val normB = sqrt(b.values.sumOf { it * it })
        if (normA == 0.0 || normB == 0.0) return 0.0
        return (dot / (normA * normB)).coerceIn(0.0, 1.0)
    }

    private fun vector(text: String): Map<String, Double> =
        tokenRegex.findAll(text.lowercase())
            .map { it.value }
            .groupingBy { it }
            .eachCount()
            .mapValues { (_, count) -> 1.0 + kotlin.math.ln(count.toDouble()) }
}
