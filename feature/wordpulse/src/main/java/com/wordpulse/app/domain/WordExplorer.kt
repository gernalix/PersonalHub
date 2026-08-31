package com.wordpulse.app.domain

import com.wordpulse.app.data.WordEntry

object WordExplorer {
    private const val MAX_NEIGHBOR_SCAN = 5_000

    fun search(
        entries: List<WordEntry>,
        query: String,
        mode: SearchMode,
        sort: SearchSort,
    ): List<WordSearchResult> {
        val normalizedQuery = TextNormalizer.normalize(query)
        if (normalizedQuery.isBlank()) {
            return summarize(entries, distanceFrom = null)
                .sortBy(sort)
                .take(100)
        }

        val matcher: (String) -> Int? = when (mode) {
            SearchMode.Substring -> { word -> if (word.contains(normalizedQuery)) 0 else null }
            SearchMode.Prefix -> { word -> if (word.startsWith(normalizedQuery)) 0 else null }
            SearchMode.Suffix -> { word -> if (word.endsWith(normalizedQuery)) 0 else null }
            SearchMode.Fuzzy -> { word ->
                val distance = Levenshtein.distance(normalizedQuery, word)
                if (distance <= fuzzyLimit(normalizedQuery.length)) distance else null
            }
            SearchMode.Similar -> { word ->
                val distance = Levenshtein.distance(normalizedQuery, word)
                val similarity = Levenshtein.similarity(normalizedQuery, word)
                if (similarity >= 0.55 || distance <= fuzzyLimit(normalizedQuery.length)) distance else null
            }
            SearchMode.Regex -> regexMatcher(normalizedQuery)
        }

        return summarize(entries, matcher)
            .sortBy(sort)
            .take(100)
    }

    fun detailFor(entries: List<WordEntry>, normalizedWord: String): WordDetail? {
        val sortedEntries = entries.sortedWith(compareBy<WordEntry> { it.createdAtUtcMs }.thenBy { it.id })
        val occurrences = sortedEntries.filter { it.normalizedWord == normalizedWord }
        if (occurrences.isEmpty()) return null

        val intervals = occurrences.zipWithNext { previous, current -> current.createdAtUtcMs - previous.createdAtUtcMs }
        val sample = occurrences.last().originalWord
        return WordDetail(
            normalizedWord = normalizedWord,
            sampleOriginalWord = sample,
            totalOccurrences = occurrences.size,
            firstOccurrenceUtcMs = occurrences.first().createdAtUtcMs,
            latestOccurrenceUtcMs = occurrences.last().createdAtUtcMs,
            sessionIds = occurrences.map { it.sessionId }.distinct(),
            averageIntervalBetweenOccurrencesMs = intervals.takeIf { it.isNotEmpty() }?.average(),
            longestIntervalBetweenOccurrencesMs = intervals.maxOrNull(),
            shortestIntervalBetweenOccurrencesMs = intervals.minOrNull(),
            closestSimilarWords = nearestNeighbors(sortedEntries, normalizedWord),
            prefix = normalizedWord.take(4),
            suffix = normalizedWord.takeLast(4),
            detectedFragments = normalizedWord.fragments(),
        )
    }

    private fun summarize(
        entries: List<WordEntry>,
        distanceFrom: ((String) -> Int?)?,
    ): List<WordSearchResult> =
        entries.groupBy { it.normalizedWord }
            .mapNotNull { (normalized, group) ->
                val distance = distanceFrom?.invoke(normalized)
                if (distanceFrom != null && distance == null) return@mapNotNull null
                val latest = group.maxBy { it.createdAtUtcMs }
                WordSearchResult(
                    normalizedWord = normalized,
                    sampleOriginalWord = latest.originalWord,
                    occurrences = group.size,
                    latestOccurrenceUtcMs = latest.createdAtUtcMs,
                    length = latest.originalWord.length,
                    distance = distance,
                )
            }

    private fun List<WordSearchResult>.sortBy(sort: SearchSort): List<WordSearchResult> =
        when (sort) {
            SearchSort.Frequency -> sortedWith(compareByDescending<WordSearchResult> { it.occurrences }
                .thenBy { it.distance ?: Int.MAX_VALUE }
                .thenByDescending { it.latestOccurrenceUtcMs })
            SearchSort.Recency -> sortedByDescending { it.latestOccurrenceUtcMs }
            SearchSort.Alphabetical -> sortedBy { it.normalizedWord }
            SearchSort.Length -> sortedWith(compareBy<WordSearchResult> { it.length }.thenBy { it.normalizedWord })
        }

    private fun regexMatcher(pattern: String): (String) -> Int? {
        val regex = runCatching { Regex(pattern) }.getOrNull()
        return { word -> if (regex?.containsMatchIn(word) == true) 0 else null }
    }

    private fun nearestNeighbors(entries: List<WordEntry>, normalizedWord: String): List<WordNeighbor> =
        entries.asSequence()
            .filter { it.normalizedWord != normalizedWord }
            .groupBy { it.normalizedWord }
            .asSequence()
            .take(MAX_NEIGHBOR_SCAN)
            .map { (candidate, group) ->
                val distance = Levenshtein.distance(normalizedWord, candidate)
                val latest = group.maxBy { it.createdAtUtcMs }
                WordNeighbor(
                    normalizedWord = candidate,
                    sampleOriginalWord = latest.originalWord,
                    distance = distance,
                    similarity = Levenshtein.similarity(normalizedWord, candidate),
                    occurrences = group.size,
                )
            }
            .sortedWith(compareBy<WordNeighbor> { it.distance }.thenByDescending { it.occurrences })
            .take(12)
            .toList()

    private fun fuzzyLimit(length: Int): Int =
        when {
            length <= 4 -> 1
            length <= 8 -> 2
            else -> 3
        }

    private fun String.fragments(): List<String> =
        (3..minOf(5, length))
            .flatMap { size -> windowed(size) }
            .distinct()
            .take(24)
}
