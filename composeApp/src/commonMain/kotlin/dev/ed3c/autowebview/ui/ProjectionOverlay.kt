package dev.ed3c.autowebview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ed3c.autowebview.domain.RenderingMode
import dev.ed3c.autowebview.runtime.AgentBrowserRuntime
import kotlin.math.roundToInt

@Composable
fun ProjectionOverlay(runtime: AgentBrowserRuntime) {
    val projections by runtime.projections.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            projections.filter { it.anchorRect != null }.forEach { projection ->
                val rect = projection.anchorRect!!
                val x = rect.x.toFloat().coerceIn(0f, size.width)
                val y = rect.y.toFloat().coerceIn(0f, size.height)
                val path = Path().apply {
                    moveTo(12f, size.height - 24f)
                    cubicTo(size.width * 0.25f, size.height * 0.75f, x * 0.65f, y * 1.15f, x, y)
                }
                drawPath(path, Color(0xAA0EA5E9), alpha = projection.relevance.toFloat().coerceIn(0.25f, 0.9f))
                drawCircle(Color(0xFF0EA5E9), radius = 7f, center = Offset(x, y))
            }
        }

        projections
            .filter { it.renderingMode == RenderingMode.BUBBLE && it.anchorRect != null }
            .take(2)
            .forEach { projection ->
                val rect = projection.anchorRect!!
                Text(
                    text = projection.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .offset { IntOffset(rect.x.roundToInt().coerceAtLeast(0), (rect.y + rect.height + 8).roundToInt().coerceAtLeast(0)) }
                        .widthIn(max = 240.dp)
                        .background(Color(0xE61E293B), MaterialTheme.shapes.small)
                        .padding(8.dp),
                )
            }
    }
}
