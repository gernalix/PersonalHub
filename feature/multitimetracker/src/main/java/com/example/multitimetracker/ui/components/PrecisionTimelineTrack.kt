// [MTR] v44
// v1
package com.example.multitimetracker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Timeline track for "Precision Tool": reads as a data trace.
 *
 * - Continuous colored segment for each session (task color)
 * - Optional continuation above/below when sessions are consecutive (no gap)
 * - Clear overlap indicator via a secondary parallel rail
 * - Running state uses a thin animated scanline (technical, not playful)
 */
@Composable
fun PrecisionTimelineTrack(
    railColor: Color,
    connectPrev: Boolean,
    connectNext: Boolean,
    overlapsPrev: Boolean,
    overlapsNext: Boolean,
    isRunning: Boolean,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    laneOverflow: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    // Fixed column width for the track area.
    Box(modifier = modifier.width(24.dp).fillMaxHeight()) {
        val t = rememberInfiniteTransition(label = "timeline_track")
        val p by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 950, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "scan"
        )

        Canvas(modifier = Modifier.fillMaxHeight().width(24.dp)) {
            // Geometry (all in px)
            val laneOffset = with(density) { (laneIndex.coerceAtMost(2) * 6).dp.toPx() }
            val xMain = with(density) { 10.dp.toPx() } + laneOffset
            val wMain = with(density) { 2.dp.toPx() }

            val xOverlap = with(density) { 16.dp.toPx() } + laneOffset
            val wOverlap = with(density) { 2.dp.toPx() }
            val overlapColor = (if (railColor.luminance() > 0.5f) Color.Black else Color.White).copy(alpha = 0.30f)

            val h = size.height

            // Main rail: always present (segment per row). It becomes visually continuous
            // thanks to connectPrev/connectNext and the fact that list items touch.
            val top = if (connectPrev) 0f else with(density) { 6.dp.toPx() }
            val bottom = if (connectNext) h else h - with(density) { 6.dp.toPx() }

            drawLine(
                color = railColor,
                start = Offset(xMain, top),
                end = Offset(xMain, bottom),
                strokeWidth = wMain
            )

            // Overlap indicator: secondary parallel rail, subtle but explicit.
            if (overlapsPrev || overlapsNext) {
                val oTop = if (overlapsPrev) 0f else with(density) { 10.dp.toPx() }
                val oBottom = if (overlapsNext) h else h - with(density) { 10.dp.toPx() }
                drawLine(
                    color = overlapColor,
                    start = Offset(xOverlap, oTop),
                    end = Offset(xOverlap, oBottom),
                    strokeWidth = wOverlap
                )
            }

            // Running scanline: thin moving segment on top of the rail.
            if (laneOverflow) {
                val dotR = with(density) { 1.5.dp.toPx() }
                val cy = with(density) { 10.dp.toPx() }
                val gap = with(density) { 4.dp.toPx() }
                drawCircle(color = railColor, radius = dotR, center = Offset(xMain, cy - gap))
                drawCircle(color = railColor, radius = dotR, center = Offset(xMain, cy))
                drawCircle(color = railColor, radius = dotR, center = Offset(xMain, cy + gap))
            }

            if (isRunning) {
                val seg = (h * 0.18f).coerceIn(with(density) { 12.dp.toPx() }, with(density) { 44.dp.toPx() })
                val y = if (h <= seg) 0f else (h - seg) * p
                val scan = railColor.copy(alpha = 0.55f)
                drawLine(
                    color = scan,
                    start = Offset(xMain, y),
                    end = Offset(xMain, y + seg),
                    strokeWidth = wMain
                )
            }
        }
    }
}
