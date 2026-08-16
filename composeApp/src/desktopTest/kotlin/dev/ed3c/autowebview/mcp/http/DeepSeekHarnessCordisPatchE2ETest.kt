package dev.ed3c.autowebview.mcp.http

import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import dev.ed3c.autowebview.providers.DeepSeekHarnessCordisBinding
import dev.ed3c.autowebview.providers.DeepSeekHarnessEndpointClass
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Issue #49 — the patch this repository *renders* is fed to the pinned upstream loader as
 * configuration, and then edited while the tree is live.
 *
 * The existing pinned-process subject (#35) constructs plugin options by hand. That proves the
 * upstream plugin works against this listener; it cannot prove that the exact YAML
 * [DeepSeekHarnessCordisBinding.renderCordisPatch] emits parses, mounts, and survives a reload.
 * This driver therefore renders the patch with production code, writes it as the profile's user
 * patch layer, and lets the external specification drive the upstream HMR path against it.
 */
class DeepSeekHarnessCordisPatchE2ETest {
    @Test
    fun renderedPatchIsAcceptedByThePinnedUpstreamLoader() {
        val upstreamRootValue = System.getenv(ENV_UPSTREAM_ROOT)?.trim().orEmpty()
        if (upstreamRootValue.isEmpty()) return

        val upstreamRoot = Path.of(upstreamRootValue).toAbsolutePath().normalize()
        assertEquals(
            PINNED_UPSTREAM_COMMIT,
            System.getenv(ENV_UPSTREAM_COMMIT)?.trim(),
            "Unexpected DeepSeek Harness evidence subject",
        )
        assertTrue(Files.isDirectory(upstreamRoot), "Pinned DeepSeek Harness workspace is absent")
        assertTrue(
            Files.isRegularFile(upstreamRoot.resolve(STAGED_E2E_SPEC)),
            "Repository-owned DeepSeek Harness HMR specification was not staged",
        )

        val token = System.getenv(ENV_SYNTHETIC_TOKEN)
            ?.takeIf(String::isNotBlank)
            ?: fail("Masked synthetic DeepSeek Harness credential is absent")

        val runtime = AgentBrowserRuntime()
        runBlocking {
            runtime.onPageContext(
                PageContext(
                    url = "https://example.com",
                    title = "DeepSeek Harness Cordis Cordis patch E2E",
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
        val profileDir = Files.createTempDirectory("deepseek-harness-cordis-patch-profile-")
        val outputFile = Files.createTempFile("deepseek-harness-cordis-patch-e2e-", ".log")

        try {
            // Production code renders the patch; the test never reconstructs an equivalent one.
            val binding = DeepSeekHarnessCordisBinding(
                endpoint = server.endpoint,
                endpointClass = DeepSeekHarnessEndpointClass.LOOPBACK_HTTP,
                bearerTokenEnvironmentVariable = ENV_SYNTHETIC_TOKEN,
            )
            val renderedPatch = binding.renderCordisPatch()
            assertFalse(token in renderedPatch, "Rendered patch embedded the credential value")
            Files.writeString(profileDir.resolve(PROFILE_PATCH_FILENAME), renderedPatch)

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
                    environment()[ENV_PROFILE_DIR] = profileDir.toString()
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
                fail("Pinned DeepSeek Harness Cordis patch E2E process exceeded the bounded timeout")
            }

            val output = readBoundedUtf8(outputFile, MAX_CAPTURED_OUTPUT_BYTES)
            assertFalse(token in output, "Cordis patch E2E output exposed the synthetic credential")
            assertFalse(SECRET_FIXTURE in output, "Cordis patch E2E output exposed the secret fixture")
            assertFalse(server.endpoint in output, "Cordis patch E2E output exposed the loopback endpoint")

            val safeOutput = redact(output, token, server.endpoint)
            assertEquals(0, process.exitValue(), "Pinned DeepSeek Harness Cordis patch E2E failed: $safeOutput")
            assertTrue(PATCH_PASS_MARKER in output, "Cordis patch marker is absent: $safeOutput")
            // The patch file the external process edited must still contain no credential value.
            val finalPatch = Files.readString(profileDir.resolve(PROFILE_PATCH_FILENAME))
            assertFalse(token in finalPatch, "Edited patch layer embedded the credential value")
        } finally {
            server.close()
            deleteRecursively(profileDir)
            Files.deleteIfExists(outputFile)
            awaitPortReleased(port)
            awaitWorkerThreadsStopped(threadPrefix)
        }
    }

    private fun readBoundedUtf8(path: Path, maximumBytes: Int): String {
        val output = ByteArrayOutputStream()
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8 * 1_024)
            while (output.size() < maximumBytes) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, minOf(read, maximumBytes - output.size()))
            }
        }
        return output.toByteArray().decodeToString()
    }

    private fun redact(output: String, token: String, endpoint: String): String = output
        .replace(token, "<redacted-credential>")
        .replace(endpoint, "<redacted-endpoint>")
        .take(MAX_FAILURE_OUTPUT_CHARACTERS)

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    private fun awaitPortReleased(port: Int) {
        val deadline = System.nanoTime() + PORT_RELEASE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            try {
                java.net.Socket().use {
                    it.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                }
            } catch (_: java.io.IOException) {
                return
            }
            Thread.sleep(50)
        }
        fail("Desktop MCP port $port was not released")
    }

    private fun awaitWorkerThreadsStopped(prefix: String) {
        val deadline = System.nanoTime() + PORT_RELEASE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val alive = Thread.getAllStackTraces().keys.any { it.name.startsWith(prefix) && it.isAlive }
            if (!alive) return
            Thread.sleep(50)
        }
        fail("Desktop MCP worker threads did not stop")
    }

    private companion object {
        const val ENV_UPSTREAM_ROOT = "DSH_E2E_ROOT"
        const val ENV_UPSTREAM_COMMIT = "DSH_E2E_COMMIT"
        const val ENV_SYNTHETIC_TOKEN = "DSH_E2E_TOKEN"
        const val ENV_ENDPOINT = "KOTLIN_AUTO_WEBVIEW_MCP_URL"
        const val ENV_PROFILE_DIR = "KAW_PROFILE_DIR"
        const val PINNED_UPSTREAM_COMMIT = "47f943859bef60e4160492346772ded9b24f765a"
        const val PROFILE_PATCH_FILENAME = "cordis.patch.yml"
        const val STAGED_E2E_SPEC =
            "packages/mcp/mcp-client/tests/kotlin-auto-webview-cordis-patch.external.e2e.ts"
        const val PATCH_PASS_MARKER = "KMP_DSH_CORDIS_PATCH_PASS"
                const val SECRET_FIXTURE = "super-secret-value"
        const val PROCESS_TIMEOUT_SECONDS = 240L
        const val PROCESS_GRACE_SECONDS = 5L
        const val MAX_CAPTURED_OUTPUT_BYTES = 64 * 1_024
        const val MAX_FAILURE_OUTPUT_CHARACTERS = 4_000
        const val PORT_RELEASE_TIMEOUT_NANOS = 10_000_000_000L
    }
}
