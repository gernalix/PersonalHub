package com.example.multitimetracker.ui.util

import com.gernalix.personalhub.core.ui.HubTimeFormat

/** Backwards-compatible Timer wrapper over the shared PersonalHub formatter. */
object TimeFormat {
    fun formatDurationMs(
        ms: Long,
        showSeconds: Boolean = true,
        hideHoursIfZero: Boolean = false,
    ): String = formatDuration(
        ms = ms,
        showSeconds = showSeconds,
        hideHoursIfZero = hideHoursIfZero,
    )
}

fun formatDuration(
    ms: Long,
    showSeconds: Boolean = true,
    hideHoursIfZero: Boolean = false,
): String =
    HubTimeFormat.compactDuration(
        durationMs = ms,
        showSeconds = showSeconds,
        hideHoursIfZero = hideHoursIfZero,
    )
