package com.example.multitimetracker.model

data class TimeMachinePeriodSelection(
    val startMs: Long,
    val endMs: Long
)

data class TimeMachineTagTotalRow(
    val tagId: Long,
    val tagName: String,
    val totalMs: Long
)

data class TimeMachineTagComparisonRow(
    val tagId: Long,
    val tagName: String,
    val selectedDayTotalMs: Long,
    val todayTotalMs: Long
) {
    val differenceMs: Long
        get() = todayTotalMs - selectedDayTotalMs
}

fun computeTimeMachineTagTotals(
    sessions: List<SessionUi>,
    tags: List<Tag>,
    period: TimeMachinePeriodSelection,
    nowMs: Long
): List<TimeMachineTagTotalRow> {
    if (period.endMs <= period.startMs) return emptyList()

    val tagNamesById = tags.associate { it.id to it.name }
    val intervalsByTagId = linkedMapOf<Long, MutableList<Pair<Long, Long>>>()

    sessions.asSequence()
        .filter { it.deletedAtMs == null }
        .distinctBy { it.id }
        .forEach { session ->
            val effectiveEndMs = session.endMs ?: nowMs
            val clippedStartMs = maxOf(session.startMs, period.startMs)
            val clippedEndMs = minOf(effectiveEndMs, period.endMs)
            if (clippedEndMs <= clippedStartMs) return@forEach

            session.tagIds.forEach { tagId ->
                intervalsByTagId.getOrPut(tagId) { mutableListOf() }
                    .add(clippedStartMs to clippedEndMs)
            }
        }

    return intervalsByTagId
        .map { (tagId, intervals) ->
            TimeMachineTagTotalRow(
                tagId = tagId,
                tagName = tagNamesById[tagId].orEmpty().ifBlank { "#$tagId" },
                totalMs = unionTotalMs(intervals)
            )
        }
        .filter { it.totalMs > 0L }
        .sortedWith(compareByDescending<TimeMachineTagTotalRow> { it.totalMs }.thenBy { it.tagName.lowercase() })
}

private fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
    if (intervals.isEmpty()) return 0L
    val sorted = intervals.sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
    var totalMs = 0L
    var currentStartMs = sorted.first().first
    var currentEndMs = sorted.first().second

    for (i in 1 until sorted.size) {
        val (startMs, endMs) = sorted[i]
        if (endMs <= startMs) continue
        if (startMs <= currentEndMs) {
            if (endMs > currentEndMs) currentEndMs = endMs
        } else {
            totalMs += (currentEndMs - currentStartMs).coerceAtLeast(0L)
            currentStartMs = startMs
            currentEndMs = endMs
        }
    }

    totalMs += (currentEndMs - currentStartMs).coerceAtLeast(0L)
    return totalMs
}

data class EffectiveTimeContext(
    val liveNowMs: Long,
    val timeMachineTargetMs: Long?
) {
    val nowMs: Long
        get() = timeMachineTargetMs ?: liveNowMs

    val isReadOnly: Boolean
        get() = timeMachineTargetMs != null
}

data class TemporalContext(
    val timeMachineTargetMs: Long?
) {
    fun effectiveTime(liveNowMs: Long): EffectiveTimeContext =
        EffectiveTimeContext(
            liveNowMs = liveNowMs,
            timeMachineTargetMs = timeMachineTargetMs
        )

    fun effectiveNowMs(liveNowMs: Long): Long = effectiveTime(liveNowMs).nowMs

    val isReadOnly: Boolean
        get() = timeMachineTargetMs != null
}

fun UiState.effectiveTimeContext(): EffectiveTimeContext =
    TemporalContext(timeMachineTargetMs).effectiveTime(liveNowMs = nowMs)
