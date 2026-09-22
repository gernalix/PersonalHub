package com.supercontacts.app.ui.contacts

import com.gernalix.personalhub.core.ui.HubTimeFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

object ContactTimeFormatter {
    fun formatDateTime(
        timestampUtcMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String =
        HubTimeFormat.pattern(
            epochMs = timestampUtcMillis,
            pattern = "dd-MM-yy HH:mm",
            zoneId = zoneId,
        )

    fun formatMonth(
        month: YearMonth,
        locale: Locale = Locale.getDefault(),
    ): String =
        HubTimeFormat.yearMonth(month, "LLLL yyyy", locale)

    fun formatDate(date: LocalDate): String =
        HubTimeFormat.localDate(date, "dd-MM-yy")

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
