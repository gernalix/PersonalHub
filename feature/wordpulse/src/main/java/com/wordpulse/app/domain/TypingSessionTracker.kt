package com.wordpulse.app.domain

import com.wordpulse.app.data.TimeProvider
import kotlin.math.ceil
import kotlin.math.sqrt

data class CaptureTextValue(
    val text: String = "",
    val selectionStart: Int = text.length,
    val selectionEnd: Int = text.length,
    val compositionStart: Int? = null,
    val compositionEnd: Int? = null,
) {
    val hasComposition: Boolean
        get() = compositionStart != null && compositionEnd != null
}

data class TypingMetrics(
    val typingStartedAtUtcMs: Long?,
    val submittedAtUtcMs: Long,
    val typingDurationMs: Long?,
    val finalCharacterCount: Int,
    val insertedCharacterCount: Int,
    val deletedCharacterCount: Int,
    val replacementCount: Int,
    val correctionActionCount: Int,
    val longestInterKeyPauseMs: Long?,
    val meanInterKeyIntervalMs: Double?,
    val interKeyIntervalVariabilityMs: Double?,
    val invalidInputAttemptCount: Int,
    val medianInterKeyIntervalMs: Double? = null,
    val p95InterKeyIntervalMs: Double? = null,
    val interKeyIntervalCoefficientOfVariation: Double? = null,
    val microPauseCount: Int = 0,
    val lastEditToSubmitMs: Long? = null,
    val motorComplexityScore: Double? = null,
    val motorComplexityBand: MotorComplexityBand? = null,
)

