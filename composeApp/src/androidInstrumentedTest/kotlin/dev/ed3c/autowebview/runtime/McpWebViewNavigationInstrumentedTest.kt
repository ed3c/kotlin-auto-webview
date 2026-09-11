package dev.ed3c.autowebview.runtime

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ed3c.autowebview.MainActivity
import dev.ed3c.autowebview.domain.PageContext
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
        val fixtureHtml = "<html><head><title>Fixture</title></head><body>applied</body></html>"
        var platformRequestedUrl: String? = null

        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            webView = WebView(activity)
            activity.setContentView(webView)
        }

        val runtime = AgentBrowserRuntime()
        runtime.bindNavigationPort(BrowserNavigationPort { url ->
            assertEquals(destination, url)
            webView.loadDataWithBaseURL(url, fixtureHtml, "text/html", "utf-8", url)
            platformRequestedUrl = url
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
        assertEquals(destination, platformRequestedUrl, "confirmed URL must reach the Android WebView API")

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
