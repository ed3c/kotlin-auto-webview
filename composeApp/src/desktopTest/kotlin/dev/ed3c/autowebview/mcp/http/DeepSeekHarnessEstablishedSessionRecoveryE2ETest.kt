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
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DeepSeekHarnessEstablishedSessionRecoveryE2ETest {
    @Test
    fun pinnedCordisClientReplacesToolGenerationAfterListenerLoss() {
        val upstreamRootValue = System.getenv(ENV_UPSTREAM_ROOT)?.trim().orEmpty()
        if (upstreamRootValue.isEmpty()) return

        val upstreamRoot = Path.of(upstreamRootValue).toAbsolutePath().normalize()
        assertEquals(
            PINNED_UPSTREAM_COMMIT,
            System.getenv(ENV_UPSTREAM_COMMIT)?.trim(),
            "Unexpected DeepSeek Harness established-session evidence subject",
        )
        assertTrue(Files.isDirectory(upstreamRoot), "Pinned DeepSeek Harness workspace is absent")
        assertTrue(
            Files.isRegularFile(upstreamRoot.resolve(STAGED_RECOVERY_SPEC)),
            "Repository-owned established-session recovery specification was not staged",
        )

        val token = System.getenv(ENV_SYNTHETIC_TOKEN)
            ?.takeIf(String::isNotBlank)
            ?: fail("Masked synthetic DeepSeek Harness credential is absent")
        val port = selectUnusedLoopbackPort()
        val endpoint = "http://127.0.0.1:$port/mcp"
        val controlDirectory = Files.createTempDirectory("deepseek-harness-established-recovery-")
        val outputFile = Files.createTempFile("deepseek-harness-established-recovery-", ".log")
        val sessionReadyMarker = controlDirectory.resolve(SESSION_READY_MARKER)
        val listenerStoppedMarker = controlDirectory.resolve(LISTENER_STOPPED_MARKER)
        val outageCallFailedMarker = controlDirectory.resolve(OUTAGE_CALL_FAILED_MARKER)
        val listenerRestartedMarker = controlDirectory.resolve(LISTENER_RESTARTED_MARKER)

        val generationOneRuntime = runtime(
            title = "DeepSeek Harness listener generation one",
            markdown = "generation-one-public password = $GENERATION_ONE_SECRET",
            capturedAtEpochMs = 3,
        )
        val generationTwoRuntime = runtime(
            title = "DeepSeek Harness listener generation two",
            markdown = "generation-two-public password = $GENERATION_TWO_SECRET",
            capturedAtEpochMs = 4,
        )

        var process: Process? = null
        var generationOneServer: DesktopMcpLoopbackServer? = null
        var generationTwoServer: DesktopMcpLoopbackServer? = null
        var generationOneWorkers: String? = null
        var generationTwoWorkers: String? = null

        try {
            generationOneServer = startServer(port, token, generationOneRuntime)
            generationOneWorkers = generationOneServer.workerThreadPrefix

            process = ProcessBuilder(
                "pnpm",
                "exec",
                "vitest",
                "run",
                "--config",
                "vitest.e2e.config.ts",
                STAGED_RECOVERY_SPEC,
                "--reporter=dot",
            )
                .directory(upstreamRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .apply {
                    environment()[ENV_ENDPOINT] = endpoint
                    environment()[ENV_SYNTHETIC_TOKEN] = token
                    environment()[ENV_CONTROL_DIRECTORY] = controlDirectory.toString()
                    environment()["DSH_E2E_MAX_WORKERS"] = "1"
                    environment()["NO_COLOR"] = "1"
                }
                .start()

            awaitMarker(
                marker = sessionReadyMarker,
                process = process,
                outputFile = outputFile,
                token = token,
                endpoint = endpoint,
                description = "initial established-session marker",
                timeoutSeconds = INITIAL_SESSION_TIMEOUT_SECONDS,
            )
            assertTrue(process.isAlive, "DeepSeek Harness process exited before listener shutdown")

            generationOneServer.close()
            generationOneServer = null
            awaitPortReleased(port)
            generationOneWorkers?.let(::awaitWorkerThreadsStopped)
            generationOneWorkers = null
            Files.writeString(listenerStoppedMarker, "stopped\n")

            awaitMarker(
                marker = outageCallFailedMarker,
                process = process,
                outputFile = outputFile,
                token = token,
                endpoint = endpoint,
                description = "outage-call failure marker",
                timeoutSeconds = OUTAGE_PROBE_TIMEOUT_SECONDS,
            )
            assertTrue(process.isAlive, "DeepSeek Harness process exited before listener replacement")

            generationTwoServer = startServer(port, token, generationTwoRuntime)
            generationTwoWorkers = generationTwoServer.workerThreadPrefix
            Files.writeString(listenerRestartedMarker, "restarted\n")

            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                stopProcess(process)
                fail("Pinned DeepSeek Harness established-session recovery exceeded the bounded timeout")
            }

            val output = readBoundedUtf8(outputFile, MAX_CAPTURED_OUTPUT_BYTES)
            assertFalse(token in output, "Established-session recovery output exposed the synthetic credential")
            assertFalse(GENERATION_ONE_SECRET in output, "Established-session recovery output exposed generation-one secret")
            assertFalse(GENERATION_TWO_SECRET in output, "Established-session recovery output exposed generation-two secret")
            val safeOutput = redact(output, token, endpoint)
            assertEquals(
                0,
                process.exitValue(),
                "Pinned DeepSeek Harness established-session recovery failed: $safeOutput",
            )
            assertTrue(
                PASS_MARKER in output,
                "Established-session recovery pass marker is absent: $safeOutput",
            )
            assertEquals(
                DispatcherMode.WAITING_FOR_CONFIRMATION,
                generationTwoRuntime.dispatcherState.value.mode,
            )
            assertEquals(
                NAVIGATION_URL,
                generationTwoRuntime.dispatcherState.value.pendingAction?.arguments?.get("url"),
            )
        } finally {
            process?.takeIf(Process::isAlive)?.let(::stopProcess)
            generationOneServer?.close()
            generationTwoServer?.close()
            Files.deleteIfExists(outputFile)
            deleteRecursively(controlDirectory)
            awaitPortReleased(port)
            generationOneWorkers?.let(::awaitWorkerThreadsStopped)
            generationTwoWorkers?.let(::awaitWorkerThreadsStopped)
        }
    }

    private fun runtime(title: String, markdown: String, capturedAtEpochMs: Long): AgentBrowserRuntime =
        AgentBrowserRuntime().also { runtime ->
            runBlocking {
                runtime.onPageContext(
                    PageContext(
                        url = "https://example.com/established-recovery",
                        title = title,
                        markdown = markdown,
                        capturedAtEpochMs = capturedAtEpochMs,
                    ),
                )
            }
        }

    private fun startServer(
        port: Int,
        token: String,
        runtime: AgentBrowserRuntime,
    ): DesktopMcpLoopbackServer {
        val tokenBytes = token.encodeToByteArray()
        return try {
            assertNotNull(
                DesktopMcpLoopbackServer.startIfEnabled(
                    config = DesktopMcpLoopbackServerConfig.forTest(port = port),
                    bearerToken = tokenBytes,
                    gateway = BrowserMcpGateway(runtime),
                ),
            )
        } finally {
            tokenBytes.fill(0)
        }
    }

    private fun awaitMarker(
        marker: Path,
        process: Process,
        outputFile: Path,
        token: String,
        endpoint: String,
        description: String,
        timeoutSeconds: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(marker)) return
            if (!process.isAlive) {
                val output = readBoundedUtf8(outputFile, MAX_CAPTURED_OUTPUT_BYTES)
                fail("DeepSeek Harness exited before $description: ${redact(output, token, endpoint)}")
            }
            Thread.sleep(50)
        }
        fail("DeepSeek Harness did not emit $description within the bounded deadline")
    }

    private fun stopProcess(process: Process) {
        process.destroy()
        if (!process.waitFor(PROCESS_GRACE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(PROCESS_GRACE_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun selectUnusedLoopbackPort(): Int = ServerSocket().use { probe ->
        probe.reuseAddress = true
        probe.bind(
            InetSocketAddress(
                InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                0,
            ),
        )
        probe.localPort
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
        .replace(GENERATION_ONE_SECRET, "<redacted-generation-one-secret>")
        .replace(GENERATION_TWO_SECRET, "<redacted-generation-two-secret>")
        .replace(Regex("Bearer\\s+[^\\s\\\"']+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
        .takeLast(MAX_FAILURE_OUTPUT_CHARACTERS)

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
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
        fail("Established-session recovery listener did not release its loopback port")
    }

    private fun awaitWorkerThreadsStopped(prefix: String) {
        repeat(100) {
            val live = Thread.getAllStackTraces().keys.any { thread ->
                thread.isAlive && thread.name.startsWith(prefix)
            }
            if (!live) return
            Thread.sleep(20)
        }
        fail("Established-session recovery listener left worker threads alive")
    }

    private companion object {
        const val ENV_UPSTREAM_ROOT = "DSH_E2E_ROOT"
        const val ENV_UPSTREAM_COMMIT = "DSH_E2E_COMMIT"
        const val ENV_SYNTHETIC_TOKEN = "DSH_E2E_TOKEN"
        const val ENV_ENDPOINT = "KOTLIN_AUTO_WEBVIEW_MCP_URL"
        const val ENV_CONTROL_DIRECTORY = "KOTLIN_AUTO_WEBVIEW_E2E_CONTROL_DIR"
        const val PINNED_UPSTREAM_COMMIT = "47f943859bef60e4160492346772ded9b24f765a"
        const val STAGED_RECOVERY_SPEC =
            "packages/mcp/mcp-client/tests/kotlin-auto-webview-established-session-recovery.external.e2e.ts"
        const val SESSION_READY_MARKER = "initial-session-ready"
        const val LISTENER_STOPPED_MARKER = "listener-stopped"
        const val OUTAGE_CALL_FAILED_MARKER = "outage-call-failed"
        const val LISTENER_RESTARTED_MARKER = "listener-restarted"
        const val PASS_MARKER = "KMP_DSH_ESTABLISHED_RECOVERY_PASS"
        const val GENERATION_ONE_SECRET = "established-generation-one-secret"
        const val GENERATION_TWO_SECRET = "established-generation-two-secret"
        const val NAVIGATION_URL = "https://example.com/deepseek-harness-established-recovery"
        const val INITIAL_SESSION_TIMEOUT_SECONDS = 30L
        const val OUTAGE_PROBE_TIMEOUT_SECONDS = 30L
        const val PROCESS_TIMEOUT_SECONDS = 150L
        const val PROCESS_GRACE_SECONDS = 5L
        const val MAX_CAPTURED_OUTPUT_BYTES = 96 * 1_024
        const val MAX_FAILURE_OUTPUT_CHARACTERS = 6_000
    }
}
