package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded exact-request duplicate suppression for action-bearing MCP calls.
 *
 * This is not a replacement for authenticated transport or a cryptographic request signature.
 * It stores only an opaque deterministic key, rejects exact duplicates during the window, removes
 * expired entries, and fails closed rather than evicting a live entry when capacity is exhausted.
 */
class BoundedMcpHttpReplayGuard(
    private val windowMs: Long = 60_000,
    private val maxEntries: Int = 1_024,
) : McpHttpReplayGuard {
    private val mutex = Mutex()
    private val expiresAtByKey = linkedMapOf<String, Long>()

    init {
        require(windowMs > 0) { "Replay window must be positive" }
        require(maxEntries > 0) { "Replay capacity must be positive" }
    }

    override suspend fun admit(key: McpHttpReplayKey, nowEpochMs: Long): McpHttpReplayDecision =
        mutex.withLock {
            require(nowEpochMs >= 0) { "Replay time cannot be negative" }
            val iterator = expiresAtByKey.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value <= nowEpochMs) iterator.remove()
            }

            when {
                key.value in expiresAtByKey -> McpHttpReplayDecision.DUPLICATE
                expiresAtByKey.size >= maxEntries -> McpHttpReplayDecision.CAPACITY_EXHAUSTED
                else -> {
                    expiresAtByKey[key.value] = saturatingAdd(nowEpochMs, windowMs)
                    McpHttpReplayDecision.ACCEPTED
                }
            }
        }
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
