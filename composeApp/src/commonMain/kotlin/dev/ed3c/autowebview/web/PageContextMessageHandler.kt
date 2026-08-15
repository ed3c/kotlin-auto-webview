package dev.ed3c.autowebview.web

import com.multiplatform.webview.jsbridge.IJsMessageHandler
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class PageContextMessageHandler(
    private val scope: CoroutineScope,
    private val runtime: AgentBrowserRuntime,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : IJsMessageHandler {
    override fun methodName(): String = "PageContext"

    override fun handle(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        scope.launch {
            runCatching { json.decodeFromString<PageContext>(message.params) }
                .onSuccess {
                    runtime.onPageContext(it)
                    callback("{\"ok\":true}")
                }
                .onFailure { error ->
                    callback("{\"ok\":false,\"error\":\"${error.message?.replace("\"", "'") ?: "decode failure"}\"}")
                }
        }
    }
}
