package dev.ed3c.autowebview.mcp.http

import dev.ed3c.autowebview.dispatcher.DispatcherMode
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DeepSeekHarnessCordisE2ETest {
    @Test
    fun pinnedCordisClientRegistersAndCallsKmpTools() {
        val upstreamRootValue = System.getenv(ENV_UPSTREAM_ROOT)?.trim().orEmpty()
        if (upstreamRootValue.isEmpty()) return

        val upstreamRoot = Path.of(upstreamRootValue).toAbsolutePath().normalize()
        val expectedCommit = System.getenv(ENV_UPSTREAM_COMMIT)?.trim()
        assertEquals(PINNED_UPSTREAM_COMMIT, expectedCommit, "Unexpected DeepSeek Harness evidence subject")
        assertTrue(Files.isDirectory(upstreamRoot), "Pinned DeepSeek Harness workspace is absent")
        assertTrue(
            Files.isRegularFile(upstreamRoot.resolve(STAGED_E2E_SPEC)),
            "Repository-owned DeepSeek Harness E2E specification was not staged",
        )

        val token = System.getenv(ENV_SYNTHETIC_TOKEN)
            ?.takeIf(String::isNotBlank)
            ?: fail("Masked synthetic DeepSeek Harness credential is absent")
        val runtime = AgentBrowserRuntime()
        runBlocking {
            runtime.onPageContext(
                PageContext(
                    url = "https://example.com",
                    title = "DeepSeek Harness E2E",
                    markdown = "password = $SECRET_FIXTURE",
                    capturedAtEpochMs = 1,
                ),
            )
        }

        val tokenBytes = token.encodeToByteArray()
        val server = try {
            assertNotNull(
                DesktopMcpLoopbackServer.startIfEnabled(
                    config = DesktopMcpLoopbackServerConfig.forTest(),
                    bearerToken = tokenBytes,
                    gateway = BrowserMcpGateway(runtime),
                ),
            )
        } finally {
            tokenBytes.fill(0)
        }
        val port = server.port
        val threadPrefix = server.workerThreadPrefix
        val outputFile = Files.createTempFile("deepseek-harness-cordis-e2e-", ".log")

        try {
            val process = ProcessBuilder(
                "pnpm",
                "exec",
                "vitest",
                "run",
                "--config",
                "vitest.e2e.config.ts",
                STAGED_E2E_SPEC,
                "--reporter=dot",
            )
                .directory(upstreamRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .apply {
                    environment()[ENV_ENDPOINT] = server.endpoint
                    environment()[ENV_SYNTHETIC_TOKEN] = token
                    environment()["DSH_E2E_MAX_WORKERS"] = "1"
                    environment()["NO_COLOR"] = "1"
                }
                .start()

            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(PROCESS_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(PROCESS_GRACE_SECONDS, TimeUnit.SECONDS)
                }
                fail("Pinned DeepSeek Harness E2E process exceeded the bounded timeout")
            }

            val output = readBoundedUtf8(outputFile, MAX_CAPTURED_OUTPUT_BYTES)
            assertFalse(token in output, "DeepSeek Harness E2E output exposed the synthetic credential")
            assertFalse(SECRET_FIXTURE in output, "DeepSeek Harness E2E output exposed the secret fixture")
            assertFalse(server.endpoint in output, "DeepSeek Harness E2E output exposed the loopback endpoint")

            val safeOutput = redact(output, token, server.endpoint)
            assertEquals(
                0,
                process.exitValue(),
                "Pinned DeepSeek Harness E2E process failed: $safeOutput",
            )
            assertTrue(
                PASS_MARKER in output,
                "Pinned DeepSeek Harness E2E marker is absent: $safeOutput",
            )
            assertEquals(
                DispatcherMode.WAITING_FOR_CONFIRMATION,
                runtime.dispatcherState.value.mode,
            )
            assertEquals(
                NAVIGATION_URL,
                runtime.dispatcherState.value.pendingAction?.arguments?.get("url"),
            )
        } finally {
            server.close()
            Files.deleteIfExists(outputFile)
            awaitPortReleased(port)
            awaitWorkerThreadsStopped(threadPrefix)
        }
    }

    private fun readBoundedUtf8(path: Path, maximumBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8 * 1_024))
        var truncated = false
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(4 * 1_024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val remaining = maximumBytes - output.size()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                output.write(buffer, 0, minOf(read, remaining))
                if (read > remaining) {
                    truncated = true
                    break
                }
            }
        }
        return buildString {
            append(output.toByteArray().decodeToString())
            if (truncated) append("\n<bounded-output-truncated>")
        }
    }

    private fun redact(output: String, token: String, endpoint: String): String = output
        .replace(token, "<redacted-token>")
        .replace(endpoint, "<redacted-loopback-endpoint>")
        .replace(SECRET_FIXTURE, "<redacted-secret-fixture>")
        .replace(Regex("Bearer\\s+[^\\s\\\"']+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
        .takeLast(MAX_FAILURE_OUTPUT_CHARACTERS)

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
        fail("DeepSeek Harness E2E listener did not release its loopback port")
    }

    private fun awaitWorkerThreadsStopped(prefix: String) {
        repeat(100) {
            val live = Thread.getAllStackTraces().keys.any { thread ->
                thread.isAlive && thread.name.startsWith(prefix)
            }
            if (!live) return
            Thread.sleep(20)
        }
        fail("DeepSeek Harness E2E listener left worker threads alive")
    }

    private companion object {
        const val ENV_UPSTREAM_ROOT = "DSH_E2E_ROOT"
        const val ENV_UPSTREAM_COMMIT = "DSH_E2E_COMMIT"
        const val ENV_SYNTHETIC_TOKEN = "DSH_E2E_TOKEN"
        const val ENV_ENDPOINT = "KOTLIN_AUTO_WEBVIEW_MCP_URL"
        const val PINNED_UPSTREAM_COMMIT = "47f943859bef60e4160492346772ded9b24f765a"
        const val STAGED_E2E_SPEC =
            "packages/mcp/mcp-client/tests/kotlin-auto-webview.external.e2e.ts"
        const val PASS_MARKER = "KMP_DSH_E2E_PASS"
        const val SECRET_FIXTURE = "super-secret-value"
        const val NAVIGATION_URL = "https://example.com/deepseek-harness-e2e"
        const val PROCESS_TIMEOUT_SECONDS = 120L
        const val PROCESS_GRACE_SECONDS = 5L
        const val MAX_CAPTURED_OUTPUT_BYTES = 64 * 1_024
        const val MAX_FAILURE_OUTPUT_CHARACTERS = 4_000
    }
}
