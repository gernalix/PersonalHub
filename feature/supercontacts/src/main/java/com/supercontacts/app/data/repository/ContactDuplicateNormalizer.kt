package com.supercontacts.app.data.repository

import java.text.Normalizer
import kotlin.math.min

object ContactDuplicateNormalizer {
    fun normalizePhone(value: String): String =
        PhoneNormalizer.normalize(value)

    fun normalizeEmail(value: String): String =
        value.trim().lowercase()

    fun normalizeLinkOrUsername(value: String): String =
        value
            .trim()
            .lowercase()
            .trim('@')
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
            .trim('@')

    fun normalizeLooseText(value: String): String =
        Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun phoneMatches(left: String, right: String): Boolean {
        val first = normalizePhone(left)
        val second = normalizePhone(right)
        if (first.isBlank() || second.isBlank()) return false
        if (first == second) return true
        val shortest = min(first.length, second.length)
        return shortest >= PHONE_SUFFIX_MIN_LENGTH &&
            (first.endsWith(second) || second.endsWith(first))
    }

    fun looseTextMatches(left: String, right: String): Boolean {
        val first = normalizeLooseText(left)
        val second = normalizeLooseText(right)
        if (first.length < 3 || second.length < 3) return false
        if (first == second) return true
        if (first.length >= 4 && second.length >= 4 && (first.contains(second) || second.contains(first))) {
            return true
        }
        val firstTokens = first.split(" ").filter { it.length >= 3 }.toSet()
        val secondTokens = second.split(" ").filter { it.length >= 3 }.toSet()
        if (firstTokens.isNotEmpty() && firstTokens.intersect(secondTokens).isNotEmpty()) {
            return true
        }
        val maxDistance = (maxOf(first.length, second.length) / 4).coerceAtLeast(1)
        return levenshteinDistance(first, second, maxDistance) <= maxDistance
    }

    fun firstTokenPrefix(value: String, minLength: Int): String =
        normalizeLooseText(value)
            .split(" ")
            .firstOrNull()
            ?.takeIf { it.length >= minLength }
            ?.take(8)
            .orEmpty()

    private fun levenshteinDistance(left: String, right: String, stopAfter: Int): Int {
        if (kotlin.math.abs(left.length - right.length) > stopAfter) return stopAfter + 1
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            var rowMin = current[0]
            for (rightIndex in right.indices) {
                val cost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + cost,
                )
                rowMin = min(rowMin, current[rightIndex + 1])
            }
            if (rowMin > stopAfter) return stopAfter + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private const val PHONE_SUFFIX_MIN_LENGTH = 7
}
