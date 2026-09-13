package com.wordpulse.app.data

import com.wordpulse.app.domain.PvtCalibrationSample
import com.wordpulse.app.domain.PvtSummary

fun PvtResultEntity.toCalibrationSample(): PvtCalibrationSample? {
    val fatigue = pairedFatigueScore ?: return null
    val reactionTime = medianReactionTimeMs ?: return null
    return PvtCalibrationSample(fatigue, reactionTime, lapseCount, falseStartCount)
}

fun PvtResultEntity.toSummary() = PvtSummary(
    completedAtUtcMs = completedAtUtcMs,
    trialsCompleted = trialCount,
    medianReactionTimeMs = medianReactionTimeMs,
    p90ReactionTimeMs = p90ReactionTimeMs,
    lapseCount = lapseCount,
    falseStartCount = falseStartCount,
    pairedFatigueScore = pairedFatigueScore,
)
