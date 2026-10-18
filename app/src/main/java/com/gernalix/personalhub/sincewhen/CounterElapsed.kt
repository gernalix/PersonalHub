package com.gernalix.personalhub.sincewhen

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Calendar years/months and elapsed clock units share one ordered, signed calculation. */
internal object CounterElapsed {
    val units = listOf(ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.WEEKS, ChronoUnit.DAYS,
        ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS)
    data class Result(val negative: Boolean, val parts: List<Pair<ChronoUnit, Long>>)

    fun between(anchor: Long, reference: Long, selected: Set<String>, zone: ZoneId): Result {
        val ordered = units.filter { it.name in selected }.ifEmpty { listOf(ChronoUnit.DAYS) }
        val negative = reference < anchor
        var cursor = Instant.ofEpochMilli(minOf(anchor, reference)).atZone(zone)
        val end = Instant.ofEpochMilli(maxOf(anchor, reference)).atZone(zone)
        val parts = buildList {
            ordered.forEachIndexed { index, unit ->
                val amount = unit.between(cursor, end).coerceAtLeast(0)
                if (amount > 0 || (isEmpty() && index == ordered.lastIndex)) add(unit to amount)
                cursor = cursor.plus(amount, unit)
            }
        }
        return Result(negative, parts)
    }
}
