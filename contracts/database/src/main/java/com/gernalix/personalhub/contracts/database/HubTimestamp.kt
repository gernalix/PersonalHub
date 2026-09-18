package com.gernalix.personalhub.contracts.database

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Canonical PersonalHub timestamp contract.
 *
 * Persistence uses Unix epoch milliseconds. Human-facing timestamps use the device locale/time
 * zone with the single PersonalHub pattern requested by the product: EEE d/M/yy hh:mm.
 */
object HubTimestamp {
    const val DISPLAY_PATTERN = "EEE d/M/yy hh:mm"

    fun nowEpochMs(): Long = System.currentTimeMillis()

    fun format(
        epochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter
        .ofPattern(DISPLAY_PATTERN, locale)
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(epochMs))

    /**
     * Transitional reader for migrations only. New persistence must write Long epoch milliseconds.
     */
    fun legacyTextToEpochMs(value: String?): Long? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return normalized.toLongOrNull()
            ?: runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()
    }
}
