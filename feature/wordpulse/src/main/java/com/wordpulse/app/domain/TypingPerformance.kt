package com.wordpulse.app.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val submittedAtUtcMs: Long? = null,
    val activeDurationPerCharacterMs: Double? = null,
    val meanInterKeyIntervalMs: Double? = null,
    val medianInterKeyIntervalMs: Double? = null,
    val p95InterKeyIntervalMs: Double? = null,
    val interKeyIntervalCoefficientOfVariation: Double? = null,
    val microPausesPerCharacter: Double = 0.0,
    val lastEditToSubmitMs: Double? = null,
    val motorComplexityScore: Double? = null,
    val motorComplexityBand: MotorComplexityBand? = null,
) {
    val lengthBand: WordLengthBand = WordLengthBand.fromLength(finalCharacterCount)

    companion object {
        fun fromMetrics(
            entryId: Long,
            metrics: TypingMetrics,
            word: String? = null,
        ): TypingPerformanceSample {
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
            val activeDuration = if (duration != null) {
                (duration - (metrics.lastEditToSubmitMs ?: 0L)).coerceAtLeast(0L)
            } else {
                null
            }
            val activeDurationPerCharacter = if (
                characterCount > 0 && activeDuration != null && activeDuration > 0L
            ) {
                activeDuration.toDouble() / characterCount
            } else {
                durationPerCharacter
            }
            val motorComplexity = word?.let(WordMotorComplexityCalculator::calculate)
            return TypingPerformanceSample(
                entryId = entryId,
                finalCharacterCount = characterCount,
                charactersPerMinute = charactersPerMinute,
                durationPerCharacterMs = durationPerCharacter,
                correctionActionsPerCharacter = safeRatio(metrics.correctionActionCount, characterCount),
                deletedCharactersPerCharacter = safeRatio(metrics.deletedCharacterCount, characterCount),
                longestInterKeyPauseMs = metrics.longestInterKeyPauseMs?.toDouble(),
                interKeyIntervalVariabilityMs = metrics.interKeyIntervalVariabilityMs,
                invalidInputAttemptCount = metrics.invalidInputAttemptCount.toDouble(),
                submittedAtUtcMs = metrics.submittedAtUtcMs,
                activeDurationPerCharacterMs = activeDurationPerCharacter,
                meanInterKeyIntervalMs = metrics.meanInterKeyIntervalMs,
                medianInterKeyIntervalMs = metrics.medianInterKeyIntervalMs,
                p95InterKeyIntervalMs = metrics.p95InterKeyIntervalMs,
                interKeyIntervalCoefficientOfVariation = metrics.interKeyIntervalCoefficientOfVariation,
                microPausesPerCharacter = safeRatio(metrics.microPauseCount, characterCount),
                lastEditToSubmitMs = metrics.lastEditToSubmitMs?.toDouble(),
                motorComplexityScore = motorComplexity?.score ?: metrics.motorComplexityScore,
                motorComplexityBand = motorComplexity?.band ?: metrics.motorComplexityBand,
            )
        }

        private fun safeRatio(numerator: Int, denominator: Int): Double =
            if (denominator <= 0) 0.0 else numerator.toDouble() / denominator
    }
}

enum class TypingSignalKind {
    DurationPerCharacter,
    ActiveDurationPerCharacter,
    MeanInterval,
    CorrectionActions,
    DeletedCharacters,
    LongestPause,
    P95Pause,
    IntervalVariability,
    IntervalCoefficientOfVariation,
    MicroPauseRate,
    SubmitHesitation,
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
    val fatigueScore: Int? = null,
    val rawFatigueScore: Int? = fatigueScore,
    val domains: FatigueDomainScores = FatigueDomainScores(),
    val context: AlertnessContext = AlertnessContext(),
) {
    val alertnessScore: Int?
        get() = fatigueScore?.let { 100 - it }
}

data class RobustMetricBaseline(
    val median: Double,
    val medianAbsoluteDeviation: Double,
    val interquartileRange: Double,
)

