package com.wordpulse.app.domain

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingPerformanceTest {
    private val calculator = TypingBaselineCalculator(minimumSampleCount = 12)
    private val detector = TypingAnomalyDetector(calculator)

    @Test
    fun reportsInsufficientDataAndSeparatesLengthBands() {
        val history = List(11) { sample(it.toLong(), 6, durationPerCharacter = 100.0) }
        val evaluation = detector.evaluate(
            sample(99, 6, durationPerCharacter = 180.0),
            history + List(20) { sample((100 + it).toLong(), 3, 100.0) },
        )

        assertEquals(TypingDeviationLevel.InsufficientData, evaluation.level)
        assertEquals(11, evaluation.baselineSampleCount)
    }

    @Test
    fun medianAndMadDetectModerateAndMarkedDeviations() {
        val history = (0 until 20).map {
            sample(it.toLong(), 6, durationPerCharacter = 95.0 + (it % 5) * 2.5)
        }

        val moderate = detector.evaluate(sample(100, 6, 145.0, corrections = 0.20), history)
        val marked = detector.evaluate(sample(101, 6, 240.0, corrections = 0.40), history)

        assertTrue(moderate.level != TypingDeviationLevel.Normal)
        assertEquals(TypingDeviationLevel.MarkedDeviation, marked.level)
        assertTrue(marked.signals.any { it.kind == TypingSignalKind.DurationPerCharacter })
    }

    @Test
    fun zeroMadUsesFallbackAndOutlierDoesNotMoveBaseline() {
        val history = List(19) { sample(it.toLong(), 6, 100.0) } +
            sample(30, 6, 10_000.0)

        val evaluation = detector.evaluate(
            sample(99, 6, durationPerCharacter = 100.0, corrections = 0.35),
            history,
        )

        assertEquals(TypingDeviationLevel.MarkedDeviation, evaluation.level)
        assertTrue(evaluation.signals.any { it.kind == TypingSignalKind.CorrectionActions })
        assertTrue(evaluation.signals.none { it.kind == TypingSignalKind.DurationPerCharacter })
    }

    @Test
    fun zeroMadAndZeroIqrStillUseRelativeDurationChange() {
        val history = List(12) { sample(it.toLong(), 6, 100.0) }

        val evaluation = detector.evaluate(sample(99, 6, 160.0), history)

        assertEquals(TypingDeviationLevel.MarkedDeviation, evaluation.level)
        assertTrue(evaluation.signals.any { it.kind == TypingSignalKind.DurationPerCharacter })
    }

    @Test
    fun discordantSignalsDoNotFlagOneMildChange() {
        val history = List(12) { sample(it.toLong(), 6, 100.0, corrections = 0.10) }
        val evaluation = detector.evaluate(
            sample(99, 6, durationPerCharacter = 120.0, corrections = 0.0),
            history,
        )

        assertEquals(TypingDeviationLevel.Normal, evaluation.level)
    }

    @Test
    fun formatterUsesLocalDuplicateDateAndExplainsSignalsWithoutMedicalClaims() {
        val formatter = TypingInsightFormatter(ZoneOffset.UTC)
        val duplicate = formatter.duplicate(2, 1_767_225_600_000L)
        val performance = formatter.performance(
            TypingPerformanceEvaluation(
                level = TypingDeviationLevel.MarkedDeviation,
                lengthBand = WordLengthBand.Medium,
                baselineSampleCount = 20,
                signals = listOf(
                    TypingDeviationSignal(
                        kind = TypingSignalKind.DurationPerCharacter,
                        currentValue = 134.0,
                        baselineMedian = 100.0,
                        ratioToBaseline = 1.34,
                        robustScore = 5.0,
                        strength = TypingSignalStrength.Marked,
                    ),
                    TypingDeviationSignal(
                        kind = TypingSignalKind.CorrectionActions,
                        currentValue = 0.23,
                        baselineMedian = 0.10,
                        ratioToBaseline = 2.3,
                        robustScore = 4.0,
                        strength = TypingSignalStrength.Moderate,
                    ),
                ),
            ),
        )

        assertEquals("Parola scritta 2 volte, ultima volta il 01/01/26", duplicate)
        assertTrue(performance.contains("34% più lenta"))
        assertTrue(performance.contains("2,3× più correzioni"))
        assertTrue(!performance.contains("lucid"))
        assertTrue(!performance.contains("diagnos"))
    }

    private fun sample(
        id: Long,
        length: Int,
        durationPerCharacter: Double,
        corrections: Double = 0.0,
    ): TypingPerformanceSample =
        TypingPerformanceSample(
            entryId = id,
            finalCharacterCount = length,
            charactersPerMinute = 60_000.0 / durationPerCharacter,
            durationPerCharacterMs = durationPerCharacter,
            correctionActionsPerCharacter = corrections,
            deletedCharactersPerCharacter = corrections,
            longestInterKeyPauseMs = durationPerCharacter * 1.5,
            interKeyIntervalVariabilityMs = durationPerCharacter * 0.1,
            invalidInputAttemptCount = 0.0,
        )
}
