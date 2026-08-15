package dev.ed3c.autowebview.edge

import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.min

@Serializable
data class PairedPeer(
    val peerId: String,
    val origin: String,
    val keyId: String,
    val sessionEpoch: Long,
    val pairedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        require(peerId.isNotBlank()) { "Peer id cannot be blank" }
        require(origin.isNotBlank()) { "Origin cannot be blank" }
        require(keyId.isNotBlank()) { "Key id cannot be blank" }
        require(sessionEpoch > 0) { "Session epoch must be positive" }
        require(pairedAtEpochMs >= 0) { "Pairing time cannot be negative" }
        require(expiresAtEpochMs > pairedAtEpochMs) { "Pairing expiry must follow pairing time" }
    }
}

@Serializable
data class PairingPolicy(
    val expectedOrigin: String,
    val allowedPeerIds: Set<String>,
    val allowedKeyIds: Set<String>,
    val allowedRemoteCapabilityIds: Set<String> = emptySet(),
    val maximumRemoteActionRisk: ActionRisk = ActionRisk.HIGH,
    val maximumPayloadCharacters: Int = 8_192,
    val maximumTrackedStreams: Int = 32,
    val maximumChunkAgeMs: Long = 30_000,
    val maximumChunkLifetimeMs: Long = 60_000,
) {
    init {
        require(isSafeOrigin(expectedOrigin)) { "Expected origin must be an exact HTTPS/WSS origin" }
        require(allowedPeerIds.none(String::isBlank)) { "Allowed peer ids cannot contain blanks" }
        require(allowedKeyIds.none(String::isBlank)) { "Allowed key ids cannot contain blanks" }
        require(allowedRemoteCapabilityIds.none(String::isBlank)) {
            "Allowed remote capability ids cannot contain blanks"
        }
        require(maximumPayloadCharacters > 0) { "Payload budget must be positive" }
        require(maximumTrackedStreams > 0) { "Tracked stream budget must be positive" }
        require(maximumChunkAgeMs > 0) { "Chunk age budget must be positive" }
        require(maximumChunkLifetimeMs > 0) { "Chunk lifetime budget must be positive" }
    }
}

@Serializable
enum class StreamPayloadKind {
    PROJECTION_CANDIDATE,
    TYPED_ACTION_PROPOSAL,
    HEARTBEAT,
}

@Serializable
sealed interface OpenClawStreamPayload

@Serializable
@SerialName("projection_candidate")
data class ProjectionCandidatePayload(
    val cacheId: String,
    val summary: String,
    val relevanceHint: Double,
    val tags: Set<String> = emptySet(),
) : OpenClawStreamPayload {
    init {
        require(cacheId.isNotBlank()) { "Cache id cannot be blank" }
        require(summary.isNotBlank()) { "Projection summary cannot be blank" }
        require(relevanceHint in 0.0..1.0) { "Relevance hint must be between 0 and 1" }
    }
}

@Serializable
@SerialName("typed_action_proposal")
data class TypedActionProposalPayload(
    val action: AgentAction,
) : OpenClawStreamPayload

@Serializable
@SerialName("heartbeat")
data class HeartbeatPayload(
    val nonce: String,
    val sentAtEpochMs: Long,
) : OpenClawStreamPayload {
    init {
        require(nonce.isNotBlank()) { "Heartbeat nonce cannot be blank" }
        require(sentAtEpochMs >= 0) { "Heartbeat time cannot be negative" }
    }
}

@Serializable
data class StreamChunk(
    val streamId: String,
    val peerId: String,
    val origin: String,
    val keyId: String,
    val sessionEpoch: Long,
    val sequence: Long,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val contextFingerprint: String? = null,
    val payload: OpenClawStreamPayload,
    val replayToken: String,
)

@Serializable
enum class StreamSessionState {
    DISCONNECTED,
    PAIRED,
    CLOSED,
}

@Serializable
enum class StreamRejectionReason {
    SESSION_CLOSED,
    NOT_PAIRED,
    PEER_MISMATCH,
    ORIGIN_MISMATCH,
    KEY_MISMATCH,
    SESSION_EPOCH_MISMATCH,
    PAIRING_EXPIRED,
    CHUNK_EXPIRED,
    OLD_SEQUENCE,
    SEQUENCE_GAP,
    REPLAY_TOKEN_MISMATCH,
    CONTEXT_REQUIRED,
    CONTEXT_MISMATCH,
    STREAM_CANCELLED,
    TOO_MANY_STREAMS,
    BUFFER_FULL,
    PAYLOAD_TOO_LARGE,
    PAYLOAD_INVALID,
    SENSITIVE_PAYLOAD,
    CAPABILITY_NOT_ALLOWED,
    ACTION_RISK_NOT_ALLOWED,
    INVALID_CHUNK,
}

