package com.gernalix.sostanze.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object UtcDateCodec {
    fun isoUtc(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    fun prescriptionIsoUtc(epochDay: Long): String =
        LocalDate.ofEpochDay(epochDay)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toString()
}
