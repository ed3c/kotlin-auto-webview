package dev.ed3c.autowebview.mcp.http

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpSemanticActionReplayTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sha256MatchesPublishedKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(byteArrayOf()),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
        assertEquals(
            "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
            sha256Hex("The quick brown fox jumps over the lazy dog".encodeToByteArray()),
        )
    }

    @Test
    fun canonicalJsonSortsObjectKeysRecursivelyAndPreservesArrayOrder() {
        val first = buildJsonObject {
            put("z", buildJsonObject {
                put("b", 2)
                put("a", 1)
            })
            put("a", buildJsonArray {
                add(JsonPrimitive("first"))
                add(JsonPrimitive("second"))
            })
        }
        val second = JsonObject(
            linkedMapOf(
                "a" to JsonArray(listOf(JsonPrimitive("first"), JsonPrimitive("second"))),
                "z" to JsonObject(linkedMapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))),
            ),
        )
        val reversedArray = buildJsonObject {
            put("a", JsonArray(listOf(JsonPrimitive("second"), JsonPrimitive("first"))))
            put("z", JsonObject(linkedMapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))))
        }

        assertEquals(canonicalReplayJson(first), canonicalReplayJson(second))
        assertNotEquals(canonicalReplayJson(first), canonicalReplayJson(reversedArray))
    }

    @Test
    fun replayKeyIsASecretFreeDigestOfSemanticActionIdentity() {
        val arguments = buildJsonObject {
            put("url", "https://private.example/account?action=approve")
        }
        val first = semanticActionReplayKey(
            subjectId = "private-subject",
            credentialEpoch = "credential-epoch-42",
            scheme = "https",
            authority = "private.example:443",
            path = "/mcp",
            method = "tools/call",
            toolName = "browser_propose_navigation",
            arguments = arguments,
        )
        val second = semanticActionReplayKey(
            subjectId = "private-subject",
            credentialEpoch = "credential-epoch-42",
            scheme = "https",
            authority = "private.example:443",
            path = "/mcp",
            method = "tools/call",
            toolName = "browser_propose_navigation",
            arguments = JsonObject(linkedMapOf("url" to JsonPrimitive("https://private.example/account?action=approve"))),
        )

        assertEquals(first, second)
        assertTrue(first.value.matches(Regex("[0-9a-f]{64}")))
        assertFalse("private.example" in first.value)
        assertFalse("private-subject" in first.value)
        assertFalse("credential-epoch" in first.value)
        assertFalse("approve" in first.value)
    }

    @Test
    fun semanticReplayIgnoresJsonRpcIdWhitespaceAndObjectKeyOrder() = runTest {
        var gatewayCalls = 0
        val bridge = bridge(
            gateway = McpJsonRpcGateway { payload ->
                gatewayCalls += 1
                echoSuccess(payload)
            },
        )

        val first = bridge.handle(
            request(
                navigationBody(
                    id = 1,
                    url = "https://example.com/same-action",
                    argumentsFirst = false,
                ),
            ),
            nowEpochMs = 1_000,
        )
        val duplicate = bridge.handle(
            request(
                navigationBody(
                    id = 2,
                    url = "https://example.com/same-action",
                    argumentsFirst = true,
                ),
            ),
            nowEpochMs = 1_001,
        )

        assertEquals(200, first.status)
        assertEquals(409, duplicate.status)
        assertEquals(McpHttpBridgeErrorCode.REPLAY_DETECTED, duplicate.errorCode)
        assertEquals(1, gatewayCalls)
    }

    @Test
    fun readOnlyCallsAreRepeatableAndDoNotConsumeReplayCapacity() = runTest {
        var gatewayCalls = 0
        val bridge = bridge(
            gateway = McpJsonRpcGateway { payload ->
                gatewayCalls += 1
                echoSuccess(payload)
            },
            replayGuard = BoundedMcpHttpReplayGuard(windowMs = 60_000, maxEntries = 1),
        )
        val captureBody = captureBody(id = 7)

        val firstRead = bridge.handle(request(captureBody), nowEpochMs = 2_000)
        val secondRead = bridge.handle(request(captureBody), nowEpochMs = 2_001)
        val firstAction = bridge.handle(
            request(navigationBody(id = 8, url = "https://example.com/action")),
            nowEpochMs = 2_002,
        )
        val duplicateAction = bridge.handle(
            request(navigationBody(id = 9, url = "https://example.com/action")),
            nowEpochMs = 2_003,
        )

        assertEquals(200, firstRead.status)
        assertEquals(200, secondRead.status)
        assertEquals(200, firstAction.status)
        assertEquals(McpHttpBridgeErrorCode.REPLAY_DETECTED, duplicateAction.errorCode)
        assertEquals(3, gatewayCalls)
    }

    @Test
    fun differentActionSemanticsSubjectsAndCredentialEpochsRemainSeparate() = runTest {
        val sharedGuard = BoundedMcpHttpReplayGuard(windowMs = 60_000, maxEntries = 8)
        var gatewayCalls = 0
        val gateway = McpJsonRpcGateway { payload ->
            gatewayCalls += 1
            echoSuccess(payload)
        }
        val bridge = bridge(gateway = gateway, replayGuard = sharedGuard)
        val sameAction = navigationBody(id = 1, url = "https://example.com/domain")

        val subjectAEpoch1 = bridge.handle(
            request(sameAction, authorization = "Bearer subject-a-epoch-1"),
            nowEpochMs = 3_000,
        )
        val subjectBEpoch1 = bridge.handle(
            request(sameAction, authorization = "Bearer subject-b-epoch-1"),
            nowEpochMs = 3_001,
        )
        val subjectAEpoch2 = bridge.handle(
            request(sameAction, authorization = "Bearer subject-a-epoch-2"),
            nowEpochMs = 3_002,
        )
        val differentActionSameId = bridge.handle(
            request(
                navigationBody(id = 1, url = "https://example.com/different"),
                authorization = "Bearer subject-a-epoch-1",
            ),
            nowEpochMs = 3_003,
        )
        val duplicate = bridge.handle(
            request(
                navigationBody(id = 999, url = "https://example.com/domain"),
                authorization = "Bearer subject-a-epoch-1",
            ),
            nowEpochMs = 3_004,
        )

        assertEquals(200, subjectAEpoch1.status)
        assertEquals(200, subjectBEpoch1.status)
        assertEquals(200, subjectAEpoch2.status)
        assertEquals(200, differentActionSameId.status)
        assertEquals(McpHttpBridgeErrorCode.REPLAY_DETECTED, duplicate.errorCode)
        assertEquals(4, gatewayCalls)
    }

    @Test
    fun unknownMissingAndNonStringNavigationArgumentsFailBeforeGateway() = runTest {
        var gatewayCalls = 0
        val bridge = bridge(
            gateway = McpJsonRpcGateway { payload ->
                gatewayCalls += 1
                echoSuccess(payload)
            },
        )
        val bodies = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"https://example.com","ignored":"bypass"}}}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{}}}""",
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":42}}}""",
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"http://example.com"}}}""",
        )

        for ((index, body) in bodies.withIndex()) {
            val response = bridge.handle(request(body), nowEpochMs = 4_000L + index)
            assertEquals(400, response.status)
            assertEquals(McpHttpBridgeErrorCode.INVALID_JSON_RPC, response.errorCode)
        }
        assertEquals(0, gatewayCalls)
    }

    private fun bridge(
        gateway: McpJsonRpcGateway,
        replayGuard: McpHttpReplayGuard = BoundedMcpHttpReplayGuard(windowMs = 60_000, maxEntries = 32),
    ): McpStreamableHttpBridge = McpStreamableHttpBridge(
        gateway = gateway,
        endpointPolicy = McpHttpEndpointPolicy(
            scheme = "http",
            authority = "127.0.0.1:3090",
            path = "/mcp",
            allowMissingOrigin = true,
        ),
        authenticationVerifier = McpHttpAuthenticationVerifier { input ->
            when (input.authorizationHeader) {
                "Bearer subject-a-epoch-1" -> McpHttpAuthenticationDecision.Accepted(
                    subjectId = "subject-a",
                    credentialEpoch = "epoch-1",
                )
                "Bearer subject-a-epoch-2" -> McpHttpAuthenticationDecision.Accepted(
                    subjectId = "subject-a",
                    credentialEpoch = "epoch-2",
                )
                "Bearer subject-b-epoch-1" -> McpHttpAuthenticationDecision.Accepted(
                    subjectId = "subject-b",
                    credentialEpoch = "epoch-1",
                )
                else -> McpHttpAuthenticationDecision.Rejected(
                    McpHttpAuthenticationRejectionReason.INVALID_CREDENTIALS,
                )
            }
        },
        replayGuard = replayGuard,
    )

    private fun request(
        body: String,
        authorization: String = "Bearer subject-a-epoch-1",
    ): McpHttpBridgeRequest = McpHttpBridgeRequest(
        method = "POST",
        scheme = "http",
        authority = "127.0.0.1:3090",
        path = "/mcp",
        headers = linkedMapOf(
            "Content-Type" to listOf("application/json"),
            "Accept" to listOf("application/json, text/event-stream"),
            "Authorization" to listOf(authorization),
            "MCP-Protocol-Version" to listOf("2025-11-25"),
        ),
        body = body,
        declaredContentLength = body.encodeToByteArray().size.toLong(),
    )

    private fun captureBody(id: Int): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"browser_capture_context","arguments":{}}}"""

    private fun navigationBody(
        id: Int,
        url: String,
        argumentsFirst: Boolean = false,
    ): String = if (argumentsFirst) {
        """
            {
              "method": "tools/call",
              "params": {
                "arguments": {"url": "$url"},
                "name": "browser_propose_navigation"
              },
              "id": $id,
              "jsonrpc": "2.0"
            }
        """.trimIndent()
    } else {
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"$url"}}}"""
    }

    private fun echoSuccess(payload: String): String {
        val request = json.parseToJsonElement(payload).jsonObject
        val id: JsonElement = request["id"] ?: JsonNull
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", buildJsonObject { })
        }.toString()
    }
}
