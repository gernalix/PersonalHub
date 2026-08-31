package com.example.multitimetracker.util

fun formatDuration(ms: Long, showSeconds: Boolean = true): String {
    val totalSeconds = ms / 1000

    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return buildString {
        if (days > 0) {
            append("${days}g ")
            append("${hours}h ")
        } else {
            append("${hours}h ")
        }
        append("${minutes}m")
        if (showSeconds) append(" ${seconds}s")
    }.trim()
}
