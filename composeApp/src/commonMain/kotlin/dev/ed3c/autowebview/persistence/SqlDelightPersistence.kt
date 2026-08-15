package dev.ed3c.autowebview.persistence

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@kotlinx.serialization.Serializable
data class PersistentMemoryPolicy(
    val maximumCacheRecords: Int = 512,
    val maximumAuditEvents: Int = 2_000,
    val maximumQueryCandidates: Int = 512,
) {
    init {
        require(maximumCacheRecords > 0) { "Cache retention must be positive" }
        require(maximumAuditEvents > 0) { "Audit retention must be positive" }
        require(maximumQueryCandidates > 0) { "Query candidate budget must be positive" }
    }
}

@OptIn(ExperimentalTime::class)
class SqlDelightSemanticCache(
    driver: SqlDriver,
    private val policy: PersistentMemoryPolicy = PersistentMemoryPolicy(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val sanitizer: PersistenceSanitizer = PersistenceSanitizer(),
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SemanticCache {
    private val database = AppDatabase(driver)
    private val queries = database.semanticCacheQueries

    override suspend fun put(record: SemanticCacheRecord) {
        val safe = sanitizer.sanitize(record)
        val accessedAt = maxOf(safe.createdAtEpochMs, nowEpochMs())
        database.transaction {
            queries.upsertCache(
                id = safe.id,
                source_url = safe.sourceUrl,
                title = safe.title,
                summary = safe.summary,
                content = safe.content,
                created_at_epoch_ms = safe.createdAtEpochMs,
                last_accessed_at_epoch_ms = accessedAt,
                tags_json = json.encodeToString(safe.tags),
            )
            queries.pruneCacheToLimit(policy.maximumCacheRecords.toLong())
        }
    }

    override suspend fun query(text: String, limit: Int): List<CacheMatch> {
        if (limit <= 0) return emptyList()
        val records = queries.selectCacheCandidates(
            policy.maximumQueryCandidates.toLong(),
            ::mapCacheRecord,
        ).awaitAsList()
        val matches = PersistentCacheRanking.rank(records, text, limit)
        if (matches.isNotEmpty()) {
            val accessedAt = nowEpochMs()
            database.transaction {
                matches.forEach { match -> queries.touchCache(accessedAt, match.record.id) }
            }
        }
        return matches
    }

    override suspend fun remove(id: String) {
        queries.deleteCacheById(id)
    }

    override suspend fun clear() {
        queries.deleteAllCache()
    }

    suspend fun storedRecordCount(): Long = queries.countCache().awaitAsOne()

    private fun mapCacheRecord(
        id: String,
        sourceUrl: String,
        title: String,
        summary: String,
        content: String,
        createdAtEpochMs: Long,
        @Suppress("UNUSED_PARAMETER") lastAccessedAtEpochMs: Long,
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
                relevance = LexicalSimilarity.cosine(
                    text,
                    record.content + " " + record.summary + " " + record.tags.sorted().joinToString(" "),
                ),
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

@kotlinx.serialization.Serializable
data class PersistedAuditEvent(
    val sequence: Long,
    val event: AuditEvent,
)

interface AuditEvidenceStore {
    suspend fun append(event: AuditEvent)
    suspend fun recentEntries(limit: Int = 100): List<PersistedAuditEvent>
    suspend fun recent(limit: Int = 100): List<AuditEvent> = recentEntries(limit).map(PersistedAuditEvent::event)
    suspend fun pruneBefore(epochMs: Long)
}

class SqlDelightAuditEvidenceStore(
    driver: SqlDriver,
    private val policy: PersistentMemoryPolicy = PersistentMemoryPolicy(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val sanitizer: AuditSanitizer = AuditSanitizer(),
) : AuditEvidenceStore {
    private val database = AppDatabase(driver)
    private val queries = database.semanticCacheQueries

    override suspend fun append(event: AuditEvent) {
        val safe = sanitizer.sanitize(event)
        database.transaction {
            queries.appendAudit(
                at_epoch_ms = safe.atEpochMs,
                category = safe.category,
                message = safe.message,
                metadata_json = json.encodeToString(safe.metadata),
            )
            queries.pruneAuditToLimit(policy.maximumAuditEvents.toLong())
        }
    }

    override suspend fun recentEntries(limit: Int): List<PersistedAuditEvent> =
        queries.selectRecentAudit(limit.coerceAtLeast(0).toLong()) {
                sequence,
                atEpochMs,
                category,
                message,
                metadataJson,
            ->
            PersistedAuditEvent(
                sequence = sequence,
                event = AuditEvent(
                    atEpochMs = atEpochMs,
                    category = category,
                    message = message,
                    metadata = runCatching {
                        json.decodeFromString<Map<String, String>>(metadataJson)
                    }.getOrDefault(emptyMap()),
                ),
            )
        }.awaitAsList()

    override suspend fun pruneBefore(epochMs: Long) {
        queries.pruneAuditBefore(epochMs)
    }

    suspend fun storedEventCount(): Long = queries.countAudit().awaitAsOne()
}

class PersistenceSanitizer(
    private val redactor: SecretRedactor = SecretRedactor(),
) {
    fun sanitize(record: SemanticCacheRecord): SemanticCacheRecord = record.copy(
        sourceUrl = redactor.redact(stripQueryAndFragment(record.sourceUrl)),
        title = redactor.redact(record.title),
        summary = redactor.redact(record.summary),
        content = redactor.redact(record.content),
        tags = record.tags.mapTo(linkedSetOf()) { tag -> redactor.redact(tag).take(MAX_TAG_LENGTH) },
    )

    private fun stripQueryAndFragment(url: String): String = url.substringBefore('#').substringBefore('?')

    private companion object {
        const val MAX_TAG_LENGTH = 128
    }
}

class AuditSanitizer(
    private val redactor: SecretRedactor = SecretRedactor(),
) {
    fun sanitize(event: AuditEvent): AuditEvent = event.copy(
        category = redactor.redact(event.category).take(MAX_CATEGORY_LENGTH),
        message = redactor.redact(event.message),
        metadata = event.metadata.mapValues { (key, value) ->
            if (redactor.isSensitiveKey(key)) "[REDACTED]" else redactor.redact(value)
        },
    )

    private companion object {
        const val MAX_CATEGORY_LENGTH = 128
    }
}

class SecretRedactor {
    private val sensitiveKey = Regex(
        "(?i)(password|passwd|secret|token|api[_-]?key|authorization|cookie|payment|card|cvv|cvc|session)",
    )
    private val patterns = listOf(
        Regex("(?i)(api[_-]?key|secret|token|password|authorization)\\s*[:=]\\s*[^\\s,;]{4,}"),
        Regex("(?i)bearer\\s+[a-z0-9._~+/-]{8,}"),
        Regex("\\b(?:\\d[ -]*?){13,19}\\b"),
        Regex("-----BEGIN [A-Z ]+ PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+ PRIVATE KEY-----"),
    )

    fun isSensitiveKey(key: String): Boolean = sensitiveKey.containsMatchIn(key)

    fun redact(input: String): String {
        var result = input
        patterns.forEach { pattern -> result = pattern.replace(result, "[REDACTED]") }
        return result
    }
}
