// v332
package com.example.multitimetracker.ui.util

import com.example.multitimetracker.model.SessionUi
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class RangeSummary(
    val totalMs: Long,
    val perTagMs: Map<Long, Long>
)

object SummaryUtils {

    fun startOfTodayMs(nowMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        val zdt = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        return zdt.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    /**
     * Start of week (Monday 00:00) in the given zone.
     */
    fun startOfWeekMs(nowMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        val zdt = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        val localDate = zdt.toLocalDate()
        val dow = localDate.dayOfWeek
        val delta = (dow.value - DayOfWeek.MONDAY.value + 7) % 7
        val monday = localDate.minusDays(delta.toLong())
        return monday.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    fun summarizeRange(
        sessions: List<SessionUi>,
        rangeStartMs: Long,
        rangeEndMsExclusive: Long,
        nowMs: Long
    ): RangeSummary {
        var total = 0L
        val perTag = mutableMapOf<Long, Long>()

        sessions.forEach { s ->
            val end = s.endMs ?: nowMs
            val a = maxOf(s.startMs, rangeStartMs)
            val b = minOf(end, rangeEndMsExclusive)
            if (b > a) {
                val dur = b - a
                total += dur
                s.tagIds.forEach { tagId ->
                    perTag[tagId] = (perTag[tagId] ?: 0L) + dur
                }
            }
        }

        return RangeSummary(totalMs = total, perTagMs = perTag)
    }
}
