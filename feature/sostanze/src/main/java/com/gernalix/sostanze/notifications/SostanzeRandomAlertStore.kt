package com.gernalix.sostanze.notifications

import android.content.Context

enum class SostanzeRandomAlertWindow(val millis: Long) {
    HOUR(60L * 60L * 1000L),
    DAY(24L * 60L * 60L * 1000L);

    companion object {
        fun parse(value: String): SostanzeRandomAlertWindow =
            entries.firstOrNull { it.name == value } ?: DAY
    }
}

data class SostanzeRandomAlertConfig(
    val substanceId: Long,
    val label: String,
    val enabled: Boolean,
    val count: Int,
    val window: SostanzeRandomAlertWindow,
    val scheduledAtMs: List<Long>,
) {
    val identity: String = "substance-$substanceId"
}

object SostanzeRandomAlertStore {
    private const val PREFS = "sostanze_random_alerts"
    private const val IDS = "ids"

    fun read(context: Context, substanceId: Long, label: String = ""): SostanzeRandomAlertConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = "$substanceId."
        val scheduled = prefs.getStringSet(prefix + "scheduled", emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .sorted()
        return SostanzeRandomAlertConfig(
            substanceId = substanceId,
            label = prefs.getString(prefix + "label", null) ?: label,
            enabled = prefs.getBoolean(prefix + "enabled", false),
            count = prefs.getInt(prefix + "count", 0).coerceAtLeast(0),
            window = SostanzeRandomAlertWindow.parse(prefs.getString(prefix + "window", SostanzeRandomAlertWindow.DAY.name).orEmpty()),
            scheduledAtMs = scheduled,
        )
    }

    fun all(context: Context): List<SostanzeRandomAlertConfig> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(IDS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .map { read(context, it) }
    }

    fun write(context: Context, config: SostanzeRandomAlertConfig) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(IDS, emptySet()).orEmpty() + config.substanceId.toString()
        val prefix = "${config.substanceId}."
        prefs.edit()
            .putStringSet(IDS, ids)
            .putString(prefix + "label", config.label)
            .putBoolean(prefix + "enabled", config.enabled)
            .putInt(prefix + "count", config.count.coerceAtLeast(0))
            .putString(prefix + "window", config.window.name)
            .putStringSet(prefix + "scheduled", config.scheduledAtMs.map { it.toString() }.toSet())
            .apply()
    }
}

fun planSostanzeRandomAlertInstants(
    nowMs: Long,
    count: Int,
    window: SostanzeRandomAlertWindow,
    nextOffsetMs: (Long) -> Long,
): List<Long> {
    val cleanCount = count.coerceAtLeast(0)
    if (cleanCount == 0) return emptyList()
    val windowMs = window.millis.coerceAtLeast(cleanCount.toLong())
    val values = linkedSetOf<Long>()
    var attempts = 0
    while (values.size < cleanCount && attempts < cleanCount * 8) {
        attempts += 1
        values.add(nowMs + nextOffsetMs(windowMs).coerceIn(1L, windowMs))
    }
    var fallback = nowMs + 1L
    while (values.size < cleanCount) {
        values.add(fallback)
        fallback += 1L
    }
    return values.sorted()
}
