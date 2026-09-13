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
            medianInterKeyIntervalMs = medianInterKeyIntervalMs,
            p95InterKeyIntervalMs = p95InterKeyIntervalMs,
            interKeyIntervalCoefficientOfVariation = interKeyIntervalCoefficientOfVariation,
            microPauseCount = microPauseCount ?: 0,
            lastEditToSubmitMs = lastEditToSubmitMs,
        )
        val sample = TypingPerformanceSample.fromMetrics(id, metrics, originalWord)
        return sample.copy(
            activeDurationPerCharacterMs = sample.activeDurationPerCharacterMs.takeIf { lastEditToSubmitMs != null },
            microPausesPerCharacter = sample.microPausesPerCharacter.takeIf { microPauseCount != null } ?: Double.NaN,
        )
    }
