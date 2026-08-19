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
    fun click_requires_a_fresh_observable_postcondition() {
        val preDigest = "a".repeat(64)
        val post = observation(documentDigest = "b".repeat(64))

        assertEquals(
            PlaySafePostconditionVerdict.VERIFIED_APPLIED,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.CLICK,
                payload = ClickPayload,
                expectedFingerprint = post.fingerprint,
                preDocumentDigestSha256 = preDigest,
                post = post,
                pageUrlChanged = false,
            ),
        )
        assertEquals(
            PlaySafePostconditionVerdict.INCONCLUSIVE,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.CLICK,
                payload = ClickPayload,
                expectedFingerprint = post.fingerprint,
                preDocumentDigestSha256 = post.documentDigestSha256,
                post = post,
                pageUrlChanged = false,
            ),
        )
    }

    @Test
    fun fill_and_select_verify_value_digest_not_callback_success() {
        val fillValue = "hello"
        val filled = observation(valueDigest = sha256(fillValue))
        assertEquals(
            PlaySafePostconditionVerdict.VERIFIED_APPLIED,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.FILL_TEXT,
                payload = FillTextPayload(fillValue),
                expectedFingerprint = filled.fingerprint,
                preDocumentDigestSha256 = "a".repeat(64),
                post = filled,
                pageUrlChanged = false,
            ),
        )

        val selected = observation(tag = "select", valueDigest = sha256("choice-b"))
        assertEquals(
            PlaySafePostconditionVerdict.VERIFIED_APPLIED,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.SELECT_OPTION,
                payload = SelectOptionPayload("choice-b"),
                expectedFingerprint = selected.fingerprint,
                preDocumentDigestSha256 = "a".repeat(64),
                post = selected,
                pageUrlChanged = false,
            ),
        )
        assertEquals(
            PlaySafePostconditionVerdict.INCONCLUSIVE,
            PlaySafeWebPostconditionVerifier.verify(
                kind = BrowserActionKind.FILL_TEXT,
                payload = FillTextPayload("different"),
                expectedFingerprint = filled.fingerprint,
                preDocumentDigestSha256 = "a".repeat(64),
                post = filled,
                pageUrlChanged = false,
            ),
        )
    }

    private fun observation(
        tag: String = "input",
        documentDigest: String = "a".repeat(64),
        valueDigest: String? = null,
    ): PlaySafeWebElementObservation = PlaySafeWebElementObservation(
        pageUrl = "https://app.example.test/page",
        pageNonce = "nonce-01",
        localId = "interactive-0",
        token = "token-01",
        tag = tag,
        role = if (tag == "select") "select" else "textbox",
        accessibleName = "Field",
        inputType = if (tag == "input") "text" else null,
        visible = true,
        enabled = true,
        editable = true,
        sensitivity = "NONE",
        documentDigestSha256 = documentDigest,
        valueDigestSha256 = valueDigest,
    )
}
