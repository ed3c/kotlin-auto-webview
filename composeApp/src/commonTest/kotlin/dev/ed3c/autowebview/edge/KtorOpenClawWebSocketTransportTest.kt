package dev.ed3c.autowebview.edge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorOpenClawWebSocketTransportTest {
    private val policy = OpenClawWebSocketEndpointPolicy(
        allowedHosts = setOf("edge.example.test"),
        maximumFrameChars = 8_192,
    )

    @Test
    fun admitsOnlyExplicitAllowlistedWssEndpoint() {
        val url = policy.admit("wss://edge.example.test/openclaw")
        assertEquals("edge.example.test", url.host)
        assertEquals("wss", url.protocol.name)
    }

    @Test
    fun rejectsPlaintextOrNonAllowlistedEndpoint() {
        assertFailsWith<IllegalArgumentException> {
            policy.admit("ws://edge.example.test/openclaw")
        }
        assertFailsWith<IllegalArgumentException> {
            policy.admit("wss://other.example.test/openclaw")
        }
    }

    @Test
    fun rejectsCredentialQueryAndFragmentBearingUrls() {
        assertFailsWith<IllegalArgumentException> {
            policy.admit("wss://user:secret@edge.example.test/openclaw")
        }
        assertFailsWith<IllegalArgumentException> {
            policy.admit("wss://edge.example.test/openclaw?token=secret")
        }
        assertFailsWith<IllegalArgumentException> {
            policy.admit("wss://edge.example.test/openclaw#private")
        }
    }

    @Test
    fun frameBudgetIsBounded() {
        assertFailsWith<IllegalArgumentException> {
            OpenClawWebSocketEndpointPolicy(
                allowedHosts = setOf("edge.example.test"),
                maximumFrameChars = 2 * 1024 * 1024,
            )
        }
    }
}
