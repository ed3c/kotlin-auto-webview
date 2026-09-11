package dev.ed3c.autowebview.mcp

import dev.ed3c.autowebview.dispatcher.DispatcherMode
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.domain.InteractiveElement
import dev.ed3c.autowebview.executor.BrowserScriptEvaluator
import dev.ed3c.autowebview.executor.CurrentWebViewBrowserActionPlatform
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun advertisesStatusAsToolNotResource() = runTest {
        val gateway = BrowserMcpGateway(AgentBrowserRuntime())
        val tools = gateway.handle(
            """{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}""",
        )
        val resources = gateway.handle(
            """{"jsonrpc":"2.0","id":8,"method":"resources/list","params":{}}""",
        )
        val toolNames = json.parseToJsonElement(tools).jsonObject["result"]!!.jsonObject["tools"]!!
            .jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val resourceNames = json.parseToJsonElement(resources).jsonObject["result"]!!.jsonObject["resources"]!!
            .jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

        assertTrue("browser_action_status" in toolNames)
        assertTrue("browser_propose_action" in toolNames)
        assertTrue("browser_action_status" !in resourceNames)
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
    fun navigationToolCreatesProposalAndExposesBoundedStatus() = runTest {
        val runtime = AgentBrowserRuntime()
        val response = BrowserMcpGateway(runtime).handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"https://example.com/next"}}}""",
        )
        assertTrue("WAITING_FOR_CONFIRMATION" in response)
        assertEquals(DispatcherMode.WAITING_FOR_CONFIRMATION, runtime.dispatcherState.value.mode)
        assertEquals("https://example.com/next", runtime.dispatcherState.value.pendingAction?.arguments?.get("url"))

        val resultText = json.parseToJsonElement(response).jsonObject["result"]!!.jsonObject["content"]!!
            .jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        val proposalId = json.parseToJsonElement(resultText).jsonObject["proposalId"]!!.jsonPrimitive.content
        val status = BrowserMcpGateway(runtime).handle(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"browser_action_status","arguments":{"proposalId":"$proposalId"}}}""",
        )
        assertTrue("WAITING_FOR_CONFIRMATION" in status)
        assertTrue("\\\"terminal\\\":false" in status)
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
    fun rejectsCredentialBearingAndControlCharacterNavigation() = runTest {
        val gateway = BrowserMcpGateway(AgentBrowserRuntime())
        val credential = gateway.handle(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"https://user:secret@example.com"}}}""",
        )
        val control = gateway.handle(
            """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"https://example.com/\u0000"}}}""",
        )

        assertTrue("credentials are forbidden" in credential)
        assertTrue("control characters" in control)
    }

    @Test
    fun typedFillProposalIsDiscoverableBoundedAndDoesNotEchoValue() = runTest {
        val runtime = AgentBrowserRuntime()
        runtime.bindInteractionPlatform(
            CurrentWebViewBrowserActionPlatform(
                currentContext = { runtime.latestPageContext() },
                evaluator = BrowserScriptEvaluator { error("proposal must not evaluate JavaScript") },
            ),
        )
        runtime.onPageContext(
            PageContext(
                url = "https://app.example.test/form",
                title = "Fixture",
                markdown = "fixture",
                capturedAtEpochMs = 1,
                interactiveElements = listOf(
                    InteractiveElement(
                        fingerprint = "deadbeef",
                        tag = "input",
                        role = "textbox",
                        accessibleName = "Name",
                        inputType = "text",
                    ),
                ),
            ),
        )
        val gateway = BrowserMcpGateway(runtime)
        val response = gateway.handle(
            """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"browser_propose_action","arguments":{"kind":"fill_text","targetFingerprint":"deadbeef","value":"private-value"}}}""",
        )

        assertTrue("WAITING_FOR_CONFIRMATION" in response)
        assertTrue("private-value" !in response)
        assertTrue(runtime.dispatcherState.value.pendingAction?.arguments?.values?.none { "private-value" in it } == true)
    }

    @Test
    fun typedActionRejectsSelectorInjectionAndCrossOriginClick() = runTest {
        val runtime = AgentBrowserRuntime()
        runtime.onPageContext(
            PageContext(
                url = "https://app.example.test/form",
                title = "Fixture",
                markdown = "fixture",
                capturedAtEpochMs = 1,
                interactiveElements = listOf(
                    InteractiveElement(
                        fingerprint = "deadbeef",
                        tag = "a",
                        role = "link",
                        accessibleName = "Next",
                    ),
                ),
            ),
        )
        val gateway = BrowserMcpGateway(runtime)
        val selector = gateway.handle(
            """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"browser_propose_action","arguments":{"kind":"click","targetFingerprint":"deadbeef","expectedUrl":"https://app.example.test/next","selector":"#pay"}}}""",
        )
        val crossOrigin = gateway.handle(
            """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"browser_propose_action","arguments":{"kind":"click","targetFingerprint":"deadbeef","expectedUrl":"https://other.example.test/next"}}}""",
        )

        assertTrue("unsupported arguments" in selector)
        assertTrue("current HTTPS origin" in crossOrigin)
        assertEquals(DispatcherMode.READY, runtime.dispatcherState.value.mode)
    }
}
