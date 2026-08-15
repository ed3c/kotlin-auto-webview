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
        assertTrue("@deepseek-ai/dsh-mcp-client" in first)
        assertTrue("transport: streamable-http" in first)
        assertTrue("KOTLIN_AUTO_WEBVIEW_MCP_TOKEN" in first)
        assertTrue("process.env.KOTLIN_AUTO_WEBVIEW_MCP_TOKEN" in first)
        assertTrue("Bearer \${token}" in first)
        assertFalse("private-token-value" in first)
        assertFalse("Authorization: Bearer" in first)
        assertTrue("47f943859bef60e4160492346772ded9b24f765a" in first)
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
    fun remoteEndpointsRequireHttpsAndBearerEnvironmentReference() {
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(endpoint = "http://agent.example.invalid/mcp")
        }
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(bearerEnvironmentVariable = null)
        }
        assertFailsWith<IllegalArgumentException> {
            remoteBinding(bearerEnvironmentVariable = "literal-token-value")
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
    fun loopbackDevelopmentBindingIsExplicitAndUnauthenticated() {
        val binding = DeepSeekHarnessCordisBinding(
            endpoint = "http://127.0.0.1:3090/mcp",
            endpointClass = DeepSeekHarnessEndpointClass.LOOPBACK_HTTP,
        )

        val patch = binding.renderCordisPatch()
        assertTrue("http://127.0.0.1:3090/mcp" in patch)
        assertFalse("headers:" in patch)
        assertFalse("Authorization" in patch)
    }

    @Test
    fun insecureNonLoopbackAndLoopbackSecretReferenceFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            DeepSeekHarnessCordisBinding(
                endpoint = "http://agent.example.invalid/mcp",
                endpointClass = DeepSeekHarnessEndpointClass.LOOPBACK_HTTP,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DeepSeekHarnessCordisBinding(
                endpoint = "http://localhost:3090/mcp",
                endpointClass = DeepSeekHarnessEndpointClass.LOOPBACK_HTTP,
                bearerTokenEnvironmentVariable = "KOTLIN_AUTO_WEBVIEW_MCP_TOKEN",
            )
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
    fun bindingRoundTripsWithoutSecretValueFields() {
        val binding = remoteBinding()
        val encoded = json.encodeToString(binding)
        val decoded = json.decodeFromString(DeepSeekHarnessCordisBinding.serializer(), encoded)

        assertEquals(binding, decoded)
        assertTrue("bearerTokenEnvironmentVariable" in encoded)
        assertFalse("private-token-value" in encoded)
        assertFalse("headers" in encoded)
        assertFalse("certificate" in encoded)
        assertFalse("privateKey" in encoded)
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
}
