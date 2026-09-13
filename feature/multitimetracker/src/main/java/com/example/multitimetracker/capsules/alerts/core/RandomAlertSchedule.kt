package com.example.multitimetracker.capsules.alerts.core

private const val HOUR_MS = 60 * 60 * 1000L
private const val DAY_MS = 24 * HOUR_MS

enum class RandomAlertWindow(val millis: Long) {
    HOUR(HOUR_MS),
    DAY(DAY_MS);

    companion object {
        fun parse(value: String): RandomAlertWindow =
            entries.firstOrNull { it.name == value } ?: DAY
    }
}

internal fun planRandomAlertInstants(
    nowMs: Long,
    count: Int,
    window: RandomAlertWindow,
    nextOffsetMs: (Long) -> Long,
): List<Long> {
    val cleanCount = count.coerceAtLeast(0)
    if (cleanCount == 0) return emptyList()
    val windowMs = window.millis.coerceAtLeast(cleanCount.toLong())
    val values = linkedSetOf<Long>()
    var attempts = 0
    while (values.size < cleanCount && attempts < cleanCount * 8) {
        attempts += 1
        val offset = nextOffsetMs(windowMs).coerceIn(1L, windowMs)
        values.add(nowMs + offset)
    }
    var fallback = nowMs + 1L
    while (values.size < cleanCount) {
        values.add(fallback)
        fallback += 1L
    }
    return values.sorted()
}