class TypingSessionTracker(
    private val timeProvider: TimeProvider,
) {
    private var currentValue = CaptureTextValue()
    private var typingStartedAtUtcMs: Long? = null
    private var lastAcceptedEditAtUtcMs: Long? = null
    private var insertedCharacterCount = 0
    private var deletedCharacterCount = 0
    private var replacementCount = 0
    private var correctionActionCount = 0
    private var invalidInputAttemptCount = 0
    private val interKeyIntervalsMs = mutableListOf<Long>()

    fun currentValue(): CaptureTextValue = currentValue

    fun recordAcceptedTransition(newValue: CaptureTextValue) {
        require(CaptureWordPolicy.isValidFieldText(newValue.text)) {
            "TypingSessionTracker accepts only policy-valid field values"
        }

        val previousValue = currentValue
        if (previousValue.text == newValue.text) {
            currentValue = newValue
            return
        }

        val nowUtcMs = timeProvider.nowUtcMs()
        if (typingStartedAtUtcMs == null && newValue.text.isNotEmpty()) {
            typingStartedAtUtcMs = nowUtcMs
        }
        lastAcceptedEditAtUtcMs?.let { previousEditAt ->
            interKeyIntervalsMs += (nowUtcMs - previousEditAt).coerceAtLeast(0L)
        }
        lastAcceptedEditAtUtcMs = nowUtcMs

        val edit = analyzeTextEdit(previousValue.text, newValue.text)
        insertedCharacterCount += edit.insertedCount
        deletedCharacterCount += edit.deletedCount

        val reliableReplacement = edit.insertedCount > 0 &&
            edit.deletedCount > 0 &&
            !previousValue.hasComposition &&
            !newValue.hasComposition
        when {
            reliableReplacement -> {
                replacementCount += 1
                correctionActionCount += 1
            }
            edit.deletedCount > 0 && edit.insertedCount == 0 -> {
                correctionActionCount += 1
            }
        }

        currentValue = newValue
    }

    fun recordInvalidInputAttempt() {
        invalidInputAttemptCount += 1
    }

    fun submit(): TypingMetrics? {
        val finalText = currentValue.text
        if (!CaptureWordPolicy.isValidWord(finalText)) {
            reset()
            return null
        }

        val submittedAtUtcMs = timeProvider.nowUtcMs()
        val intervals = interKeyIntervalsMs.toList()
        val sortedIntervals = intervals.sorted()
        val mean = intervals.takeIf(List<Long>::isNotEmpty)?.average()
        val variability = mean?.let { intervalMean ->
            sqrt(
                intervals.sumOf { interval ->
                    val difference = interval - intervalMean
                    difference * difference
                } / intervals.size,
            )
        }
        val median = percentile(sortedIntervals, 0.50)
        val p95 = percentile(sortedIntervals, 0.95)
        val coefficientOfVariation = if (mean != null && mean > 0.0 && variability != null) {
            variability / mean
        } else {
            null
        }
        val microPauseThresholdMs = median?.let {
            maxOf(MIN_MICRO_PAUSE_MS, it * MICRO_PAUSE_MEDIAN_MULTIPLIER)
        }
        val microPauseCount = microPauseThresholdMs?.let { threshold ->
            intervals.count { it.toDouble() >= threshold }
        } ?: 0
        val lastEditToSubmitMs = lastAcceptedEditAtUtcMs?.let { lastEdit ->
            (submittedAtUtcMs - lastEdit).coerceAtLeast(0L)
        }
        val motorComplexity = WordMotorComplexityCalculator.calculate(finalText)
        val metrics = TypingMetrics(
            typingStartedAtUtcMs = typingStartedAtUtcMs,
            submittedAtUtcMs = submittedAtUtcMs,
            typingDurationMs = typingStartedAtUtcMs?.let {
                (submittedAtUtcMs - it).coerceAtLeast(0L)
            },
            finalCharacterCount = finalText.length,
            insertedCharacterCount = insertedCharacterCount,
            deletedCharacterCount = deletedCharacterCount,
            replacementCount = replacementCount,
            correctionActionCount = correctionActionCount,
            longestInterKeyPauseMs = intervals.maxOrNull(),
            meanInterKeyIntervalMs = mean,
            interKeyIntervalVariabilityMs = variability,
            invalidInputAttemptCount = invalidInputAttemptCount,
            medianInterKeyIntervalMs = median,
            p95InterKeyIntervalMs = p95,
            interKeyIntervalCoefficientOfVariation = coefficientOfVariation,
            microPauseCount = microPauseCount,
            lastEditToSubmitMs = lastEditToSubmitMs,
            motorComplexityScore = motorComplexity?.score,
            motorComplexityBand = motorComplexity?.band,
        )
        reset()
        return metrics
    }

    fun reset() {
        currentValue = CaptureTextValue()
        typingStartedAtUtcMs = null
        lastAcceptedEditAtUtcMs = null
        insertedCharacterCount = 0
        deletedCharacterCount = 0
        replacementCount = 0
        correctionActionCount = 0
        invalidInputAttemptCount = 0
        interKeyIntervalsMs.clear()
    }

    fun restoreWithoutTracking(value: CaptureTextValue) {
        reset()
        require(CaptureWordPolicy.isValidFieldText(value.text))
        currentValue = value
    }

    private fun analyzeTextEdit(previous: String, current: String): TextEditDelta {
        val prefixLength = previous.commonPrefixWith(current).length
        val remainingPrevious = previous.length - prefixLength
        val remainingCurrent = current.length - prefixLength
        var suffixLength = 0
        while (
            suffixLength < remainingPrevious &&
            suffixLength < remainingCurrent &&
            previous[previous.lastIndex - suffixLength] == current[current.lastIndex - suffixLength]
        ) {
            suffixLength += 1
        }
        return TextEditDelta(
            insertedCount = current.length - prefixLength - suffixLength,
            deletedCount = previous.length - prefixLength - suffixLength,
        )
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Double? {
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted.first().toDouble()
        val position = (sorted.size - 1) * fraction.coerceIn(0.0, 1.0)
        val lower = position.toInt()
        val upper = ceil(position).toInt().coerceAtMost(sorted.lastIndex)
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private data class TextEditDelta(
        val insertedCount: Int,
        val deletedCount: Int,
    )

    private companion object {
        const val MIN_MICRO_PAUSE_MS = 600.0
        const val MICRO_PAUSE_MEDIAN_MULTIPLIER = 3.0
    }
}
