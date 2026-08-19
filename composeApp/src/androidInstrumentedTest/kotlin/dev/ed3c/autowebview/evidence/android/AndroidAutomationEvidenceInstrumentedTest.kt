package dev.ed3c.autowebview.evidence.android

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.ed3c.autowebview.device.policy.DistributionProfile
import dev.ed3c.autowebview.device.profile.AndroidCompiledDistributionProfile
import dev.ed3c.autowebview.domain.InteractiveElement
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

/**
 * Framework-only Stage-7 runner.
 *
 * `android.test.*` is intentionally not used: current compile SDKs do not expose the old
 * InstrumentationTestCase API to this KMP AndroidTest compilation. This runner speaks the stable
 * instrumentation status protocol directly so Gradle/ddmlib can observe named test start/pass/fail
 * events without adding a test dependency or widening composeApp/build.gradle.kts.
 */
class AndroidAutomationEvidenceInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val cases = listOf(
            EvidenceCase(
                name = "testPackageBoundaryAndAccessibilityStateRemainSeparated",
                block = ::testPackageBoundaryAndAccessibilityStateRemainSeparated,
            ),
            EvidenceCase(
                name = "testCompiledProfileIdentityIsClosedAndShizukuIsNotInvented",
                block = ::testCompiledProfileIdentityIsClosedAndShizukuIsNotInvented,
            ),
            EvidenceCase(
                name = "testPlaySafeWebViewExactActionsAndNegativeControls",
                block = ::testPlaySafeWebViewExactActionsAndNegativeControls,
            ),
        )

        var failures = 0
        cases.forEachIndexed { index, case ->
            sendStatus(
                STATUS_START,
                statusBundle(case.name, index + 1, cases.size, "${case.name}: START\n"),
            )
            try {
                case.block()
                sendStatus(
                    STATUS_OK,
                    statusBundle(case.name, index + 1, cases.size, "."),
                )
            } catch (error: Throwable) {
                failures += 1
                val status = statusBundle(
                    case.name,
                    index + 1,
                    cases.size,
                    "${case.name}: FAIL\n",
                )
                status.putString(KEY_STACK, Log.getStackTraceString(error))
                sendStatus(STATUS_FAILURE, status)
            }
        }

        finish(
            if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString(
                    KEY_STREAM,
                    if (failures == 0) {
                        "OK (${cases.size} bounded evidence cases)\n"
                    } else {
                        "FAILURES!!! cases=${cases.size} failures=$failures\n"
                    },
                )
            },
        )
    }

    private fun testPackageBoundaryAndAccessibilityStateRemainSeparated() {
        val context = targetContext
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
                assertEqualsValue(PLAY_SAFE_PACKAGE, packageName, "Play-safe application identity drift")
                assertCondition(accessibilityServices.isEmpty(), "Play-safe packaged AccessibilityService")
            }

            DistributionProfile.ENTERPRISE_SIDELOAD -> {
                assertEqualsValue(ENTERPRISE_PACKAGE, packageName, "Enterprise application identity drift")
                assertEqualsValue(1, accessibilityServices.size, "Enterprise AccessibilityService count")
                val service = accessibilityServices.single()
                assertEqualsValue(ENTERPRISE_SERVICE, service.name, "Enterprise service identity drift")
                assertCondition(service.exported, "Enterprise AccessibilityService must remain exported to system binding")
                assertEqualsValue(
                    Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
                    service.permission,
                    "Enterprise AccessibilityService permission drift",
                )

                // Reading the user-owned setting is evidence only. This runner never writes it.
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                val component = ComponentName(packageName, ENTERPRISE_SERVICE).flattenToString()
                assertCondition(
                    enabled.split(':').none { it.equals(component, ignoreCase = true) },
                    "Automated Stage-7 runner must not enable AccessibilityService",
                )
            }

            DistributionProfile.ACCESSIBILITY_TOOL ->
                throw AssertionError("ACCESSIBILITY_TOOL must not be distributable")
        }
    }

    private fun testCompiledProfileIdentityIsClosedAndShizukuIsNotInvented() {
        when (AndroidCompiledDistributionProfile.current) {
            DistributionProfile.PLAY_SAFE -> assertEqualsValue(
                PLAY_SAFE_PACKAGE,
                AndroidCompiledDistributionProfile.applicationId,
                "Play-safe compiled profile identity drift",
            )

            DistributionProfile.ENTERPRISE_SIDELOAD -> assertEqualsValue(
                ENTERPRISE_PACKAGE,
                AndroidCompiledDistributionProfile.applicationId,
                "Enterprise compiled profile identity drift",
            )

            DistributionProfile.ACCESSIBILITY_TOOL ->
                throw AssertionError("ACCESSIBILITY_TOOL variant exists unexpectedly")
        }

        val context = targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS,
        )
        val componentNames = buildList {
            packageInfo.services.orEmpty().forEach { add(it.name.orEmpty()) }
            packageInfo.providers.orEmpty().forEach { add(it.name.orEmpty()) }
            packageInfo.receivers.orEmpty().forEach { add(it.name.orEmpty()) }
        }
        assertCondition(
            componentNames.none { it.contains("shizuku", ignoreCase = true) },
            "Shizuku component was invented by evidence harness",
        )
    }

    private fun testPlaySafeWebViewExactActionsAndNegativeControls() {
        if (AndroidCompiledDistributionProfile.current != DistributionProfile.PLAY_SAFE) {
            assertEqualsValue(
                DistributionProfile.ENTERPRISE_SIDELOAD,
                AndroidCompiledDistributionProfile.current,
                "Enterprise evidence variant profile drift",
            )
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

            assertEqualsValue(2, duplicates.size, "Duplicate-label fixture cardinality drift")
            assertCondition(
                duplicates[0].fingerprint != duplicates[1].fingerprint,
                "Duplicate labels collapsed to one exact fingerprint",
            )
            assertEqualsValue("", password.accessibleName, "Sensitive accessible name leaked")
            assertCondition(
                first.interactiveElements.none { it.accessibleName == "Shadow Secret" },
                "Shadow-root descendant was silently traversed",
            )
            assertCondition(
                first.interactiveElements.none { it.accessibleName == "Iframe Secret" },
                "Iframe descendant was silently traversed",
            )

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
            assertEqualsValue(PlatformBrowserActionResult.Completed, fill, "Fill postcondition not verified")

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
            assertEqualsValue(PlatformBrowserActionResult.Completed, select, "Select postcondition not verified")

            val passwordTarget = runBlocking { platform.resolve(first.query(password)) }.single()
            val sensitive = runBlocking {
                platform.perform(
                    BrowserActionCommand(
                        proposalId = "fixture-sensitive",
                        pageUrl = BASE_PAGE_URL,
                        targetExecutionToken = passwordTarget.executionToken,
                        targetFingerprint = password.fingerprint,
                        kind = BrowserActionKind.FILL_TEXT,
                        payload = FillTextPayload("synthetic-sensitive-fixture"),
                    ),
                    BrowserActionCancellationSignal { false },
                )
            }
            assertCondition(sensitive is PlatformBrowserActionResult.Rejected, "Sensitive target did not fail closed")

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
            assertCondition(disabledResult is PlatformBrowserActionResult.Rejected, "Disabled target did not fail closed")

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
            assertEqualsValue(
                PlatformBrowserActionResult.CancelledBeforeSideEffect,
                cancelled,
                "Pre-dispatch cancellation did not preserve NONE",
            )

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
            assertCondition(expired is PlatformBrowserActionResult.Rejected, "Expired target token did not reject")
            assertEqualsValue(
                "target-token-expired",
                (expired as PlatformBrowserActionResult.Rejected).code,
                "Expired token rejection code drift",
            )

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
            assertCondition(wrong is PlatformBrowserActionResult.Rejected, "Wrong click destination did not reject")

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
            assertEqualsValue(PlatformBrowserActionResult.Completed, click, "Exact anchor navigation not verified")
        } finally {
            runOnMainSync { webView.destroy() }
        }
    }

    private fun createFixtureWebView(): WebView {
        val reference = AtomicReference<WebView>()
        val loaded = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        runOnMainSync {
            try {
                val webView = WebView(targetContext)
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
        assertCondition(loaded.await(20, TimeUnit.SECONDS), "Fixture WebView did not load")
        failure.get()?.let { throw AssertionError("Fixture WebView failed", it) }
        return reference.get() ?: throw AssertionError("Fixture WebView absent")
    }

    private fun PlaySafeWebViewPageObservation.singleElement(name: String): InteractiveElement =
        interactiveElements.single { it.accessibleName == name }

    private fun PlaySafeWebViewPageObservation.query(element: InteractiveElement) =
        BrowserTargetQuery(
            pageUrl = pageUrl,
            fingerprint = element.fingerprint,
            expectedRole = element.role,
            expectedAccessibleName = element.accessibleName,
        )

    private fun assertCondition(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private fun assertEqualsValue(expected: Any?, actual: Any?, message: String) {
        if (expected != actual) throw AssertionError("$message: expected=$expected actual=$actual")
    }

    private fun statusBundle(testName: String, current: Int, total: Int, stream: String): Bundle =
        Bundle().apply {
            putString(KEY_ID, "AndroidAutomationEvidenceInstrumentation")
            putInt(KEY_NUM_TESTS, total)
            putString(KEY_CLASS, RUNNER_CLASS)
            putString(KEY_TEST, testName)
            putInt(KEY_CURRENT, current)
            putString(KEY_STREAM, stream)
        }

    private data class EvidenceCase(
        val name: String,
        val block: () -> Unit,
    )

    private companion object {
        const val STATUS_START = 1
        const val STATUS_OK = 0
        const val STATUS_FAILURE = -2
        const val KEY_ID = "id"
        const val KEY_NUM_TESTS = "numtests"
        const val KEY_CLASS = "class"
        const val KEY_TEST = "test"
        const val KEY_CURRENT = "current"
        const val KEY_STREAM = "stream"
        const val KEY_STACK = "stack"
        const val RUNNER_CLASS =
            "dev.ed3c.autowebview.evidence.android.AndroidAutomationEvidenceInstrumentation"
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
