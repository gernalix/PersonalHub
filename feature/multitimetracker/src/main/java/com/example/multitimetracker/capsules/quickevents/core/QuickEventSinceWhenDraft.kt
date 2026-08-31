package com.example.multitimetracker.capsules.quickevents.core

import com.example.multitimetracker.model.DEFAULT_LIFE_PERIOD_COLOR_ARGB
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.LifePeriodDisplayUnit
import com.example.multitimetracker.model.QuickEventEntry

internal fun quickEventSinceWhenDraft(entry: QuickEventEntry): LifePeriod =
    LifePeriod(
        id = 0L,
        title = entry.title,
        description = "",
        startMs = entry.timestampMs,
        endMs = null,
        colorArgb = DEFAULT_LIFE_PERIOD_COLOR_ARGB,
        tagIds = entry.tagIds,
        displayUnits = setOf(LifePeriodDisplayUnit.DAYS)
    )

internal fun quickEventSinceWhenActionEnabled(readOnly: Boolean): Boolean = !readOnly
