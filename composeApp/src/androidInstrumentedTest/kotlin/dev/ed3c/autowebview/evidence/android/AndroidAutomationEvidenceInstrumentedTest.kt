package dev.ed3c.autowebview.evidence.android

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import android.test.InstrumentationTestCase
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.ed3c.autowebview.device.policy.DistributionProfile
import dev.ed3c.autowebview.device.profile.AndroidCompiledDistributionProfile
import dev.ed3c.autowebview.executor.BrowserActionCancellationSignal
import dev.ed3c.autowebview.executor.BrowserActionCommand
import dev.ed3c.autowebview.executor.BrowserActionKind
import dev.ed3c.autowebview.executor.BrowserTargetQuery
import dev.ed3c.autowebview.executor.ClickPayload
import dev.ed3c.autowebview.executor.FillTextPayload
import dev.ed3c.autowebview.executor.PlatformBrowserActionResult
import dev.ed3c.autowebview.executor.SelectOptionPayload
import dev.ed3c.autowebview.executor.webview.PlaySafeWebViewBrowserActionPlatform
import dev.ed3c.autowebview.executor.webview.PlaySafeWebViewPageObservation
import dev.ed3c.autowebview.executor.webview.PlaySafeWebViewPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

@Suppress("DEPRECATION")
class AndroidAutomationEvidenceInstrumentedTest : InstrumentationTestCase() {
    fun testPackageBoundaryAndAccessibilityStateRemainSeparated() {
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val profile = AndroidCompiledDistributionProfile.current
        val packageInfo = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_META_DATA,
        )
        val accessibilityServices = packageInfo.services.orEmpty().filter {
            it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
        }

        when (profile) {
            DistributionProfile.PLAY_SAFE -> {
                assertEquals(PLAY_SAFE_PACKAGE, packageName)
                assertTrue(accessibilityServices.isEmpty())
            }

            DistributionProfile.ENTERPRISE_SIDELOAD -> {
                assertEquals(ENTERPRISE_PACKAGE, packageName)
                assertEquals(1, accessibilityServices.size)
                val service = accessibilityServices.single()
                assertEquals(ENTERPRISE_SERVICE, service.name)
                assertTrue(service.exported)
                assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, service.permission)

                // Reading the user-owned setting is evidence only. This test never writes it.
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                val component = ComponentName(packageName, ENTERPRISE_SERVICE).flattenToString()
                assertFalse(enabled.split(':').any { it.equals(component, ignoreCase = true) })
            }

