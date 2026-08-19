package dev.ed3c.autowebview.executor.webview

import dev.ed3c.autowebview.executor.BrowserActionKind
import dev.ed3c.autowebview.executor.ClickPayload
import dev.ed3c.autowebview.executor.FillTextPayload
import dev.ed3c.autowebview.executor.SelectOptionPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PlaySafeWebViewContractsTest {
    @Test
    fun owned_origin_policy_is_https_exact_and_fail_closed() {
        val policy = PlaySafeWebViewPolicy(setOf("https://app.example.test"))

        assertTrue(policy.admits("https://app.example.test/account"))
        assertFalse(policy.admits("https://other.example.test/account"))
        assertFalse(policy.admits("http://app.example.test/account"))
        assertFailsWith<IllegalArgumentException> {
            PlaySafeWebViewPolicy(setOf("http://app.example.test"))
        }
    }

    @Test
    fun click_navigation_expectation_is_exact_target_and_owned_url_only() {
        val target = observation(tag = "a", role = "link", accessibleName = "Continue")
        val policy = PlaySafeWebViewPolicy(
            allowedOrigins = setOf("https://app.example.test"),
            clickNavigationExpectations = mapOf(
                target.fingerprint to "https://app.example.test/complete",
            ),
        )

        assertEquals(
            "https://app.example.test/complete",
            policy.expectedClickNavigation(target.fingerprint),
        )
        assertFailsWith<IllegalArgumentException> {
            PlaySafeWebViewPolicy(
                allowedOrigins = setOf("https://app.example.test"),
                clickNavigationExpectations = mapOf(
                    target.fingerprint to "https://evil.example.test/complete",
                ),
            )
        }
    }

    @Test
    fun fixed_bridge_binds_click_to_the_existing_exact_anchor_href_only() {
        assertTrue(PLAY_SAFE_FIXED_BRIDGE_JS.contains("element.tagName.toLowerCase() !== 'a'"))
        assertTrue(PLAY_SAFE_FIXED_BRIDGE_JS.contains("new URL(element.getAttribute('href') || '', location.href)"))
        assertTrue(PLAY_SAFE_FIXED_BRIDGE_JS.contains("actualDestination.href !== expectedDestination.href"))
        assertFalse(PLAY_SAFE_FIXED_BRIDGE_JS.contains("location.assign("))
        assertFalse(PLAY_SAFE_FIXED_BRIDGE_JS.contains("location.replace("))
        assertFalse(PLAY_SAFE_FIXED_BRIDGE_JS.contains("window.location ="))
    }

    @Test
    fun fingerprint_binds_document_generation_position_and_sanitized_identity() {
        val base = playSafeFingerprint(
            pageUrl = "https://app.example.test/page",
            pageNonce = "nonce-a",
            localId = "interactive-1",
            tag = "button",
            role = "button",
            accessibleName = "Save",
            inputType = null,
        )
        val newGeneration = playSafeFingerprint(
            pageUrl = "https://app.example.test/page",
            pageNonce = "nonce-b",
            localId = "interactive-1",
            tag = "button",
            role = "button",
            accessibleName = "Save",
            inputType = null,
        )
        val semanticDrift = playSafeFingerprint(
            pageUrl = "https://app.example.test/page",
            pageNonce = "nonce-a",
            localId = "interactive-1",
            tag = "button",
            role = "button",
            accessibleName = "Delete",
            inputType = null,
        )

        assertNotEquals(base, newGeneration)
        assertNotEquals(base, semanticDrift)
    }

    @Test
    fun click_requires_declared_exact_navigation_not_unrelated_dom_change() {
        val pre = observation(tag = "a", role = "link", accessibleName = "Continue")
        val expectedUrl = "https://app.example.test/complete"

        assertEquals(
            PlaySafePostconditionVerdict.VERIFIED_APPLIED,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.CLICK,
                payload = ClickPayload,
                expectedFingerprint = pre.fingerprint,
                pre = pre,
                post = null,
                currentPageUrl = expectedUrl,
                expectedClickNavigationUrl = expectedUrl,
            ),
        )
        assertEquals(
            PlaySafePostconditionVerdict.INCONCLUSIVE,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.CLICK,
                payload = ClickPayload,
                expectedFingerprint = pre.fingerprint,
                pre = pre,
                post = pre.copy(documentDigestSha256 = "b".repeat(64)),
                currentPageUrl = pre.pageUrl,
                expectedClickNavigationUrl = expectedUrl,
            ),
        )
        assertEquals(
            PlaySafePostconditionVerdict.INCONCLUSIVE,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.CLICK,
                payload = ClickPayload,
                expectedFingerprint = pre.fingerprint,
                pre = pre,
                post = null,
                currentPageUrl = "https://app.example.test/wrong",
                expectedClickNavigationUrl = expectedUrl,
            ),
        )
    }

    @Test
    fun fill_requires_value_digest_and_input_change_event_observation() {
        val value = "hello"
        val pre = observation(valueDigest = sha256("before"), inputEvents = 3, changeEvents = 7)
        val post = pre.copy(
            valueDigestSha256 = sha256(value),
            inputEventCount = 4,
            changeEventCount = 8,
        )

        assertEquals(
            PlaySafePostconditionVerdict.VERIFIED_APPLIED,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.FILL_TEXT,
                payload = FillTextPayload(value),
                expectedFingerprint = pre.fingerprint,
                pre = pre,
                post = post,
                currentPageUrl = pre.pageUrl,
                expectedClickNavigationUrl = null,
            ),
        )
        assertEquals(
            PlaySafePostconditionVerdict.INCONCLUSIVE,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.FILL_TEXT,
                payload = FillTextPayload(value),
                expectedFingerprint = pre.fingerprint,
                pre = pre,
                post = post.copy(inputEventCount = pre.inputEventCount),
                currentPageUrl = pre.pageUrl,
                expectedClickNavigationUrl = null,
            ),
        )
    }

    @Test
    fun select_requires_selected_value_digest_and_change_event() {
        val value = "choice-b"
        val pre = observation(
            tag = "select",
            role = "select",
            inputType = null,
            valueDigest = sha256("choice-a"),
            inputEvents = 2,
            changeEvents = 5,
        )
        val post = pre.copy(
            valueDigestSha256 = sha256(value),
            inputEventCount = 3,
            changeEventCount = 6,
        )

        assertEquals(
            PlaySafePostconditionVerdict.VERIFIED_APPLIED,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.SELECT_OPTION,
                payload = SelectOptionPayload(value),
                expectedFingerprint = pre.fingerprint,
                pre = pre,
                post = post,
                currentPageUrl = pre.pageUrl,
                expectedClickNavigationUrl = null,
            ),
        )
        assertEquals(
            PlaySafePostconditionVerdict.INCONCLUSIVE,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.SELECT_OPTION,
                payload = SelectOptionPayload(value),
                expectedFingerprint = pre.fingerprint,
                pre = pre,
                post = post.copy(changeEventCount = pre.changeEventCount),
                currentPageUrl = pre.pageUrl,
                expectedClickNavigationUrl = null,
            ),
        )
    }

    private fun observation(
        tag: String = "input",
        role: String? = "textbox",
        accessibleName: String = "Field",
        inputType: String? = if (tag == "input") "text" else null,
        documentDigest: String = "a".repeat(64),
        valueDigest: String? = null,
        inputEvents: Int = 0,
        changeEvents: Int = 0,
    ): PlaySafeWebElementObservation = PlaySafeWebElementObservation(
        pageUrl = "https://app.example.test/page",
        pageNonce = "nonce-01",
        localId = "interactive-0",
        token = "token-01",
        tag = tag,
        role = role,
        accessibleName = accessibleName,
        inputType = inputType,
        visible = true,
        enabled = true,
        editable = tag in setOf("input", "textarea", "select"),
        sensitivity = "NONE",
        documentDigestSha256 = documentDigest,
        valueDigestSha256 = valueDigest,
        inputEventCount = inputEvents,
        changeEventCount = changeEvents,
    )
}
