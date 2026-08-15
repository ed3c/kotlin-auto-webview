package dev.ed3c.autowebview.providers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekHarnessCordisBindingTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun remoteBindingRendersDeterministicSecretFreeCordisPatch() {
        val binding = remoteBinding()

        val first = binding.renderCordisPatch()
        val second = binding.renderCordisPatch()

        assertEquals(first, second)
        assertPatchUsesEnvironmentAuthentication(first)
        assertTrue("https://agent.example.invalid/mcp" in first)
        assertTrue("47f943859bef60e4160492346772ded9b24f765a" in first)
    }

    @Test
    fun loopbackBindingRendersTheSameSecretFreeAuthenticationBoundary() {
        val binding = loopbackBinding()

        val first = binding.renderCordisPatch()
        val second = binding.renderCordisPatch()

        assertEquals(first, second)
        assertTrue("http://127.0.0.1:3090/mcp" in first)
        assertPatchUsesEnvironmentAuthentication(first)
    }

    @Test
    fun publicToolNamesMatchDeepSeekHarnessServerQualifiedConvention() {
        val names = remoteBinding().expectedPublicToolNames()

        assertEquals(
            "mcp__kotlin_auto_webview__browser_capture_context",
            names.getValue("browser_capture_context"),
        )
        assertEquals(
            "mcp__kotlin_auto_webview__browser_propose_navigation",
            names.getValue("browser_propose_navigation"),
        )
        assertTrue(names.values.all { it.length <= 64 })
    }

    @Test
    fun unknownKmpToolCannotBeProjectedIntoHarnessNamespace() {
        assertFailsWith<IllegalArgumentException> {
            remoteBinding().publicToolName("arbitrary_native_execute")
        }
    }

    @Test
    fun serverNameMustMatchUpstreamContract() {
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(serverName = "contains spaces")
        }
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(serverName = "a".repeat(33))
        }
    }

    @Test
    fun bothEndpointClassesRequireBearerEnvironmentReference() {
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(bearerEnvironmentVariable = null)
        }
        assertFailsWith<IllegalArgumentException> {
            loopbackBinding(bearerEnvironmentVariable = null)
        }
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(bearerEnvironmentVariable = "literal-token-value")
        }
        assertFailsWith<IllegalArgumentException> {
            loopbackBinding(bearerEnvironmentVariable = "literal-token-value")
        }
    }

    @Test
    fun remoteEndpointsRequireHttps() {
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(endpoint = "http://agent.example.invalid/mcp")
        }
    }

    @Test
    fun endpointCredentialsQueriesFragmentsAndControlsFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(endpoint = "https://user:password@agent.example.invalid/mcp")
        }
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(endpoint = "https://agent.example.invalid/mcp?token=secret")
        }
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(endpoint = "https://agent.example.invalid/mcp#secret")
        }
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(endpoint = "https://agent.example.invalid/mcp\nnext")
        }
    }

    @Test
    fun loopbackDevelopmentBindingRemainsHttpOnlyAndHostRestricted() {
        listOf(
            "http://localhost:3090/mcp",
            "http://127.0.0.1:3090/mcp",
            "http://[::1]:3090/mcp",
        ).forEach { endpoint ->
            DeepSeekHarnessCordisBinding(
                endpoint = endpoint,
                endpointClass = DeepSeekHarnessEndpointClass.LOOPBACK_HTTP,
                bearerTokenEnvironmentVariable = "KOTLIN_AUTO_WEBVIEW_MCP_TOKEN",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            loopbackBinding(endpoint = "https://127.0.0.1:3090/mcp")
        }
        assertFailsWith<IllegalArgumentException> {
            loopbackBinding(endpoint = "http://agent.example.invalid/mcp")
        }
    }

    @Test
    fun reconnectAndTimeoutBudgetsFailBeforeRendering() {
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(toolCallTimeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DeepSeekHarnessReconnectPolicy(initialDelayMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DeepSeekHarnessReconnectPolicy(initialDelayMs = 1_000, maximumDelayMs = 500)
        }
        assertFailsWith<IllegalArgumentException> {
            DeepSeekHarnessReconnectPolicy(maximumAttempts = 0)
        }
    }

    @Test
    fun remoteAndLoopbackBindingsRoundTripWithoutSecretValueFields() {
        listOf(remoteBinding(), loopbackBinding()).forEach { binding ->
            val encoded = json.encodeToString(binding)
            val decoded = json.decodeFromString(DeepSeekHarnessCordisBinding.serializer(), encoded)

            assertEquals(binding, decoded)
            assertTrue("bearerTokenEnvironmentVariable" in encoded)
            assertFalse("private-token-value" in encoded)
            assertFalse("headers" in encoded)
            assertFalse("certificate" in encoded)
            assertFalse("privateKey" in encoded)
        }
    }

    private fun assertPatchUsesEnvironmentAuthentication(patch: String) {
        assertTrue("@deepseek-ai/dsh-mcp-client" in patch)
        assertTrue("transport: streamable-http" in patch)
        assertTrue("headers:" in patch)
        assertTrue("KOTLIN_AUTO_WEBVIEW_MCP_TOKEN" in patch)
        assertTrue("process.env.KOTLIN_AUTO_WEBVIEW_MCP_TOKEN" in patch)
        assertTrue("Bearer \${token}" in patch)
        assertFalse("private-token-value" in patch)
        assertFalse("Authorization: Bearer" in patch)
    }

    private fun remoteBinding(
        serverName: String = "kotlin_auto_webview",
        endpoint: String = "https://agent.example.invalid/mcp",
        bearerEnvironmentVariable: String? = "KOTLIN_AUTO_WEBVIEW_MCP_TOKEN",
        toolCallTimeoutMs: Long = 60_000,
    ) = DeepSeekHarnessCordisBinding(
        serverName = serverName,
        endpoint = endpoint,
        endpointClass = DeepSeekHarnessEndpointClass.REMOTE_HTTPS,
        bearerTokenEnvironmentVariable = bearerEnvironmentVariable,
        toolCallTimeoutMs = toolCallTimeoutMs,
    )

    private fun loopbackBinding(
        endpoint: String = "http://127.0.0.1:3090/mcp",
        bearerEnvironmentVariable: String? = "KOTLIN_AUTO_WEBVIEW_MCP_TOKEN",
    ) = DeepSeekHarnessCordisBinding(
        endpoint = endpoint,
        endpointClass = DeepSeekHarnessEndpointClass.LOOPBACK_HTTP,
        bearerTokenEnvironmentVariable = bearerEnvironmentVariable,
    )
}
