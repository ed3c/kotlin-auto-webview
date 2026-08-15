package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpStreamableHttpBridgeSseTest {
    @Test
    fun sseIsNotUsedUnlessTheEndpointEnablesIt() = runTest {
        val response = bridge(sseEnabled = false).handle(request(SSE_PREFERRED), NOW)

        assertEquals(McpHttpResponseMode.JSON_SINGLE_RESPONSE, response.mode)
        assertEquals("application/json", response.headers["Content-Type"])
        assertEquals(GATEWAY_RESPONSE, response.body)
        assertTrue(response.events.isEmpty())
    }

    @Test
    fun sseIsUsedOnlyWhenTheClientRanksItAboveJson() = runTest {
        val preferred = bridge().handle(request(SSE_PREFERRED), NOW)
        assertEquals(McpHttpResponseMode.SSE_REQUEST_SCOPED_RESPONSE, preferred.mode)

        for (accept in listOf(EQUAL_PREFERENCE, JSON_PREFERRED, NO_QUALITY_VALUES)) {
            assertEquals(
                McpHttpResponseMode.JSON_SINGLE_RESPONSE,
                bridge().handle(request(accept), NOW).mode,
                "unexpected SSE for Accept: $accept",
            )
        }
    }

    @Test
    fun theRequestScopedStreamCarriesExactlyOneTerminatingResponseEvent() = runTest {
        val response = bridge().handle(request(SSE_PREFERRED), NOW)

        assertEquals(200, response.status)
        assertEquals("text/event-stream", response.headers["Content-Type"])
        assertEquals("no-store", response.headers["Cache-Control"])
        // The stream ends with the request: no session identifier, no keep-alive.
        assertEquals("close", response.headers["Connection"])
        assertNull(response.body)

        val event = response.events.single()
        assertEquals(0, event.id)
        assertEquals("message", event.event)
        assertEquals(GATEWAY_RESPONSE, event.data)
        assertEquals("id: 0\nevent: message\ndata: $GATEWAY_RESPONSE\n\n", event.frame())
    }

    @Test
    fun multiLineDataIsFramedAsOneEventWithOneDataLinePerLine() {
        val event = McpHttpSseEvent(id = 3, event = "message", data = "first\nsecond")

        assertEquals("id: 3\nevent: message\ndata: first\ndata: second\n\n", event.frame())
    }

    @Test
    fun aStreamThatWouldExceedTheResponseBudgetIsRefusedRatherThanTruncated() = runTest {
        // Budget chosen so the JSON body itself still fits: the refusal must come from the SSE
        // framing overhead, not from the gateway-response check that runs before it.
        val response = bridge(maxResponseBodyBytes = 48).handle(request(SSE_PREFERRED), NOW)

        assertEquals(500, response.status)
        assertEquals(McpHttpBridgeErrorCode.SSE_BUDGET_EXCEEDED, response.errorCode)
        assertEquals(McpHttpResponseMode.JSON_SINGLE_RESPONSE, response.mode)
        assertTrue(response.events.isEmpty())
    }

    @Test
    fun aNotificationStillEndsWithoutABodyEvenWhenSseIsPreferred() = runTest {
        val response = bridge().handle(
            request(SSE_PREFERRED, body = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""),
            NOW,
        )

        assertEquals(202, response.status)
        assertEquals(McpHttpResponseMode.JSON_SINGLE_RESPONSE, response.mode)
        assertNull(response.body)
    }

    private fun bridge(
        sseEnabled: Boolean = true,
        maxResponseBodyBytes: Int = 256 * 1_024,
    ) = McpStreamableHttpBridge(
        gateway = { GATEWAY_RESPONSE },
        endpointPolicy = McpHttpEndpointPolicy(
            scheme = "http",
            authority = AUTHORITY,
            path = "/mcp",
            maxResponseBodyBytes = maxResponseBodyBytes,
            sseResponseEnabled = sseEnabled,
        ),
        authenticationVerifier = {
            McpHttpAuthenticationDecision.Accepted(subjectId = "test", credentialEpoch = "epoch-1")
        },
    )

    private fun request(accept: String, body: String = PING_BODY) = McpHttpBridgeRequest(
        method = "POST",
        scheme = "http",
        authority = AUTHORITY,
        path = "/mcp",
        headers = mapOf(
            "Content-Type" to listOf("application/json"),
            "Accept" to listOf(accept),
            "Authorization" to listOf("Bearer test"),
        ),
        body = body,
    )

    private companion object {
        const val AUTHORITY = "127.0.0.1:3090"
        const val NOW = 1_700_000_000_000L
        const val PING_BODY = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        const val GATEWAY_RESPONSE = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        const val SSE_PREFERRED = "application/json;q=0.5, text/event-stream;q=1.0"
        const val EQUAL_PREFERENCE = "application/json;q=0.8, text/event-stream;q=0.8"
        const val JSON_PREFERRED = "application/json, text/event-stream;q=0.3"
        const val NO_QUALITY_VALUES = "application/json, text/event-stream"
    }
}
