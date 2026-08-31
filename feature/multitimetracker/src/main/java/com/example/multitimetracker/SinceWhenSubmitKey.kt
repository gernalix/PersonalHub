package com.example.multitimetracker

import com.example.multitimetracker.model.LifePeriodDisplayUnit
import java.util.Locale

internal fun lifePeriodSubmitKey(
    title: String,
    description: String,
    startMs: Long,
    endMs: Long?,
    colorArgb: Long,
    tagIds: Set<Long>,
    displayUnits: Set<LifePeriodDisplayUnit>
): String =
    "life-period|" +
        title.trim().lowercase(Locale.ROOT) + "|" +
        description.trim() + "|" +
        startMs + "|" +
        (endMs?.toString() ?: "") + "|" +
        colorArgb + "|" +
        tagIds.sorted().joinToString(",") + "|" +
        displayUnits.map { it.name }.sorted().joinToString(",")
