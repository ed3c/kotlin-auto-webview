package dev.ed3c.autowebview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiplatform.webview.util.addTempDirectoryRemovalHook
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun main() = application {
    addTempDirectoryRemovalHook()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kotlin Auto WebView",
    ) {
        var initialized by remember { mutableStateOf(false) }
        var restartRequired by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0f) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                KCEF.init(
                    builder = {
                        installDir(File("kcef-bundle"))
                        progress {
                            onDownloading { progress = it }
                            onInitialized { initialized = true }
                        }
                        download {
                            github { release("jbr-release-17.0.12b1207.37") }
                        }
                        settings { cachePath = File("cache").absolutePath }
                    },
                    onError = { error -> errorMessage = error?.message ?: "KCEF initialization failed" },
                    onRestartRequired = { restartRequired = true },
                )
            }
        }

        MaterialTheme {
            when {
                restartRequired -> CenterMessage("KCEF installed. Restart the desktop app.")
                errorMessage != null -> CenterMessage(errorMessage!!)
                initialized -> App()
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Initializing Chromium ${progress.toInt()}%")
                    }
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose { KCEF.disposeBlocking() }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CenterMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, modifier = Modifier.padding(24.dp))
    }
}
