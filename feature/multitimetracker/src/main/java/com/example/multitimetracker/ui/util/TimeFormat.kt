package com.example.multitimetracker.ui.util

/**
 * Backwards-compatible wrapper for older call sites.
 *
 * Some screens used `TimeFormat.formatDurationMs(...)` while this file originally exposed
 * only the top-level `formatDuration(...)` function.
 */
object TimeFormat {
    fun formatDurationMs(
        ms: Long,
        showSeconds: Boolean = true,
        hideHoursIfZero: Boolean = false
    ): String = formatDuration(
        ms = ms,
        showSeconds = showSeconds,
        hideHoursIfZero = hideHoursIfZero
    )
}

/**
 * Formats a duration in ms.
 *
 * Output example: 5h 11m 22s
 *
 * - Units are shown only when meaningful (leading zeros are never padded).
 * - If hideHoursIfZero=true and hours==0, hours are omitted.
 * - If showSeconds=false, seconds are omitted.
 */
fun formatDuration(
    ms: Long,
    showSeconds: Boolean = true,
    hideHoursIfZero: Boolean = false
): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    val parts = mutableListOf<String>()

    if (!hideHoursIfZero || hours > 0L) {
        if (hours > 0L) parts.add("${hours}h")
    }

    // Show minutes when meaningful (or when hours are shown).
    val showMinutes = (hours > 0L) || (minutes > 0L)
    if (showMinutes) {
        parts.add("${minutes}m")
    }

    if (showSeconds) {
        val showSecondsPart = seconds > 0L || parts.isEmpty()
        if (showSecondsPart) {
            parts.add("${seconds}s")
        }
    }

    // If seconds are hidden and everything is zero, show 0m.
    if (!showSeconds && parts.isEmpty()) {
        parts.add("0m")
    }

    return parts.joinToString(" ")
}

