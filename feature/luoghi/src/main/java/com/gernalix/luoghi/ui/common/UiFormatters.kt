package com.gernalix.luoghi.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gernalix.luoghi.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.text.NumberFormat
import kotlinx.coroutines.delay

@Composable
fun rememberMinuteNow(enabled: Boolean): Long {
    var nowMs by remember(enabled) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            delay(60_000L - (now % 60_000L))
            nowMs = System.currentTimeMillis()
        }
    }
    return nowMs
}

@Composable
fun localizedTime(timestamp: Long): String = localizedDateTime(timestamp, FormatStyle.SHORT, dateOnly = false)

@Composable
fun localizedFullDate(timestamp: Long): String {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val zone = ZoneId.systemDefault()
    return remember(timestamp, locale, zone) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(timestamp).atZone(zone))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}

@Composable
fun localizedPercent(value: Double?): String {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    return remember(value, locale) {
        value?.takeIf { it.isFinite() }?.let {
            NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }
                .format(it.coerceAtLeast(0.0) / 100.0)
        } ?: "—"
    }
}

@Composable
fun localizedDayHeading(timestamp: Long, nowMs: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> localizedFullDate(timestamp)
    }
}

@Composable
fun localizedDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
    if (totalMinutes == 0L) return stringResource(R.string.duration_less_than_minute)
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes / 60L) % 24L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L && hours > 0L -> stringResource(
            R.string.duration_days_hours,
            pluralStringResource(R.plurals.days, days.toInt(), days),
            pluralStringResource(R.plurals.hours, hours.toInt(), hours),
        )
        days > 0L -> pluralStringResource(R.plurals.days, days.toInt(), days)
        hours > 0L && minutes > 0L -> stringResource(
            R.string.duration_hours_minutes,
            pluralStringResource(R.plurals.hours, hours.toInt(), hours),
            pluralStringResource(R.plurals.minutes, minutes.toInt(), minutes),
        )
        hours > 0L -> pluralStringResource(R.plurals.hours, hours.toInt(), hours)
        else -> pluralStringResource(R.plurals.minutes, minutes.toInt(), minutes)
    }
}

@Composable
fun localizedDistance(distanceMeters: Long): String {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    return remember(distanceMeters, locale) {
        val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
        if (distanceMeters >= 1_000L) {
            format.format(distanceMeters / 1_000.0) + " km"
        } else {
            NumberFormat.getIntegerInstance(locale).format(distanceMeters.coerceAtLeast(0L)) + " m"
        }
    }
}

@Composable
fun visitCount(count: Int): String = pluralStringResource(R.plurals.visits, count, count)

@Composable
fun lastVisitLabel(timestamp: Long?): String? {
    timestamp ?: return null
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.last_visit_today)
        today.minusDays(1) -> stringResource(R.string.last_visit_yesterday)
        else -> stringResource(R.string.last_visit_date_format, localizedFullDate(timestamp))
    }
}

@Composable
fun localizedDateTime(timestamp: Long): String = localizedDateTime(timestamp, FormatStyle.SHORT, dateOnly = null)

@Composable
private fun localizedDateTime(timestamp: Long, style: FormatStyle, dateOnly: Boolean?): String {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val zone = ZoneId.systemDefault()
    return remember(timestamp, locale, zone, style, dateOnly) {
        val formatter = when (dateOnly) {
            true -> DateTimeFormatter.ofLocalizedDate(style)
            false -> DateTimeFormatter.ofLocalizedTime(style)
            null -> DateTimeFormatter.ofLocalizedDateTime(style)
        }
        formatter.withLocale(locale).format(Instant.ofEpochMilli(timestamp).atZone(zone))
    }
}
