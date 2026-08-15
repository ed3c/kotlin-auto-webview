package dev.ed3c.autowebview.edge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenClawStreamContractTest {
    private val policy = PairingPolicy(
        expectedOrigin = "openclaw.local",
        allowedPeerIds = setOf("mac-1"),
        allowedKeyIds = setOf("key-1"),
    )

    @Test
    fun anonymousPeerFailsClosed() {
        val session = OpenClawStreamSession(policy)
        assertEquals(
            StreamRejectionReason.NOT_PAIRED,
            (session.admit(chunk(sequence = 1), nowEpochMs = 100) as StreamAdmission.Rejected).reason,
        )
    }

    @Test
    fun wrongOriginCannotPair() {
        val session = OpenClawStreamSession(policy)
        val paired = session.pair(peer(origin = "evil.invalid"), nowEpochMs = 100)
        assertFalse(paired)
    }

    @Test
    fun orderedDeliveryRejectsOldSequenceAndReplay() {
        val session = OpenClawStreamSession(policy)
        assertTrue(session.pair(peer(), 100))

        assertIs<StreamAdmission.Accepted>(session.admit(chunk(sequence = 2, replayToken = "r2"), 100))
        assertEquals(
            StreamRejectionReason.OLD_SEQUENCE,
            (session.admit(chunk(sequence = 1, replayToken = "r1"), 100) as StreamAdmission.Rejected).reason,
        )
        assertEquals(
            StreamRejectionReason.OLD_SEQUENCE,
            (session.admit(chunk(sequence = 2, replayToken = "r2-other"), 100) as StreamAdmission.Rejected).reason,
        )

        val otherStreamReplay = chunk(
            streamId = "stream-2",
            sequence = 1,
            replayToken = "r2",
        )
        assertEquals(
            StreamRejectionReason.DUPLICATE_REPLAY_TOKEN,
            (session.admit(otherStreamReplay, 100) as StreamAdmission.Rejected).reason,
        )
    }

    @Test
    fun expiredAndStaleContextChunksFailClosed() {
        val session = OpenClawStreamSession(policy)
        assertTrue(session.pair(peer(), 100))

        assertEquals(
            StreamRejectionReason.CHUNK_EXPIRED,
            (session.admit(chunk(sequence = 1, expiresAt = 100), 100) as StreamAdmission.Rejected).reason,
        )
        assertEquals(
            StreamRejectionReason.CONTEXT_MISMATCH,
            (
                session.admit(
                    chunk(sequence = 1, contextFingerprint = "page-a"),
                    100,
                    activeContextFingerprint = "page-b",
                ) as StreamAdmission.Rejected
            ).reason,
        )
    }

    @Test
    fun boundedBufferProvidesBackpressure() {
        val session = OpenClawStreamSession(policy, maximumBufferedChunks = 1)
        assertTrue(session.pair(peer(), 100))
        assertIs<StreamAdmission.Accepted>(session.admit(chunk(sequence = 1, replayToken = "a"), 100))
        assertEquals(
            StreamRejectionReason.BUFFER_FULL,
            (session.admit(chunk(sequence = 2, replayToken = "b"), 100) as StreamAdmission.Rejected).reason,
        )
        assertEquals(1, session.bufferedCount())
        assertEquals(listOf(1L), session.drain().map { it.sequence })
    }

    @Test
    fun pairingExpiryRequiresReauthentication() {
        val session = OpenClawStreamSession(policy)
        assertTrue(session.pair(peer(expiresAt = 110), 100))
        assertEquals(
            StreamRejectionReason.PAIRING_EXPIRED,
            (session.admit(chunk(sequence = 1), 110) as StreamAdmission.Rejected).reason,
        )
        assertTrue(session.reconnect(peer(expiresAt = 200), 111))
    }

    @Test
    fun reconnectDelayUsesBoundedExponentialBackoffAndJitter() {
        val policy = ReconnectPolicy(
            initialDelayMs = 100,
            maximumDelayMs = 1_000,
            multiplier = 2,
            jitterPermille = 200,
            maximumAttempts = 6,
        )

        assertEquals(100, policy.delayMs(attempt = 0, jitterSamplePermille = 0))
        assertEquals(180, policy.delayMs(attempt = 1, jitterSamplePermille = -500))
        assertEquals(440, policy.delayMs(attempt = 2, jitterSamplePermille = 500))
        assertEquals(1_000, policy.delayMs(attempt = 5, jitterSamplePermille = 1_000))
    }

    private fun peer(
        origin: String = "openclaw.local",
        expiresAt: Long = 1_000,
    ) = PairedPeer(
        peerId = "mac-1",
        origin = origin,
        keyId = "key-1",
        pairedAtEpochMs = 10,
        expiresAtEpochMs = expiresAt,
    )

    private fun chunk(
        streamId: String = "stream-1",
        sequence: Long,
        replayToken: String = "r-$sequence",
        expiresAt: Long = 500,
        contextFingerprint: String? = null,
    ) = StreamChunk(
        streamId = streamId,
        peerId = "mac-1",
        origin = "openclaw.local",
        keyId = "key-1",
        sequence = sequence,
        issuedAtEpochMs = 50,
        expiresAtEpochMs = expiresAt,
        contextFingerprint = contextFingerprint,
        payloadKind = StreamPayloadKind.PROJECTION_CANDIDATE,
        payload = "{}",
        replayToken = replayToken,
    )
}