sealed interface StreamAdmission {
    data class Accepted(
        val chunk: StreamChunk,
        val payloadKind: StreamPayloadKind,
    ) : StreamAdmission

    data class Rejected(val reason: StreamRejectionReason) : StreamAdmission
}

class OpenClawStreamSession(
    private val policy: PairingPolicy,
    private val maximumBufferedChunks: Int = 64,
) {
    private var state: StreamSessionState = StreamSessionState.DISCONNECTED
    private var activePeer: PairedPeer? = null
    private var rememberedPeer: PairedPeer? = null
    private val latestSequenceByStream = mutableMapOf<String, Long>()
    private val cancelledStreams = mutableSetOf<String>()
    private val buffer = ArrayDeque<StreamChunk>()

    init {
        require(maximumBufferedChunks > 0) { "Buffer budget must be positive" }
    }

    fun pair(peer: PairedPeer, nowEpochMs: Long): Boolean {
        if (state == StreamSessionState.CLOSED) return false
        if (!validPairing(peer, nowEpochMs)) {
            activePeer = null
            state = StreamSessionState.DISCONNECTED
            return false
        }

        val previous = rememberedPeer
        if (previous != null) {
            if (peer.peerId != previous.peerId || peer.origin != previous.origin) return false
            if (peer.sessionEpoch < previous.sessionEpoch) return false
            if (peer.sessionEpoch == previous.sessionEpoch && peer.keyId != previous.keyId) return false
            if (peer.sessionEpoch > previous.sessionEpoch) resetStreamState()
        }

        activePeer = peer
        rememberedPeer = peer
        state = StreamSessionState.PAIRED
        return true
    }

    fun admit(
        chunk: StreamChunk,
        nowEpochMs: Long,
        activeContextFingerprint: String? = null,
    ): StreamAdmission {
        if (state == StreamSessionState.CLOSED) {
            return rejected(StreamRejectionReason.SESSION_CLOSED)
        }
        val peer = activePeer ?: return rejected(StreamRejectionReason.NOT_PAIRED)
        if (nowEpochMs >= peer.expiresAtEpochMs) {
            activePeer = null
            state = StreamSessionState.DISCONNECTED
            buffer.clear()
            return rejected(StreamRejectionReason.PAIRING_EXPIRED)
        }
        if (chunk.peerId != peer.peerId) return rejected(StreamRejectionReason.PEER_MISMATCH)
        if (chunk.origin != peer.origin) return rejected(StreamRejectionReason.ORIGIN_MISMATCH)
        if (chunk.keyId != peer.keyId) return rejected(StreamRejectionReason.KEY_MISMATCH)
        if (chunk.sessionEpoch != peer.sessionEpoch) {
            return rejected(StreamRejectionReason.SESSION_EPOCH_MISMATCH)
        }
        if (!validChunkIdentity(chunk)) return rejected(StreamRejectionReason.INVALID_CHUNK)
        if (
            nowEpochMs < chunk.issuedAtEpochMs ||
            nowEpochMs >= chunk.expiresAtEpochMs ||
            nowEpochMs - chunk.issuedAtEpochMs > policy.maximumChunkAgeMs ||
            chunk.expiresAtEpochMs - chunk.issuedAtEpochMs > policy.maximumChunkLifetimeMs
        ) {
            return rejected(StreamRejectionReason.CHUNK_EXPIRED)
        }
        if (chunk.replayToken != expectedReplayToken(chunk)) {
            return rejected(StreamRejectionReason.REPLAY_TOKEN_MISMATCH)
        }

        val payloadKind = chunk.payload.kind()
        if (payloadKind != StreamPayloadKind.HEARTBEAT) {
            if (chunk.contextFingerprint.isNullOrBlank()) {
                return rejected(StreamRejectionReason.CONTEXT_REQUIRED)
            }
            if (chunk.contextFingerprint != activeContextFingerprint) {
                return rejected(StreamRejectionReason.CONTEXT_MISMATCH)
            }
        } else if (chunk.contextFingerprint != null) {
            return rejected(StreamRejectionReason.INVALID_CHUNK)
        }

        validatePayload(chunk.payload)?.let(::rejected)?.let { return it }

        if (chunk.streamId in cancelledStreams) {
            return rejected(StreamRejectionReason.STREAM_CANCELLED)
        }

        val lastSequence = latestSequenceByStream[chunk.streamId]
        if (lastSequence == null) {
            if (latestSequenceByStream.size >= policy.maximumTrackedStreams) {
                return rejected(StreamRejectionReason.TOO_MANY_STREAMS)
            }
            if (chunk.sequence != 0L) return rejected(StreamRejectionReason.SEQUENCE_GAP)
        } else {
            if (chunk.sequence <= lastSequence) return rejected(StreamRejectionReason.OLD_SEQUENCE)
            if (chunk.sequence != lastSequence + 1L) return rejected(StreamRejectionReason.SEQUENCE_GAP)
        }

        if (buffer.size >= maximumBufferedChunks) {
            return rejected(StreamRejectionReason.BUFFER_FULL)
        }

        latestSequenceByStream[chunk.streamId] = chunk.sequence
        buffer.addLast(chunk)
        return StreamAdmission.Accepted(chunk, payloadKind)
    }

    fun cancelStream(streamId: String): Boolean {
        if (state != StreamSessionState.PAIRED || streamId !in latestSequenceByStream) return false
        cancelledStreams += streamId
        buffer.removeAll { chunk -> chunk.streamId == streamId }
        return true
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
        if (state == StreamSessionState.CLOSED) return
        activePeer = null
        state = StreamSessionState.DISCONNECTED
        buffer.clear()
    }

    fun reconnect(peer: PairedPeer, nowEpochMs: Long): Boolean = pair(peer, nowEpochMs)

    fun close() {
        activePeer = null
        rememberedPeer = null
        state = StreamSessionState.CLOSED
        resetStreamState()
    }

    fun sessionState(): StreamSessionState = state

    fun bufferedCount(): Int = buffer.size

    fun trackedStreamCount(): Int = latestSequenceByStream.size

    private fun validPairing(peer: PairedPeer, nowEpochMs: Long): Boolean =
        peer.peerId in policy.allowedPeerIds &&
            peer.keyId in policy.allowedKeyIds &&
            peer.origin == policy.expectedOrigin &&
            peer.pairedAtEpochMs <= nowEpochMs &&
            nowEpochMs < peer.expiresAtEpochMs

    private fun validChunkIdentity(chunk: StreamChunk): Boolean =
        chunk.streamId.isNotBlank() &&
            chunk.streamId.length <= MAX_IDENTIFIER_LENGTH &&
            chunk.peerId.isNotBlank() &&
            chunk.keyId.isNotBlank() &&
            chunk.sessionEpoch > 0 &&
            chunk.sequence >= 0L &&
            chunk.issuedAtEpochMs >= 0L &&
            chunk.expiresAtEpochMs > chunk.issuedAtEpochMs &&
            chunk.replayToken.isNotBlank() &&
            chunk.replayToken.length <= MAX_REPLAY_TOKEN_LENGTH &&
            chunk.contextFingerprint?.length?.let { it <= MAX_IDENTIFIER_LENGTH } != false

    private fun validatePayload(payload: OpenClawStreamPayload): StreamRejectionReason? {
        if (payload.estimatedCharacters() > policy.maximumPayloadCharacters) {
            return StreamRejectionReason.PAYLOAD_TOO_LARGE
        }
        if (payload.containsSensitiveMaterial()) {
            return StreamRejectionReason.SENSITIVE_PAYLOAD
        }
        return when (payload) {
            is ProjectionCandidatePayload -> when {
                payload.cacheId.length > MAX_IDENTIFIER_LENGTH -> StreamRejectionReason.PAYLOAD_INVALID
                payload.summary.length > MAX_SUMMARY_LENGTH -> StreamRejectionReason.PAYLOAD_TOO_LARGE
                payload.tags.size > MAX_TAG_COUNT -> StreamRejectionReason.PAYLOAD_TOO_LARGE
                payload.tags.any { it.isBlank() || it.length > MAX_TAG_LENGTH } ->
                    StreamRejectionReason.PAYLOAD_INVALID
                else -> null
            }
            is TypedActionProposalPayload -> when {
                payload.action.id.isBlank() || payload.action.capabilityId.isBlank() || payload.action.name.isBlank() ->
                    StreamRejectionReason.PAYLOAD_INVALID
                payload.action.capabilityId !in policy.allowedRemoteCapabilityIds ->
                    StreamRejectionReason.CAPABILITY_NOT_ALLOWED
                payload.action.risk > policy.maximumRemoteActionRisk ->
                    StreamRejectionReason.ACTION_RISK_NOT_ALLOWED
                payload.action.parameters.size > MAX_ACTION_PARAMETERS ->
                    StreamRejectionReason.PAYLOAD_TOO_LARGE
                payload.action.parameters.any { (key, value) ->
                    key.isBlank() || key.length > MAX_PARAMETER_KEY_LENGTH ||
                        value.length > MAX_PARAMETER_VALUE_LENGTH ||
                        key.any(Char::isISOControl) || value.any(Char::isISOControl)
                } -> StreamRejectionReason.PAYLOAD_INVALID
                else -> null
            }
            is HeartbeatPayload -> when {
                payload.nonce.length > MAX_IDENTIFIER_LENGTH -> StreamRejectionReason.PAYLOAD_TOO_LARGE
                payload.nonce.any(Char::isISOControl) -> StreamRejectionReason.PAYLOAD_INVALID
                else -> null
            }
        }
    }

    private fun resetStreamState() {
        latestSequenceByStream.clear()
        cancelledStreams.clear()
        buffer.clear()
    }

    private fun rejected(reason: StreamRejectionReason) = StreamAdmission.Rejected(reason)

    private companion object {
        const val MAX_IDENTIFIER_LENGTH = 256
        const val MAX_REPLAY_TOKEN_LENGTH = 1_024
        const val MAX_SUMMARY_LENGTH = 2_048
        const val MAX_TAG_COUNT = 16
        const val MAX_TAG_LENGTH = 64
        const val MAX_ACTION_PARAMETERS = 16
        const val MAX_PARAMETER_KEY_LENGTH = 128
        const val MAX_PARAMETER_VALUE_LENGTH = 2_048
    }
}

