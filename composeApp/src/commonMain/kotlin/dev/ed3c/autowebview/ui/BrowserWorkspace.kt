package dev.ed3c.autowebview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.unit.dp
import com.multiplatform.webview.jsbridge.rememberWebViewJsBridge
import com.multiplatform.webview.web.LoadingState
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import dev.ed3c.autowebview.web.ContextExtractorScript
import dev.ed3c.autowebview.web.PageContextMessageHandler
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun BrowserWorkspace(runtime: AgentBrowserRuntime) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("https://example.com") }
    val webViewState = rememberWebViewState(address)
    val navigator = rememberWebViewNavigator()
    val jsBridge = rememberWebViewJsBridge(navigator)

    LaunchedEffect(jsBridge) {
        jsBridge.register(PageContextMessageHandler(scope, runtime))
    }
    LaunchedEffect(webViewState) {
        webViewState.webSettings.apply {
            isJavaScriptEnabled = true
            customUserAgentString = null
        }
    }
    LaunchedEffect(webViewState.loadingState) {
        if (webViewState.loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(ContextExtractorScript.source)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFF8FAFC))) {
        val wide = maxWidth >= 900.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                BrowserPane(
                    modifier = Modifier.weight(1f),
                    address = address,
                    onAddressChange = { address = it },
                    onNavigate = { navigator.loadUrl(normalizeUrl(address)) },
                    onBack = navigator::navigateBack,
                    onForward = navigator::navigateForward,
                    onReload = navigator::reload,
                    onCapture = { navigator.evaluateJavaScript("window.__kawCaptureContext && window.__kawCaptureContext();") },
                    runtime = runtime,
                    webViewContent = {
                        WebView(
                            state = webViewState,
                            navigator = navigator,
                            webViewJsBridge = jsBridge,
                            captureBackPresses = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    loading = webViewState.loadingState is LoadingState.Loading,
                )
                ContextRail(runtime, Modifier.width(340.dp).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                BrowserPane(
                    modifier = Modifier.weight(1f),
                    address = address,
                    onAddressChange = { address = it },
                    onNavigate = { navigator.loadUrl(normalizeUrl(address)) },
                    onBack = navigator::navigateBack,
                    onForward = navigator::navigateForward,
                    onReload = navigator::reload,
                    onCapture = { navigator.evaluateJavaScript("window.__kawCaptureContext && window.__kawCaptureContext();") },
                    runtime = runtime,
                    webViewContent = {
                        WebView(
                            state = webViewState,
                            navigator = navigator,
                            webViewJsBridge = jsBridge,
                            captureBackPresses = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    loading = webViewState.loadingState is LoadingState.Loading,
                )
                ContextRail(runtime, Modifier.fillMaxWidth().heightIn(max = 260.dp))
            }
        }
    }
}

@Composable
private fun BrowserPane(
    modifier: Modifier,
    address: String,
    onAddressChange: (String) -> Unit,
    onNavigate: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onCapture: () -> Unit,
    runtime: AgentBrowserRuntime,
    loading: Boolean,
    webViewContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Surface(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onBack) { Text("←") }
                IconButton(onClick = onForward) { Text("→") }
                IconButton(onClick = onReload) { Text("↻") }
                OutlinedTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("HTTPS address") },
                )
                Button(onClick = onNavigate) { Text("Go") }
                Button(onClick = onCapture) { Text("Context") }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(runtime) {
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            scope.launch { runtime.userInteractionStarted() }
                            waitForUpOrCancellation(pass = PointerEventPass.Initial)
                            scope.launch { runtime.userInteractionEnded() }
                        }
                    },
            ) {
                webViewContent()
                ProjectionOverlay(runtime)
                if (loading) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                }
            }
        }
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "https://")
        else -> "https://$trimmed"
    }
}