data class TypingBaseline(
    val lengthBand: WordLengthBand,
    val sampleCount: Int,
    val metrics: Map<TypingSignalKind, RobustMetricBaseline>,
    val contextual: Boolean = false,
    val motorComplexityContextual: Boolean = false,
)

class TypingBaselineCalculator(
    val minimumSampleCount: Int = DEFAULT_MINIMUM_SAMPLE_COUNT,
) {
    fun calculate(
        currentBand: WordLengthBand,
        history: List<TypingPerformanceSample>,
        currentSubmittedAtUtcMs: Long? = null,
        currentMotorComplexityBand: MotorComplexityBand? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TypingBaseline? {
        val sameBand = history.filter { it.lengthBand == currentBand }
        if (sameBand.size < minimumSampleCount) return null

        val sameMotorBand = currentMotorComplexityBand?.let { band ->
            sameBand.filter { it.motorComplexityBand == band }
        }.orEmpty()
        val motorMatched = currentMotorComplexityBand != null && sameMotorBand.size >= minimumSampleCount
        val motorComparable = if (motorMatched) sameMotorBand else sameBand

        val localHour = currentSubmittedAtUtcMs?.let {
            Instant.ofEpochMilli(it).atZone(zoneId).hour
        }
        val contextual = if (localHour == null) {
            emptyList()
        } else {
            motorComparable.filter { sample ->
                sample.submittedAtUtcMs?.let { submitted ->
                    val historicalHour = Instant.ofEpochMilli(submitted).atZone(zoneId).hour
                    circularHourDistance(localHour, historicalHour) <= CONTEXT_HOUR_RADIUS
                } ?: false
            }
        }
        val timeMatched = contextual.size >= minimumSampleCount
        val comparable = if (timeMatched) contextual else motorComparable

        val metrics = buildMap {
            baseline(comparable.mapNotNull { it.durationPerCharacterMs })
                ?.let { put(TypingSignalKind.DurationPerCharacter, it) }
            baseline(comparable.mapNotNull { it.activeDurationPerCharacterMs })
                ?.let { put(TypingSignalKind.ActiveDurationPerCharacter, it) }
            baseline(comparable.mapNotNull { it.meanInterKeyIntervalMs })
                ?.let { put(TypingSignalKind.MeanInterval, it) }
            baseline(comparable.map { it.correctionActionsPerCharacter })
                ?.let { put(TypingSignalKind.CorrectionActions, it) }
            baseline(comparable.map { it.deletedCharactersPerCharacter })
                ?.let { put(TypingSignalKind.DeletedCharacters, it) }
            baseline(comparable.mapNotNull { it.longestInterKeyPauseMs })
                ?.let { put(TypingSignalKind.LongestPause, it) }
            baseline(comparable.mapNotNull { it.p95InterKeyIntervalMs })
                ?.let { put(TypingSignalKind.P95Pause, it) }
            baseline(comparable.mapNotNull { it.interKeyIntervalVariabilityMs })
                ?.let { put(TypingSignalKind.IntervalVariability, it) }
            baseline(comparable.mapNotNull { it.interKeyIntervalCoefficientOfVariation })
                ?.let { put(TypingSignalKind.IntervalCoefficientOfVariation, it) }
            baseline(comparable.map { it.microPausesPerCharacter })
                ?.let { put(TypingSignalKind.MicroPauseRate, it) }
            baseline(comparable.mapNotNull { it.lastEditToSubmitMs })
                ?.let { put(TypingSignalKind.SubmitHesitation, it) }
            baseline(comparable.map { it.invalidInputAttemptCount })
                ?.let { put(TypingSignalKind.InvalidInputAttempts, it) }
        }
        return TypingBaseline(
            lengthBand = currentBand,
            sampleCount = comparable.size,
            metrics = metrics,
            contextual = timeMatched,
            motorComplexityContextual = motorMatched,
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

    private fun circularHourDistance(first: Int, second: Int): Int {
        val absolute = abs(first - second)
        return minOf(absolute, 24 - absolute)
    }

    private companion object {
        const val DEFAULT_MINIMUM_SAMPLE_COUNT = 12
        const val OUTLIER_IQR_MULTIPLIER = 3.0
        const val CONTEXT_HOUR_RADIUS = 2
    }
}

class TypingAnomalyDetector(
    private val baselineCalculator: TypingBaselineCalculator = TypingBaselineCalculator(),
) {
    fun evaluate(
        current: TypingPerformanceSample,
        history: List<TypingPerformanceSample>,
        sessionHistory: List<TypingPerformanceSample> = emptyList(),
        sleepContext: SleepContext? = null,
        pvtCalibrationSamples: List<PvtCalibrationSample> = emptyList(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TypingPerformanceEvaluation {
        val baseline = baselineCalculator.calculate(
            currentBand = current.lengthBand,
            history = history,
            currentSubmittedAtUtcMs = current.submittedAtUtcMs,
            currentMotorComplexityBand = current.motorComplexityBand,
            zoneId = zoneId,
        ) ?: return TypingPerformanceEvaluation(
            level = TypingDeviationLevel.InsufficientData,
            lengthBand = current.lengthBand,
            baselineSampleCount = history.count { it.lengthBand == current.lengthBand },
            context = buildContext(
                current = current,
                sleepContext = sleepContext,
                contextualBaselineUsed = false,
                motorComplexityBaselineUsed = false,
                zoneId = zoneId,
            ),
        )

        val alertCandidates = listOfNotNull(
            current.durationPerCharacterMs?.let {
                SignalCandidate(TypingSignalKind.DurationPerCharacter, it)
            },
            SignalCandidate(TypingSignalKind.CorrectionActions, current.correctionActionsPerCharacter),
            SignalCandidate(TypingSignalKind.DeletedCharacters, current.deletedCharactersPerCharacter),
            current.p95InterKeyIntervalMs?.let {
                SignalCandidate(TypingSignalKind.P95Pause, it)
            },
            current.interKeyIntervalVariabilityMs?.let {
                SignalCandidate(TypingSignalKind.IntervalVariability, it)
            },
            SignalCandidate(TypingSignalKind.InvalidInputAttempts, current.invalidInputAttemptCount),
        )
        val signals = alertCandidates.mapNotNull { candidate ->
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

        val speedDurationCandidate = when {
            current.activeDurationPerCharacterMs != null &&
                baseline.metrics.containsKey(TypingSignalKind.ActiveDurationPerCharacter) ->
                SignalCandidate(
                    TypingSignalKind.ActiveDurationPerCharacter,
                    current.activeDurationPerCharacterMs,
                )
            current.durationPerCharacterMs != null ->
                SignalCandidate(TypingSignalKind.DurationPerCharacter, current.durationPerCharacterMs)
            else -> null
        }
        val domains = FatigueDomainScores(
            speed = domainScore(
                baseline = baseline,
                candidates = listOfNotNull(
                    speedDurationCandidate,
                    current.meanInterKeyIntervalMs?.let {
                        SignalCandidate(TypingSignalKind.MeanInterval, it)
                    },
                ),
                take = 2,
            ),
            rhythm = domainScore(
                baseline = baseline,
                candidates = listOfNotNull(
                    current.p95InterKeyIntervalMs?.let { SignalCandidate(TypingSignalKind.P95Pause, it) },
                    current.longestInterKeyPauseMs?.let { SignalCandidate(TypingSignalKind.LongestPause, it) },
                    current.interKeyIntervalVariabilityMs?.let {
                        SignalCandidate(TypingSignalKind.IntervalVariability, it)
                    },
                    current.interKeyIntervalCoefficientOfVariation?.let {
                        SignalCandidate(TypingSignalKind.IntervalCoefficientOfVariation, it)
                    },
                    SignalCandidate(TypingSignalKind.MicroPauseRate, current.microPausesPerCharacter),
                    current.lastEditToSubmitMs?.let {
                        SignalCandidate(TypingSignalKind.SubmitHesitation, it)
                    },
                ),
                take = 3,
            ),
            control = domainScore(
                baseline = baseline,
                candidates = listOf(
                    SignalCandidate(TypingSignalKind.CorrectionActions, current.correctionActionsPerCharacter),
                    SignalCandidate(TypingSignalKind.DeletedCharacters, current.deletedCharactersPerCharacter),
                    SignalCandidate(TypingSignalKind.InvalidInputAttempts, current.invalidInputAttemptCount),
                ),
                take = 2,
            ),
            sessionDrift = sessionDriftScore(current, sessionHistory),
            sleepContext = sleepContextScore(current, sleepContext),
        )
        val rawFatigue = weightedFatigueScore(domains)
        val calibratedFatigue = rawFatigue?.let { PvtCalibrator.calibrate(it, pvtCalibrationSamples) }
        return TypingPerformanceEvaluation(
            level = level,
            lengthBand = current.lengthBand,
            baselineSampleCount = baseline.sampleCount,
            signals = signals,
            fatigueScore = calibratedFatigue,
            rawFatigueScore = rawFatigue,
            domains = domains,
            context = buildContext(
                current = current,
                sleepContext = sleepContext,
                contextualBaselineUsed = baseline.contextual,
                motorComplexityBaselineUsed = baseline.motorComplexityContextual,
                zoneId = zoneId,
            ),
        )
    }

    private fun domainScore(
        baseline: TypingBaseline,
        candidates: List<SignalCandidate>,
        take: Int,
    ): Int? {
        val scores = candidates.mapNotNull { candidate ->
            baseline.metrics[candidate.kind]?.let { metric ->
                continuousDeviationScore(candidate.kind, candidate.value, metric)
            }
        }.sortedDescending().take(take)
        if (scores.isEmpty()) return null
        return scores.average().roundToInt().coerceIn(0, 100)
    }

    private fun continuousDeviationScore(
        kind: TypingSignalKind,
        current: Double,
        baseline: RobustMetricBaseline,
    ): Double? {
        if (!current.isFinite()) return null
        if (current <= baseline.median) return 0.0
        val robustScore = robustScore(kind, current, baseline)
        return (robustScore / FULL_SCALE_ROBUST_SCORE * 100.0).coerceIn(0.0, 100.0)
    }

    private fun sessionDriftScore(
        current: TypingPerformanceSample,
        sessionHistory: List<TypingPerformanceSample>,
    ): Int? {
        val ordered = (sessionHistory + current)
            .distinctBy { it.entryId }
            .sortedBy { it.submittedAtUtcMs ?: Long.MAX_VALUE }
        if (ordered.size < MINIMUM_SESSION_DRIFT_SAMPLES) return null
        val firstWindow = ordered.take(SESSION_DRIFT_WINDOW)
        val recentWindow = ordered.takeLast(SESSION_DRIFT_WINDOW)
        val relativeIncreases = listOfNotNull(
            relativeIncrease(
                firstWindow.mapNotNull { it.activeDurationPerCharacterMs },
                recentWindow.mapNotNull { it.activeDurationPerCharacterMs },
            ),
            relativeIncrease(
                firstWindow.mapNotNull { it.meanInterKeyIntervalMs },
                recentWindow.mapNotNull { it.meanInterKeyIntervalMs },
            ),
            relativeIncrease(
                firstWindow.mapNotNull { it.p95InterKeyIntervalMs },
                recentWindow.mapNotNull { it.p95InterKeyIntervalMs },
            ),
            relativeIncrease(
                firstWindow.mapNotNull { it.interKeyIntervalVariabilityMs },
                recentWindow.mapNotNull { it.interKeyIntervalVariabilityMs },
            ),
        ).filter { it > 0.0 }.sortedDescending().take(3)
        if (relativeIncreases.isEmpty()) return 0
        return (relativeIncreases.average() / SESSION_DRIFT_FULL_SCALE_INCREASE * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun relativeIncrease(baselineValues: List<Double>, recentValues: List<Double>): Double? {
        if (baselineValues.isEmpty() || recentValues.isEmpty()) return null
        val baselineMedian = median(baselineValues)
        val recentMedian = median(recentValues)
        if (baselineMedian <= 0.0) return null
        return (recentMedian / baselineMedian - 1.0).coerceAtLeast(0.0)
    }

    private fun sleepContextScore(current: TypingPerformanceSample, sleepContext: SleepContext?): Int? {
        sleepContext ?: return null
        val submitted = current.submittedAtUtcMs ?: return null
        val hoursAwake = (submitted - sleepContext.lastWakeUtcMs).toDouble() / HOUR_MS
        if (!hoursAwake.isFinite() || hoursAwake < 0.0) return null
        val awakeComponent = ((hoursAwake - AWAKE_NEUTRAL_HOURS) /
            (AWAKE_FULL_SCALE_HOURS - AWAKE_NEUTRAL_HOURS) * 100.0).coerceIn(0.0, 100.0)
        val deficitHours = sleepContext.personalMedianSleepDurationMs?.let { medianSleep ->
            ((medianSleep - sleepContext.lastSleepDurationMs).coerceAtLeast(0L)).toDouble() / HOUR_MS
        }
        val deficitComponent = deficitHours?.let {
            (it / SLEEP_DEFICIT_FULL_SCALE_HOURS * 100.0).coerceIn(0.0, 100.0)
        }
        val score = if (deficitComponent == null) {
            awakeComponent
        } else {
            awakeComponent * 0.70 + deficitComponent * 0.30
        }
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun weightedFatigueScore(domains: FatigueDomainScores): Int? {
        val weighted = listOfNotNull(
            domains.speed?.let { WeightedDomain(it, 0.35) },
            domains.rhythm?.let { WeightedDomain(it, 0.30) },
            domains.sessionDrift?.let { WeightedDomain(it, 0.15) },
            domains.sleepContext?.let { WeightedDomain(it, 0.12) },
            domains.control?.let { WeightedDomain(it, 0.08) },
        )
        if (weighted.isEmpty()) return null
        val totalWeight = weighted.sumOf(WeightedDomain::weight)
        return (weighted.sumOf { it.score * it.weight } / totalWeight)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun buildContext(
        current: TypingPerformanceSample,
        sleepContext: SleepContext?,
        contextualBaselineUsed: Boolean,
        motorComplexityBaselineUsed: Boolean,
        zoneId: ZoneId,
    ): AlertnessContext {
        val submitted = current.submittedAtUtcMs
        val localHour = submitted?.let { Instant.ofEpochMilli(it).atZone(zoneId).hour }
        val hoursAwake = if (submitted != null && sleepContext != null) {
            ((submitted - sleepContext.lastWakeUtcMs).toDouble() / HOUR_MS)
                .takeIf { it.isFinite() && it >= 0.0 }
        } else {
            null
        }
        val sleepHours = sleepContext?.lastSleepDurationMs?.toDouble()?.div(HOUR_MS)
        val deficitHours = sleepContext?.personalMedianSleepDurationMs?.let { medianSleep ->
            ((medianSleep - sleepContext.lastSleepDurationMs).coerceAtLeast(0L)).toDouble() / HOUR_MS
        }
        return AlertnessContext(
            localHour = localHour,
            contextualBaselineUsed = contextualBaselineUsed,
            motorComplexityBaselineUsed = motorComplexityBaselineUsed,
            motorComplexityScore = current.motorComplexityScore,
            hoursAwake = hoursAwake,
            lastSleepDurationHours = sleepHours,
            sleepDeficitHours = deficitHours,
        )
    }

    private fun detectSignal(
        kind: TypingSignalKind,
        current: Double,
        baseline: RobustMetricBaseline,
    ): TypingDeviationSignal? {
        if (!current.isFinite() || current <= baseline.median) return null

        val ratio = if (baseline.median > 0.0) current / baseline.median else null
        val robustScore = robustScore(kind, current, baseline)
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

    private fun robustScore(
        kind: TypingSignalKind,
        current: Double,
        baseline: RobustMetricBaseline,
    ): Double =
        when {
            baseline.medianAbsoluteDeviation > 0.0 ->
                (current - baseline.median) / (MAD_SCALE * baseline.medianAbsoluteDeviation)
            baseline.interquartileRange > 0.0 ->
                (current - baseline.median) / (IQR_SCALE * baseline.interquartileRange)
            else -> zeroSpreadScore(kind, current, baseline.median)
        }.coerceAtLeast(0.0)

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
            TypingSignalKind.DeletedCharacters,
            TypingSignalKind.MicroPauseRate -> when {
                current >= 0.30 -> 6.0
                current >= 0.15 -> 3.0
                else -> 0.0
            }
            TypingSignalKind.InvalidInputAttempts -> when {
                current >= 3.0 -> 6.0
                current >= 1.0 -> 3.0
                else -> 0.0
            }
            TypingSignalKind.SubmitHesitation -> when {
                current >= 2_000.0 -> 6.0
                current >= 1_000.0 -> 3.0
                else -> 0.0
            }
            else -> 0.0
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private data class SignalCandidate(
        val kind: TypingSignalKind,
        val value: Double,
    )

    private data class WeightedDomain(
        val score: Int,
        val weight: Double,
    )

    private companion object {
        const val MAD_SCALE = 1.4826
        const val IQR_SCALE = 0.7413
        const val MODERATE_SCORE = 2.5
        const val MARKED_SCORE = 4.5
        const val FULL_SCALE_ROBUST_SCORE = 6.0
        const val MODERATE_RELATIVE_INCREASE = 0.25
        const val MARKED_RELATIVE_INCREASE = 0.50
        const val MINIMUM_SESSION_DRIFT_SAMPLES = 6
        const val SESSION_DRIFT_WINDOW = 3
        const val SESSION_DRIFT_FULL_SCALE_INCREASE = 0.50
        const val HOUR_MS = 3_600_000.0
        const val AWAKE_NEUTRAL_HOURS = 12.0
        const val AWAKE_FULL_SCALE_HOURS = 18.0
        const val SLEEP_DEFICIT_FULL_SCALE_HOURS = 3.0
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
        val alertness = evaluation.alertnessScore?.let { "Alertness $it/100. " }.orEmpty()
        return alertness + "Prestazione insolita: ${phrases.joinToString(" e ")} rispetto alla tua norma recente."
    }

    private fun formatSignal(signal: TypingDeviationSignal): String =
        when (signal.kind) {
            TypingSignalKind.DurationPerCharacter,
            TypingSignalKind.ActiveDurationPerCharacter,
            TypingSignalKind.MeanInterval -> {
                val percent = signal.ratioToBaseline
                    ?.let { ((it - 1.0) * 100.0).coerceAtLeast(0.0) }
                    ?: 0.0
                "digitazione ${decimalFormat.format(percent)}% più lenta"
            }
            TypingSignalKind.CorrectionActions ->
                "${formatRatio(signal)} più correzioni"
            TypingSignalKind.DeletedCharacters ->
                "${formatRatio(signal)} più caratteri cancellati"
            TypingSignalKind.LongestPause,
            TypingSignalKind.P95Pause ->
                "pause ${formatRatio(signal)} più lunghe"
            TypingSignalKind.IntervalVariability,
            TypingSignalKind.IntervalCoefficientOfVariation ->
                "ritmo ${formatRatio(signal)} più variabile"
            TypingSignalKind.MicroPauseRate ->
                "${formatRatio(signal)} più micro-pause"
            TypingSignalKind.SubmitHesitation ->
                "esitazione finale ${formatRatio(signal)} più lunga"
            TypingSignalKind.InvalidInputAttempts ->
                "${decimalFormat.format(signal.currentValue)} tentativi di input non validi"
        }

    private fun formatRatio(signal: TypingDeviationSignal): String =
        signal.ratioToBaseline
            ?.let { "${decimalFormat.format(it)}×" }
            ?: "molte"
}
