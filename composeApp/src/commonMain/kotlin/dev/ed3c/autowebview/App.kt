package dev.ed3c.autowebview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import dev.ed3c.autowebview.ui.BrowserWorkspace

@Composable
fun App() {
    val runtime = remember { AgentBrowserRuntime() }
    MaterialTheme(colorScheme = lightColorScheme()) {
        BrowserWorkspace(runtime)
    }
}
