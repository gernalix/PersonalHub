package com.supercontacts.app.ui.contacts

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object ContactTimeFormatter {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yy")
    private val monthFormatter = DateTimeFormatter.ofPattern("LLLL yyyy")

    fun formatDateTime(
        timestampUtcMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String =
        dateTimeFormatter
            .withZone(zoneId)
            .format(Instant.ofEpochMilli(timestampUtcMillis))

    fun formatMonth(
        month: YearMonth,
        locale: Locale = Locale.getDefault(),
    ): String =
        month.format(monthFormatter.withLocale(locale))

    fun formatDate(date: LocalDate): String =
        date.format(dateFormatter)

    fun weekdayHeaders(
        locale: Locale = Locale.getDefault(),
    ): List<String> =
        (1..7).map { dayOfWeek ->
            java.time.DayOfWeek.of(dayOfWeek)
                .getDisplayName(TextStyle.SHORT, locale)
        }

    fun dateCellTag(date: LocalDate): String =
        "initiative-day-${date}"
}
