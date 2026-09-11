package dev.ed3c.autowebview.navigation

import dev.ed3c.autowebview.domain.StableIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * In-memory navigation proposal ledger: last 32 entries, TTL 5 minutes.
 * proposalId = hash(sessionId + monotonicSequence + normalizedUrl).
 * Same URL twice yields distinct IDs. No restart replay.
 */
@OptIn(ExperimentalTime::class)
class NavigationProposalLedger(
    private val sessionId: String,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var sequence: Long = 0L
    private val entries = LinkedHashMap<String, ProposalEntry>()

    data class ProposalEntry(
        val proposalId: String,
        val normalizedUrl: String,
        val createdAtEpochMs: Long,
        val status: BrowserActionStatus,
    )

    suspend fun propose(normalizedUrl: String, initialStatus: BrowserActionStatus): ProposalEntry =
        mutex.withLock {
            evictLocked()
            sequence += 1
            val proposalId = StableIds.from(sessionId, sequence.toString(), normalizedUrl)
            val entry = ProposalEntry(
                proposalId = proposalId,
                normalizedUrl = normalizedUrl,
                createdAtEpochMs = nowEpochMs(),
                status = initialStatus,
            )
            entries[proposalId] = entry
            trimLocked()
            entry
        }

    suspend fun updateStatus(proposalId: String, status: BrowserActionStatus): ProposalEntry? =
        mutex.withLock {
            evictLocked()
            val current = entries[proposalId] ?: return@withLock null
            val updated = current.copy(status = status)
            entries[proposalId] = updated
            updated
        }

    suspend fun statusOf(proposalId: String): BrowserActionStatus = mutex.withLock {
        evictLocked()
        entries[proposalId]?.status ?: BrowserActionStatus.NONE
    }

    suspend fun get(proposalId: String): ProposalEntry? = mutex.withLock {
        evictLocked()
        entries[proposalId]
    }

    private fun evictLocked() {
        val now = nowEpochMs()
        val expired = entries.filterValues { now - it.createdAtEpochMs > TTL_MS }.keys.toList()
        expired.forEach { entries.remove(it) }
    }

    private fun trimLocked() {
        while (entries.size > CAPACITY) {
            val oldest = entries.keys.first()
            entries.remove(oldest)
        }
    }

    companion object {
        const val CAPACITY = 32
        const val TTL_MS = 5 * 60 * 1000L
    }
}
