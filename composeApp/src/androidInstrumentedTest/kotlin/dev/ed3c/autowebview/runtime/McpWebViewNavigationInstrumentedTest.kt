package dev.ed3c.autowebview.runtime

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ed3c.autowebview.MainActivity
import dev.ed3c.autowebview.domain.PageContext
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class McpWebViewNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var webView: WebView

    @After
    fun destroyWebView() {
        if (::webView.isInitialized) instrumentation.runOnMainSync { webView.destroy() }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun confirmedMcpProposalReachesRealWebViewAndObservedUrlBecomesApplied() = runBlocking {
        val destination = "https://fixture.test/next"
        val finished = CountDownLatch(1)
        var observedUrl: String? = null

        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            webView = WebView(activity)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host != "fixture.test") return null
                    val html = "<html><head><title>Fixture</title></head><body>applied</body></html>"
                    return WebResourceResponse(
                        "text/html",
                        "utf-8",
                        ByteArrayInputStream(html.encodeToByteArray()),
                    )
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    observedUrl = url
                }

                override fun onPageFinished(view: WebView, url: String) {
                    observedUrl = url
                    if (url == destination) finished.countDown()
                }
            }
            activity.setContentView(webView)
        }

        val runtime = AgentBrowserRuntime()
        runtime.bindNavigationPort(BrowserNavigationPort { url ->
            assertEquals(destination, url)
            webView.loadUrl(url)
        })
        val gateway = dev.ed3c.autowebview.mcp.BrowserMcpGateway(runtime)
        val proposalResponse = gateway.handle(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"browser_propose_navigation","arguments":{"url":"$destination"}}}""",
        )
        assertTrue("WAITING_FOR_CONFIRMATION" in proposalResponse)
        val proposalText = Json.parseToJsonElement(proposalResponse).jsonObject["result"]!!.jsonObject["content"]!!
            .jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        val proposalId = Json.parseToJsonElement(proposalText).jsonObject["proposalId"]!!.jsonPrimitive.content

        instrumentation.runOnMainSync { runBlocking { runtime.confirmPendingAction() } }
        assertTrue(finished.await(20, TimeUnit.SECONDS), "fixture navigation did not finish")
        assertEquals(destination, observedUrl)

        runtime.onPageContext(
            PageContext(
                url = destination,
                title = "Fixture",
                markdown = "applied",
                capturedAtEpochMs = Long.MAX_VALUE,
            ),
        )
        assertEquals(NavigationActionState.APPLIED, runtime.navigationStatus(proposalId)?.state)
        assertEquals(null, runtime.dispatcherState.value.pendingAction, "completed dispatcher must release the pending action")
        assertTrue(runtime.auditEvents.value.any { it.message == "User confirmed pending navigation" })
    }
}
