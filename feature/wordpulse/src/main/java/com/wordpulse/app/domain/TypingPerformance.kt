package com.wordpulse.app.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class WordLengthBand {
    Short,
    Medium,
    Long;

    companion object {
        fun fromLength(length: Int): WordLengthBand =
            when (length) {
                in 1..4 -> Short
                in 5..8 -> Medium
                else -> Long
            }
    }
}

data class TypingPerformanceSample(
    val entryId: Long,
    val finalCharacterCount: Int,
    val charactersPerMinute: Double?,
    val durationPerCharacterMs: Double?,
    val correctionActionsPerCharacter: Double,
    val deletedCharactersPerCharacter: Double,
    val longestInterKeyPauseMs: Double?,
    val interKeyIntervalVariabilityMs: Double?,
    val invalidInputAttemptCount: Double,
) {
    val lengthBand: WordLengthBand = WordLengthBand.fromLength(finalCharacterCount)

    companion object {
        fun fromMetrics(entryId: Long, metrics: TypingMetrics): TypingPerformanceSample {
            val characterCount = metrics.finalCharacterCount
            val duration = metrics.typingDurationMs
            val durationPerCharacter = if (characterCount > 0 && duration != null && duration > 0L) {
                duration.toDouble() / characterCount
            } else {
                null
            }
            val charactersPerMinute = if (characterCount > 0 && duration != null && duration > 0L) {
                characterCount * 60_000.0 / duration
            } else {
                null
            }
            return TypingPerformanceSample(
                entryId = entryId,
                finalCharacterCount = characterCount,
                charactersPerMinute = charactersPerMinute,
                durationPerCharacterMs = durationPerCharacter,
                correctionActionsPerCharacter = safeRatio(
                    metrics.correctionActionCount,
                    characterCount,
                ),
                deletedCharactersPerCharacter = safeRatio(
                    metrics.deletedCharacterCount,
                    characterCount,
                ),
                longestInterKeyPauseMs = metrics.longestInterKeyPauseMs?.toDouble(),
                interKeyIntervalVariabilityMs = metrics.interKeyIntervalVariabilityMs,
                invalidInputAttemptCount = metrics.invalidInputAttemptCount.toDouble(),
            )
        }

        private fun safeRatio(numerator: Int, denominator: Int): Double =
            if (denominator <= 0) 0.0 else numerator.toDouble() / denominator
    }
}

enum class TypingSignalKind {
    DurationPerCharacter,
    CorrectionActions,
    DeletedCharacters,
    LongestPause,
    IntervalVariability,
    InvalidInputAttempts,
}

enum class TypingDeviationLevel {
    Normal,
    ModerateDeviation,
    MarkedDeviation,
    InsufficientData,
}

enum class TypingSignalStrength {
    Moderate,
    Marked,
}

data class TypingDeviationSignal(
    val kind: TypingSignalKind,
    val currentValue: Double,
    val baselineMedian: Double,
    val ratioToBaseline: Double?,
    val robustScore: Double,
    val strength: TypingSignalStrength,
)

data class TypingPerformanceEvaluation(
    val level: TypingDeviationLevel,
    val lengthBand: WordLengthBand,
    val baselineSampleCount: Int,
    val signals: List<TypingDeviationSignal> = emptyList(),
)

data class RobustMetricBaseline(
    val median: Double,
    val medianAbsoluteDeviation: Double,
    val interquartileRange: Double,
)

data class TypingBaseline(
    val lengthBand: WordLengthBand,
    val sampleCount: Int,
    val metrics: Map<TypingSignalKind, RobustMetricBaseline>,
)

