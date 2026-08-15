package dev.ed3c.autowebview.persistence

import app.cash.sqldelight.db.SqlDriver
import dev.ed3c.autowebview.cache.LexicalSimilarity
import dev.ed3c.autowebview.cache.SemanticCache
import dev.ed3c.autowebview.domain.AuditEvent
import dev.ed3c.autowebview.domain.CacheMatch
import dev.ed3c.autowebview.domain.SemanticCacheRecord
import dev.ed3c.autowebview.persistence.db.AppDatabase
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightSemanticCache(
    driver: SqlDriver,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SemanticCache {
    private val database = AppDatabase(driver)
    private val queries = database.semanticCacheQueries

    override suspend fun put(record: SemanticCacheRecord) {
        queries.upsertCache(
            id = record.id,
            source_url = record.sourceUrl,
            title = record.title,
            summary = record.summary,
            content = record.content,
            created_at_epoch_ms = record.createdAtEpochMs,
            tags_json = json.encodeToString(record.tags),
        )
    }

    override suspend fun query(text: String, limit: Int): List<CacheMatch> =
        PersistentCacheRanking.rank(
            records = queries.selectAllCache(::mapCacheRecord).executeAsList(),
            text = text,
            limit = limit,
        )

    override suspend fun remove(id: String) {
        queries.deleteCacheById(id)
    }

    override suspend fun clear() {
        queries.deleteAllCache()
    }

    private fun mapCacheRecord(
        id: String,
        sourceUrl: String,
        title: String,
        summary: String,
        content: String,
        createdAtEpochMs: Long,
        tagsJson: String,
    ): SemanticCacheRecord = SemanticCacheRecord(
        id = id,
        sourceUrl = sourceUrl,
        title = title,
        summary = summary,
        content = content,
        createdAtEpochMs = createdAtEpochMs,
        tags = runCatching { json.decodeFromString<Set<String>>(tagsJson) }.getOrDefault(emptySet()),
    )
}

internal object PersistentCacheRanking {
    fun rank(
        records: List<SemanticCacheRecord>,
        text: String,
        limit: Int,
    ): List<CacheMatch> = records
        .map { record ->
            CacheMatch(
                record = record,
                relevance = LexicalSimilarity.cosine(text, record.content + " " + record.summary),
            )
        }
        .filter { match -> match.relevance > 0.0 }
        .sortedWith(
            compareByDescending<CacheMatch> { it.relevance }
                .thenByDescending { it.record.createdAtEpochMs }
                .thenBy { it.record.id },
        )
        .take(limit.coerceAtLeast(0))
}

interface AuditEvidenceStore {
    suspend fun append(event: AuditEvent)
    suspend fun recent(limit: Int = 100): List<AuditEvent>
    suspend fun pruneBefore(epochMs: Long)
}

class SqlDelightAuditEvidenceStore(
    driver: SqlDriver,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val sanitizer: AuditSanitizer = AuditSanitizer(),
) : AuditEvidenceStore {
    private val database = AppDatabase(driver)
    private val queries = database.semanticCacheQueries

    override suspend fun append(event: AuditEvent) {
        val safe = sanitizer.sanitize(event)
        queries.appendAudit(
            at_epoch_ms = safe.atEpochMs,
            category = safe.category,
            message = safe.message,
            metadata_json = json.encodeToString(safe.metadata),
        )
    }

    override suspend fun recent(limit: Int): List<AuditEvent> =
        queries.selectRecentAudit(limit.coerceAtLeast(0).toLong()) { atEpochMs, category, message, metadataJson ->
            AuditEvent(
                atEpochMs = atEpochMs,
                category = category,
                message = message,
                metadata = runCatching {
                    json.decodeFromString<Map<String, String>>(metadataJson)
                }.getOrDefault(emptyMap()),
            )
        }.executeAsList()

    override suspend fun pruneBefore(epochMs: Long) {
        queries.pruneAuditBefore(epochMs)
    }
}

class AuditSanitizer {
    private val sensitiveKey = Regex("(?i)(password|passwd|secret|token|api[_-]?key|authorization|cookie|payment|card|cvv|cvc)")
    private val patterns = listOf(
        Regex("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*[^\\s,;]{4,}"),
        Regex("\\b(?:\\d[ -]*?){13,19}\\b"),
        Regex("-----BEGIN [A-Z ]+ PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+ PRIVATE KEY-----"),
    )

    fun sanitize(event: AuditEvent): AuditEvent = event.copy(
        message = redact(event.message),
        metadata = event.metadata.mapValues { (key, value) ->
            if (sensitiveKey.containsMatchIn(key)) "[REDACTED]" else redact(value)
        },
    )

    private fun redact(input: String): String {
        var result = input
        patterns.forEach { pattern -> result = pattern.replace(result, "[REDACTED]") }
        return result
    }
}
