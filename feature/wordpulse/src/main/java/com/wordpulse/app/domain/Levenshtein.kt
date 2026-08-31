package com.wordpulse.app.domain

import kotlin.math.min

object Levenshtein {
    fun distance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val substitutionCost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = min(
                    min(
                        current[j - 1] + 1,
                        previous[j] + 1,
                    ),
                    previous[j - 1] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }

    fun similarity(left: String, right: String): Double {
        val maxLength = maxOf(left.length, right.length)
        if (maxLength == 0) return 1.0
        return 1.0 - (distance(left, right).toDouble() / maxLength.toDouble())
    }
}