class TypingBaselineCalculator(
    val minimumSampleCount: Int = DEFAULT_MINIMUM_SAMPLE_COUNT,
) {
    fun calculate(
        currentBand: WordLengthBand,
        history: List<TypingPerformanceSample>,
    ): TypingBaseline? {
        val comparable = history.filter { it.lengthBand == currentBand }
        if (comparable.size < minimumSampleCount) return null

        val metrics = buildMap {
            baseline(comparable.mapNotNull { it.durationPerCharacterMs })
                ?.let { put(TypingSignalKind.DurationPerCharacter, it) }
            baseline(comparable.map { it.correctionActionsPerCharacter })
                ?.let { put(TypingSignalKind.CorrectionActions, it) }
            baseline(comparable.map { it.deletedCharactersPerCharacter })
                ?.let { put(TypingSignalKind.DeletedCharacters, it) }
            baseline(comparable.mapNotNull { it.longestInterKeyPauseMs })
                ?.let { put(TypingSignalKind.LongestPause, it) }
            baseline(comparable.mapNotNull { it.interKeyIntervalVariabilityMs })
                ?.let { put(TypingSignalKind.IntervalVariability, it) }
            baseline(comparable.map { it.invalidInputAttemptCount })
                ?.let { put(TypingSignalKind.InvalidInputAttempts, it) }
        }
        return TypingBaseline(
            lengthBand = currentBand,
            sampleCount = comparable.size,
            metrics = metrics,
        )
    }

    private fun baseline(values: List<Double>): RobustMetricBaseline? {
        val finite = values.filter(Double::isFinite).sorted()
        if (finite.size < minimumSampleCount) return null

        val q1 = percentile(finite, 0.25)
        val q3 = percentile(finite, 0.75)
        val iqr = q3 - q1
        val bounded = if (iqr > 0.0) {
            val lower = q1 - OUTLIER_IQR_MULTIPLIER * iqr
            val upper = q3 + OUTLIER_IQR_MULTIPLIER * iqr
            finite.filter { it in lower..upper }.takeIf { it.size >= minimumSampleCount } ?: finite
        } else {
            finite
        }
        val median = percentile(bounded, 0.5)
        val deviations = bounded.map { abs(it - median) }.sorted()
        return RobustMetricBaseline(
            median = median,
            medianAbsoluteDeviation = percentile(deviations, 0.5),
            interquartileRange = percentile(bounded, 0.75) - percentile(bounded, 0.25),
        )
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.size == 1) return sorted.first()
        val index = (sorted.lastIndex * fraction).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
        val weight = index - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private companion object {
        const val DEFAULT_MINIMUM_SAMPLE_COUNT = 12
        const val OUTLIER_IQR_MULTIPLIER = 3.0
    }
}

