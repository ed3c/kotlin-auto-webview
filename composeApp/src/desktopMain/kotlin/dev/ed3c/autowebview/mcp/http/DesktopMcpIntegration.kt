package dev.ed3c.autowebview.mcp.http

import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit runtime profile for the Desktop MCP listener.
 *
 * The listener is disabled unless the host sets [ENABLE_VARIABLE] to exactly [ENABLED_VALUE].
 * Every other variable is read only once the profile is already enabled, and a malformed value is
 * a hard failure rather than a silent fallback to a default endpoint.
 */
data class DesktopMcpRuntimeProfile(
    val enabled: Boolean,
    val port: Int = DEFAULT_PORT,
    val path: String = "/mcp",
    val allowedOrigins: Set<String> = emptySet(),
    val allowMissingOrigin: Boolean = true,
) {
    internal fun toListenerConfig(): DesktopMcpLoopbackServerConfig =
        DesktopMcpLoopbackServerConfig.runtime(
            enabled = enabled,
            port = port,
            path = path,
            allowedOrigins = allowedOrigins,
            allowMissingOrigin = allowMissingOrigin,
        )

    companion object {
        const val ENABLE_VARIABLE: String = "KAW_MCP_LISTENER"
        const val ENABLED_VALUE: String = "enabled"
        const val PORT_VARIABLE: String = "KAW_MCP_PORT"
        const val ORIGINS_VARIABLE: String = "KAW_MCP_ALLOWED_ORIGINS"
        const val DEFAULT_PORT: Int = 3_090

        val DISABLED: DesktopMcpRuntimeProfile = DesktopMcpRuntimeProfile(enabled = false)

        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
        ): DesktopMcpRuntimeProfile {
            if (environment[ENABLE_VARIABLE]?.trim() != ENABLED_VALUE) return DISABLED

            val port = environment[PORT_VARIABLE]?.trim()?.let { raw ->
                requireNotNull(raw.toIntOrNull()?.takeIf { it in 1..65_535 }) {
                    "$PORT_VARIABLE is not an admitted TCP port"
                }
            } ?: DEFAULT_PORT

            val origins = environment[ORIGINS_VARIABLE]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()

            return DesktopMcpRuntimeProfile(
                enabled = true,
                port = port,
                allowedOrigins = origins,
                allowMissingOrigin = origins.isEmpty(),
            )
        }
    }
}

/**
 * Desktop application lifecycle owner for the default-off loopback MCP listener.
 *
 * It binds one credential lifecycle to one listener, releases both on normal shutdown, and keeps a
 * JVM shutdown hook so an abrupt exit still closes the socket, worker threads, and credential
 * custody. Nothing starts unless [DesktopMcpRuntimeProfile.enabled] is true.
 */
class DesktopMcpIntegration private constructor(
    private val credentials: DesktopMcpCredentialLifecycle,
    private val server: DesktopMcpLoopbackServer,
    private val childProcessCredential: String,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val shutdownHook = Thread({ closeInternal() }, "desktop-mcp-shutdown")

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    val endpoint: String get() = server.endpoint

    val activeEpoch: String? get() = credentials.activeEpoch

    /**
     * Environment an approved local child process is started with.
     *
     * The value is a reference the child resolves itself; nothing else in the process tree, and no
     * log line, receives the credential.
     */
    fun childProcessEnvironment(): Map<String, String> = mapOf(
        DesktopMcpCredentialLifecycle.CHILD_PROCESS_ENVIRONMENT_NAME to childProcessCredential,
    )

    /** Rotate to a higher credential epoch without interrupting the bound listener. */
    fun rotateCredential(nowEpochMs: Long): DesktopMcpCredentialMaterial {
        check(!closed.get()) { "Desktop MCP integration is closed" }
        return credentials.rotate(nowEpochMs)
    }

    /** Reject every issued credential immediately while leaving the socket teardown to [close]. */
    fun revokeCredential() = credentials.revoke()

    override fun close() {
        closeInternal()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    override fun toString(): String =
        "DesktopMcpIntegration(state=${if (closed.get()) "closed" else "running"}, endpoint=<redacted>)"

    private fun closeInternal() {
        if (!closed.compareAndSet(false, true)) return
        try {
            server.close()
        } finally {
            credentials.close()
        }
    }

    companion object {
        /**
         * Start the listener when, and only when, the profile explicitly enables it.
         *
         * Returns `null` for a disabled profile so the caller has no partially initialised object
         * to reason about.
         */
        fun startIfEnabled(
            profile: DesktopMcpRuntimeProfile,
            gateway: BrowserMcpGateway,
            nowEpochMs: Long = System.currentTimeMillis(),
            observer: McpHttpBridgeObserver = McpHttpBridgeObserver { },
        ): DesktopMcpIntegration? {
            if (!profile.enabled) return null

            val credentials = DesktopMcpCredentialLifecycle(
                expectedScheme = "http",
                expectedAuthority = "${DesktopMcpLoopbackServer.LOOPBACK_HOST}:${profile.port}",
                subjectId = SUBJECT_ID,
            )
            return try {
                val material = credentials.issue(nowEpochMs)
                val childProcessCredential = material.use { it.decodeToString() }
                val server = DesktopMcpLoopbackServer.startIfEnabled(
                    config = profile.toListenerConfig(),
                    credentials = credentials,
                    gateway = McpJsonRpcGateway(gateway::handle),
                    observer = observer,
                ) ?: error("An enabled Desktop MCP profile did not start a listener")
                DesktopMcpIntegration(credentials, server, childProcessCredential)
            } catch (failure: Throwable) {
                credentials.close()
                throw failure
            }
        }

        private const val SUBJECT_ID = "desktop-mcp-listener"
    }
}
