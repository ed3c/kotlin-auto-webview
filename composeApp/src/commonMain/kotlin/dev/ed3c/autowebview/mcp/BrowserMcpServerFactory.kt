package dev.ed3c.autowebview.mcp

import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.AgentAction
import dev.ed3c.autowebview.domain.StableIds
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class BrowserMcpServerFactory(
    private val runtime: AgentBrowserRuntime,
) {
    fun create(): Server {
        val server = Server(
            Implementation(name = "kotlin-auto-webview", version = "0.1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
            ),
        )

        server.addResource(
            uri = "browser://current-page",
            name = "Current browser page",
            description = "Sanitized current-page context captured from the embedded WebView",
            mimeType = "application/json",
        ) { request ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(runtime.currentContextJson(), request.uri, "application/json"),
                ),
            )
        }

        server.addTool(
            name = "browser_capture_context",
            description = "Read the already-sanitized current page context",
            inputSchema = ToolSchema(properties = buildJsonObject { }),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ) {
            CallToolResult(content = listOf(TextContent(runtime.currentContextJson())))
        }

        server.addTool(
            name = "browser_propose_navigation",
            description = "Propose navigation. The local dispatcher may require user confirmation before execution.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "HTTPS URL to navigate to")
                    }
                },
                required = listOf("url"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
        ) { request ->
            val url = request.arguments?.get("url")?.jsonPrimitive?.content.orEmpty()
            val action = AgentAction(
                id = StableIds.from("navigate", url),
                capabilityId = "browser.navigate",
                name = "Navigate",
                description = "Navigate to $url",
                arguments = mapOf("url" to url),
                risk = ActionRisk.MEDIUM,
            )
            val decision = runtime.propose(action)
            CallToolResult(content = listOf(TextContent("Navigation proposal: $decision")))
        }

        return server
    }
}
