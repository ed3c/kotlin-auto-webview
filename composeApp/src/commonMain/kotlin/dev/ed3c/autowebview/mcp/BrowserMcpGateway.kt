package dev.ed3c.autowebview.mcp

import dev.ed3c.autowebview.capability.PolicyDecision
import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import dev.ed3c.autowebview.domain.StableIds
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Transport-independent MCP gateway for Android, iOS, Web and Desktop.
 *
 * It supports the modern stateless discovery flow and the legacy initialize flow without forcing
 * a platform-specific SDK dependency into commonMain. External transports still own identity,
 * authorization, protocol headers, rate limits and origin policy.
 */
class BrowserMcpGateway(
    private val runtime: AgentBrowserRuntime,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun handle(payload: String): String {
        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            return error(JsonNull, PARSE_ERROR, "Invalid JSON-RPC payload")
        }

        val id = request["id"] ?: JsonNull
        if (request["jsonrpc"]?.asString() != JSON_RPC_VERSION) {
            return error(id, INVALID_REQUEST, "jsonrpc must be 2.0")
        }
        val method = request["method"]?.asString()
            ?: return error(id, INVALID_REQUEST, "method is required")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            when (method) {
                "server/discover" -> success(id, discoveryResult())
                "initialize" -> success(id, legacyInitializeResult())
                "ping" -> success(id, buildJsonObject { })
                "resources/list" -> success(id, resourcesListResult())
                "resources/read" -> readResource(id, params)
                "tools/list" -> success(id, toolsListResult())
                "tools/call" -> callTool(id, params)
                else -> error(id, METHOD_NOT_FOUND, "Unsupported MCP method: $method")
            }
        } catch (failure: IllegalArgumentException) {
            error(id, INVALID_PARAMS, failure.message ?: "Invalid parameters")
        } catch (failure: Exception) {
            error(id, INTERNAL_ERROR, failure.message ?: "Internal MCP gateway error")
        }
    }

    private fun discoveryResult(): JsonObject = buildJsonObject {
        put("protocolVersion", MODERN_PROTOCOL_VERSION)
        putJsonObject("serverInfo") {
            put("name", SERVER_NAME)
            put("version", SERVER_VERSION)
        }
        putJsonObject("capabilities") {
            putJsonObject("resources") { put("listChanged", false) }
            putJsonObject("tools") { put("listChanged", false) }
        }
        put("ttlMs", 60_000)
    }

    private fun legacyInitializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", LEGACY_PROTOCOL_VERSION)
        putJsonObject("capabilities") {
            putJsonObject("resources") {
                put("subscribe", false)
                put("listChanged", false)
            }
            putJsonObject("tools") { put("listChanged", false) }
        }
        putJsonObject("serverInfo") {
            put("name", SERVER_NAME)
            put("version", SERVER_VERSION)
        }
        put(
            "instructions",
            "State-changing browser actions remain proposals until local policy and human confirmation allow execution.",
        )
    }

    private fun resourcesListResult(): JsonObject = buildJsonObject {
        putJsonArray("resources") {
            add(buildJsonObject {
                put("uri", CURRENT_PAGE_URI)
                put("name", "Current browser page")
                put("description", "Sanitized page context captured from the embedded WebView")
                put("mimeType", "application/json")
            })
        }
    }

    private fun readResource(id: JsonElement, params: JsonObject): String {
        val uri = params["uri"]?.asString()
            ?: return error(id, INVALID_PARAMS, "uri is required")
        if (uri != CURRENT_PAGE_URI) {
            return error(
                id = id,
                code = RESOURCE_NOT_FOUND,
                message = "Resource not found",
                data = buildJsonObject { put("uri", uri) },
            )
        }
        return success(id, buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("uri", CURRENT_PAGE_URI)
                    put("mimeType", "application/json")
                    put("text", runtime.currentContextJson())
                })
            }
        })
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            add(buildJsonObject {
                put("name", "browser_capture_context")
                put("description", "Read the sanitized current-page context already held by the app")
                put("inputSchema", emptyObjectSchema())
                putJsonObject("annotations") {
                    put("readOnlyHint", true)
                    put("destructiveHint", false)
                    put("openWorldHint", false)
                }
            })
            add(buildJsonObject {
                put("name", "browser_propose_navigation")
                put("description", "Create a typed HTTPS navigation proposal; local policy may require user confirmation")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("url") {
                            put("type", "string")
                            put("format", "uri")
                            put("pattern", "^https://")
                            put("maxLength", MAX_URL_CHARS)
                        }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("url"))))
                    put("additionalProperties", false)
                })
                putJsonObject("annotations") {
                    put("readOnlyHint", false)
                    put("destructiveHint", false)
                    put("openWorldHint", true)
                }
            })
        }
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject): String {
        val name = params["name"]?.asString()
            ?: return error(id, INVALID_PARAMS, "tool name is required")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        return when (name) {
            "browser_capture_context" -> toolTextResult(id, runtime.currentContextJson())
            "browser_propose_navigation" -> {
                val url = arguments["url"]?.asString()
                    ?: return error(id, INVALID_PARAMS, "url is required")
                require(url.length <= MAX_URL_CHARS) { "url exceeds $MAX_URL_CHARS characters" }
                require(url.startsWith("https://")) { "only HTTPS navigation is accepted" }
                require(url.none { it.code < 0x20 || it.code == 0x7f }) { "url contains control characters" }

                val action = AgentAction(
                    id = StableIds.from("navigate", url),
                    capabilityId = "browser.navigate",
                    name = "Navigate",
                    description = "Navigate to $url",
                    arguments = mapOf("url" to url),
                    risk = ActionRisk.MEDIUM,
                )
                val decision = runtime.propose(action)
                val message = when (decision) {
                    PolicyDecision.Allowed -> "Navigation proposal accepted by policy"
                    is PolicyDecision.RequiresConfirmation -> "Navigation proposal awaits user confirmation: ${decision.reason}"
                    is PolicyDecision.Denied -> "Navigation proposal denied: ${decision.reason}"
                }
                toolTextResult(id, message, isError = decision is PolicyDecision.Denied)
            }
            else -> error(id, METHOD_NOT_FOUND, "Unknown tool: $name")
        }
    }

    private fun toolTextResult(id: JsonElement, text: String, isError: Boolean = false): String = success(
        id,
        buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            put("isError", isError)
        },
    )

    private fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        put("additionalProperties", false)
    }

    private fun success(id: JsonElement, result: JsonElement): String = buildJsonObject {
        put("jsonrpc", JSON_RPC_VERSION)
        put("id", id)
        put("result", result)
    }.toString()

    private fun error(
        id: JsonElement,
        code: Int,
        message: String,
        data: JsonElement? = null,
    ): String = buildJsonObject {
        put("jsonrpc", JSON_RPC_VERSION)
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
            data?.let { put("data", it) }
        }
    }.toString()

    private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.content

    companion object {
        const val MODERN_PROTOCOL_VERSION = "2026-07-28"
        const val LEGACY_PROTOCOL_VERSION = "2025-11-25"
        const val CURRENT_PAGE_URI = "browser://current-page"
        private const val JSON_RPC_VERSION = "2.0"
        private const val SERVER_NAME = "kotlin-auto-webview"
        private const val SERVER_VERSION = "0.1.0"
        private const val MAX_URL_CHARS = 2_048
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val INTERNAL_ERROR = -32603
        private const val RESOURCE_NOT_FOUND = -32002
    }
}