            DistributionProfile.ACCESSIBILITY_TOOL -> fail("ACCESSIBILITY_TOOL must not be distributable")
        }
    }

    fun testCompiledProfileIdentityIsClosedAndShizukuIsNotInvented() {
        when (AndroidCompiledDistributionProfile.current) {
            DistributionProfile.PLAY_SAFE -> assertEquals(PLAY_SAFE_PACKAGE, AndroidCompiledDistributionProfile.applicationId)
            DistributionProfile.ENTERPRISE_SIDELOAD -> assertEquals(
                ENTERPRISE_PACKAGE,
                AndroidCompiledDistributionProfile.applicationId,
            )
            DistributionProfile.ACCESSIBILITY_TOOL -> fail("ACCESSIBILITY_TOOL variant exists unexpectedly")
        }

        val context = instrumentation.targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS,
        )
        val componentNames = buildList {
            packageInfo.services.orEmpty().forEach { add(it.name.orEmpty()) }
            packageInfo.providers.orEmpty().forEach { add(it.name.orEmpty()) }
            packageInfo.receivers.orEmpty().forEach { add(it.name.orEmpty()) }
        }
        assertFalse(componentNames.any { it.contains("shizuku", ignoreCase = true) })
    }

    fun testPlaySafeWebViewExactActionsAndNegativeControls() {
        if (AndroidCompiledDistributionProfile.current != DistributionProfile.PLAY_SAFE) {
            // Enterprise variant deliberately has no Play-safe execution authority.
            assertEquals(DistributionProfile.ENTERPRISE_SIDELOAD, AndroidCompiledDistributionProfile.current)
            return
        }

        val webView = createFixtureWebView()
        try {
            val bootstrap = PlaySafeWebViewBrowserActionPlatform(
                webView = webView,
                policy = PlaySafeWebViewPolicy(setOf(OWNED_ORIGIN)),
            )
            val first = runBlocking { bootstrap.captureOwnedPage() }

            val name = first.singleElement("Name")
            val choice = first.singleElement("Choice")
            val anchor = first.singleElement("Complete")
            val duplicates = first.interactiveElements.filter { it.accessibleName == "Duplicate" }
            val password = first.interactiveElements.single { it.inputType == "password" }
            val disabled = first.singleElement("Disabled")

            assertEquals(2, duplicates.size)
            assertFalse(duplicates[0].fingerprint == duplicates[1].fingerprint)
            assertEquals("", password.accessibleName)
            assertFalse(first.interactiveElements.any { it.accessibleName == "Shadow Secret" })
            assertFalse(first.interactiveElements.any { it.accessibleName == "Iframe Secret" })

            val policy = PlaySafeWebViewPolicy(
                allowedOrigins = setOf(OWNED_ORIGIN),
                clickNavigationExpectations = mapOf(anchor.fingerprint to CLICK_DESTINATION),
            )
            val platform = PlaySafeWebViewBrowserActionPlatform(webView, policy)

            val fillTarget = runBlocking { platform.resolve(first.query(name)) }.single()
            val fill = runBlocking {
                platform.perform(
                    BrowserActionCommand(
                        proposalId = "fixture-fill",
                        pageUrl = BASE_PAGE_URL,
                        targetExecutionToken = fillTarget.executionToken,
                        targetFingerprint = name.fingerprint,
                        kind = BrowserActionKind.FILL_TEXT,
                        payload = FillTextPayload("fixture-value"),
                    ),
                    BrowserActionCancellationSignal { false },
                )
            }
            assertEquals(PlatformBrowserActionResult.Completed, fill)

            val selectTarget = runBlocking { platform.resolve(first.query(choice)) }.single()
            val select = runBlocking {
                platform.perform(
                    BrowserActionCommand(
                        proposalId = "fixture-select",
                        pageUrl = BASE_PAGE_URL,
                        targetExecutionToken = selectTarget.executionToken,
                        targetFingerprint = choice.fingerprint,
                        kind = BrowserActionKind.SELECT_OPTION,
                        payload = SelectOptionPayload("b"),
                    ),
                    BrowserActionCancellationSignal { false },
                )
            }
            assertEquals(PlatformBrowserActionResult.Completed, select)

            val passwordTarget = runBlocking { platform.resolve(first.query(password)) }.single()
            val sensitive = runBlocking {
                platform.perform(
                    BrowserActionCommand(
                        proposalId = "fixture-sensitive",
                        pageUrl = BASE_PAGE_URL,
                        targetExecutionToken = passwordTarget.executionToken,
                        targetFingerprint = password.fingerprint,
                        kind = BrowserActionKind.FILL_TEXT,
                        payload = FillTextPayload("not-a-real-secret"),
                    ),
                    BrowserActionCancellationSignal { false },
                )
            }
            assertTrue(sensitive is PlatformBrowserActionResult.Rejected)

            val disabledTarget = runBlocking { platform.resolve(first.query(disabled)) }.single()
            val disabledResult = runBlocking {
                platform.perform(
                    BrowserActionCommand(
                        proposalId = "fixture-disabled",
                        pageUrl = BASE_PAGE_URL,
                        targetExecutionToken = disabledTarget.executionToken,
                        targetFingerprint = disabled.fingerprint,
                        kind = BrowserActionKind.FILL_TEXT,
                        payload = FillTextPayload("blocked"),
                    ),
                    BrowserActionCancellationSignal { false },
                )
            }
            assertTrue(disabledResult is PlatformBrowserActionResult.Rejected)

            val cancelTarget = runBlocking { platform.resolve(first.query(name)) }.single()
            val cancelled = runBlocking {
                platform.perform(
                    BrowserActionCommand(
                        proposalId = "fixture-cancel",
                        pageUrl = BASE_PAGE_URL,
                        targetExecutionToken = cancelTarget.executionToken,
                        targetFingerprint = name.fingerprint,
                        kind = BrowserActionKind.FILL_TEXT,
                        payload = FillTextPayload("cancelled"),
                    ),
                    BrowserActionCancellationSignal { true },
                )
            }
            assertEquals(PlatformBrowserActionResult.CancelledBeforeSideEffect, cancelled)

            var fakeNow = 1_000L
            val expiring = PlaySafeWebViewBrowserActionPlatform(
                webView = webView,
                policy = PlaySafeWebViewPolicy(setOf(OWNED_ORIGIN)),
                nowEpochMs = { fakeNow },
            )
            val expiringTarget = runBlocking { expiring.resolve(first.query(name)) }.single()
            fakeNow = 4_001L
            val expired = runBlocking {
                expiring.perform(
                    BrowserActionCommand(
                        proposalId = "fixture-expired",
                        pageUrl = BASE_PAGE_URL,
                        targetExecutionToken = expiringTarget.executionToken,
                        targetFingerprint = name.fingerprint,
                        kind = BrowserActionKind.FILL_TEXT,
                        payload = FillTextPayload("expired"),
                    ),
                    BrowserActionCancellationSignal { false },
                )
            }
            assertTrue(expired is PlatformBrowserActionResult.Rejected)
            assertEquals("target-token-expired", (expired as PlatformBrowserActionResult.Rejected).code)

            val wrongDestination = PlaySafeWebViewBrowserActionPlatform(
                webView = webView,
                policy = PlaySafeWebViewPolicy(
                    allowedOrigins = setOf(OWNED_ORIGIN),
                    clickNavigationExpectations = mapOf(anchor.fingerprint to "$BASE_PAGE_URL#wrong"),
                ),
            )
            val wrongTarget = runBlocking { wrongDestination.resolve(first.query(anchor)) }.single()
            val wrong = runBlocking {
                wrongDestination.perform(
                    BrowserActionCommand(
                        proposalId = "fixture-wrong-navigation",
                        pageUrl = BASE_PAGE_URL,
                        targetExecutionToken = wrongTarget.executionToken,
                        targetFingerprint = anchor.fingerprint,
                        kind = BrowserActionKind.CLICK,
                        payload = ClickPayload,
                    ),
                    BrowserActionCancellationSignal { false },
                )
            }
            assertTrue(wrong is PlatformBrowserActionResult.Rejected)

            val clickTarget = runBlocking { platform.resolve(first.query(anchor)) }.single()
            val click = runBlocking {
                platform.perform(
                    BrowserActionCommand(
                        proposalId = "fixture-click",
                        pageUrl = BASE_PAGE_URL,
                        targetExecutionToken = clickTarget.executionToken,
                        targetFingerprint = anchor.fingerprint,
                        kind = BrowserActionKind.CLICK,
                        payload = ClickPayload,
                    ),
                    BrowserActionCancellationSignal { false },
                )
            }
            assertEquals(PlatformBrowserActionResult.Completed, click)
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    private fun createFixtureWebView(): WebView {
        val reference = AtomicReference<WebView>()
        val loaded = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            try {
                val webView = WebView(instrumentation.targetContext)
                webView.settings.javaScriptEnabled = true
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, 1080, 1920)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url == BASE_PAGE_URL) loaded.countDown()
                    }
                }
                reference.set(webView)
                webView.loadDataWithBaseURL(
                    BASE_PAGE_URL,
                    FIXTURE_HTML,
                    "text/html",
                    "UTF-8",
                    BASE_PAGE_URL,
                )
            } catch (error: Throwable) {
                failure.set(error)
                loaded.countDown()
            }
        }
        assertTrue("fixture WebView did not load", loaded.await(20, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("fixture WebView failed", it) }
        return reference.get() ?: throw AssertionError("fixture WebView absent")
    }

    private fun PlaySafeWebViewPageObservation.singleElement(name: String) =
        interactiveElements.single { it.accessibleName == name }

    private fun PlaySafeWebViewPageObservation.query(element: dev.ed3c.autowebview.domain.InteractiveElement) =
        BrowserTargetQuery(
            pageUrl = pageUrl,
            fingerprint = element.fingerprint,
            expectedRole = element.role,
            expectedAccessibleName = element.accessibleName,
        )

    private companion object {
        const val PLAY_SAFE_PACKAGE = "dev.ed3c.autowebview"
        const val ENTERPRISE_PACKAGE = "dev.ed3c.autowebview.enterprise"
        const val ENTERPRISE_SERVICE =
            "dev.ed3c.autowebview.device.accessibility.executor.EnterpriseAccessibilityService"
        const val OWNED_ORIGIN = "https://app.example.test"
        const val BASE_PAGE_URL = "https://app.example.test/page"
        const val CLICK_DESTINATION = "https://app.example.test/page#complete"

        val FIXTURE_HTML = """
            <!doctype html>
            <html>
              <body>
                <a aria-label="Complete" href="#complete">Complete</a>
                <input aria-label="Name" type="text" />
                <select aria-label="Choice">
                  <option value="a">A</option>
                  <option value="b">B</option>
                </select>
                <input aria-label="Password" type="password" />
                <input aria-label="Disabled" type="text" disabled />
                <button aria-label="Duplicate">One</button>
                <button aria-label="Duplicate">Two</button>
                <div id="shadow-host"></div>
                <iframe srcdoc="<button aria-label='Iframe Secret'>Hidden</button>"></iframe>
                <script>
                  const host = document.getElementById('shadow-host');
                  const root = host.attachShadow({mode: 'open'});
                  const button = document.createElement('button');
                  button.setAttribute('aria-label', 'Shadow Secret');
                  button.textContent = 'Hidden';
                  root.appendChild(button);
                </script>
              </body>
            </html>
        """.trimIndent()
    }
}
