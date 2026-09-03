package com.wordpulse.app.data

import com.wordpulse.app.domain.TypingMetrics
import com.wordpulse.app.domain.TypingPerformanceSample

fun TypingPerformanceRow.toPerformanceSample(): TypingPerformanceSample? {
        val submittedAt = submittedAtUtcMs ?: return null
        val finalCount = finalCharacterCount ?: return null
        val metrics = TypingMetrics(
            typingStartedAtUtcMs = typingStartedAtUtcMs,
            submittedAtUtcMs = submittedAt,
            typingDurationMs = typingDurationMs,
            finalCharacterCount = finalCount,
            insertedCharacterCount = insertedCharacterCount ?: return null,
            deletedCharacterCount = deletedCharacterCount ?: return null,
            replacementCount = replacementCount ?: return null,
            correctionActionCount = correctionActionCount ?: return null,
            longestInterKeyPauseMs = longestInterKeyPauseMs,
            meanInterKeyIntervalMs = meanInterKeyIntervalMs,
            interKeyIntervalVariabilityMs = interKeyIntervalVariabilityMs,
            invalidInputAttemptCount = invalidInputAttemptCount ?: return null,
        )
        return TypingPerformanceSample.fromMetrics(id, metrics)
    }
