package dev.ed3c.autowebview.mcp.http

import dev.ed3c.autowebview.dispatcher.DispatcherMode
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DesktopMcpLoopbackServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun listenerIsDefaultOffAndConfigurationFailsClosed() {
        val disabled = DesktopMcpLoopbackServer.startIfEnabled(
            config = DesktopMcpLoopbackServerConfig.runtime(),
            bearerToken = TOKEN.encodeToByteArray(),
            gateway = McpJsonRpcGateway(::successFor),
        )

        assertNull(disabled)
        assertFailsWith<IllegalArgumentException> {
            DesktopMcpLoopbackServerConfig.runtime(enabled = true, port = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopMcpLoopbackServer.startIfEnabled(
                config = DesktopMcpLoopbackServerConfig.forTest(),
                bearerToken = "too-short".encodeToByteArray(),
                gateway = McpJsonRpcGateway(::successFor),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopMcpLoopbackServer.startIfEnabled(
                config = DesktopMcpLoopbackServerConfig.forTest(),
                bearerToken = "a".repeat(32).encodeToByteArray(),
                gateway = McpJsonRpcGateway(::successFor),
            )
        }
    }

    @Test
    fun realLoopbackFlowPreservesSanitizationAndProposalOnlyAuthority() {
        val runtime = AgentBrowserRuntime()
        runBlocking {
            runtime.onPageContext(
                PageContext(
                    url = "https://example.com",
                    title = "Example",
                    markdown = "password = super-secret-value",
                    capturedAtEpochMs = 1,
                ),
            )
        }
        val server = startServer(gateway = BrowserMcpGateway(runtime))

        try {
            assertEquals("127.0.0.1", URI.create(server.endpoint).host)

            val initialize = post(server, initializeBody(id = 1))
            assertEquals(200, initialize.status)
            assertEquals(
                "2.0",
                responseObject(initialize).getValue("jsonrpc").jsonPrimitive.content,
            )

            val initialized = post(
                server,
                notificationBody("notifications/initialized"),
            )
            assertEquals(202, initialized.status)
            assertTrue(initialized.body.isEmpty())

            val tools = post(server, requestBody(id = 2, method = "tools/list"))
            assertEquals(200, tools.status)
            val names = responseObject(tools)
                .getValue("result")
                .jsonObject
                .getValue("tools")
                .jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(
                listOf("browser_capture_context", "browser_propose_navigation"),
                names,
            )

            val context = post(
                server,
                toolCallBody(id = 3, name = "browser_capture_context"),
            )
            assertEquals(200, context.status)
            assertTrue("[REDACTED]" in context.body)
            assertFalse("super-secret-value" in context.body)

            val navigation = post(
                server,
                toolCallBody(
                    id = 4,
                    name = "browser_propose_navigation",
                    arguments = "\"url\":\"https://example.com/next\"",
                ),
            )
            assertEquals(200, navigation.status)
            assertTrue("awaits user confirmation" in navigation.body)
            assertEquals(
                DispatcherMode.WAITING_FOR_CONFIRMATION,
                runtime.dispatcherState.value.mode,
            )
            assertEquals(
                "https://example.com/next",
                runtime.dispatcherState.value.pendingAction?.arguments?.get("url"),
            )
        } finally {
            val releasedPort = server.port
            val threadPrefix = server.workerThreadPrefix
            server.close()
            awaitPortReleased(releasedPort)
            awaitWorkerThreadsStopped(threadPrefix)
        }
    }

    @Test
    fun transportAndAuthenticationFailuresStopBeforeGateway() {
        var gatewayCalls = 0
        val allowedOrigin = "http://127.0.0.1:3080"
        val server = startServer(
            config = DesktopMcpLoopbackServerConfig.forTest(
                allowedOrigins = setOf(allowedOrigin),
                allowMissingOrigin = false,
                maxRequestBodyBytes = 128,
            ),
            gateway = McpJsonRpcGateway { payload ->
                gatewayCalls += 1
                successFor(payload)
            },
        )

        try {
            val failures = listOf(
                post(
                    server,
                    requestBody(1, "tools/list"),
                    authorization = null,
                    origin = allowedOrigin,
                ) to 401,
                post(
                    server,
                    requestBody(2, "tools/list"),
                    authorization = "Bearer ${"xYz9".repeat(12)}",
                    origin = allowedOrigin,
                ) to 403,
                post(
                    server,
                    requestBody(3, "tools/list"),
                    origin = "http://evil.example",
                ) to 403,
                post(
                    server,
                    requestBody(4, "tools/list"),
                    origin = null,
                ) to 403,
                post(
                    server,
                    requestBody(5, "tools/list"),
                    origin = allowedOrigin,
                    contentType = "text/plain",
                ) to 415,
                post(
                    server,
                    requestBody(6, "tools/list"),
                    origin = allowedOrigin,
                    accept = "application/json",
                ) to 406,
                post(
                    server,
                    requestBody(7, "tools/list"),
                    origin = allowedOrigin,
                    suffix = "?token=forbidden",
                ) to 400,
                post(
                    server,
                    requestBody(8, "tools/list"),
                    origin = allowedOrigin,
                    path = "/mcp/extra",
                ) to 404,
                post(
                    server,
                    "{" + "x".repeat(256) + "}",
                    origin = allowedOrigin,
                ) to 413,
            )

            for ((result, expectedStatus) in failures) {
                assertEquals(expectedStatus, result.status)
                assertFalse(TOKEN in result.body)
                assertFalse("super-secret-value" in result.body)
            }
            assertEquals(0, gatewayCalls)
        } finally {
            server.close()
        }
    }

    @Test
    fun rawDuplicateWrongHostAndChunkedOversizeRequestsFailClosed() {
        var gatewayCalls = 0
        val server = startServer(
            config = DesktopMcpLoopbackServerConfig.forTest(maxRequestBodyBytes = 96),
            gateway = McpJsonRpcGateway { payload ->
                gatewayCalls += 1
                successFor(payload)
            },
        )

        try {
            val body = requestBody(1, "tools/list")
            val duplicateAuthorization = rawRequest(
                server,
                buildString {
                    appendBaseHeaders(server, bodyByteCount = body.encodeToByteArray().size)
                    append("Authorization: Bearer $TOKEN\r\n")
                    append("Authorization: Bearer ${"zY8x".repeat(12)}\r\n")
                    append("Connection: close\r\n\r\n")
                    append(body)
                },
            )
            val wrongHost = rawRequest(
                server,
                buildString {
                    append("POST /mcp HTTP/1.1\r\n")
                    append("Host: localhost:${server.port}\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Accept: application/json, text/event-stream\r\n")
                    append("Authorization: Bearer $TOKEN\r\n")
                    append("Content-Length: ${body.encodeToByteArray().size}\r\n")
                    append("Connection: close\r\n\r\n")
                    append(body)
                },
            )
            val chunk = "{" + "x".repeat(160) + "}"
            val chunkedOversize = rawRequest(
                server,
                buildString {
                    append("POST /mcp HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:${server.port}\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Accept: application/json, text/event-stream\r\n")
                    append("Authorization: Bearer $TOKEN\r\n")
                    append("Transfer-Encoding: chunked\r\n")
                    append("Connection: close\r\n\r\n")
                    append(chunk.encodeToByteArray().size.toString(16))
                    append("\r\n")
                    append(chunk)
                    append("\r\n0\r\n\r\n")
                },
            )

            assertTrue(duplicateAuthorization.status in setOf(400, 403))
            assertEquals(400, wrongHost.status)
            assertEquals(413, chunkedOversize.status)
            assertEquals(0, gatewayCalls)
        } finally {
            server.close()
        }
    }

    @Test
    fun bearerVerifierScopesIdentityUsesFixedDigestAndErasesOnClose() {
        runBlocking {
            val verifier = DesktopMcpBearerAuthenticationVerifier(
                expectedToken = TOKEN.encodeToByteArray(),
                expectedAuthority = "127.0.0.1:3090",
                subjectId = "desktop-verifier-test",
                credentialEpoch = "epoch-v1",
            )

            try {
                val accepted = verifier.verify(
                    McpHttpAuthenticationInput(
                        authorizationHeader = "Bearer $TOKEN",
                        scheme = "http",
                        authority = "127.0.0.1:3090",
                    ),
                )
                val wrong = verifier.verify(
                    McpHttpAuthenticationInput(
                        authorizationHeader = "Bearer ${"wrong-9Z".repeat(6)}",
                        scheme = "http",
                        authority = "127.0.0.1:3090",
                    ),
                )
                val wrongScope = verifier.verify(
                    McpHttpAuthenticationInput(
                        authorizationHeader = "Bearer $TOKEN",
                        scheme = "http",
                        authority = "127.0.0.1:3091",
                    ),
                )

                assertIs<McpHttpAuthenticationDecision.Accepted>(accepted)
                assertIs<McpHttpAuthenticationDecision.Rejected>(wrong)
                assertEquals(
                    McpHttpAuthenticationRejectionReason.INSUFFICIENT_SCOPE,
                    assertIs<McpHttpAuthenticationDecision.Rejected>(wrongScope).reason,
                )
                assertFalse(TOKEN in verifier.toString())
                assertFalse("127.0.0.1" in verifier.toString())
            } finally {
                verifier.close()
            }

            assertIs<McpHttpAuthenticationDecision.Rejected>(
                verifier.verify(
                    McpHttpAuthenticationInput(
                        authorizationHeader = "Bearer $TOKEN",
                        scheme = "http",
                        authority = "127.0.0.1:3090",
                    ),
                ),
            )
        }
    }

    @Test
    fun listenerAndConfigRenderingAreRedacted() {
        val origin = "http://127.0.0.1:3080"
        val config = DesktopMcpLoopbackServerConfig.forTest(
            allowedOrigins = setOf(origin),
            allowMissingOrigin = false,
        )
        val server = startServer(config = config)

        try {
            for (rendered in listOf(config.toString(), server.toString())) {
                assertFalse(TOKEN in rendered)
                assertFalse(server.endpoint in rendered)
                assertFalse(origin in rendered)
                assertFalse("/mcp" in rendered)
            }
        } finally {
            server.close()
        }
    }

    private fun startServer(
        config: DesktopMcpLoopbackServerConfig = DesktopMcpLoopbackServerConfig.forTest(),
        gateway: BrowserMcpGateway,
    ): DesktopMcpLoopbackServer = assertNotNull(
        DesktopMcpLoopbackServer.startIfEnabled(
            config = config,
            bearerToken = TOKEN.encodeToByteArray(),
            gateway = gateway,
        ),
    )

    private fun startServer(
        config: DesktopMcpLoopbackServerConfig = DesktopMcpLoopbackServerConfig.forTest(),
        gateway: McpJsonRpcGateway = McpJsonRpcGateway(::successFor),
    ): DesktopMcpLoopbackServer = assertNotNull(
        DesktopMcpLoopbackServer.startIfEnabled(
            config = config,
            bearerToken = TOKEN.encodeToByteArray(),
            gateway = gateway,
        ),
    )

    private fun post(
        server: DesktopMcpLoopbackServer,
        body: String,
        authorization: String? = "Bearer $TOKEN",
        origin: String? = null,
        contentType: String = "application/json",
        accept: String = "application/json, text/event-stream",
        path: String = "/mcp",
        suffix: String = "",
    ): HttpResult {
        val connection = URI.create("http://127.0.0.1:${server.port}$path$suffix")
            .toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        connection.setRequestProperty("Accept", accept)
        if (authorization != null) connection.setRequestProperty("Authorization", authorization)
        if (origin != null) connection.setRequestProperty("Origin", origin)
        val bytes = body.encodeToByteArray()
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }

        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            HttpResult(
                status = status,
                body = stream?.use { it.readBytes().decodeToString() }.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun rawRequest(server: DesktopMcpLoopbackServer, request: String): HttpResult {
        Socket().use { socket ->
            socket.soTimeout = 5_000
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 5_000)
            socket.getOutputStream().use { output ->
                output.write(request.toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
            socket.shutdownOutput()
            val raw = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            val statusLine = raw.lineSequence().firstOrNull()
                ?: fail("Missing HTTP status line")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: fail("Invalid HTTP status line: $statusLine")
            val responseBody = raw.substringAfter("\r\n\r\n", missingDelimiterValue = "")
            return HttpResult(status = status, body = responseBody)
        }
    }

    private fun StringBuilder.appendBaseHeaders(
        server: DesktopMcpLoopbackServer,
        bodyByteCount: Int,
    ) {
        append("POST /mcp HTTP/1.1\r\n")
        append("Host: 127.0.0.1:${server.port}\r\n")
        append("Content-Type: application/json\r\n")
        append("Accept: application/json, text/event-stream\r\n")
        append("Content-Length: $bodyByteCount\r\n")
    }

    private fun responseObject(result: HttpResult) =
        json.parseToJsonElement(result.body).jsonObject

    private fun initializeBody(id: Int): String =
        """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"desktop-listener-test","version":"1"}}}"""

    private fun requestBody(id: Int, method: String): String =
        """{"jsonrpc":"2.0","id":$id,"method":"$method","params":{}}"""

    private fun notificationBody(method: String): String =
        """{"jsonrpc":"2.0","method":"$method","params":{}}"""

    private fun toolCallBody(
        id: Int,
        name: String,
        arguments: String = "",
    ): String {
        val argumentObject = if (arguments.isEmpty()) "{}" else "{$arguments}"
        return """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$argumentObject}}"""
    }

    private fun successFor(payload: String): String {
        val request = json.parseToJsonElement(payload).jsonObject
        val id = request.getValue("id")
        return """{"jsonrpc":"2.0","id":$id,"result":{}}"""
    }

    private fun awaitPortReleased(port: Int) {
        repeat(100) {
            val released = runCatching {
                ServerSocket().use { probe ->
                    probe.reuseAddress = true
                    probe.bind(
                        InetSocketAddress(
                            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                            port,
                        ),
                    )
                }
            }.isSuccess
            if (released) return
            Thread.sleep(20)
        }
        fail("Desktop MCP listener did not release its loopback port")
    }

    private fun awaitWorkerThreadsStopped(prefix: String) {
        repeat(100) {
            val live = Thread.getAllStackTraces().keys.any { thread ->
                thread.isAlive && thread.name.startsWith(prefix)
            }
            if (!live) return
            Thread.sleep(20)
        }
        fail("Desktop MCP listener left worker threads alive")
    }

    private data class HttpResult(
        val status: Int,
        val body: String,
    )

    private companion object {
        const val TOKEN = "test-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }
}