class TypingAnomalyDetector(
    private val baselineCalculator: TypingBaselineCalculator = TypingBaselineCalculator(),
) {
    fun evaluate(
        current: TypingPerformanceSample,
        history: List<TypingPerformanceSample>,
    ): TypingPerformanceEvaluation {
        val baseline = baselineCalculator.calculate(current.lengthBand, history)
            ?: return TypingPerformanceEvaluation(
                level = TypingDeviationLevel.InsufficientData,
                lengthBand = current.lengthBand,
                baselineSampleCount = history.count { it.lengthBand == current.lengthBand },
            )

        val candidates = listOfNotNull(
            current.durationPerCharacterMs?.let {
                SignalCandidate(TypingSignalKind.DurationPerCharacter, it)
            },
            SignalCandidate(TypingSignalKind.CorrectionActions, current.correctionActionsPerCharacter),
            SignalCandidate(TypingSignalKind.DeletedCharacters, current.deletedCharactersPerCharacter),
            current.longestInterKeyPauseMs?.let {
                SignalCandidate(TypingSignalKind.LongestPause, it)
            },
            current.interKeyIntervalVariabilityMs?.let {
                SignalCandidate(TypingSignalKind.IntervalVariability, it)
            },
            SignalCandidate(TypingSignalKind.InvalidInputAttempts, current.invalidInputAttemptCount),
        )
        val signals = candidates.mapNotNull { candidate ->
            baseline.metrics[candidate.kind]?.let { metric ->
                detectSignal(candidate.kind, candidate.value, metric)
            }
        }.sortedByDescending { it.robustScore }

        val markedCount = signals.count { it.strength == TypingSignalStrength.Marked }
        val level = when {
            markedCount >= 1 -> TypingDeviationLevel.MarkedDeviation
            signals.size >= 2 -> TypingDeviationLevel.ModerateDeviation
            else -> TypingDeviationLevel.Normal
        }
        return TypingPerformanceEvaluation(
            level = level,
            lengthBand = current.lengthBand,
            baselineSampleCount = baseline.sampleCount,
            signals = signals,
        )
    }

    private fun detectSignal(
        kind: TypingSignalKind,
        current: Double,
        baseline: RobustMetricBaseline,
    ): TypingDeviationSignal? {
        if (!current.isFinite() || current <= baseline.median) return null

        val ratio = if (baseline.median > 0.0) current / baseline.median else null
        val robustScore = when {
            baseline.medianAbsoluteDeviation > 0.0 ->
                (current - baseline.median) / (MAD_SCALE * baseline.medianAbsoluteDeviation)
            baseline.interquartileRange > 0.0 ->
                (current - baseline.median) / (IQR_SCALE * baseline.interquartileRange)
            else -> zeroSpreadScore(kind, current, baseline.median)
        }
        val relativeIncrease = ratio?.minus(1.0)
        val marked = robustScore >= MARKED_SCORE &&
            (relativeIncrease == null || relativeIncrease >= MARKED_RELATIVE_INCREASE)
        val moderate = robustScore >= MODERATE_SCORE &&
            (relativeIncrease == null || relativeIncrease >= MODERATE_RELATIVE_INCREASE)
        val strength = when {
            marked -> TypingSignalStrength.Marked
            moderate -> TypingSignalStrength.Moderate
            else -> return null
        }
        return TypingDeviationSignal(
            kind = kind,
            currentValue = current,
            baselineMedian = baseline.median,
            ratioToBaseline = ratio,
            robustScore = robustScore,
            strength = strength,
        )
    }

    private fun zeroSpreadScore(
        kind: TypingSignalKind,
        current: Double,
        baselineMedian: Double,
    ): Double {
        if (baselineMedian > 0.0) {
            val ratio = current / baselineMedian
            return when {
                ratio >= 1.50 -> 6.0
                ratio >= 1.25 -> 3.0
                else -> 0.0
            }
        }
        return when (kind) {
            TypingSignalKind.CorrectionActions,
            TypingSignalKind.DeletedCharacters -> when {
                current >= 0.30 -> 6.0
                current >= 0.15 -> 3.0
                else -> 0.0
            }
            TypingSignalKind.InvalidInputAttempts -> when {
                current >= 3.0 -> 6.0
                current >= 1.0 -> 3.0
                else -> 0.0
            }
            else -> 0.0
        }
    }

    private data class SignalCandidate(
        val kind: TypingSignalKind,
        val value: Double,
    )

    private companion object {
        const val MAD_SCALE = 1.4826
        const val IQR_SCALE = 0.7413
        const val MODERATE_SCORE = 2.5
        const val MARKED_SCORE = 4.5
        const val MODERATE_RELATIVE_INCREASE = 0.25
        const val MARKED_RELATIVE_INCREASE = 0.50
    }
}

class TypingInsightFormatter(
    private val zoneId: ZoneId,
) {
    private val duplicateDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
        .withZone(zoneId)
    private val decimalFormat = DecimalFormat("0.#", DecimalFormatSymbols(Locale.ITALIAN))

    fun duplicate(totalOccurrences: Int, previousOccurrenceUtcMs: Long): String =
        "Parola scritta $totalOccurrences volte, ultima volta il " +
            duplicateDateFormatter.format(Instant.ofEpochMilli(previousOccurrenceUtcMs))

    fun performance(evaluation: TypingPerformanceEvaluation): String {
        val phrases = evaluation.signals.take(2).map(::formatSignal)
        return "Prestazione insolita: ${phrases.joinToString(" e ")} rispetto alla tua norma recente."
    }

    private fun formatSignal(signal: TypingDeviationSignal): String =
        when (signal.kind) {
            TypingSignalKind.DurationPerCharacter -> {
                val percent = signal.ratioToBaseline
                    ?.let { ((it - 1.0) * 100.0).coerceAtLeast(0.0) }
                    ?: 0.0
                "digitazione ${decimalFormat.format(percent)}% più lenta"
            }
            TypingSignalKind.CorrectionActions ->
                "${formatRatio(signal)} più correzioni"
            TypingSignalKind.DeletedCharacters ->
                "${formatRatio(signal)} più caratteri cancellati"
            TypingSignalKind.LongestPause ->
                "pause ${formatRatio(signal)} più lunghe"
            TypingSignalKind.IntervalVariability ->
                "ritmo ${formatRatio(signal)} più variabile"
            TypingSignalKind.InvalidInputAttempts ->
                "${decimalFormat.format(signal.currentValue)} tentativi di input non validi"
        }

    private fun formatRatio(signal: TypingDeviationSignal): String =
        signal.ratioToBaseline
            ?.let { "${decimalFormat.format(it)}×" }
            ?: "molte"
}
