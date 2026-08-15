package dev.ed3c.autowebview.privacy

import dev.ed3c.autowebview.domain.PageContext

class PrivacyGuard(
    private val maximumContentChars: Int = 20_000,
) {
    private val secretPatterns = listOf(
        Regex("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*[^\\s,;]{6,}"),
        Regex("\\b(?:\\d[ -]*?){13,19}\\b"),
        Regex("-----BEGIN [A-Z ]+ PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+ PRIVATE KEY-----"),
    )
    private val sensitiveInputTypes = setOf("password", "credit-card", "cc-number", "cc-csc")

    fun sanitize(context: PageContext): PageContext {
        var content = context.markdown.take(maximumContentChars)
        secretPatterns.forEach { pattern -> content = pattern.replace(content, "[REDACTED]") }
        return context.copy(
            markdown = content,
            selection = context.selection.take(2_000),
            interactiveElements = context.interactiveElements
                .filterNot { it.inputType?.lowercase() in sensitiveInputTypes }
                .take(300),
        )
    }
}
