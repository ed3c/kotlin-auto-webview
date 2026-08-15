package dev.ed3c.autowebview.privacy

import dev.ed3c.autowebview.domain.InteractiveElement
import dev.ed3c.autowebview.domain.PageContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyGuardTest {
    @Test
    fun removesSecretsAndPasswordElements() {
        val sanitized = PrivacyGuard().sanitize(
            PageContext(
                url = "https://example.com",
                title = "Example",
                markdown = "api_key = abcdefghijklmnop and card 4111 1111 1111 1111",
                capturedAtEpochMs = 1,
                interactiveElements = listOf(
                    InteractiveElement("a", "input", inputType = "password"),
                    InteractiveElement("b", "button", text = "Continue"),
                ),
            ),
        )
        assertFalse("abcdefghijklmnop" in sanitized.markdown)
        assertTrue("[REDACTED]" in sanitized.markdown)
        assertEquals(listOf("b"), sanitized.interactiveElements.map { it.fingerprint })
    }
}
