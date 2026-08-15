package dev.ed3c.autowebview.mcp.http

import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopMcpIntegrationTest {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Test
    fun profileIsDisabledUnlessTheHostEnablesItExactly() {
        assertFalse(DesktopMcpRuntimeProfile.fromEnvironment(emptyMap()).enabled)
        assertFalse(
            DesktopMcpRuntimeProfile.fromEnvironment(
                mapOf(DesktopMcpRuntimeProfile.ENABLE_VARIABLE to "true"),
            ).enabled,
        )
        assertFalse(
            DesktopMcpRuntimeProfile.fromEnvironment(
                mapOf(DesktopMcpRuntimeProfile.ENABLE_VARIABLE to "ENABLED"),
            ).enabled,
        )

        val enabled = DesktopMcpRuntimeProfile.fromEnvironment(
            mapOf(DesktopMcpRuntimeProfile.ENABLE_VARIABLE to DesktopMcpRuntimeProfile.ENABLED_VALUE),
        )
        assertTrue(enabled.enabled)
        assertEquals(DesktopMcpRuntimeProfile.DEFAULT_PORT, enabled.port)
        assertTrue(enabled.allowMissingOrigin)
    }

    @Test
    fun malformedProfileValuesFailClosedInsteadOfFallingBackToADefaultEndpoint() {
        for (port in listOf("0", "-1", "70000", "3090abc", "")) {
            assertFailsWith<IllegalArgumentException> {
                DesktopMcpRuntimeProfile.fromEnvironment(
                    mapOf(
                        DesktopMcpRuntimeProfile.ENABLE_VARIABLE to
                            DesktopMcpRuntimeProfile.ENABLED_VALUE,
                        DesktopMcpRuntimeProfile.PORT_VARIABLE to port,
                    ),
                )
            }
        }
    }

    @Test
    fun explicitOriginAllowlistDeniesMissingOrigin() {
        val profile = DesktopMcpRuntimeProfile.fromEnvironment(
            mapOf(
                DesktopMcpRuntimeProfile.ENABLE_VARIABLE to DesktopMcpRuntimeProfile.ENABLED_VALUE,
                DesktopMcpRuntimeProfile.ORIGINS_VARIABLE to "http://127.0.0.1:3080 , http://127.0.0.1:3081",
            ),
        )

        assertEquals(setOf("http://127.0.0.1:3080", "http://127.0.0.1:3081"), profile.allowedOrigins)
        assertFalse(profile.allowMissingOrigin)
    }

    @Test
    fun disabledProfileStartsNothing() {
        assertNull(
            DesktopMcpIntegration.startIfEnabled(
                profile = DesktopMcpRuntimeProfile.DISABLED,
                gateway = BrowserMcpGateway(AgentBrowserRuntime()),
            ),
        )
    }

    @Test
    fun enabledProfileServesTheChildProcessCredentialAndReleasesEverythingOnShutdown() {
        val port = reserveLoopbackPort()
        val integration = assertNotNull(
            DesktopMcpIntegration.startIfEnabled(
                profile = DesktopMcpRuntimeProfile(enabled = true, port = port),
                gateway = BrowserMcpGateway(AgentBrowserRuntime()),
                nowEpochMs = System.currentTimeMillis(),
            ),
        )

        try {
            assertEquals("http://127.0.0.1:$port/mcp", integration.endpoint)
            assertEquals("epoch-1", integration.activeEpoch)

            val credential = assertNotNull(
                integration.childProcessEnvironment()[
                    DesktopMcpCredentialLifecycle.CHILD_PROCESS_ENVIRONMENT_NAME,
                ],
            )
            assertFalse(credential in integration.toString())

            assertEquals(200, post(integration.endpoint, credential))
            assertEquals(401, post(integration.endpoint, credential = null))
            assertEquals(403, post(integration.endpoint, credential = "not-the-issued-credential"))
        } finally {
            integration.close()
        }

        awaitPortReleased(port)
        assertFailsWith<IOException> { post(integration.endpoint, credential = null) }
    }

    @Test
    fun rotationAndRevocationApplyToTheRunningListener() {
        val port = reserveLoopbackPort()
        val integration = assertNotNull(
            DesktopMcpIntegration.startIfEnabled(
                profile = DesktopMcpRuntimeProfile(enabled = true, port = port),
                gateway = BrowserMcpGateway(AgentBrowserRuntime()),
                nowEpochMs = System.currentTimeMillis(),
            ),
        )

        try {
            val first = assertNotNull(
                integration.childProcessEnvironment()[
                    DesktopMcpCredentialLifecycle.CHILD_PROCESS_ENVIRONMENT_NAME,
                ],
            )
            val second = integration.rotateCredential(System.currentTimeMillis()).use { it.decodeToString() }

            assertEquals("epoch-2", integration.activeEpoch)
            // Both epochs are live inside the bounded handover window.
            assertEquals(200, post(integration.endpoint, first))
            assertEquals(200, post(integration.endpoint, second))

            integration.revokeCredential()

            assertEquals(403, post(integration.endpoint, first))
            assertEquals(403, post(integration.endpoint, second))
        } finally {
            integration.close()
            awaitPortReleased(port)
        }
    }

    private fun post(endpoint: String, credential: String?): Int {
        val builder = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(PING_BODY))
        if (credential != null) builder.header("Authorization", "Bearer $credential")
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun reserveLoopbackPort(): Int =
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            socket.localPort
        }

    private fun awaitPortReleased(port: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
            } catch (_: IOException) {
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("Desktop MCP port $port was not released")
    }

    private companion object {
        const val PING_BODY = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
    }
}
