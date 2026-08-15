package dev.ed3c.autowebview.mcp.http

import dev.ed3c.autowebview.dispatcher.DispatcherMode
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpStreamableHttpBridgeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun deepSeekHarnessLegacySequenceReachesOnlySanitizedReadsAndTypedProposals() = runTest {
        val runtime = AgentBrowserRuntime()
        val browserGateway = BrowserMcpGateway(runtime)
        val receipts = mutableListOf<McpHttpBridgeReceipt>()
        var gatewayCalls = 0
        val bridge = bridge(
            gateway = McpJsonRpcGateway { payload ->
                gatewayCalls += 1
                browserGateway.handle(payload)
            },
            observer = McpHttpBridgeObserver { receipts += it },
        )

        val initialize = bridge.handle(
            request(
                body = initializeBody(id = 1),
                mirroredMethod = "initialize",
            ),
            nowEpochMs = 1_000,
        )
        assertEquals(200, initialize.status)
        assertEquals(
            BrowserMcpGateway.LEGACY_PROTOCOL_VERSION,
            json.parseToJsonElement(initialize.body!!)
                .jsonObject["result"]!!
                .jsonObject["protocolVersion"]!!
                .jsonPrimitive.content,
        )

        val initializedNotification = bridge.handle(
            request(
                body = """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""",
                mirroredMethod = "notifications/initialized",
            ),
            nowEpochMs = 1_001,
        )
        assertEquals(202, initializedNotification.status)
        assertNull(initializedNotification.body)
        assertEquals(1, gatewayCalls)

        val tools = bridge.handle(
            request(
                body = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
                origin = "http://127.0.0.1:3080",
                mirroredMethod = "tools/list",
            ),
            nowEpochMs = 1_002,
        )
        assertEquals(200, tools.status)
        val toolsBody = requireNotNull(tools.body)
        assertTrue("browser_capture_context" in toolsBody)
        assertTrue("browser_propose_navigation" in toolsBody)

        runtime.onPageContext(
            PageContext(
                url = "https://example.com/account",
                title = "Account",
                markdown = "password = synthetic-secret-value",
                capturedAtEpochMs = 1_003,
            ),
        )
        val capture = bridge.handle(
            request(
                body = """
                    {
                      "jsonrpc": "2.0",
                      "id": 3,
                      "method": "tools/call",
                      "params": {"name": "browser_capture_context", "arguments": {}}
                    }
                """.trimIndent(),
                mirroredMethod = "tools/call",
                mirroredName = "browser_capture_context",
            ),
            nowEpochMs = 1_004,
        )
        assertEquals(200, capture.status)
        val captureBody = requireNotNull(capture.body)
        assertTrue("[REDACTED]" in captureBody)
        assertFalse("synthetic-secret-value" in captureBody)

        val proposalBody = """
            {
              "jsonrpc": "2.0",
              "id": 4,
              "method": "tools/call",
              "params": {
                "name": "browser_propose_navigation",
                "arguments": {"url": "https://example.com/next"}
              }
            }
        """.trimIndent()
        val proposal = bridge.handle(
            request(
                body = proposalBody,
                mirroredMethod = "tools/call",
                mirroredName = "browser_propose_navigation",
            ),
            nowEpochMs = 1_005,
        )
        assertEquals(200, proposal.status)
        assertTrue("awaits user confirmation" in proposal.body!!)
        assertEquals(DispatcherMode.WAITING_FOR_CONFIRMATION, runtime.dispatcherState.value.mode)
        assertEquals(
            "https://example.com/next",
            runtime.dispatcherState.value.pendingAction?.arguments?.get("url"),
        )

        val replay = bridge.handle(
            request(
                body = proposalBody,
                mirroredMethod = "tools/call",
                mirroredName = "browser_propose_navigation",
            ),
            nowEpochMs = 1_006,
        )
        assertEquals(409, replay.status)
        assertEquals(McpHttpBridgeErrorCode.REPLAY_DETECTED, replay.errorCode)
        assertEquals(4, gatewayCalls)

        assertTrue(
            receipts.any {
                it.rpcMethod == "browser_propose_navigation" ||
                    it.sideEffectState == McpHttpSideEffectState.PROPOSAL_ONLY
            },
        )
        val proposalReceipt = receipts.last { it.rpcMethod == "tools/call" && it.httpStatus == 200 }
        assertEquals(McpHttpSideEffectState.PROPOSAL_ONLY, proposalReceipt.sideEffectState)
        assertFalse(receipts.toString().contains("synthetic-secret-value"))
        assertFalse(receipts.toString().contains("https://example.com/next"))
        assertFalse(receipts.toString().contains("test-token"))
    }

    @Test
    fun transportAndAuthenticationFailuresStopBeforeTheGateway() = runTest {
        var gatewayCalls = 0
        val bridge = bridge(
            gateway = McpJsonRpcGateway {
                gatewayCalls += 1
                """{"jsonrpc":"2.0","id":1,"result":{}}"""
            },
        )
        val validBody = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        val oversizedBody = "x".repeat(64 * 1024 + 1)
        val cases = listOf(
            request(body = validBody, method = "GET") to McpHttpBridgeErrorCode.METHOD_NOT_ALLOWED,
            request(body = validBody, authority = "attacker.example") to McpHttpBridgeErrorCode.ENDPOINT_MISMATCH,
            request(body = validBody, query = "token=forbidden") to McpHttpBridgeErrorCode.QUERY_FORBIDDEN,
            request(body = validBody, origin = "https://attacker.example") to McpHttpBridgeErrorCode.ORIGIN_REJECTED,
            request(body = validBody, contentType = "text/plain") to McpHttpBridgeErrorCode.CONTENT_TYPE_REQUIRED,
            request(body = validBody, accept = "application/json") to McpHttpBridgeErrorCode.ACCEPT_REQUIRED,
            request(body = validBody, authorization = null) to McpHttpBridgeErrorCode.AUTHENTICATION_REQUIRED,
            request(body = validBody, authorization = "Bearer wrong") to McpHttpBridgeErrorCode.AUTHENTICATION_REJECTED,
            request(body = validBody, sessionId = "stateful-session") to
                McpHttpBridgeErrorCode.SESSION_MODE_UNSUPPORTED,
            request(body = oversizedBody) to McpHttpBridgeErrorCode.BODY_TOO_LARGE,
            request(body = validBody).copy(declaredContentLength = 1) to
                McpHttpBridgeErrorCode.BODY_LENGTH_INVALID,
            request(
                body = validBody,
                extraHeaders = mapOf("authorization" to listOf("Bearer test-token")),
            ) to McpHttpBridgeErrorCode.DUPLICATE_HEADER,
        )

        cases.forEachIndexed { index, (request, expectedCode) ->
            val response = bridge.handle(request, nowEpochMs = 2_000L + index)
            assertEquals(expectedCode, response.errorCode, "case $index")
        }
        assertEquals(0, gatewayCalls)
    }

    @Test
    fun malformedMetadataAndUnadmittedToolsFailBeforeTheGateway() = runTest {
        var gatewayCalls = 0
        val bridge = bridge(
            gateway = McpJsonRpcGateway {
                gatewayCalls += 1
                """{"jsonrpc":"2.0","id":1,"result":{}}"""
            },
        )

        val malformed = bridge.handle(request(body = "{"), nowEpochMs = 3_000)
        assertEquals(McpHttpBridgeErrorCode.MALFORMED_JSON, malformed.errorCode)

        val mismatchedMethod = bridge.handle(
            request(
                body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""",
                mirroredMethod = "tools/call",
            ),
            nowEpochMs = 3_001,
        )
        assertEquals(McpHttpBridgeErrorCode.REQUEST_HEADER_MISMATCH, mismatchedMethod.errorCode)

        val unadmittedTool = bridge.handle(
            request(
                body = """
                    {
                      "jsonrpc": "2.0",
                      "id": 2,
                      "method": "tools/call",
                      "params": {"name": "arbitrary_native_execute", "arguments": {}}
                    }
                """.trimIndent(),
                mirroredMethod = "tools/call",
                mirroredName = "arbitrary_native_execute",
            ),
            nowEpochMs = 3_002,
        )
        assertEquals(McpHttpBridgeErrorCode.TOOL_NOT_ADMITTED, unadmittedTool.errorCode)
        assertEquals(0, gatewayCalls)
    }

    @Test
    fun invalidGatewayResponseIsA502WithUnknownSideEffectEvidence() = runTest {
        val receipts = mutableListOf<McpHttpBridgeReceipt>()
        val bridge = bridge(
            gateway = McpJsonRpcGateway {
                """{"jsonrpc":"2.0","id":"wrong-id","result":{}}"""
            },
            observer = McpHttpBridgeObserver { receipts += it },
        )

        val response = bridge.handle(
            request(
                body = """{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}""",
                mirroredMethod = "tools/list",
            ),
            nowEpochMs = 4_000,
        )

        assertEquals(502, response.status)
        assertEquals(McpHttpBridgeErrorCode.GATEWAY_RESPONSE_INVALID, response.errorCode)
        assertEquals(McpHttpBridgeOutcome.GATEWAY_FAILURE, receipts.single().outcome)
        assertEquals(McpHttpSideEffectState.UNKNOWN, receipts.single().sideEffectState)
    }

    @Test
    fun cancellationPropagatesAndRecordsUnknownAfterGatewayInvocation() = runTest {
        val receipts = mutableListOf<McpHttpBridgeReceipt>()
        val bridge = bridge(
            gateway = McpJsonRpcGateway { throw CancellationException("synthetic cancellation") },
            observer = McpHttpBridgeObserver { receipts += it },
        )

        var cancelled = false
        try {
            bridge.handle(
                request(
                    body = """{"jsonrpc":"2.0","id":8,"method":"tools/list","params":{}}""",
                    mirroredMethod = "tools/list",
                ),
                nowEpochMs = 5_000,
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(McpHttpBridgeOutcome.CANCELLED_OR_TIMED_OUT, receipts.single().outcome)
        assertTrue(receipts.single().gatewayInvoked)
        assertEquals(McpHttpSideEffectState.UNKNOWN, receipts.single().sideEffectState)
    }

    @Test
    fun plainHttpPolicyIsRestrictedToExplicitLoopbackAuthorities() {
        var rejected = false
        try {
            McpHttpEndpointPolicy(
                scheme = "http",
                authority = "private.example:3090",
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)

        McpHttpEndpointPolicy(
            scheme = "https",
            authority = "private.example:443",
        )
    }

    @Test
    fun replayGuardRejectsDuplicatesAndCapacityWithoutEvictingLiveEntries() = runTest {
        val guard = BoundedMcpHttpReplayGuard(windowMs = 10, maxEntries = 1)

        assertEquals(McpHttpReplayDecision.ACCEPTED, guard.admit(McpHttpReplayKey("a"), 0))
        assertEquals(McpHttpReplayDecision.DUPLICATE, guard.admit(McpHttpReplayKey("a"), 1))
        assertEquals(
            McpHttpReplayDecision.CAPACITY_EXHAUSTED,
            guard.admit(McpHttpReplayKey("b"), 1),
        )
        assertEquals(McpHttpReplayDecision.ACCEPTED, guard.admit(McpHttpReplayKey("b"), 10))
    }

    private fun bridge(
        gateway: McpJsonRpcGateway,
        observer: McpHttpBridgeObserver = McpHttpBridgeObserver { },
    ): McpStreamableHttpBridge = McpStreamableHttpBridge(
        gateway = gateway,
        endpointPolicy = McpHttpEndpointPolicy(
            scheme = "http",
            authority = "127.0.0.1:3090",
            path = "/mcp",
            allowedOrigins = setOf("http://127.0.0.1:3080"),
            allowMissingOrigin = true,
        ),
        authenticationVerifier = McpHttpAuthenticationVerifier { input ->
            when (input.authorizationHeader) {
                null -> McpHttpAuthenticationDecision.Rejected(
                    McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS,
                )
                "Bearer test-token" -> McpHttpAuthenticationDecision.Accepted(
                    subjectId = "synthetic-dsh-client",
                    credentialEpoch = "test-epoch-1",
                )
                else -> McpHttpAuthenticationDecision.Rejected(
                    McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
                )
            }
        },
        replayGuard = BoundedMcpHttpReplayGuard(windowMs = 60_000, maxEntries = 32),
        observer = observer,
    )

    private fun request(
        body: String,
        method: String = "POST",
        scheme: String = "http",
        authority: String = "127.0.0.1:3090",
        path: String = "/mcp",
        query: String? = null,
        origin: String? = null,
        contentType: String = "application/json; charset=utf-8",
        accept: String = "application/json, text/event-stream",
        authorization: String? = "Bearer test-token",
        sessionId: String? = null,
        mirroredMethod: String? = null,
        mirroredName: String? = null,
        extraHeaders: Map<String, List<String>> = emptyMap(),
    ): McpHttpBridgeRequest {
        val headers = linkedMapOf<String, List<String>>()
        headers["Content-Type"] = listOf(contentType)
        headers["Accept"] = listOf(accept)
        headers["MCP-Protocol-Version"] = listOf(BrowserMcpGateway.LEGACY_PROTOCOL_VERSION)
        authorization?.let { headers["Authorization"] = listOf(it) }
        origin?.let { headers["Origin"] = listOf(it) }
        sessionId?.let { headers["Mcp-Session-Id"] = listOf(it) }
        mirroredMethod?.let { headers["Mcp-Method"] = listOf(it) }
        mirroredName?.let { headers["Mcp-Name"] = listOf(it) }
        headers.putAll(extraHeaders)
        return McpHttpBridgeRequest(
            method = method,
            scheme = scheme,
            authority = authority,
            path = path,
            query = query,
            headers = headers,
            body = body,
            declaredContentLength = body.encodeToByteArray().size.toLong(),
        )
    }

    private fun initializeBody(id: Int): String = """
        {
          "jsonrpc": "2.0",
          "id": $id,
          "method": "initialize",
          "params": {
            "protocolVersion": "${BrowserMcpGateway.LEGACY_PROTOCOL_VERSION}",
            "capabilities": {},
            "clientInfo": {"name": "dsh-mcp-client", "version": "0.0.1"}
          }
        }
    """.trimIndent()
}
