package com.gernalix.personalhub.core.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Shared PersonalHub time-formatting nucleus.
 *
 * Persistence stays epoch/ISO according to database contracts. This object only converts
 * canonical instants/local calendar values to and from user-facing strings with explicit zone
 * and locale inputs, so feature modules do not each reinvent DST/locale handling.
 */
object HubTimeFormat {
    fun dateTime(
        epochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        style: FormatStyle = FormatStyle.SHORT,
    ): String =
        DateTimeFormatter.ofLocalizedDateTime(style)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

    fun date(
        epochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        style: FormatStyle = FormatStyle.SHORT,
    ): String =
        DateTimeFormatter.ofLocalizedDate(style)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

    fun time(
        epochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        style: FormatStyle = FormatStyle.SHORT,
    ): String =
        DateTimeFormatter.ofLocalizedTime(style)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

    fun pattern(
        epochMs: Long,
        pattern: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String =
        DateTimeFormatter.ofPattern(pattern, locale)
            .withZone(zoneId)
            .format(Instant.ofEpochMilli(epochMs))

    fun localDate(
        value: LocalDate,
        pattern: String,
        locale: Locale = Locale.getDefault(),
    ): String = value.format(DateTimeFormatter.ofPattern(pattern, locale))

    fun yearMonth(
        value: YearMonth,
        pattern: String,
        locale: Locale = Locale.getDefault(),
    ): String = value.format(DateTimeFormatter.ofPattern(pattern, locale))

    fun parseLocalDateTime(
        value: String,
        pattern: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): Long =
        LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern(pattern, locale))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

    fun compactDuration(
        durationMs: Long,
        showSeconds: Boolean = true,
        hideHoursIfZero: Boolean = false,
    ): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        val parts = mutableListOf<String>()
        if ((!hideHoursIfZero || hours > 0L) && hours > 0L) parts += "${hours}h"
        if (hours > 0L || minutes > 0L) parts += "${minutes}m"
        if (showSeconds && (seconds > 0L || parts.isEmpty())) parts += "${seconds}s"
        if (!showSeconds && parts.isEmpty()) parts += "0m"
        return parts.joinToString(" ")
    }
}
