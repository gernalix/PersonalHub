package com.wordpulse.app.domain

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertnessTest {
    private val detector = TypingAnomalyDetector(TypingBaselineCalculator(minimumSampleCount = 12))

    @Test
    fun fatigueScoreCombinesSpeedRhythmAndControlWithoutOverweightingCorrections() {
        val history = (0 until 20).map { index ->
            sample(
                id = index.toLong(),
                submittedAt = 1_000_000L + index * 60_000L,
                durationPerCharacter = 100.0 + (index % 4),
                meanInterval = 95.0 + (index % 4),
                p95 = 160.0 + (index % 5),
                variability = 18.0 + (index % 3),
                corrections = 0.05,
            )
        }
        val current = sample(
            id = 100,
            submittedAt = 2_500_000L,
            durationPerCharacter = 180.0,
            meanInterval = 170.0,
            p95 = 420.0,
            variability = 70.0,
            corrections = 0.075,
            microPauses = 0.25,
            submitHesitation = 900.0,
        )

        val evaluation = detector.evaluate(current, history, zoneId = ZoneOffset.UTC)

        assertNotNull(evaluation.fatigueScore)
        assertTrue(evaluation.fatigueScore!! >= 50)
        assertTrue(evaluation.domains.speed!! > evaluation.domains.control!!)
        assertEquals(100 - evaluation.fatigueScore!!, evaluation.alertnessScore)
    }

    @Test
    fun sessionDriftAndSleepContextContributeWhenAvailable() {
        val history = List(20) { index ->
            sample(index.toLong(), 1_000_000L + index * 60_000L, 100.0, 100.0, 150.0, 20.0)
        }
        val session = (0 until 5).map { index ->
            val factor = if (index < 3) 1.0 else 1.35
            sample(
                id = 200L + index,
                submittedAt = 9_000_000L + index * 60_000L,
                durationPerCharacter = 100.0 * factor,
                meanInterval = 100.0 * factor,
                p95 = 150.0 * factor,
                variability = 20.0 * factor,
            )
        }
        val currentTime = 18L * 3_600_000L
        val current = sample(300, currentTime, 155.0, 150.0, 300.0, 55.0)
        val sleep = SleepContext(
            lastWakeUtcMs = 0L,
            lastSleepDurationMs = 5L * 3_600_000L,
            personalMedianSleepDurationMs = 8L * 3_600_000L,
        )

        val evaluation = detector.evaluate(
            current = current,
            history = history,
            sessionHistory = session,
            sleepContext = sleep,
            zoneId = ZoneOffset.UTC,
        )

        assertNotNull(evaluation.domains.sessionDrift)
        assertTrue(evaluation.domains.sessionDrift!! > 0)
        assertEquals(100, evaluation.domains.sleepContext)
        assertEquals(18.0, evaluation.context.hoursAwake!!, 0.001)
        assertEquals(3.0, evaluation.context.sleepDeficitHours!!, 0.001)
    }

    @Test
    fun pvtCalibrationRequiresEvidenceAndStaysBounded() {
        val raw = 75
        val tooFew = List(7) { index ->
            PvtCalibrationSample(
                rawFatigueScore = index * 10,
                medianReactionTimeMs = 220.0 + index * 25.0,
                lapseCount = index / 3,
                falseStartCount = 0,
            )
        }
        assertEquals(raw, PvtCalibrator.calibrate(raw, tooFew))

        val enough = List(12) { index ->
            PvtCalibrationSample(
                rawFatigueScore = index * 8,
                medianReactionTimeMs = 210.0 + index * 24.0,
                lapseCount = index / 4,
                falseStartCount = index / 6,
            )
        }
        val calibrated = PvtCalibrator.calibrate(raw, enough)

        assertTrue(calibrated in 0..100)
        assertTrue(calibrated != raw)
    }

    private fun sample(
        id: Long,
        submittedAt: Long,
        durationPerCharacter: Double,
        meanInterval: Double,
        p95: Double,
        variability: Double,
        corrections: Double = 0.0,
        microPauses: Double = 0.0,
        submitHesitation: Double = 100.0,
    ): TypingPerformanceSample =
        TypingPerformanceSample(
            entryId = id,
            finalCharacterCount = 6,
            charactersPerMinute = 60_000.0 / durationPerCharacter,
            durationPerCharacterMs = durationPerCharacter,
            correctionActionsPerCharacter = corrections,
            deletedCharactersPerCharacter = corrections,
            longestInterKeyPauseMs = p95 * 1.2,
            interKeyIntervalVariabilityMs = variability,
            invalidInputAttemptCount = 0.0,
            submittedAtUtcMs = submittedAt,
            activeDurationPerCharacterMs = durationPerCharacter,
            meanInterKeyIntervalMs = meanInterval,
            medianInterKeyIntervalMs = meanInterval * 0.95,
            p95InterKeyIntervalMs = p95,
            interKeyIntervalCoefficientOfVariation = variability / meanInterval,
            microPausesPerCharacter = microPauses,
            lastEditToSubmitMs = submitHesitation,
        )
}
