package dev.ed3c.autowebview.edge

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.math.min

@Serializable
data class PairedPeer(
    val peerId: String,
    val origin: String,
    val keyId: String,
    val pairedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

@Serializable
data class PairingPolicy(
    val expectedOrigin: String,
    val allowedPeerIds: Set<String>,
    val allowedKeyIds: Set<String>,
) {
    init {
        require(expectedOrigin.isNotBlank())
        require(allowedPeerIds.none(String::isBlank))
        require(allowedKeyIds.none(String::isBlank))
    }
}

@Serializable
enum class StreamPayloadKind {
    PROJECTION_CANDIDATE,
    TYPED_ACTION_PROPOSAL,
    HEARTBEAT,
}

@Serializable
data class StreamChunk(
    val streamId: String,
    val peerId: String,
    val origin: String,
    val keyId: String,
    val sequence: Long,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val contextFingerprint: String? = null,
    val payloadKind: StreamPayloadKind,
    val payload: String,
    val replayToken: String,
)

@Serializable
enum class StreamRejectionReason {
    NOT_PAIRED,
    PEER_MISMATCH,
    ORIGIN_MISMATCH,
    KEY_MISMATCH,
    PAIRING_EXPIRED,
    CHUNK_EXPIRED,
    OLD_SEQUENCE,
    DUPLICATE_REPLAY_TOKEN,
    CONTEXT_MISMATCH,
    BUFFER_FULL,
    INVALID_CHUNK,
}

sealed interface StreamAdmission {
    data class Accepted(val chunk: StreamChunk) : StreamAdmission
    data class Rejected(val reason: StreamRejectionReason) : StreamAdmission
}

class OpenClawStreamSession(
    private val policy: PairingPolicy,
    private val maximumBufferedChunks: Int = 64,
) {
    private var pairedPeer: PairedPeer? = null
    private val latestSequenceByStream = mutableMapOf<String, Long>()
    private val replayTokens = linkedSetOf<String>()
    private val buffer = ArrayDeque<StreamChunk>()

    init {
        require(maximumBufferedChunks > 0)
    }

    fun pair(peer: PairedPeer, nowEpochMs: Long): Boolean {
        val valid = peer.peerId in policy.allowedPeerIds &&
            peer.keyId in policy.allowedKeyIds &&
            peer.origin == policy.expectedOrigin &&
            peer.pairedAtEpochMs <= nowEpochMs &&
            nowEpochMs < peer.expiresAtEpochMs
        pairedPeer = peer.takeIf { valid }
        return valid
    }

    fun admit(
        chunk: StreamChunk,
        nowEpochMs: Long,
        activeContextFingerprint: String? = null,
    ): StreamAdmission {
        val peer = pairedPeer ?: return StreamAdmission.Rejected(StreamRejectionReason.NOT_PAIRED)
        if (nowEpochMs >= peer.expiresAtEpochMs) {
            pairedPeer = null
            return StreamAdmission.Rejected(StreamRejectionReason.PAIRING_EXPIRED)
        }
        if (chunk.peerId != peer.peerId) return StreamAdmission.Rejected(StreamRejectionReason.PEER_MISMATCH)
        if (chunk.origin != peer.origin) return StreamAdmission.Rejected(StreamRejectionReason.ORIGIN_MISMATCH)
        if (chunk.keyId != peer.keyId) return StreamAdmission.Rejected(StreamRejectionReason.KEY_MISMATCH)
        if (chunk.streamId.isBlank() || chunk.replayToken.isBlank() || chunk.sequence < 0L) {
            return StreamAdmission.Rejected(StreamRejectionReason.INVALID_CHUNK)
        }
        if (nowEpochMs < chunk.issuedAtEpochMs || nowEpochMs >= chunk.expiresAtEpochMs) {
            return StreamAdmission.Rejected(StreamRejectionReason.CHUNK_EXPIRED)
        }
        val lastSequence = latestSequenceByStream[chunk.streamId]
        if (lastSequence != null && chunk.sequence <= lastSequence) {
            return StreamAdmission.Rejected(StreamRejectionReason.OLD_SEQUENCE)
        }
        if (chunk.replayToken in replayTokens) {
            return StreamAdmission.Rejected(StreamRejectionReason.DUPLICATE_REPLAY_TOKEN)
        }
        if (chunk.contextFingerprint != null && chunk.contextFingerprint != activeContextFingerprint) {
            return StreamAdmission.Rejected(StreamRejectionReason.CONTEXT_MISMATCH)
        }
        if (buffer.size >= maximumBufferedChunks) {
            return StreamAdmission.Rejected(StreamRejectionReason.BUFFER_FULL)
        }

        latestSequenceByStream[chunk.streamId] = chunk.sequence
        replayTokens += chunk.replayToken
        while (replayTokens.size > MAX_REPLAY_TOKENS) replayTokens.remove(replayTokens.first())
        buffer.addLast(chunk)
        return StreamAdmission.Accepted(chunk)
    }

    fun drain(limit: Int = maximumBufferedChunks): List<StreamChunk> {
        val result = mutableListOf<StreamChunk>()
        repeat(limit.coerceIn(0, maximumBufferedChunks)) {
            val next = buffer.removeFirstOrNull() ?: return@repeat
            result += next
        }
        return result
    }

    fun disconnect() {
        pairedPeer = null
        buffer.clear()
    }

    fun reconnect(peer: PairedPeer, nowEpochMs: Long): Boolean = pair(peer, nowEpochMs)

    fun bufferedCount(): Int = buffer.size

    private companion object {
        const val MAX_REPLAY_TOKENS = 4_096
    }
}

@Serializable
data class ReconnectPolicy(
    val initialDelayMs: Long = 250,
    val maximumDelayMs: Long = 15_000,
    val multiplier: Int = 2,
    val jitterPermille: Int = 200,
    val maximumAttempts: Int = 8,
) {
    init {
        require(initialDelayMs > 0)
        require(maximumDelayMs >= initialDelayMs)
        require(multiplier >= 1)
        require(jitterPermille in 0..1_000)
        require(maximumAttempts > 0)
    }

    fun delayMs(attempt: Int, jitterSamplePermille: Int): Long {
        require(attempt in 0 until maximumAttempts)
        require(jitterSamplePermille in -1_000..1_000)

        var base = initialDelayMs
        repeat(attempt) {
            base = min(maximumDelayMs, saturatingMultiply(base, multiplier.toLong()))
        }

        val boundedBase = min(base, maximumDelayMs)
        val signedJitterPermille = jitterSamplePermille * jitterPermille / 1_000
        val delta = boundedBase * signedJitterPermille / 1_000
        return (boundedBase + delta).coerceIn(1, maximumDelayMs)
    }

    private fun saturatingMultiply(value: Long, factor: Long): Long =
        if (factor == 0L || value <= Long.MAX_VALUE / factor) value * factor else Long.MAX_VALUE
}

interface OpenClawTransport {
    suspend fun connect()
    suspend fun receive(): StreamChunk?
    suspend fun close()
}

class OpenClawStreamPump(
    private val transport: OpenClawTransport,
    private val session: OpenClawStreamSession,
) {
    suspend fun run(
        nowEpochMs: () -> Long,
        activeContextFingerprint: () -> String?,
        onAdmission: suspend (StreamAdmission) -> Unit,
    ) {
        transport.connect()
        try {
            while (true) {
                val chunk = transport.receive() ?: break
                onAdmission(
                    session.admit(
                        chunk = chunk,
                        nowEpochMs = nowEpochMs(),
                        activeContextFingerprint = activeContextFingerprint(),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            transport.close()
        }
    }
}
