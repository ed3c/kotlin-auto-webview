package dev.ed3c.autowebview.mcp

import dev.ed3c.autowebview.dispatcher.DispatcherMode
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.navigation.BrowserActionStatus
import dev.ed3c.autowebview.navigation.BrowserNavigationPort
import dev.ed3c.autowebview.navigation.NavigationCommand
import dev.ed3c.autowebview.navigation.NavigationDispatchResult
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BrowserMcpGatewayTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun supportsModernStatelessDiscovery() = runTest {
        val response = BrowserMcpGateway(AgentBrowserRuntime()).handle(
            """{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{}}""",
        )
        val result = json.parseToJsonElement(response).jsonObject["result"]!!.jsonObject
        assertEquals(BrowserMcpGateway.MODERN_PROTOCOL_VERSION, result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
    }

    @Test
    fun exposesOnlySanitizedCurrentPageResource() = runTest {
        val runtime = AgentBrowserRuntime()
        runtime.onPageContext(
            PageContext(
                url = "https://example.com",
                title = "Example",
                markdown = "password = super-secret-value",
                capturedAtEpochMs = 1,
            ),
        )
        val response = BrowserMcpGateway(runtime).handle(
            """{"jsonrpc":"2.0","id":"read-1","method":"resources/read","params":{"uri":"browser://current-page"}}""",
        )
        assertTrue("[REDACTED]" in response)
        assertTrue("super-secret-value" !in response)
    }

    @Test
    fun navigationToolReturnsProposalIdImmediatelyWithoutExecuting() = runTest {
        val runtime = AgentBrowserRuntime(sessionId = "mcp-session")
        val gateway = BrowserMcpGateway(runtime)
        val response = gateway.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"https://example.com/next"}}}""",
        )
        val proposalId = toolPayload(response)["proposalId"]!!.jsonPrimitive.content
        assertTrue(proposalId.isNotBlank())
        assertEquals("WAITING_FOR_CONFIRMATION", toolPayload(response)["status"]!!.jsonPrimitive.content)
        assertEquals(DispatcherMode.WAITING_FOR_CONFIRMATION, runtime.dispatcherState.value.mode)
        assertEquals("https://example.com/next", runtime.dispatcherState.value.pendingAction?.arguments?.get("url"))

        val statusResponse = gateway.handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"browser_action_status","arguments":{"proposalId":"$proposalId"}}}""",
        )
        assertEquals("WAITING_FOR_CONFIRMATION", toolPayload(statusResponse)["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun sameUrlProposalsReturnDistinctIdsViaMcp() = runTest {
        val runtime = AgentBrowserRuntime(sessionId = "mcp-dup")
        val gateway = BrowserMcpGateway(runtime)
        val first = toolPayload(
            gateway.handle(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"https://example.com/same"}}}""",
            ),
        )["proposalId"]!!.jsonPrimitive.content
        val second = toolPayload(
            gateway.handle(
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"https://example.com/same"}}}""",
            ),
        )["proposalId"]!!.jsonPrimitive.content
        assertNotEquals(first, second)
    }

    @Test
    fun rejectsNonHttpsNavigation() = runTest {
        val response = BrowserMcpGateway(AgentBrowserRuntime()).handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"javascript:alert(1)"}}}""",
        )
        val code = json.parseToJsonElement(response).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        assertEquals("-32602", code)
    }

    @Test
    fun actionStatusNoneForUnknownProposal() = runTest {
        val response = BrowserMcpGateway(AgentBrowserRuntime()).handle(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"browser_action_status","arguments":{"proposalId":"missing"}}}""",
        )
        assertEquals(BrowserActionStatus.NONE.name, toolPayload(response)["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun confirmedNavigationReachesAppliedViaStatus() = runTest {
        val runtime = AgentBrowserRuntime(
            sessionId = "mcp-applied",
            observationTimeoutMs = 200,
            observationPollMs = 5,
        )
        runtime.bindNavigationPort(
            port = object : BrowserNavigationPort {
                override suspend fun navigate(command: NavigationCommand): NavigationDispatchResult =
                    NavigationDispatchResult.Accepted
            },
            urlObserver = { "https://example.com/applied" },
        )
        val gateway = BrowserMcpGateway(runtime)
        val propose = gateway.handle(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"https://example.com/applied"}}}""",
        )
        val proposalId = toolPayload(propose)["proposalId"]!!.jsonPrimitive.content
        runtime.confirmPendingAction()
        val status = gateway.handle(
            """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"browser_action_status","arguments":{"proposalId":"$proposalId"}}}""",
        )
        assertEquals(BrowserActionStatus.APPLIED.name, toolPayload(status)["status"]!!.jsonPrimitive.content)
    }

    private fun toolPayload(response: String) = json.parseToJsonElement(
        json.parseToJsonElement(response)
            .jsonObject["result"]!!
            .jsonObject["content"]!!
            .jsonArray
            .first()
            .jsonObject["text"]!!
            .jsonPrimitive
            .content,
    ).jsonObject
}
