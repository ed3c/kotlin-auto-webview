package dev.ed3c.autowebview.mcp.http

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Platform dispatch and absence semantics are exercised everywhere; the real round-trip runs only
 * where the platform store exists.
 *
 * `resolve` takes the OS name as a parameter precisely so the decision table is testable off its
 * own platform — otherwise the branch a given runner cannot reach would never be covered anywhere.
 */
class McpHostKeyStoreTest {
    private val workspace: Path = Files.createTempDirectory("mcp-host-key-store")
    private val keychainPath: Path = workspace.resolve("kotlin-auto-webview-test.keychain-db")
    private val keychainPassword = "test-keychain-password"
    private var keychainCreated = false

    @AfterTest
    fun cleanUp() {
        // The test keychain is deleted by path and was never added to the search list, so the
        // user's login keychain is untouched either way.
        if (keychainCreated) runHostTool(listOf(SECURITY, "delete-keychain", keychainPath.toString()))
        Files.walk(workspace).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun anUnknownPlatformReportsThatItIsUnsupportedRatherThanReturningNothing() {
        val resolution = McpHostKeyStores.resolve(osName = "SomeFutureOS")

        val unavailable = assertIs<McpHostKeyStoreResolution.Unavailable>(resolution)
        assertEquals(McpHostKeyStoreUnavailability.UNSUPPORTED_PLATFORM, unavailable.reason)
    }

    @Test
    fun linuxWithoutTheClientToolReportsToolAbsentRatherThanDegrading() {
        val resolution = McpHostKeyStores.resolve(osName = "Linux")

        // On a developer desktop with a session bus this resolves; in a headless container — the
        // environment this project actually verifies Linux behaviour in — the tool is absent.
        // Either way the answer is typed, and neither answer is "quietly keep it in memory".
        when (resolution) {
            is McpHostKeyStoreResolution.Available ->
                assertTrue(resolution.store is LinuxSecretServiceKeyStore)
            is McpHostKeyStoreResolution.Unavailable -> assertTrue(
                resolution.reason == McpHostKeyStoreUnavailability.TOOL_ABSENT ||
                    resolution.reason == McpHostKeyStoreUnavailability.SERVICE_UNREACHABLE,
                "unexpected reason ${resolution.reason}",
            )
        }
    }

    @Test
    fun macOsResolvesToTheKeychainStore() {
        val resolution = McpHostKeyStores.resolve(osName = "Mac OS X")

        // /usr/bin/security ships with macOS; on a non-macOS runner the same call reports the tool
        // absent, which is the branch that must not be mistaken for "no store configured".
        when (resolution) {
            is McpHostKeyStoreResolution.Available ->
                assertTrue(resolution.store is MacOsKeychainStore)
            is McpHostKeyStoreResolution.Unavailable ->
                assertEquals(McpHostKeyStoreUnavailability.TOOL_ABSENT, resolution.reason)
        }
    }

    @Test
    fun anIssuedValueSurvivesAndIsReplacedAndRemoved() {
        if (!onMacOs()) return
        createTestKeychain()
        val store = MacOsKeychainStore(service = SERVICE, keychain = keychainPath)
        val account = "listener-under-test"

        assertNull(store.retrieve(account), "nothing may be stored before the first write")

        store.store(account, "first-issued-value".encodeToByteArray())
        assertContentEquals("first-issued-value".encodeToByteArray(), store.retrieve(account))

        // Rotation must replace rather than accumulate, or a stale value stays retrievable.
        store.store(account, "second-issued-value".encodeToByteArray())
        assertContentEquals("second-issued-value".encodeToByteArray(), store.retrieve(account))

        store.delete(account)
        assertNull(store.retrieve(account), "revocation must remove it from storage, not only memory")

        // Deleting something absent is success: the postcondition is "not stored".
        store.delete(account)
        assertNull(store.retrieve(account))
    }

    @Test
    fun accountsDoNotSeeEachOthersValues() {
        if (!onMacOs()) return
        createTestKeychain()
        val store = MacOsKeychainStore(service = SERVICE, keychain = keychainPath)

        store.store("listener-a", "value-a".encodeToByteArray())
        store.store("listener-b", "value-b".encodeToByteArray())

        assertContentEquals("value-a".encodeToByteArray(), store.retrieve("listener-a"))
        assertContentEquals("value-b".encodeToByteArray(), store.retrieve("listener-b"))

        store.delete("listener-a")
        assertNull(store.retrieve("listener-a"))
        assertContentEquals(
            "value-b".encodeToByteArray(),
            store.retrieve("listener-b"),
            "deleting one account must not remove another",
        )
    }

    @Test
    fun theStoreNeverRendersItsServiceOrValues() {
        val rendered = MacOsKeychainStore(service = SERVICE, keychain = keychainPath).toString()

        assertTrue(SERVICE !in rendered)
        assertTrue(keychainPath.toString() !in rendered)
    }

    private fun onMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().let { "mac" in it || "darwin" in it }

    private fun createTestKeychain() {
        val created = runHostTool(
            listOf(SECURITY, "create-keychain", "-p", keychainPassword, keychainPath.toString()),
        )
        check(created.exitCode == 0) { "could not create the test keychain (${created.exitCode})" }
        keychainCreated = true
        val unlocked = runHostTool(
            listOf(SECURITY, "unlock-keychain", "-p", keychainPassword, keychainPath.toString()),
        )
        check(unlocked.exitCode == 0) { "could not unlock the test keychain (${unlocked.exitCode})" }
    }

    private companion object {
        const val SECURITY = "/usr/bin/security"
        const val SERVICE = "dev.ed3c.autowebview.mcp.test"
    }
}
