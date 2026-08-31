// Version 40
package com.example.multitimetracker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Precision Tool" running indicator: thin technical scanline on a constant rail.
 */
@Composable
fun PrecisionLeftRail(
    railColor: Color,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    railWidth: Dp = 2.dp
) {
    val t = rememberInfiniteTransition(label = "rail_scan")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_offset"
    )

    Canvas(
        modifier = modifier
            .width(railWidth)
            .fillMaxHeight()
    ) {
        // Main rail
        drawLine(
            color = railColor,
            start = Offset(size.width / 2, 0f),
            end = Offset(size.width / 2, size.height),
            strokeWidth = size.width
        )

        // Running scanline
        if (isRunning) {
            val seg = (size.height * 0.15f).coerceIn(12.dp.toPx(), 44.dp.toPx())
            val y = if (size.height <= seg) 0f else (size.height - seg) * p
            drawLine(
                color = railColor.copy(alpha = 0.65f),
                start = Offset(size.width / 2, y),
                end = Offset(size.width / 2, y + seg),
                strokeWidth = size.width
            )
        }
    }
}