fun expectedReplayToken(chunk: StreamChunk): String = listOf(
    chunk.peerId,
    chunk.sessionEpoch.toString(),
    chunk.streamId,
    chunk.sequence.toString(),
    chunk.payload.kind().name,
).joinToString("|")

private fun OpenClawStreamPayload.kind(): StreamPayloadKind = when (this) {
    is ProjectionCandidatePayload -> StreamPayloadKind.PROJECTION_CANDIDATE
    is TypedActionProposalPayload -> StreamPayloadKind.TYPED_ACTION_PROPOSAL
    is HeartbeatPayload -> StreamPayloadKind.HEARTBEAT
}

private fun OpenClawStreamPayload.estimatedCharacters(): Int = when (this) {
    is ProjectionCandidatePayload -> cacheId.length + summary.length + tags.sumOf(String::length) + 32
    is TypedActionProposalPayload -> with(action) {
        id.length + capabilityId.length + name.length + description.length +
            parameters.entries.sumOf { (key, value) -> key.length + value.length } + 32
    }
    is HeartbeatPayload -> nonce.length + 24
}

private fun OpenClawStreamPayload.containsSensitiveMaterial(): Boolean = when (this) {
    is ProjectionCandidatePayload ->
        sensitiveText(summary) || tags.any(::sensitiveText)
    is TypedActionProposalPayload ->
        sensitiveText(action.description) || action.parameters.any { (key, value) ->
            SENSITIVE_KEY.containsMatchIn(key) || sensitiveText(value)
        }
    is HeartbeatPayload -> false
}

private fun sensitiveText(value: String): Boolean = SENSITIVE_PATTERNS.any { pattern ->
    pattern.containsMatchIn(value)
}

private val SENSITIVE_KEY = Regex(
    "(?i)(password|passwd|secret|token|api[_-]?key|authorization|cookie|payment|card|cvv|cvc|session)",
)

private val SENSITIVE_PATTERNS = listOf(
    Regex("(?i)(api[_-]?key|secret|token|password|authorization)\\s*[:=]\\s*[^\\s,;]{4,}"),
    Regex("(?i)bearer\\s+[a-z0-9._~+/-]{8,}"),
    Regex("\\b(?:\\d[ -]*?){13,19}\\b"),
    Regex("-----BEGIN [A-Z ]+ PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+ PRIVATE KEY-----"),
)

private fun isSafeOrigin(origin: String): Boolean {
    val scheme = when {
        origin.startsWith("wss://") -> "wss://"
        origin.startsWith("https://") -> "https://"
        else -> return false
    }
    val authority = origin.removePrefix(scheme)
    return authority.isNotBlank() &&
        '/' !in authority &&
        '@' !in authority &&
        '?' !in authority &&
        '#' !in authority &&
        authority.none(Char::isWhitespace)
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
