package com.wordpulse.app.domain

import kotlin.math.hypot

enum class MotorComplexityBand {
    Low,
    Medium,
    High,
}

data class WordMotorComplexity(
    val averageKeyTravel: Double,
    val handAlternationRate: Double,
    val repeatedPairRate: Double,
    val score: Double,
    val band: MotorComplexityBand,
)

object WordMotorComplexityCalculator {
    fun calculate(word: String): WordMotorComplexity? {
        if (!CaptureWordPolicy.isValidWord(word) || word.length < 2) return null
        val transitions = word.zipWithNext()
        val distances = transitions.mapNotNull { (first, second) ->
            val a = KEY_POSITIONS[first] ?: return@mapNotNull null
            val b = KEY_POSITIONS[second] ?: return@mapNotNull null
            hypot(a.x - b.x, a.y - b.y)
        }
        if (distances.size != transitions.size) return null

        val repeatedPairs = transitions.count { (first, second) -> first == second }
        val alternations = transitions.count { (first, second) ->
            val firstHand = hand(first)
            val secondHand = hand(second)
            firstHand != null && secondHand != null && firstHand != secondHand
        }
        val averageTravel = distances.average()
        val alternationRate = alternations.toDouble() / transitions.size
        val repeatedPairRate = repeatedPairs.toDouble() / transitions.size
        val normalizedTravel = (averageTravel / HIGH_TRAVEL_REFERENCE).coerceIn(0.0, 1.0)
        val sameHandRate = 1.0 - alternationRate
        val score = (
            normalizedTravel * TRAVEL_WEIGHT +
                sameHandRate * SAME_HAND_WEIGHT +
                (1.0 - repeatedPairRate) * NON_REPEAT_WEIGHT
            ).coerceIn(0.0, 1.0)
        val band = when {
            score < LOW_THRESHOLD -> MotorComplexityBand.Low
            score < HIGH_THRESHOLD -> MotorComplexityBand.Medium
            else -> MotorComplexityBand.High
        }
        return WordMotorComplexity(
            averageKeyTravel = averageTravel,
            handAlternationRate = alternationRate,
            repeatedPairRate = repeatedPairRate,
            score = score,
            band = band,
        )
    }

    private fun hand(character: Char): Hand? = when (character) {
        in LEFT_HAND_KEYS -> Hand.Left
        in RIGHT_HAND_KEYS -> Hand.Right
        else -> null
    }

    private data class KeyPosition(val x: Double, val y: Double)

    private enum class Hand { Left, Right }

    private val KEY_POSITIONS: Map<Char, KeyPosition> = buildMap {
        "qwertyuiop".forEachIndexed { index, character ->
            put(character, KeyPosition(index.toDouble(), 0.0))
        }
        "asdfghjkl".forEachIndexed { index, character ->
            put(character, KeyPosition(index + 0.25, 1.0))
        }
        "zxcvbnm".forEachIndexed { index, character ->
            put(character, KeyPosition(index + 0.75, 2.0))
        }
    }
    private val LEFT_HAND_KEYS = "qwertasdfgzxcvb".toSet()
    private val RIGHT_HAND_KEYS = "yuiophjklnm".toSet()

    private const val HIGH_TRAVEL_REFERENCE = 5.0
    private const val TRAVEL_WEIGHT = 0.65
    private const val SAME_HAND_WEIGHT = 0.25
    private const val NON_REPEAT_WEIGHT = 0.10
    private const val LOW_THRESHOLD = 0.35
    private const val HIGH_THRESHOLD = 0.60
}
