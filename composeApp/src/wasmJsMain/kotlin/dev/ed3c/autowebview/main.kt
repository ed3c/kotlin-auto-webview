package dev.ed3c.autowebview

import kotlinx.browser.document
import org.jetbrains.compose.ui.window.ComposeViewport

fun main() {
    ComposeViewport(document.body!!) { App() }
}
