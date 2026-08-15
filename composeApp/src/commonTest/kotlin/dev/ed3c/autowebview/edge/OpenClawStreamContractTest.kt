package dev.ed3c.autowebview.edge

import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenClawStreamContractTest {
    @Test
    fun pairingRequiresExactPeerOriginKeyAndOpenSession() {
        val session = session()
        assertFalse(session.pair(peer(peerId = "unknown"), NOW))
        assertFalse(session.pair(peer(origin = "wss://other.local"), NOW))
        assertFalse(session.pair(peer(keyId = "unknown-key"), NOW))
        assertTrue(session.pair(peer(), NOW))
        assertEquals(StreamSessionState.PAIRED, session.sessionState())

        session.close()
        assertEquals(StreamSessionState.CLOSED, session.sessionState())
        assertFalse(session.pair(peer(sessionEpoch = 2, keyId = "key-2"), NOW))
        assertEquals(
            StreamRejectionReason.SESSION_CLOSED,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(), NOW, CONTEXT),
            ).reason,
        )
    }

    @Test
    fun pairingPolicyRejectsCredentialBearingOrPathOrigins() {
        assertFailsWith<IllegalArgumentException> {
            policy(origin = "wss://user@private-node.local")
        }
        assertFailsWith<IllegalArgumentException> {
            policy(origin = "https://private-node.local/path")
        }
        assertFailsWith<IllegalArgumentException> {
            policy(origin = "http://private-node.local")
        }
    }

    @Test
    fun projectionChunksRequireExactContextContiguousSequenceAndDeterministicReplayToken() {
        val session = pairedSession()

        assertIs<StreamAdmission.Accepted>(
            session.admit(projectionChunk(sequence = 0), NOW, CONTEXT),
        )
        assertEquals(
            StreamRejectionReason.SEQUENCE_GAP,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(sequence = 2), NOW, CONTEXT),
            ).reason,
        )
        assertIs<StreamAdmission.Accepted>(
            session.admit(projectionChunk(sequence = 1), NOW, CONTEXT),
        )
        assertEquals(
            StreamRejectionReason.OLD_SEQUENCE,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(sequence = 1), NOW, CONTEXT),
            ).reason,
        )

        val original = projectionChunk(streamId = "other", sequence = 0)
        val wrongToken = original.copy(replayToken = projectionChunk().replayToken)
        assertEquals(
            StreamRejectionReason.REPLAY_TOKEN_MISMATCH,
            assertIs<StreamAdmission.Rejected>(
                session.admit(wrongToken, NOW, CONTEXT),
            ).reason,
        )
    }

    @Test
    fun nonHeartbeatPayloadsRequireContextWhileHeartbeatMustRemainUnbound() {
        val session = pairedSession()

        assertEquals(
            StreamRejectionReason.CONTEXT_REQUIRED,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(contextFingerprint = null), NOW, null),
            ).reason,
        )
        assertEquals(
            StreamRejectionReason.CONTEXT_MISMATCH,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(contextFingerprint = "old-page"), NOW, CONTEXT),
            ).reason,
        )

        assertIs<StreamAdmission.Accepted>(
            session.admit(heartbeatChunk(streamId = "heartbeat", sequence = 0), NOW, CONTEXT),
        )
        assertEquals(
            StreamRejectionReason.INVALID_CHUNK,
            assertIs<StreamAdmission.Rejected>(
                session.admit(
                    heartbeatChunk(streamId = "bound-heartbeat", sequence = 0).copy(
                        contextFingerprint = CONTEXT,
                    ),
                    NOW,
                    CONTEXT,
                ),
            ).reason,
        )
    }

    @Test
    fun payloadBudgetsPrivacyCapabilityAndRiskFailClosed() {
        val smallBudgetSession = pairedSession(
            policy = policy(maximumPayloadCharacters = 64),
        )
        assertEquals(
            StreamRejectionReason.PAYLOAD_TOO_LARGE,
            assertIs<StreamAdmission.Rejected>(
                smallBudgetSession.admit(
                    projectionChunk(
                        streamId = "large",
                        payload = ProjectionCandidatePayload(
                            cacheId = "cache",
                            summary = "x".repeat(128),
                            relevanceHint = 0.5,
                        ),
                    ),
                    NOW,
                    CONTEXT,
                ),
            ).reason,
        )

        val session = pairedSession()
        assertEquals(
            StreamRejectionReason.SENSITIVE_PAYLOAD,
            assertIs<StreamAdmission.Rejected>(
                session.admit(
                    projectionChunk(
                        streamId = "secret",
                        payload = ProjectionCandidatePayload(
                            cacheId = "cache",
                            summary = "authorization=private-token-value",
                            relevanceHint = 0.5,
                        ),
                    ),
                    NOW,
                    CONTEXT,
                ),
            ).reason,
        )

        assertEquals(
            StreamRejectionReason.CAPABILITY_NOT_ALLOWED,
            assertIs<StreamAdmission.Rejected>(
                session.admit(
                    actionChunk(
                        streamId = "unknown-capability",
                        action = action(capabilityId = "browser.interact"),
                    ),
                    NOW,
                    CONTEXT,
                ),
            ).reason,
        )
        assertEquals(
            StreamRejectionReason.ACTION_RISK_NOT_ALLOWED,
            assertIs<StreamAdmission.Rejected>(
                session.admit(
                    actionChunk(
                        streamId = "destructive",
                        action = action(risk = ActionRisk.DESTRUCTIVE),
                    ),
                    NOW,
                    CONTEXT,
                ),
            ).reason,
        )
        assertEquals(
            StreamRejectionReason.SENSITIVE_PAYLOAD,
            assertIs<StreamAdmission.Rejected>(
                session.admit(
                    actionChunk(
                        streamId = "sensitive-parameter",
                        action = action(arguments = mapOf("token" to "private-token-value")),
                    ),
                    NOW,
                    CONTEXT,
                ),
            ).reason,
        )

        val accepted = assertIs<StreamAdmission.Accepted>(
            session.admit(actionChunk(streamId = "allowed"), NOW, CONTEXT),
        )
        assertEquals(StreamPayloadKind.TYPED_ACTION_PROPOSAL, accepted.payloadKind)
        assertEquals(1, session.bufferedCount())
    }

    @Test
    fun trackedStreamsAndBufferAreBoundedWithoutSilentEviction() {
        val session = pairedSession(
            policy = policy(maximumTrackedStreams = 1),
            maximumBufferedChunks = 1,
        )
        assertIs<StreamAdmission.Accepted>(
            session.admit(projectionChunk(streamId = "one", sequence = 0), NOW, CONTEXT),
        )
        assertEquals(
            StreamRejectionReason.TOO_MANY_STREAMS,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(streamId = "two", sequence = 0), NOW, CONTEXT),
            ).reason,
        )
        assertEquals(
            StreamRejectionReason.BUFFER_FULL,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(streamId = "one", sequence = 1), NOW, CONTEXT),
            ).reason,
        )
        assertEquals(1, session.drain().size)
        assertIs<StreamAdmission.Accepted>(
            session.admit(projectionChunk(streamId = "one", sequence = 1), NOW, CONTEXT),
        )
        assertEquals(1, session.trackedStreamCount())
    }

    @Test
    fun reconnectPreservesSequenceAndCancellationWhileHigherEpochRotatesState() {
        val session = pairedSession()
        assertIs<StreamAdmission.Accepted>(
            session.admit(projectionChunk(sequence = 0), NOW, CONTEXT),
        )
        session.disconnect()
        assertEquals(StreamSessionState.DISCONNECTED, session.sessionState())
        assertEquals(0, session.bufferedCount())
        assertEquals(
            StreamRejectionReason.NOT_PAIRED,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(sequence = 1), NOW, CONTEXT),
            ).reason,
        )

        assertTrue(session.reconnect(peer(expiresAtEpochMs = 20_000), NOW))
        assertEquals(
            StreamRejectionReason.OLD_SEQUENCE,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(sequence = 0), NOW, CONTEXT),
            ).reason,
        )
        assertIs<StreamAdmission.Accepted>(
            session.admit(projectionChunk(sequence = 1), NOW, CONTEXT),
        )
        assertTrue(session.cancelStream(STREAM))
        assertEquals(
            StreamRejectionReason.STREAM_CANCELLED,
            assertIs<StreamAdmission.Rejected>(
                session.admit(projectionChunk(sequence = 2), NOW, CONTEXT),
            ).reason,
        )

        assertFalse(
            session.pair(
                peer(keyId = "key-2", sessionEpoch = 1, expiresAtEpochMs = 20_000),
                NOW,
            ),
        )
        assertTrue(
            session.pair(
                peer(keyId = "key-2", sessionEpoch = 2, expiresAtEpochMs = 20_000),
                NOW,
            ),
        )
        assertIs<StreamAdmission.Accepted>(
            session.admit(
                projectionChunk(sequence = 0, keyId = "key-2", sessionEpoch = 2),
                NOW,
                CONTEXT,
            ),
        )
    }

    @Test
    fun pairingAndChunkExpiryLifetimeAndFutureTimeFailClosed() {
        val expiredSession = session()
        assertFalse(expiredSession.pair(peer(expiresAtEpochMs = NOW), NOW))

        val session = pairedSession(
            policy = policy(maximumChunkAgeMs = 100, maximumChunkLifetimeMs = 500),
        )
        assertEquals(
            StreamRejectionReason.CHUNK_EXPIRED,
            assertIs<StreamAdmission.Rejected>(
                session.admit(
                    projectionChunk(issuedAtEpochMs = NOW + 1, expiresAtEpochMs = NOW + 100),
                    NOW,
                    CONTEXT,
                ),
            ).reason,
        )
        assertEquals(
            StreamRejectionReason.CHUNK_EXPIRED,
            assertIs<StreamAdmission.Rejected>(
                session.admit(
                    projectionChunk(issuedAtEpochMs = NOW - 101, expiresAtEpochMs = NOW + 1),
                    NOW,
                    CONTEXT,
                ),
            ).reason,
        )
        assertEquals(
            StreamRejectionReason.CHUNK_EXPIRED,
            assertIs<StreamAdmission.Rejected>(
                session.admit(
                    projectionChunk(issuedAtEpochMs = NOW, expiresAtEpochMs = NOW + 501),
                    NOW,
                    CONTEXT,
                ),
            ).reason,
        )
    }

    @Test
    fun streamPumpAlwaysClosesTransportAndPreservesCancellation() = runTest {
        val transport = RecordingTransport(
            receiveBlock = { throw CancellationException("cancelled") },
        )
        val pump = OpenClawStreamPump(transport, pairedSession())

        assertFailsWith<CancellationException> {
            pump.run(
                nowEpochMs = { NOW },
                activeContextFingerprint = { CONTEXT },
                onAdmission = {},
            )
        }
        assertTrue(transport.connected)
        assertTrue(transport.closed)
    }

    @Test
    fun streamPumpReportsTypedAdmissionAndStopsAtEndOfStream() = runTest {
        val chunks = ArrayDeque(
            listOf(
                projectionChunk(sequence = 0),
                projectionChunk(sequence = 1),
            ),
        )
        val transport = RecordingTransport(
            receiveBlock = { chunks.removeFirstOrNull() },
        )
        val admissions = mutableListOf<StreamAdmission>()

        OpenClawStreamPump(transport, pairedSession()).run(
            nowEpochMs = { NOW },
            activeContextFingerprint = { CONTEXT },
            onAdmission = { admissions += it },
        )

        assertEquals(2, admissions.size)
        assertTrue(admissions.all { it is StreamAdmission.Accepted })
        assertTrue(transport.closed)
    }

    @Test
    fun reconnectBackoffIsBoundedAndDeterministic() {
        val policy = ReconnectPolicy(
            initialDelayMs = 100,
            maximumDelayMs = 1_000,
            multiplier = 2,
            jitterPermille = 200,
            maximumAttempts = 5,
        )
        assertEquals(100, policy.delayMs(attempt = 0, jitterSamplePermille = 0))
        assertEquals(240, policy.delayMs(attempt = 1, jitterSamplePermille = 1_000))
        assertEquals(800, policy.delayMs(attempt = 3, jitterSamplePermille = 0))
        assertTrue(policy.delayMs(attempt = 4, jitterSamplePermille = 1_000) <= 1_000)
    }

    @Test
    fun chunksAndPayloadsRoundTripThroughSerialization() {
        val json = Json {
            classDiscriminator = "payloadType"
            encodeDefaults = true
        }
        val projection = projectionChunk()
        assertEquals(
            projection,
            json.decodeFromString<StreamChunk>(json.encodeToString(projection)),
        )
        val action = actionChunk()
        assertEquals(
            action,
            json.decodeFromString<StreamChunk>(json.encodeToString(action)),
        )
    }

    private fun session(
        policy: PairingPolicy = policy(),
        maximumBufferedChunks: Int = 4,
    ) = OpenClawStreamSession(policy, maximumBufferedChunks)

    private fun pairedSession(
        policy: PairingPolicy = policy(),
        maximumBufferedChunks: Int = 4,
    ) = session(policy, maximumBufferedChunks).also { created ->
        assertTrue(created.pair(peer(), NOW))
    }

    private fun policy(
        origin: String = ORIGIN,
        maximumPayloadCharacters: Int = 8_192,
        maximumTrackedStreams: Int = 8,
        maximumChunkAgeMs: Long = 30_000,
        maximumChunkLifetimeMs: Long = 60_000,
    ) = PairingPolicy(
        expectedOrigin = origin,
        allowedPeerIds = setOf(PEER),
        allowedKeyIds = setOf("key-1", "key-2"),
        allowedRemoteCapabilityIds = setOf("browser.navigate"),
        maximumRemoteActionRisk = ActionRisk.HIGH,
        maximumPayloadCharacters = maximumPayloadCharacters,
        maximumTrackedStreams = maximumTrackedStreams,
        maximumChunkAgeMs = maximumChunkAgeMs,
        maximumChunkLifetimeMs = maximumChunkLifetimeMs,
    )

    private fun peer(
        peerId: String = PEER,
        origin: String = ORIGIN,
        keyId: String = "key-1",
        sessionEpoch: Long = 1,
        pairedAtEpochMs: Long = NOW - 10,
        expiresAtEpochMs: Long = NOW + 10_000,
    ) = PairedPeer(
        peerId = peerId,
        origin = origin,
        keyId = keyId,
        sessionEpoch = sessionEpoch,
        pairedAtEpochMs = pairedAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
    )

    private fun projectionChunk(
        streamId: String = STREAM,
        sequence: Long = 0,
        keyId: String = "key-1",
        sessionEpoch: Long = 1,
        issuedAtEpochMs: Long = NOW - 1,
        expiresAtEpochMs: Long = NOW + 1_000,
        contextFingerprint: String? = CONTEXT,
        payload: OpenClawStreamPayload = ProjectionCandidatePayload(
            cacheId = "cache-1",
            summary = "Relevant sanitized context",
            relevanceHint = 0.8,
            tags = setOf("kmp", "cache"),
        ),
    ): StreamChunk {
        val chunk = StreamChunk(
            streamId = streamId,
            peerId = PEER,
            origin = ORIGIN,
            keyId = keyId,
            sessionEpoch = sessionEpoch,
            sequence = sequence,
            issuedAtEpochMs = issuedAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            contextFingerprint = contextFingerprint,
            payload = payload,
            replayToken = "pending",
        )
        return chunk.copy(replayToken = expectedReplayToken(chunk))
    }

    private fun actionChunk(
        streamId: String = "action-stream",
        sequence: Long = 0,
        action: AgentAction = action(),
    ): StreamChunk = projectionChunk(
        streamId = streamId,
        sequence = sequence,
        payload = TypedActionProposalPayload(action),
    )

    private fun heartbeatChunk(
        streamId: String,
        sequence: Long,
    ): StreamChunk = projectionChunk(
        streamId = streamId,
        sequence = sequence,
        contextFingerprint = null,
        payload = HeartbeatPayload(
            nonce = "heartbeat-$sequence",
            sentAtEpochMs = NOW,
        ),
    )

    private fun action(
        capabilityId: String = "browser.navigate",
        risk: ActionRisk = ActionRisk.MEDIUM,
        arguments: Map<String, String> = mapOf("url" to "https://example.com"),
    ) = AgentAction(
        id = "action-1",
        capabilityId = capabilityId,
        name = "Navigate",
        description = "Navigate to an approved public page",
        risk = risk,
        arguments = arguments,
    )

    private class RecordingTransport(
        private val receiveBlock: suspend () -> StreamChunk?,
    ) : OpenClawTransport {
        var connected = false
        var closed = false

        override suspend fun connect() {
            connected = true
        }

        override suspend fun receive(): StreamChunk? = receiveBlock()

        override suspend fun close() {
            closed = true
        }
    }

    private companion object {
        const val PEER = "private-node"
        const val ORIGIN = "wss://private-node.local:8443"
        const val STREAM = "projection-stream"
        const val CONTEXT = "page-fingerprint"
        const val NOW = 1_000L
    }
}
