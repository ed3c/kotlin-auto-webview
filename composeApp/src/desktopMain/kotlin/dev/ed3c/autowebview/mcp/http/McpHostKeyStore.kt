package dev.ed3c.autowebview.mcp.http

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Somewhere the operating system will keep an issued bearer value at rest.
 *
 * [DesktopMcpCredentialLifecycle] deliberately keeps only digests in memory, so a restart loses the
 * issued value and the host has to mint and redistribute a new one. A host key store closes that
 * gap without weakening the in-memory rules: the raw bytes live in the OS store and inside a
 * one-shot handle, never in a field of this process.
 */
interface McpHostKeyStore {
    /** Replace whatever is stored for [account]. The caller still owns zeroing [value]. */
    fun store(account: String, value: ByteArray)

    /** Return the stored bytes, or `null` when nothing is stored for [account]. */
    fun retrieve(account: String): ByteArray?

    /** Remove [account]. Absent is success: the postcondition is "not stored". */
    fun delete(account: String)
}

/**
 * Why no host key store is available here.
 *
 * Absence is a typed reason rather than a bare `null`, because "this platform has no such service"
 * and "the tool is missing from this image" lead an operator to different actions.
 */
enum class McpHostKeyStoreUnavailability {
    /** No integration exists for this operating system. */
    UNSUPPORTED_PLATFORM,

    /** The platform has such a service, but its client tool is not installed. */
    TOOL_ABSENT,

    /** The tool exists but could not be used — typically a headless container with no session. */
    SERVICE_UNREACHABLE,
}

sealed interface McpHostKeyStoreResolution {
    data class Available(val store: McpHostKeyStore) : McpHostKeyStoreResolution
    data class Unavailable(val reason: McpHostKeyStoreUnavailability) : McpHostKeyStoreResolution
}

/**
 * Resolve the operating system's store, or say precisely why there is none.
 *
 * **Absence never degrades silently.** A caller that wants persistence has to handle
 * [McpHostKeyStoreResolution.Unavailable] itself; nothing here quietly turns "kept by the operating
 * system" into "kept in this process only". That distinction is the entire security property, and
 * an unstated fallback is exactly how such a property disappears.
 */
object McpHostKeyStores {
    const val DEFAULT_SERVICE: String = "dev.ed3c.autowebview.mcp"

    fun resolve(
        service: String = DEFAULT_SERVICE,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): McpHostKeyStoreResolution {
        val normalized = osName.lowercase()
        return when {
            "mac" in normalized || "darwin" in normalized ->
                MacOsKeychainStore(service).resolveIfUsable()
            "linux" in normalized ->
                LinuxSecretServiceKeyStore(service).resolveIfUsable()
            else -> McpHostKeyStoreResolution.Unavailable(
                McpHostKeyStoreUnavailability.UNSUPPORTED_PLATFORM,
            )
        }
    }
}

/**
 * macOS Keychain through the `security` tool.
 *
 * An explicit [keychain] is honoured so a test never has to touch the user's login keychain.
 */
class MacOsKeychainStore(
    private val service: String = McpHostKeyStores.DEFAULT_SERVICE,
    private val keychain: Path? = null,
) : McpHostKeyStore {
    override fun store(account: String, value: ByteArray) {
        // `-U` updates in place, so a rotation replaces the entry rather than accumulating entries.
        val command = buildList {
            addAll(listOf(SECURITY, "add-generic-password", "-U", "-a", account, "-s", service, "-w"))
            add(value.decodeToString())
            keychain?.let { add(it.toString()) }
        }
        val result = runHostTool(command)
        check(result.exitCode == 0) { "keychain store failed (${result.exitCode})" }
    }

    override fun retrieve(account: String): ByteArray? {
        val command = buildList {
            addAll(listOf(SECURITY, "find-generic-password", "-a", account, "-s", service, "-w"))
            keychain?.let { add(it.toString()) }
        }
        val result = runHostTool(command)
        if (result.exitCode != 0) return null
        return result.stdout.trimEnd('\n').takeIf(String::isNotEmpty)?.encodeToByteArray()
    }

    override fun delete(account: String) {
        val command = buildList {
            addAll(listOf(SECURITY, "delete-generic-password", "-a", account, "-s", service))
            keychain?.let { add(it.toString()) }
        }
        runHostTool(command)
    }

    override fun toString(): String = "MacOsKeychainStore(service=<redacted>)"

    internal fun resolveIfUsable(): McpHostKeyStoreResolution =
        if (Path.of(SECURITY).toFile().canExecute()) {
            McpHostKeyStoreResolution.Available(this)
        } else {
            McpHostKeyStoreResolution.Unavailable(McpHostKeyStoreUnavailability.TOOL_ABSENT)
        }

    private companion object {
        const val SECURITY = "/usr/bin/security"
    }
}

/**
 * Linux Secret Service through `secret-tool`.
 *
 * A headless container usually has neither the tool nor a session bus. That is the case a caller
 * must handle rather than assume away, and it is why resolution distinguishes a missing tool from a
 * present tool that cannot reach the service.
 */
class LinuxSecretServiceKeyStore(
    private val service: String = McpHostKeyStores.DEFAULT_SERVICE,
) : McpHostKeyStore {
    override fun store(account: String, value: ByteArray) {
        val result = runHostTool(
            listOf(TOOL, "store", "--label=$service", "service", service, "account", account),
            input = value,
        )
        check(result.exitCode == 0) { "secret-tool store failed (${result.exitCode})" }
    }

    override fun retrieve(account: String): ByteArray? {
        val result = runHostTool(listOf(TOOL, "lookup", "service", service, "account", account))
        if (result.exitCode != 0) return null
        return result.stdout.trimEnd('\n').takeIf(String::isNotEmpty)?.encodeToByteArray()
    }

    override fun delete(account: String) {
        runHostTool(listOf(TOOL, "clear", "service", service, "account", account))
    }

    override fun toString(): String = "LinuxSecretServiceKeyStore(service=<redacted>)"

    internal fun resolveIfUsable(): McpHostKeyStoreResolution {
        val probe = runCatching { runHostTool(listOf(TOOL, "--version")) }.getOrNull()
            ?: return McpHostKeyStoreResolution.Unavailable(McpHostKeyStoreUnavailability.TOOL_ABSENT)
        return if (probe.exitCode == 0) {
            McpHostKeyStoreResolution.Available(this)
        } else {
            McpHostKeyStoreResolution.Unavailable(McpHostKeyStoreUnavailability.SERVICE_UNREACHABLE)
        }
    }

    private companion object {
        const val TOOL = "secret-tool"
    }
}

internal data class HostToolResult(val exitCode: Int, val stdout: String)

/**
 * Run a host tool with a bounded deadline.
 *
 * Output is drained before waiting so a tool that fills its pipe cannot deadlock the caller, and
 * neither the command line nor the output is logged — on macOS the issued value is an argument.
 */
internal fun runHostTool(command: List<String>, input: ByteArray? = null): HostToolResult {
    val process = ProcessBuilder(command).redirectErrorStream(false).start()
    if (input != null) process.outputStream.use { it.write(input) } else process.outputStream.close()

    val stdout = process.inputStream.bufferedReader().readText()
    process.errorStream.use { it.readBytes() }
    if (!process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return HostToolResult(exitCode = -1, stdout = "")
    }
    return HostToolResult(process.exitValue(), stdout)
}

private const val TOOL_TIMEOUT_SECONDS = 10L
