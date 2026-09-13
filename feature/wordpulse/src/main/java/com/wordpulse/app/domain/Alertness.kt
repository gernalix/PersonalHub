package com.wordpulse.app.domain

import kotlin.math.abs
import kotlin.math.sqrt

data class SleepContext(
    val lastWakeUtcMs: Long,
    val lastSleepDurationMs: Long,
    val personalMedianSleepDurationMs: Long? = null,
)

data class FatigueDomainScores(
    val speed: Int? = null,
    val rhythm: Int? = null,
    val control: Int? = null,
    val sessionDrift: Int? = null,
    val sleepContext: Int? = null,
)

data class AlertnessContext(
    val localHour: Int? = null,
    val contextualBaselineUsed: Boolean = false,
    val motorComplexityBaselineUsed: Boolean = false,
    val motorComplexityScore: Double? = null,
    val hoursAwake: Double? = null,
    val lastSleepDurationHours: Double? = null,
    val sleepDeficitHours: Double? = null,
)

data class PvtCalibrationSample(
    val rawFatigueScore: Int,
    val medianReactionTimeMs: Double,
    val lapseCount: Int,
    val falseStartCount: Int,
) {
    val impairmentIndex: Double
        get() = medianReactionTimeMs + lapseCount * LAPSE_PENALTY_MS + falseStartCount * FALSE_START_PENALTY_MS

    private companion object {
        const val LAPSE_PENALTY_MS = 250.0
        const val FALSE_START_PENALTY_MS = 100.0
    }
}

data class PvtTestState(
    val active: Boolean = false,
    val stimulusVisible: Boolean = false,
    val startedAtUtcMs: Long? = null,
    val endsAtUtcMs: Long? = null,
    val trialsCompleted: Int = 0,
    val medianReactionTimeMs: Double? = null,
    val p90ReactionTimeMs: Double? = null,
    val lapseCount: Int = 0,
    val falseStartCount: Int = 0,
)

data class PvtSummary(
    val completedAtUtcMs: Long,
    val trialsCompleted: Int,
    val medianReactionTimeMs: Double?,
    val p90ReactionTimeMs: Double?,
    val lapseCount: Int,
    val falseStartCount: Int,
    val pairedFatigueScore: Int?,
)

object PvtCalibrator {
    fun calibrate(rawFatigueScore: Int, samples: List<PvtCalibrationSample>): Int {
        val valid = samples.filter {
            it.rawFatigueScore in 0..100 &&
                it.medianReactionTimeMs.isFinite() &&
                it.medianReactionTimeMs > 0.0
        }
        if (valid.size < MINIMUM_CALIBRATION_SAMPLES) return rawFatigueScore.coerceIn(0, 100)

        val xs = valid.map { it.rawFatigueScore.toDouble() }
        val ys = valid.map(PvtCalibrationSample::impairmentIndex)
        val correlation = correlation(xs, ys)
        if (!correlation.isFinite() || correlation < MINIMUM_USEFUL_CORRELATION) {
            return rawFatigueScore.coerceIn(0, 100)
        }

        val xMean = xs.average()
        val yMean = ys.average()
        val denominator = xs.sumOf { value -> (value - xMean) * (value - xMean) }
        if (denominator <= 0.0) return rawFatigueScore.coerceIn(0, 100)
        val slope = xs.indices.sumOf { index ->
            (xs[index] - xMean) * (ys[index] - yMean)
        } / denominator
        if (!slope.isFinite() || slope <= 0.0) return rawFatigueScore.coerceIn(0, 100)
        val intercept = yMean - slope * xMean
        val predictedImpairment = intercept + slope * rawFatigueScore

        val median = percentile(ys.sorted(), 0.5)
        val absoluteDeviations = ys.map { abs(it - median) }.sorted()
        val mad = percentile(absoluteDeviations, 0.5)
        val pvtFatigue = when {
            mad > 0.0 -> ((predictedImpairment - median) / (MAD_SCALE * mad) * SCORE_PER_ROBUST_Z)
                .coerceIn(0.0, 100.0)
            median > 0.0 -> (((predictedImpairment / median) - 1.0) / FALLBACK_RELATIVE_RANGE * 100.0)
                .coerceIn(0.0, 100.0)
            else -> rawFatigueScore.toDouble()
        }
        val confidence = ((valid.size - MINIMUM_CALIBRATION_SAMPLES + 1).toDouble() / FULL_CONFIDENCE_SAMPLE_COUNT)
            .coerceIn(MINIMUM_BLEND_WEIGHT, MAXIMUM_BLEND_WEIGHT)
        return ((1.0 - confidence) * rawFatigueScore + confidence * pvtFatigue)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun correlation(xs: List<Double>, ys: List<Double>): Double {
        if (xs.size != ys.size || xs.size < 2) return Double.NaN
        val xMean = xs.average()
        val yMean = ys.average()
        var covariance = 0.0
        var xVariance = 0.0
        var yVariance = 0.0
        for (index in xs.indices) {
            val dx = xs[index] - xMean
            val dy = ys[index] - yMean
            covariance += dx * dy
            xVariance += dx * dx
            yVariance += dy * dy
        }
        val denominator = sqrt(xVariance * yVariance)
        return if (denominator > 0.0) covariance / denominator else Double.NaN
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted.first()
        val position = (sorted.lastIndex * fraction).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private const val MINIMUM_CALIBRATION_SAMPLES = 8
    private const val MINIMUM_USEFUL_CORRELATION = 0.25
    private const val MAD_SCALE = 1.4826
    private const val SCORE_PER_ROBUST_Z = 16.0
    private const val FALLBACK_RELATIVE_RANGE = 0.50
    private const val FULL_CONFIDENCE_SAMPLE_COUNT = 20.0
    private const val MINIMUM_BLEND_WEIGHT = 0.15
    private const val MAXIMUM_BLEND_WEIGHT = 0.35
}
