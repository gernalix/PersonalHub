package com.wordpulse.app.domain

import com.wordpulse.app.data.WordEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object MetricsCalculator {
    private const val ONE_MINUTE_MS = 60_000L
    private const val ONE_DAY_MS = 86_400_000L
    private const val MAX_TIMELINE_ROWS = 500
    private const val FRAGMENT_LIFECYCLE_WINDOWS = 8
    private const val MUTATION_GRAMMAR_WINDOWS = 8
    private const val GENERATIVE_PHASE_WINDOWS = 8
    private const val CREATIVE_EPISODE_WINDOWS = 8
    private const val WORD_GENEALOGY_RECENT_ENTRIES = 240
    private const val WORD_GENEALOGY_UNIQUE_LIMIT = 120
    private const val WORD_GENEALOGY_PARENT_LOOKBACK = 60
    private const val LANGUAGE_EPOCH_WINDOWS = 8
    private const val PHONOTACTIC_MIN_WORDS = 40
    private const val CONSONANT_SPINE_MIN_WORDS = 32
    private const val CONSONANT_SPINE_WINDOWS = 8
    private const val VOWEL_PALETTE_MIN_WORDS = 32
    private const val VOWEL_PALETTE_WINDOWS = 8
    private const val SOUND_SHAPE_DRIFT_WINDOWS = 8
    private const val SOUND_SHAPE_DRIFT_MIN_WORDS = 40
    private const val MOTIF_ROUTE_WINDOWS = 8
    private const val MOTIF_ATTRACTOR_RECENT_ENTRIES = 360
    private const val MOTIF_ATTRACTOR_WINDOWS = 8
    private const val MEMORY_ECHO_RECENT_ENTRIES = 360
    private const val MEMORY_ECHO_LOOKBACK_LIMIT = 160
    private const val MEMORY_ECHO_MIN_GAP = 8
    private const val MEMORY_ECHO_WINDOWS = 8
    private const val WORD_CONSTELLATION_RECENT_ENTRIES = 220
    private const val WORD_CONSTELLATION_UNIQUE_LIMIT = 96
    private const val FAMILY_MIGRATION_RECENT_ENTRIES = 260
    private const val FAMILY_MIGRATION_UNIQUE_LIMIT = 96
    private const val FAMILY_MIGRATION_WINDOWS = 8

    fun calculate(
        entries: List<WordEntry>,
        currentSessionId: String?,
        nowUtcMs: Long,
        zoneId: ZoneId,
    ): WordMetrics {
        val sortedEntries = entries.sortedWith(compareBy<WordEntry> { it.createdAtUtcMs }.thenBy { it.id })
        if (sortedEntries.isEmpty()) {
            val scores = buildScores(emptyList(), TimingMetrics(), RhythmMetrics())
            return WordMetrics(
                scores = scores,
                dashboard = DashboardMetrics(),
                disclosure = ProgressiveDisclosure(),
            )
        }

        val occurrences = buildOccurrenceSummaries(sortedEntries)
        val consecutiveTransitions = buildConsecutiveTransitions(sortedEntries)
        val timeline = buildTimeline(sortedEntries, currentSessionId)
        val general = buildGeneralMetrics(sortedEntries, currentSessionId, nowUtcMs, zoneId)
        val timing = buildTimingMetrics(sortedEntries)
        val similarity = buildSimilarityMetrics(consecutiveTransitions)
        val rhythm = buildRhythmMetrics(sortedEntries, similarity, zoneId)
        val scores = buildScores(sortedEntries, timing, rhythm)
        val baselines = buildBaselines(sortedEntries, nowUtcMs, zoneId)
        val families = buildFamilyMetrics(occurrences)
        val fragments = buildFragmentMetrics(sortedEntries)
        val wordStats = buildWordStats(sortedEntries)
        val evolution = buildEvolutionMetrics(consecutiveTransitions)
        val phases = buildGenerativePhaseMetrics(sortedEntries, currentSessionId)
        val creativeEpisodes = buildCreativeEpisodeMetrics(sortedEntries, currentSessionId)
        val genealogy = buildWordGenealogyMetrics(sortedEntries, currentSessionId)
        val languageEpochs = buildLanguageEpochMetrics(sortedEntries, currentSessionId)
        val phonotactics = buildPhonotacticMetrics(sortedEntries, currentSessionId)
        val consonantSpine = buildConsonantSpineMetrics(sortedEntries, currentSessionId)
        val vowelPalette = buildVowelPaletteMetrics(sortedEntries, currentSessionId)
        val soundShapeDrift = buildSoundShapeDriftMetrics(sortedEntries, currentSessionId)
        val sessionContrast = buildSessionContrastMetrics(sortedEntries, currentSessionId)
        val motifRoutes = buildMotifRouteMetrics(sortedEntries, currentSessionId)
        val motifAttractors = buildMotifAttractorMetrics(sortedEntries, currentSessionId)
        val memoryEchoes = buildMemoryEchoMetrics(sortedEntries, currentSessionId)
        val constellations = buildWordConstellationMetrics(sortedEntries, currentSessionId)
        val familyMigrations = buildFamilyMigrationMetrics(sortedEntries, currentSessionId)
        val entropy = buildEntropyMetrics(sortedEntries, fragments)
        val vocabulary = buildVocabularyMetrics(sortedEntries, occurrences, families)
        val disclosure = buildDisclosure(
            entries = sortedEntries,
            baselines = baselines,
            families = families,
            fragments = fragments,
            evolution = evolution,
            phases = phases,
            creativeEpisodes = creativeEpisodes,
            genealogy = genealogy,
            languageEpochs = languageEpochs,
            phonotactics = phonotactics,
            consonantSpine = consonantSpine,
            vowelPalette = vowelPalette,
            soundShapeDrift = soundShapeDrift,
            sessionContrast = sessionContrast,
            motifRoutes = motifRoutes,
            motifAttractors = motifAttractors,
            memoryEchoes = memoryEchoes,
            constellations = constellations,
            familyMigrations = familyMigrations,
        )
        val dashboard = buildDashboard(
            general = general,
            timing = timing,
            wordStats = wordStats,
            scores = scores,
            timeline = timeline,
            baselines = baselines,
            nowUtcMs = nowUtcMs,
        )
        val sessionStoryline = buildSessionStoryline(
            dashboard = dashboard,
            entropy = entropy,
            vocabulary = vocabulary,
            rhythm = rhythm,
            phases = phases,
            creativeEpisodes = creativeEpisodes,
            languageEpochs = languageEpochs,
        )

        return WordMetrics(
            general = general,
            timing = timing,
            wordStats = wordStats,
            repeatedWords = buildRepeatedWordMetrics(occurrences),
            fragments = fragments,
            similarity = similarity,
            scores = scores,
            baselines = baselines,
            dashboard = dashboard,
            sessionStoryline = sessionStoryline,
            disclosure = disclosure,
            entropy = entropy,
            vocabulary = vocabulary,
            rhythm = rhythm,
            families = families,
            evolution = evolution,
            phases = phases,
            creativeEpisodes = creativeEpisodes,
            genealogy = genealogy,
            languageEpochs = languageEpochs,
            phonotactics = phonotactics,
            consonantSpine = consonantSpine,
            vowelPalette = vowelPalette,
            soundShapeDrift = soundShapeDrift,
            sessionContrast = sessionContrast,
            motifRoutes = motifRoutes,
            motifAttractors = motifAttractors,
            memoryEchoes = memoryEchoes,
            constellations = constellations,
            familyMigrations = familyMigrations,
            timeline = timeline,
            insights = buildInsights(dashboard, baselines, vocabulary, rhythm, disclosure),
        )
    }

    private fun buildGeneralMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
        nowUtcMs: Long,
        zoneId: ZoneId,
    ): GeneralMetrics {
        val total = entries.size
        val unique = entries.distinctBy { it.normalizedWord }.size
        val duplicateCount = total - unique
        val today = Instant.ofEpochMilli(nowUtcMs).atZone(zoneId).toLocalDate()
        val todayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val lastSevenDaysStart = nowUtcMs - (7 * ONE_DAY_MS)

        return GeneralMetrics(
            totalSubmittedWords = total,
            uniqueWords = unique,
            duplicateCount = duplicateCount,
            duplicatePercentage = total.percentOf(duplicateCount),
            todaysWords = entries.count { it.createdAtUtcMs in todayStart until tomorrowStart },
            lastSevenDaysWords = entries.count { it.createdAtUtcMs >= lastSevenDaysStart },
            currentSessionWords = entries.count { it.sessionId == currentSessionId },
        )
    }

    private fun buildTimingMetrics(entries: List<WordEntry>): TimingMetrics {
        val first = entries.first().createdAtUtcMs
        val last = entries.last().createdAtUtcMs
        val activeMinutes = maxOf((last - first).toDouble() / ONE_MINUTE_MS.toDouble(), 1.0 / 60.0)
        val intervals = intervals(entries)

        return TimingMetrics(
            wordsPerMinute = entries.size / activeMinutes,
            charactersPerMinute = entries.sumOf { it.originalWord.length } / activeMinutes,
            averageSubmissionIntervalMs = intervals.takeIf { it.isNotEmpty() }?.average(),
            longestPauseMs = intervals.maxOrNull(),
            shortestPauseMs = intervals.minOrNull(),
            fastestOneMinuteBurst = fastestOneMinuteBurst(entries),
        )
    }

    private fun fastestOneMinuteBurst(entries: List<WordEntry>): Int {
        var left = 0
        var best = 0
        entries.indices.forEach { right ->
            while (entries[right].createdAtUtcMs - entries[left].createdAtUtcMs >= ONE_MINUTE_MS) {
                left += 1
            }
            best = maxOf(best, right - left + 1)
        }
        return best
    }

    private fun buildWordStats(entries: List<WordEntry>): WordStats {
        val lengths = entries.map { it.originalWord.length }.sorted()
        val shortestLength = lengths.first()
        val longestLength = lengths.last()

        return WordStats(
            averageLength = lengths.average(),
            medianLength = lengths.medianInt(),
            shortestWords = entries.filter { it.originalWord.length == shortestLength }
                .map { it.originalWord }
                .distinct()
                .take(8),
            longestWords = entries.filter { it.originalWord.length == longestLength }
                .map { it.originalWord }
                .distinct()
                .take(8),
            lengthDistribution = lengths.groupingBy { it }
                .eachCount()
                .map { (length, count) -> LengthBucket(length, count) }
                .sortedBy { it.length },
        )
    }

    private fun buildOccurrenceSummaries(entries: List<WordEntry>): List<WordOccurrenceSummary> =
        entries.groupBy { it.normalizedWord }
            .map { (normalized, group) ->
                val byTime = group.sortedWith(compareBy<WordEntry> { it.createdAtUtcMs }.thenBy { it.id })
                WordOccurrenceSummary(
                    normalizedWord = normalized,
                    sampleOriginalWord = byTime.last().originalWord,
                    count = group.size,
                    firstOccurrenceUtcMs = byTime.first().createdAtUtcMs,
                    latestOccurrenceUtcMs = byTime.last().createdAtUtcMs,
                )
            }

    private fun buildRepeatedWordMetrics(summaries: List<WordOccurrenceSummary>): RepeatedWordMetrics =
        RepeatedWordMetrics(
            mostCommonWords = summaries.sortedWith(compareByDescending<WordOccurrenceSummary> { it.count }
                .thenByDescending { it.latestOccurrenceUtcMs }
                .thenBy { it.normalizedWord })
                .take(12),
            newestUniqueWords = summaries.filter { it.count == 1 }
                .sortedByDescending { it.latestOccurrenceUtcMs }
                .take(12),
            repeatedWords = summaries.filter { it.count > 1 }
                .sortedWith(compareByDescending<WordOccurrenceSummary> { it.latestOccurrenceUtcMs }
                    .thenBy { it.normalizedWord })
                .take(12),
        )

    private fun buildFragmentMetrics(entries: List<WordEntry>): FragmentMetrics {
        val words = entries.map { it.normalizedWord }.filter { it.isNotBlank() }
        val prefixMaps = (2..4).associateWith { length ->
            words.mapNotNull { it.takeIf { word -> word.length >= length }?.take(length) }.toFragmentCounts()
        }
        val suffixMaps = (2..4).associateWith { length ->
            words.mapNotNull { it.takeIf { word -> word.length >= length }?.takeLast(length) }.toFragmentCounts()
        }
        val ngramMaps = (2..4).associateWith { length ->
            words.flatMap { word -> word.windowedOrEmpty(length) }.toFragmentCounts()
        }
        val internal = words.flatMap { word ->
            (3..5).flatMap { length ->
                if (word.length <= length + 1) {
                    emptyList()
                } else {
                    (1..(word.length - length - 1)).map { start -> word.substring(start, start + length) }
                }
            }
        }.toFragmentCounts(limit = 20)

        val ranked = rankUsefulFragments(words)
        val lifecycles = buildFragmentLifecycles(words, ranked)

        return FragmentMetrics(
            prefixesByLength = prefixMaps,
            suffixesByLength = suffixMaps,
            letterNgramsByLength = ngramMaps,
            repeatedInternalFragments = internal,
            usefulFragments = ranked.take(24),
            newFragments = ranked.filter { it.trend == FragmentTrend.New }.take(8),
            growingFragments = ranked.filter { it.trend == FragmentTrend.Growing }.take(8),
            decliningFragments = ranked.filter { it.trend == FragmentTrend.Declining }.take(8),
            stableFragments = ranked.filter { it.trend == FragmentTrend.Stable }.take(8),
            lifecycles = lifecycles,
            ecology = buildMotifEcologyMetrics(words, lifecycles),
        )
    }

    private fun buildFragmentLifecycles(
        words: List<String>,
        rankedFragments: List<RankedFragment>,
    ): List<FragmentLifecycle> {
        if (words.size < 40 || rankedFragments.isEmpty()) return emptyList()
        val candidates = rankedFragments.take(24).map { it.fragment }.toSet()
        val countsByFragment = candidates.associateWith { IntArray(FRAGMENT_LIFECYCLE_WINDOWS) }
        words.forEachIndexed { index, word ->
            val window = ((index.toLong() * FRAGMENT_LIFECYCLE_WINDOWS) / words.size)
                .toInt()
                .coerceIn(0, FRAGMENT_LIFECYCLE_WINDOWS - 1)
            word.fragmentsForDiscovery().forEach { fragment ->
                countsByFragment[fragment]?.let { counts -> counts[window] += 1 }
            }
        }

        return rankedFragments.mapNotNull { ranked ->
            val counts = countsByFragment[ranked.fragment]?.toList().orEmpty()
            val total = counts.sum()
            if (total == 0) return@mapNotNull null
            val peakWindow = counts.withIndex()
                .maxWithOrNull(compareBy<IndexedValue<Int>> { it.value }.thenBy { -it.index })
                ?.index ?: 0
            val firstHalf = counts.take(counts.size / 2).sum()
            val secondHalf = counts.drop(counts.size / 2).sum()
            val movementScore = abs(secondHalf - firstHalf).toDouble() / total.toDouble()
            FragmentLifecycle(
                fragment = ranked.fragment,
                windowCounts = counts,
                totalDocuments = total,
                peakWindow = peakWindow,
                trend = classifyFragmentLifecycle(counts),
                movementScore = movementScore,
            )
        }.sortedWith(
            compareByDescending<FragmentLifecycle> { it.movementScore }
                .thenByDescending { it.totalDocuments }
                .thenBy { it.fragment },
        ).take(12)
    }

    private fun classifyFragmentLifecycle(counts: List<Int>): FragmentTrend {
        val splitIndex = counts.size / 2
        val firstHalfAverage = counts.take(splitIndex).averageOrZero()
        val secondHalfAverage = counts.drop(splitIndex).averageOrZero()
        return when {
            firstHalfAverage == 0.0 && secondHalfAverage > 0.0 -> FragmentTrend.New
            secondHalfAverage > firstHalfAverage * 1.25 -> FragmentTrend.Growing
            firstHalfAverage > secondHalfAverage * 1.25 -> FragmentTrend.Declining
            else -> FragmentTrend.Stable
        }
    }

    private fun buildMotifEcologyMetrics(
        words: List<String>,
        lifecycles: List<FragmentLifecycle>,
    ): MotifEcologyMetrics {
        if (lifecycles.isEmpty()) return MotifEcologyMetrics()
        val stories = lifecycles.mapNotNull { lifecycle ->
            val activeWindows = lifecycle.windowCounts.withIndex().filter { it.value > 0 }
            if (activeWindows.isEmpty()) return@mapNotNull null
            val birthWindow = activeWindows.first().index
            val lastActiveWindow = activeWindows.last().index
            val extinctionWindow = (lastActiveWindow + 1).takeIf { it < lifecycle.windowCounts.size }
            val activeSignature = lifecycle.windowCounts
                .drop(birthWindow)
                .take(lastActiveWindow - birthWindow + 1)
                .joinToString(separator = "") { count -> if (count > 0) "1" else "0" }
            val resurrected = activeSignature.contains("101")
            val status = classifyMotifEcologyStatus(
                counts = lifecycle.windowCounts,
                birthWindow = birthWindow,
                peakWindow = lifecycle.peakWindow,
                extinctionWindow = extinctionWindow,
                resurrected = resurrected,
            )
            MotifEcologyStory(
                fragment = lifecycle.fragment,
                windowCounts = lifecycle.windowCounts,
                birthWindow = birthWindow,
                peakWindow = lifecycle.peakWindow,
                extinctionWindow = extinctionWindow,
                status = status,
                interpretation = motifEcologyInterpretation(
                    fragment = lifecycle.fragment,
                    status = status,
                    birthWindow = birthWindow,
                    peakWindow = lifecycle.peakWindow,
                    extinctionWindow = extinctionWindow,
                ),
                examples = words
                    .filter { word -> word.contains(lifecycle.fragment) }
                    .distinct()
                    .take(4),
                intensity = motifEcologyIntensity(lifecycle),
            )
        }.sortedWith(
            compareByDescending<MotifEcologyStory> { it.intensity }
                .thenBy { it.birthWindow }
                .thenBy { it.fragment },
        ).take(12)

        return MotifEcologyMetrics(
            stories = stories,
            dominantStory = stories.firstOrNull(),
            narrative = stories.firstOrNull()?.let { story ->
                "${story.fragment} shows ${story.status.lowercase(Locale.US)}: ${story.interpretation}"
            },
        )
    }

    private fun classifyMotifEcologyStatus(
        counts: List<Int>,
        birthWindow: Int,
        peakWindow: Int,
        extinctionWindow: Int?,
        resurrected: Boolean,
    ): String {
        val first = counts.firstOrNull() ?: 0
        val last = counts.lastOrNull() ?: 0
        val lastIndex = counts.lastIndex
        return when {
            resurrected -> "Resurgence"
            birthWindow >= counts.size / 2 && extinctionWindow == null -> "Late birth"
            birthWindow > 0 && peakWindow >= lastIndex - 1 && extinctionWindow == null -> "Takeover"
            extinctionWindow != null && peakWindow <= counts.size / 2 -> "Extinction"
            last > first * 2 && peakWindow >= counts.size / 2 -> "Bloom"
            first > last * 2 && peakWindow <= counts.size / 2 -> "Fade"
            else -> "Stable habitat"
        }
    }

    private fun motifEcologyInterpretation(
        fragment: String,
        status: String,
        birthWindow: Int,
        peakWindow: Int,
        extinctionWindow: Int?,
    ): String {
        val birth = "born w${birthWindow + 1}"
        val peak = "peaks w${peakWindow + 1}"
        val ending = extinctionWindow?.let { ", extinct w${it + 1}" }.orEmpty()
        return when (status) {
            "Resurgence" -> "$fragment disappears and resurfaces after $birth, $peak$ending"
            "Late birth" -> "$fragment enters late and becomes visible, $birth, $peak"
            "Takeover" -> "$fragment rises toward the end, $birth, $peak"
            "Extinction" -> "$fragment dominates early then vanishes, $birth, $peak$ending"
            "Bloom" -> "$fragment grows across the run, $birth, $peak"
            "Fade" -> "$fragment fades after an early peak, $birth, $peak$ending"
            else -> "$fragment remains present across the run, $birth, $peak$ending"
        }
    }

    private fun motifEcologyIntensity(lifecycle: FragmentLifecycle): Double {
        val peakShare = lifecycle.windowCounts.maxOrNull()
            ?.let { peak -> peak.toDouble() / lifecycle.totalDocuments.coerceAtLeast(1).toDouble() }
            ?: 0.0
        return (lifecycle.movementScore * 65.0 + peakShare * 35.0).coerceIn(0.0, 100.0)
    }

    private fun rankUsefulFragments(words: List<String>): List<RankedFragment> {
        if (words.size < 20) return emptyList()
        val minimumDocumentFrequency = maxOf(3, (words.size * 0.03).roundToInt())
        val splitIndex = words.size / 2
        val allFragments = mutableMapOf<String, MutableFragmentStats>()
        words.forEachIndexed { index, word ->
            val fragmentsInWord = word.fragmentsForDiscovery()
            fragmentsInWord.forEach { fragment ->
                val stats = allFragments.getOrPut(fragment) { MutableFragmentStats() }
                stats.documentFrequency += 1
                if (index < splitIndex) {
                    stats.firstHalfDocuments += 1
                } else {
                    stats.secondHalfDocuments += 1
                }
            }
            (3..5).forEach { length ->
                word.windowedOrEmpty(length).forEach { fragment ->
                    allFragments.getOrPut(fragment) { MutableFragmentStats() }.count += 1
                }
            }
        }

        return allFragments.mapNotNull { (fragment, stats) ->
            val documentFrequency = stats.documentFrequency
            if (documentFrequency < minimumDocumentFrequency) return@mapNotNull null
            val firstRate = if (splitIndex == 0) 0.0 else stats.firstHalfDocuments.toDouble() / splitIndex.toDouble()
            val secondSize = words.size - splitIndex
            val secondRate = if (secondSize == 0) 0.0 else stats.secondHalfDocuments.toDouble() / secondSize.toDouble()
            val trend = when {
                firstRate == 0.0 && secondRate > 0.0 -> FragmentTrend.New
                secondRate > firstRate * 1.25 -> FragmentTrend.Growing
                firstRate > secondRate * 1.25 -> FragmentTrend.Declining
                else -> FragmentTrend.Stable
            }
            val lengthWeight = ln(fragment.length + 1.0)
            val coverage = documentFrequency.toDouble() / words.size.toDouble()
            val saturationPenalty = 1.0 - abs(0.5 - coverage)
            RankedFragment(
                fragment = fragment,
                count = stats.count,
                documentFrequency = documentFrequency,
                usefulness = documentFrequency * lengthWeight * saturationPenalty,
                trend = trend,
            )
        }.sortedWith(compareByDescending<RankedFragment> { it.usefulness }.thenBy { it.fragment })
    }

    private fun buildConsecutiveTransitions(entries: List<WordEntry>): List<ConsecutiveTransition> =
        entries.zipWithNext { previous, current ->
            val distance = Levenshtein.distance(previous.normalizedWord, current.normalizedWord)
            ConsecutiveTransition(
                previousOriginalWord = previous.originalWord,
                currentOriginalWord = current.originalWord,
                previousNormalizedWord = previous.normalizedWord,
                currentNormalizedWord = current.normalizedWord,
                distance = distance,
                similarity = Levenshtein.similarity(previous.normalizedWord, current.normalizedWord),
            )
        }

    private fun buildSimilarityMetrics(transitions: List<ConsecutiveTransition>): SimilarityMetrics {
        val pairs = transitions.map { transition ->
            SimilarityPair(
                previousWord = transition.previousOriginalWord,
                currentWord = transition.currentOriginalWord,
                distance = transition.distance,
                similarity = transition.similarity,
            )
        }
        if (pairs.isEmpty()) return SimilarityMetrics()

        val similarities = pairs.map { it.similarity }.sorted()
        val distances = pairs.map { it.distance }.sorted()
        return SimilarityMetrics(
            averageSimilarity = similarities.average(),
            medianSimilarity = similarities.medianDouble(),
            highestSimilarity = pairs.maxWithOrNull(compareBy<SimilarityPair> { it.similarity }.thenByDescending { it.distance }),
            lowestSimilarity = pairs.minWithOrNull(compareBy<SimilarityPair> { it.similarity }.thenBy { it.distance }),
            distribution = buildSimilarityDistribution(similarities),
            averageEditDistance = distances.average(),
            medianEditDistance = distances.medianInt(),
        )
    }

    private fun buildEvolutionMetrics(transitions: List<ConsecutiveTransition>): EvolutionMetrics {
        if (transitions.isEmpty()) return EvolutionMetrics()
        val classified = transitions.map { transition -> transition to classifyEvolution(transition) }
        val summaries = classified.groupBy { it.second }
            .map { (label, group) ->
                val transitionGroup = group.map { it.first }
                EvolutionTransition(
                    label = label,
                    count = transitionGroup.size,
                    sharePercentage = transitionGroup.size.toDouble() / transitions.size.toDouble() * 100.0,
                    averageEditDistance = transitionGroup.map { it.distance }.average(),
                    examples = transitionGroup
                        .map { "${it.previousOriginalWord} -> ${it.currentOriginalWord}" }
                        .distinct()
                        .take(3),
                )
            }
            .sortedWith(compareByDescending<EvolutionTransition> { it.count }.thenBy { it.label })

        val continuity = classified.count { it.second != "Jump" }.toDouble() / transitions.size.toDouble() * 100.0
        return EvolutionMetrics(
            transitions = summaries,
            dominantTransitionLabel = summaries.firstOrNull()?.label,
            continuityPercentage = continuity,
            averageDriftDistance = transitions.map { it.distance }.average(),
            mutationGrammar = buildMutationGrammarMetrics(transitions),
        )
    }

    private fun buildMutationGrammarMetrics(transitions: List<ConsecutiveTransition>): MutationGrammarMetrics {
        if (transitions.size < 12) return MutationGrammarMetrics()
        val events = transitions.mapIndexed { index, transition ->
            MutationOperatorEvent(
                index = index,
                label = classifyMutationOperator(transition),
                distance = transition.distance,
                example = "${transition.previousOriginalWord} -> ${transition.currentOriginalWord}",
            )
        }
        val summaries = events.groupBy { it.label }
            .map { (label, group) ->
                MutationOperatorSummary(
                    label = label,
                    count = group.size,
                    sharePercentage = group.size.toDouble() / events.size.toDouble() * 100.0,
                    averageEditDistance = group.map { it.distance }.average(),
                    interpretation = mutationOperatorInterpretation(label),
                    examples = group.map { it.example }.distinct().take(3),
                )
            }
            .sortedWith(
                compareByDescending<MutationOperatorSummary> { it.count }
                    .thenBy { it.label },
            )
        val dominant = summaries.firstOrNull()
        val choreography = buildMutationOperatorChoreography(events)
        val dominantChoreography = choreography.firstOrNull()
        return MutationGrammarMetrics(
            operators = summaries,
            segments = buildMutationGrammarSegments(events),
            choreography = choreography,
            dominantOperator = dominant?.label,
            dominantChoreography = dominantChoreography?.let { "${it.fromOperator} -> ${it.toOperator}" },
            narrative = dominant?.let { summary ->
                "${summary.label} drives ${summary.sharePercentage.formatWholePercent()} of word-to-word movement: ${summary.interpretation}"
            },
        )
    }

    private fun buildMutationOperatorChoreography(events: List<MutationOperatorEvent>): List<MutationOperatorTransition> {
        if (events.size < 2) return emptyList()
        val operatorPairs = events.zipWithNext { previous, current -> previous to current }
        val examplesByRoute = mutableMapOf<Pair<String, String>, MutableList<String>>()
        operatorPairs.forEach { (previous, current) ->
            examplesByRoute.getOrPut(previous.label to current.label) { mutableListOf() }
                .add("${previous.example}; then ${current.example}")
        }
        return operatorPairs
            .groupBy { (previous, current) -> previous.label to current.label }
            .map { (route, pairs) ->
                MutationOperatorTransition(
                    fromOperator = route.first,
                    toOperator = route.second,
                    count = pairs.size,
                    sharePercentage = pairs.size.toDouble() / operatorPairs.size.toDouble() * 100.0,
                    relationship = classifyMutationOperatorTransition(route.first, route.second),
                    examples = examplesByRoute[route].orEmpty().distinct().take(2),
                )
            }
            .sortedWith(
                compareByDescending<MutationOperatorTransition> { it.count }
                    .thenBy { it.fromOperator }
                    .thenBy { it.toOperator },
            )
            .take(12)
    }

    private fun buildMutationGrammarSegments(events: List<MutationOperatorEvent>): List<MutationGrammarSegment> {
        if (events.isEmpty()) return emptyList()
        val windowCount = minOf(
            MUTATION_GRAMMAR_WINDOWS,
            (events.size / 4).coerceAtLeast(2),
        )
        return (0 until windowCount).mapNotNull { window ->
            val start = window * events.size / windowCount
            val end = (window + 1) * events.size / windowCount
            val slice = events.subList(start, end)
            if (slice.isEmpty()) return@mapNotNull null
            val dominant = slice.groupingBy { it.label }
                .eachCount()
                .toList()
                .maxWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
                ?: return@mapNotNull null
            MutationGrammarSegment(
                startOrdinal = start + 2,
                endOrdinal = end + 1,
                dominantOperator = dominant.first,
                sharePercentage = dominant.second.toDouble() / slice.size.toDouble() * 100.0,
                example = slice.first { it.label == dominant.first }.example,
            )
        }
    }

    private fun classifyMutationOperator(transition: ConsecutiveTransition): String {
        val previous = transition.previousNormalizedWord
        val current = transition.currentNormalizedWord
        if (previous == current) return "Repeat"

        val minimumLength = minOf(previous.length, current.length)
        val sharedPrefix = commonPrefixLength(previous, current)
        val sharedSuffix = commonSuffixLength(previous, current)
        val familyThreshold = max(3, minimumLength / 2)
        return when {
            current.startsWith(previous) && current.length > previous.length -> "Suffix growth"
            previous.startsWith(current) && previous.length > current.length -> "Suffix pruning"
            current.endsWith(previous) && current.length > previous.length -> "Prefix growth"
            previous.endsWith(current) && previous.length > current.length -> "Prefix pruning"
            previous.consonantFrame() == current.consonantFrame() && previous.consonantFrame().length >= 2 -> "Vowel recoloring"
            sharedPrefix >= familyThreshold && sharedSuffix >= 2 -> "Frame mutation"
            sharedPrefix >= familyThreshold -> "Ending swap"
            sharedSuffix >= familyThreshold -> "Opening swap"
            previous.toCvSkeleton() == current.toCvSkeleton() && previous.length == current.length -> "Sound substitution"
            sharesDiscoveryFragment(previous, current) -> "Motif carryover"
            abs(current.length - previous.length) >= 3 && transition.similarity >= 0.35 -> "Size reshape"
            else -> "Open jump"
        }
    }

    private fun mutationOperatorInterpretation(label: String): String =
        when (label) {
            "Suffix growth" -> "new material is appended after an existing base"
            "Suffix pruning" -> "the word is shortened from the end"
            "Prefix growth" -> "new material is inserted before an existing base"
            "Prefix pruning" -> "the word is shortened from the beginning"
            "Vowel recoloring" -> "the consonant frame stays while vowels shift"
            "Sound substitution" -> "the sound shape stays while letters are swapped"
            "Frame mutation" -> "both the opening and ending survive while the middle changes"
            "Ending swap" -> "the opening survives while the ending is replaced"
            "Opening swap" -> "the ending survives while the opening is replaced"
            "Motif carryover" -> "a discovered motif is carried into the next form"
            "Size reshape" -> "the word expands or contracts while staying partly related"
            "Repeat" -> "the same form resurfaces immediately"
            else -> "the next word jumps to a different form-space"
        }

    private fun classifyMutationOperatorTransition(from: String, to: String): String =
        when {
            from == to -> "operator loop"
            from.contains("growth") && to.contains("pruning") -> "growth correction"
            from.contains("pruning") && to.contains("growth") -> "regrowth"
            from == "Vowel recoloring" && to.contains("swap") -> "color to frame shift"
            from.contains("swap") && to == "Vowel recoloring" -> "frame to color shift"
            to == "Open jump" -> "release into open space"
            from == "Open jump" -> "re-entry from jump"
            else -> "operator handoff"
        }

    private fun buildGenerativePhaseMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): GenerativePhaseMetrics {
        val phaseEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }
        if (phaseEntries.size < 30) return GenerativePhaseMetrics()

        val windowCount = minOf(GENERATIVE_PHASE_WINDOWS, phaseEntries.size)
        val seen = mutableSetOf<String>()
        val segments = (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * phaseEntries.size / windowCount
            val end = (windowIndex + 1) * phaseEntries.size / windowCount
            val windowEntries = phaseEntries.subList(start, end)
            if (windowEntries.isEmpty()) return@mapNotNull null

            var novel = 0
            var repeated = 0
            windowEntries.forEach { entry ->
                if (seen.add(entry.normalizedWord)) {
                    novel += 1
                } else {
                    repeated += 1
                }
            }

            val distances = (start until end).mapNotNull { index ->
                if (index == 0) {
                    null
                } else {
                    Levenshtein.distance(
                        phaseEntries[index - 1].normalizedWord,
                        phaseEntries[index].normalizedWord,
                    )
                }
            }
            val dominantFragment = dominantFragment(windowEntries)
            val dominantShare = dominantFragment?.documentSharePercentage ?: 0.0
            val noveltyPercentage = windowEntries.size.percentOf(novel)
            val repetitionPercentage = windowEntries.size.percentOf(repeated)
            val averageDistance = distances.takeIf { it.isNotEmpty() }?.average()
            val pace = phasePaceWordsPerMinute(windowEntries)
            val label = classifyGenerativePhase(
                noveltyPercentage = noveltyPercentage,
                repetitionPercentage = repetitionPercentage,
                dominantFragmentShare = dominantShare,
                averageEditDistance = averageDistance,
            )

            GenerativePhaseSegment(
                label = label,
                startOrdinal = start + 1,
                endOrdinal = end,
                dominantFragment = dominantFragment?.fragment,
                noveltyPercentage = noveltyPercentage,
                repetitionPercentage = repetitionPercentage,
                averageEditDistance = averageDistance,
                paceWordsPerMinute = pace,
                intensity = phaseIntensity(noveltyPercentage, repetitionPercentage, dominantShare, averageDistance),
            )
        }

        if (segments.isEmpty()) return GenerativePhaseMetrics()
        val dominantLabel = segments.groupBy { it.label }
            .maxWithOrNull(compareBy<Map.Entry<String, List<GenerativePhaseSegment>>> { entry ->
                entry.value.sumOf { it.endOrdinal - it.startOrdinal + 1 }
            }.thenBy { it.key })
            ?.key
        return GenerativePhaseMetrics(
            segments = segments,
            dominantLabel = dominantLabel,
            phaseSwitchCount = segments.zipWithNext().count { (previous, current) -> previous.label != current.label },
            fingerprint = buildSessionFingerprint(phaseEntries, segments),
        )
    }

    private fun classifyGenerativePhase(
        noveltyPercentage: Double,
        repetitionPercentage: Double,
        dominantFragmentShare: Double,
        averageEditDistance: Double?,
    ): String {
        val drift = averageEditDistance ?: 0.0
        return when {
            repetitionPercentage >= 35.0 -> "Repetition loop"
            dominantFragmentShare >= 60.0 && drift <= 3.0 -> "Motif lock"
            dominantFragmentShare >= 45.0 -> "Family drift"
            drift >= 6.0 && noveltyPercentage >= 75.0 -> "Wide exploration"
            drift >= 6.0 -> "Jumping"
            noveltyPercentage >= 85.0 -> "Open exploration"
            else -> "Mixed drift"
        }
    }

    private fun buildSessionFingerprint(
        entries: List<WordEntry>,
        segments: List<GenerativePhaseSegment>,
    ): List<SessionFingerprintDimension> {
        val total = entries.size
        val duplicatePercentage = duplicatePercentage(entries)
        val averageDistance = averageConsecutiveEditDistance(entries) ?: 0.0
        val topFragmentShare = dominantFragment(entries)?.documentSharePercentage ?: 0.0
        val pace = phasePaceWordsPerMinute(entries).percentileLikeScale()
        val phaseVariety = if (segments.isEmpty()) 0.0 else {
            segments.map { it.label }.distinct().size.toDouble() / segments.size.toDouble() * 100.0
        }

        return listOf(
            SessionFingerprintDimension(
                label = "Exploration",
                value = entries.distinctBy { it.normalizedWord }.size.toDouble() / total.toDouble() * 100.0,
                detail = "Unique forms in this session",
            ),
            SessionFingerprintDimension(
                label = "Motif pull",
                value = topFragmentShare,
                detail = "Largest recurring fragment footprint",
            ),
            SessionFingerprintDimension(
                label = "Drift",
                value = (averageDistance / 8.0 * 100.0).coerceIn(0.0, 100.0),
                detail = "Average consecutive edit distance",
            ),
            SessionFingerprintDimension(
                label = "Looping",
                value = duplicatePercentage.coerceIn(0.0, 100.0),
                detail = "Duplicate pressure",
            ),
            SessionFingerprintDimension(
                label = "Pace",
                value = pace.coerceIn(0.0, 100.0),
                detail = "Submission tempo",
            ),
            SessionFingerprintDimension(
                label = "Phase variety",
                value = phaseVariety.coerceIn(0.0, 100.0),
                detail = "How often the process changes mode",
            ),
        )
    }

    private fun buildCreativeEpisodeMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): CreativeEpisodeMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }
        if (sessionEntries.size < 32) return CreativeEpisodeMetrics()

        val windowCount = minOf(CREATIVE_EPISODE_WINDOWS, sessionEntries.size / 6)
            .coerceAtLeast(2)
        val seen = mutableSetOf<String>()
        val episodes = (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * sessionEntries.size / windowCount
            val end = (windowIndex + 1) * sessionEntries.size / windowCount
            val windowEntries = sessionEntries.subList(start, end)
            if (windowEntries.isEmpty()) return@mapNotNull null

            var novel = 0
            var repeated = 0
            windowEntries.forEach { entry ->
                if (seen.add(entry.normalizedWord)) {
                    novel += 1
                } else {
                    repeated += 1
                }
            }

            val distances = (start until end).mapNotNull { index ->
                if (index == 0) {
                    null
                } else {
                    Levenshtein.distance(
                        sessionEntries[index - 1].normalizedWord,
                        sessionEntries[index].normalizedWord,
                    )
                }
            }
            val dominant = dominantFragment(windowEntries)
            val dominantShare = dominant?.documentSharePercentage ?: 0.0
            val noveltyPercentage = windowEntries.size.percentOf(novel)
            val repetitionPercentage = windowEntries.size.percentOf(repeated)
            val averageDistance = distances.takeIf { it.isNotEmpty() }?.average()
            val label = classifyCreativeEpisode(
                noveltyPercentage = noveltyPercentage,
                repetitionPercentage = repetitionPercentage,
                dominantMotifShare = dominantShare,
                averageEditDistance = averageDistance,
            )

            CreativeEpisode(
                label = label,
                startOrdinal = start + 1,
                endOrdinal = end,
                noveltyPercentage = noveltyPercentage,
                repetitionPercentage = repetitionPercentage,
                averageEditDistance = averageDistance,
                dominantMotif = dominant?.fragment,
                intensity = creativeEpisodeIntensity(
                    label = label,
                    noveltyPercentage = noveltyPercentage,
                    repetitionPercentage = repetitionPercentage,
                    dominantMotifShare = dominantShare,
                    averageEditDistance = averageDistance,
                ),
                interpretation = interpretCreativeEpisode(label),
            )
        }

        if (episodes.isEmpty()) return CreativeEpisodeMetrics()
        val highlighted = episodes
            .filterNot { it.label == "Quiet weave" }
            .maxWithOrNull(compareBy<CreativeEpisode> { it.intensity }.thenBy { it.startOrdinal })
            ?: episodes.maxWithOrNull(compareBy<CreativeEpisode> { it.intensity }.thenBy { it.startOrdinal })

        return CreativeEpisodeMetrics(
            episodes = episodes,
            highlightedEpisode = highlighted,
            creativePressure = episodes.map { episode ->
                val drift = ((episode.averageEditDistance ?: 0.0) / 8.0 * 100.0).coerceIn(0.0, 100.0)
                (episode.noveltyPercentage * 0.55 + drift * 0.45).coerceIn(0.0, 100.0)
            }.average(),
            repetitivePressure = episodes.map { it.repetitionPercentage }.average(),
        )
    }

    private fun classifyCreativeEpisode(
        noveltyPercentage: Double,
        repetitionPercentage: Double,
        dominantMotifShare: Double,
        averageEditDistance: Double?,
    ): String {
        val drift = averageEditDistance ?: 0.0
        return when {
            repetitionPercentage >= 55.0 -> "Fixation pocket"
            noveltyPercentage >= 85.0 && drift >= 5.0 -> "Invention burst"
            noveltyPercentage >= 75.0 && drift >= 3.5 -> "Mutation run"
            dominantMotifShare >= 55.0 && noveltyPercentage >= 55.0 -> "Motif forging"
            repetitionPercentage >= 35.0 && drift <= 2.5 -> "Settled groove"
            dominantMotifShare >= 55.0 -> "Motif orbit"
            drift >= 5.0 -> "Wild crossing"
            noveltyPercentage >= 75.0 -> "Open drift"
            else -> "Quiet weave"
        }
    }

    private fun creativeEpisodeIntensity(
        label: String,
        noveltyPercentage: Double,
        repetitionPercentage: Double,
        dominantMotifShare: Double,
        averageEditDistance: Double?,
    ): Double {
        val drift = ((averageEditDistance ?: 0.0) / 8.0 * 100.0).coerceIn(0.0, 100.0)
        return when (label) {
            "Fixation pocket",
            "Settled groove" -> maxOf(repetitionPercentage, dominantMotifShare)
            "Motif forging",
            "Motif orbit" -> maxOf(dominantMotifShare, noveltyPercentage)
            "Invention burst",
            "Mutation run",
            "Wild crossing",
            "Open drift" -> maxOf(noveltyPercentage, drift)
            else -> maxOf(noveltyPercentage, repetitionPercentage, dominantMotifShare, drift)
        }.coerceIn(0.0, 100.0)
    }

    private fun interpretCreativeEpisode(label: String): String =
        when (label) {
            "Invention burst" -> "new forms with large leaps"
            "Mutation run" -> "new forms mutating around the edge"
            "Motif forging" -> "a motif is being established"
            "Fixation pocket" -> "the process is looping on known forms"
            "Settled groove" -> "stable reuse with low drift"
            "Motif orbit" -> "movement stays inside one motif"
            "Wild crossing" -> "large jumps without a stable motif"
            "Open drift" -> "mostly new forms with gentle movement"
            else -> "mixed movement without one dominant pull"
        }

    private fun buildWordGenealogyMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): WordGenealogyMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }
        val uniqueEntries = sessionEntries
            .takeLast(WORD_GENEALOGY_RECENT_ENTRIES)
            .distinctBy { it.normalizedWord }
            .takeLast(WORD_GENEALOGY_UNIQUE_LIMIT)
        if (uniqueEntries.size < 12) return WordGenealogyMetrics()

        val nodes = uniqueEntries.mapIndexed { index, entry ->
            GenealogyNode(
                index = index,
                originalWord = entry.originalWord,
                normalizedWord = entry.normalizedWord,
            )
        }
        val rootByIndex = mutableMapOf<Int, Int>()
        val depthByIndex = mutableMapOf<Int, Int>()
        val links = mutableListOf<InternalGenealogyLink>()

        nodes.forEach { node ->
            val parent = findGenealogyParent(nodes, node.index)
            if (parent == null) {
                rootByIndex[node.index] = node.index
                depthByIndex[node.index] = 0
            } else {
                rootByIndex[node.index] = rootByIndex[parent.parentIndex] ?: parent.parentIndex
                depthByIndex[node.index] = (depthByIndex[parent.parentIndex] ?: 0) + 1
                links += InternalGenealogyLink(
                    parentIndex = parent.parentIndex,
                    childIndex = node.index,
                    distance = parent.distance,
                    relationship = parent.relationship,
                    ordinal = node.index + 1,
                )
            }
        }

        val rootCount = rootByIndex.values.toSet().size
        if (links.isEmpty()) {
            return WordGenealogyMetrics(rootCount = rootCount)
        }

        val lineages = rootByIndex.entries
            .groupBy({ it.value }, { it.key })
            .mapNotNull { (rootIndex, indices) ->
                val sortedIndices = indices.sorted()
                val lineageLinks = links.filter { rootByIndex[it.childIndex] == rootIndex }
                if (sortedIndices.size < 2 || lineageLinks.isEmpty()) return@mapNotNull null
                WordLineage(
                    rootWord = nodes[rootIndex].originalWord,
                    words = sortedIndices.map { nodes[it].originalWord }.take(12),
                    depth = sortedIndices.maxOf { depthByIndex[it] ?: 0 },
                    branchCount = lineageLinks.size,
                    averageParentDistance = lineageLinks.map { it.distance }.average(),
                )
            }
            .sortedWith(
                compareByDescending<WordLineage> { it.branchCount }
                    .thenByDescending { it.depth }
                    .thenBy { it.rootWord },
            )
            .take(8)

        val branchingWord = links
            .groupingBy { it.parentIndex }
            .eachCount()
            .entries
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { -it.key })
            ?.takeIf { it.value >= 2 }
            ?.let { nodes[it.key].originalWord }

        val publicLinks = links
            .sortedBy { it.ordinal }
            .takeLast(18)
            .map { link ->
                WordGenealogyLink(
                    parentWord = nodes[link.parentIndex].originalWord,
                    childWord = nodes[link.childIndex].originalWord,
                    distance = link.distance,
                    ordinal = link.ordinal,
                    relationship = link.relationship,
                )
            }

        return WordGenealogyMetrics(
            lineages = lineages,
            links = publicLinks,
            rootCount = rootCount,
            branchingWord = branchingWord,
            ancestryContinuityPercentage = links.size.toDouble() / (nodes.size - 1).toDouble() * 100.0,
        )
    }

    private fun findGenealogyParent(
        nodes: List<GenealogyNode>,
        childIndex: Int,
    ): GenealogyCandidate? {
        if (childIndex == 0) return null
        val child = nodes[childIndex]
        val startIndex = maxOf(0, childIndex - WORD_GENEALOGY_PARENT_LOOKBACK)
        return (startIndex until childIndex).mapNotNull { parentIndex ->
            val parent = nodes[parentIndex]
            val distance = Levenshtein.distance(parent.normalizedWord, child.normalizedWord)
            val sharedPrefix = commonPrefixLength(parent.normalizedWord, child.normalizedWord)
            val sharedSuffix = commonSuffixLength(parent.normalizedWord, child.normalizedWord)
            val sharesMotif = sharedPrefix >= 3 ||
                sharedSuffix >= 3 ||
                sharesDiscoveryFragment(parent.normalizedWord, child.normalizedWord)
            val distanceLimit = genealogyDistanceLimit(child.normalizedWord)
            val motifDistanceLimit = maxOf(
                distanceLimit,
                (maxOf(parent.normalizedWord.length, child.normalizedWord.length) * 0.55).roundToInt(),
            )
            if (distance > distanceLimit && !(sharesMotif && distance <= motifDistanceLimit)) {
                return@mapNotNull null
            }
            GenealogyCandidate(
                parentIndex = parentIndex,
                distance = distance,
                sharedPrefix = sharedPrefix,
                sharedSuffix = sharedSuffix,
                relationship = classifyGenealogyRelationship(
                    parent = parent.normalizedWord,
                    child = child.normalizedWord,
                    distance = distance,
                    sharedPrefix = sharedPrefix,
                    sharedSuffix = sharedSuffix,
                ),
            )
        }.minWithOrNull(
            compareBy<GenealogyCandidate> { it.distance }
                .thenByDescending { it.sharedPrefix }
                .thenByDescending { it.sharedSuffix }
                .thenByDescending { it.parentIndex },
        )
    }

    private fun genealogyDistanceLimit(word: String): Int =
        maxOf(1, minOf(5, (word.length * 0.35).roundToInt()))

    private fun classifyGenealogyRelationship(
        parent: String,
        child: String,
        distance: Int,
        sharedPrefix: Int,
        sharedSuffix: Int,
    ): String =
        when {
            distance == 0 -> "Recurrence"
            child.startsWith(parent) && child.length > parent.length -> "Stem extension"
            parent.startsWith(child) && parent.length > child.length -> "Stem contraction"
            sharedPrefix >= 3 && sharedSuffix >= 2 -> "Frame mutation"
            sharedPrefix >= 3 -> "Prefix branch"
            sharedSuffix >= 3 -> "Suffix branch"
            distance <= 2 -> "Close mutation"
            else -> "Motif echo"
        }

    private fun buildLanguageEpochMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): LanguageEpochMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }
        if (sessionEntries.size < 48) return LanguageEpochMetrics()

        val windowCount = minOf(LANGUAGE_EPOCH_WINDOWS, sessionEntries.size / 6).coerceAtLeast(2)
        val seen = mutableSetOf<String>()
        val epochProfiles = (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * sessionEntries.size / windowCount
            val end = (windowIndex + 1) * sessionEntries.size / windowCount
            val windowEntries = sessionEntries.subList(start, end)
            if (windowEntries.isEmpty()) return@mapNotNull null

            var novel = 0
            var repeated = 0
            windowEntries.forEach { entry ->
                if (seen.add(entry.normalizedWord)) {
                    novel += 1
                } else {
                    repeated += 1
                }
            }

            val profile = buildEpochFragmentProfile(windowEntries)
            val distances = (start until end).mapNotNull { index ->
                if (index == 0) {
                    null
                } else {
                    Levenshtein.distance(
                        sessionEntries[index - 1].normalizedWord,
                        sessionEntries[index].normalizedWord,
                    )
                }
            }
            val noveltyPercentage = windowEntries.size.percentOf(novel)
            val repetitionPercentage = windowEntries.size.percentOf(repeated)
            val averageDistance = distances.takeIf { it.isNotEmpty() }?.average()
            val averageLength = windowEntries.map { it.normalizedWord.length }.average()
            val label = classifyLanguageEpoch(
                dominantMotif = profile.dominantFragment,
                dominantMotifShare = profile.dominantSharePercentage,
                noveltyPercentage = noveltyPercentage,
                repetitionPercentage = repetitionPercentage,
                averageEditDistance = averageDistance,
            )

            InternalLanguageEpochProfile(
                epoch = LanguageEpoch(
                    label = label,
                    startOrdinal = start + 1,
                    endOrdinal = end,
                    dominantMotif = profile.dominantFragment,
                    dominantMotifSharePercentage = profile.dominantSharePercentage,
                    noveltyPercentage = noveltyPercentage,
                    repetitionPercentage = repetitionPercentage,
                    averageEditDistance = averageDistance,
                    averageWordLength = averageLength,
                    intensity = languageEpochIntensity(profile.dominantSharePercentage, noveltyPercentage, repetitionPercentage, averageDistance),
                ),
                fragmentProfile = profile.fragmentProfile,
            )
        }

        if (epochProfiles.size < 2) return LanguageEpochMetrics()
        val shifts = epochProfiles.zipWithNext { previous, current ->
            val motifSimilarity = weightedJaccard(previous.fragmentProfile, current.fragmentProfile)
            val motifDistance = 1.0 - motifSimilarity
            val previousEpoch = previous.epoch
            val currentEpoch = current.epoch
            val motifChanged = previousEpoch.dominantMotif != currentEpoch.dominantMotif
            val noveltyDelta = currentEpoch.noveltyPercentage - previousEpoch.noveltyPercentage
            val repetitionDelta = currentEpoch.repetitionPercentage - previousEpoch.repetitionPercentage
            val driftDelta = (currentEpoch.averageEditDistance ?: 0.0) - (previousEpoch.averageEditDistance ?: 0.0)
            val intensity = (
                motifDistance * 45.0 +
                    (if (motifChanged) 20.0 else 0.0) +
                    (abs(noveltyDelta) / 100.0 * 15.0) +
                    (abs(repetitionDelta) / 100.0 * 10.0) +
                    (abs(driftDelta) / 8.0 * 10.0)
                ).coerceIn(0.0, 100.0)
            LanguageShift(
                fromLabel = previousEpoch.label,
                toLabel = currentEpoch.label,
                boundaryOrdinal = currentEpoch.startOrdinal,
                intensity = intensity,
                motifChange = "${previousEpoch.dominantMotif ?: "open"} -> ${currentEpoch.dominantMotif ?: "open"}",
                noveltyDeltaPercentage = noveltyDelta,
                driftDelta = driftDelta,
                label = classifyLanguageShift(motifChanged, noveltyDelta, repetitionDelta, driftDelta, intensity),
            )
        }.filter { shift -> shift.intensity >= 18.0 }

        if (shifts.isEmpty()) {
            return LanguageEpochMetrics(
                epochs = epochProfiles.map { it.epoch },
                averageShiftIntensity = 0.0,
            )
        }

        return LanguageEpochMetrics(
            epochs = epochProfiles.map { it.epoch },
            shifts = shifts.sortedByDescending { it.intensity }.take(8),
            strongestShift = shifts.maxByOrNull { it.intensity },
            averageShiftIntensity = shifts.map { it.intensity }.average(),
        )
    }

    private fun buildEpochFragmentProfile(entries: List<WordEntry>): EpochFragmentProfile {
        val counts = mutableMapOf<String, Int>()
        entries.forEach { entry ->
            entry.normalizedWord.fragmentsForDiscovery().forEach { fragment ->
                counts[fragment] = (counts[fragment] ?: 0) + 1
            }
        }
        val dominant = counts.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key.length }
                .thenBy { it.key },
        )
        return EpochFragmentProfile(
            fragmentProfile = counts
                .filter { it.value >= 2 }
                .toList()
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .take(24)
                .toMap(),
            dominantFragment = dominant?.key,
            dominantSharePercentage = dominant?.let { it.value.toDouble() / entries.size.toDouble() * 100.0 } ?: 0.0,
        )
    }

    private fun classifyLanguageEpoch(
        dominantMotif: String?,
        dominantMotifShare: Double,
        noveltyPercentage: Double,
        repetitionPercentage: Double,
        averageEditDistance: Double?,
    ): String {
        val drift = averageEditDistance ?: 0.0
        return when {
            repetitionPercentage >= 35.0 -> "Loop epoch"
            dominantMotif != null && dominantMotifShare >= 45.0 -> "$dominantMotif epoch"
            drift >= 5.0 && noveltyPercentage >= 75.0 -> "Exploration epoch"
            drift >= 5.0 -> "Bridge epoch"
            noveltyPercentage >= 85.0 -> "Open epoch"
            else -> "Mixed epoch"
        }
    }

    private fun classifyLanguageShift(
        motifChanged: Boolean,
        noveltyDelta: Double,
        repetitionDelta: Double,
        driftDelta: Double,
        intensity: Double,
    ): String =
        when {
            motifChanged && intensity >= 45.0 -> "Motif replacement"
            repetitionDelta >= 25.0 -> "Loop onset"
            repetitionDelta <= -25.0 -> "Loop release"
            noveltyDelta >= 25.0 -> "Exploration burst"
            noveltyDelta <= -25.0 -> "Exploration collapse"
            driftDelta >= 2.0 -> "Drift jump"
            driftDelta <= -2.0 -> "Drift settling"
            motifChanged -> "Motif handoff"
            else -> "Texture shift"
        }

    private fun languageEpochIntensity(
        dominantMotifShare: Double,
        noveltyPercentage: Double,
        repetitionPercentage: Double,
        averageEditDistance: Double?,
    ): Double {
        val motifPull = dominantMotifShare.coerceIn(0.0, 100.0)
        val loopPull = repetitionPercentage.coerceIn(0.0, 100.0)
        val explorationPull = noveltyPercentage.coerceIn(0.0, 100.0)
        val driftPull = ((averageEditDistance ?: 0.0) / 8.0 * 100.0).coerceIn(0.0, 100.0)
        return maxOf(motifPull, loopPull, explorationPull, driftPull)
    }

    private fun weightedJaccard(left: Map<String, Int>, right: Map<String, Int>): Double {
        if (left.isEmpty() && right.isEmpty()) return 1.0
        val keys = left.keys + right.keys
        val intersection = keys.sumOf { key -> minOf(left[key] ?: 0, right[key] ?: 0) }
        val union = keys.sumOf { key -> maxOf(left[key] ?: 0, right[key] ?: 0) }
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    private fun buildPhonotacticMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): PhonotacticMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }.filter { it.normalizedWord.length >= 3 }
        if (sessionEntries.size < PHONOTACTIC_MIN_WORDS) return PhonotacticMetrics()

        val lanes = listOfNotNull(
            buildPhonotacticLane("Onset", sessionEntries, ::onsetFragmentsForPhonotactics),
            buildPhonotacticLane("Core", sessionEntries, ::coreFragmentsForPhonotactics),
            buildPhonotacticLane("Coda", sessionEntries, ::codaFragmentsForPhonotactics),
        )
        val transitions = buildPhonotacticTransitions(sessionEntries)
        val skeletons = buildPhonotacticSkeletons(sessionEntries)
        val rules = buildPhonotacticRules(sessionEntries, lanes, transitions, skeletons)
        val strongestMotif = lanes
            .flatMap { lane -> lane.motifs.map { motif -> lane.role to motif } }
            .maxWithOrNull(compareBy<Pair<String, PhonotacticMotif>> { it.second.strength }.thenBy { it.first })

        return PhonotacticMetrics(
            lanes = lanes,
            transitions = transitions,
            skeletons = skeletons,
            rules = rules,
            dominantRule = strongestMotif?.let { (role, motif) ->
                "$role ${motif.motif} shapes ${motif.coveragePercentage.formatWholePercent()} of this session"
            },
        )
    }

    private fun buildConsonantSpineMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): ConsonantSpineMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }.filter { entry -> entry.normalizedWord.consonantFrame().length >= 2 }
        if (sessionEntries.size < CONSONANT_SPINE_MIN_WORDS) return ConsonantSpineMetrics()

        val windowCount = minOf(
            CONSONANT_SPINE_WINDOWS,
            (sessionEntries.size / 4).coerceAtLeast(2),
        )
        val segments = (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * sessionEntries.size / windowCount
            val endExclusive = (windowIndex + 1) * sessionEntries.size / windowCount
            val window = sessionEntries.subList(start, endExclusive)
            if (window.isEmpty()) {
                null
            } else {
                val spineCounts = window
                    .groupingBy { entry -> entry.normalizedWord.consonantFrame() }
                    .eachCount()
                val dominant = spineCounts.entries.maxWithOrNull(
                    compareBy<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { it.key.length }
                        .thenBy { it.key },
                ) ?: return@mapNotNull null
                val dominantExamples = window
                    .asSequence()
                    .filter { entry -> entry.normalizedWord.consonantFrame() == dominant.key }
                    .map { it.originalWord }
                    .distinct()
                    .take(4)
                    .toList()
                val averageVowelSlots = window.map { entry ->
                    entry.normalizedWord.count { it.isVowel() }
                }.average()

                ConsonantSpineSegment(
                    startOrdinal = start + 1,
                    endOrdinal = endExclusive,
                    dominantSpine = dominant.key,
                    sharePercentage = window.size.percentOf(dominant.value),
                    averageVowelSlots = averageVowelSlots,
                    examples = dominantExamples,
                )
            }
        }
        if (segments.isEmpty()) return ConsonantSpineMetrics()

        val shifts = segments.zipWithNext().mapNotNull { (previous, current) ->
            val shareDelta = current.sharePercentage - previous.sharePercentage
            val vowelSlotDelta = current.averageVowelSlots - previous.averageVowelSlots
            val distance = Levenshtein.distance(previous.dominantSpine, current.dominantSpine)
            val scale = maxOf(previous.dominantSpine.length, current.dominantSpine.length).coerceAtLeast(1)
            val intensity = (
                distance.toDouble() / scale.toDouble() * 55.0 +
                    (abs(shareDelta) / 100.0 * 25.0).coerceAtMost(25.0) +
                    (abs(vowelSlotDelta) / 4.0 * 20.0).coerceAtMost(20.0)
                ).coerceIn(0.0, 100.0)

            if (
                previous.dominantSpine == current.dominantSpine &&
                abs(shareDelta) < 18.0 &&
                abs(vowelSlotDelta) < 0.75
            ) {
                null
            } else {
                ConsonantSpineShift(
                    fromSpine = previous.dominantSpine,
                    toSpine = current.dominantSpine,
                    boundaryOrdinal = current.startOrdinal,
                    intensity = intensity,
                    relationship = classifyConsonantSpineShift(previous, current, shareDelta, vowelSlotDelta, distance),
                )
            }
        }
        val dominantSpine = segments
            .groupBy { it.dominantSpine }
            .maxWithOrNull(
                compareBy<Map.Entry<String, List<ConsonantSpineSegment>>> { (_, groupedSegments) ->
                    groupedSegments.sumOf { it.endOrdinal - it.startOrdinal + 1 }
                }.thenBy { it.key },
            )
            ?.key
        val strongestShift = shifts.maxWithOrNull(compareBy<ConsonantSpineShift> { it.intensity }.thenBy { it.boundaryOrdinal })

        return ConsonantSpineMetrics(
            segments = segments,
            shifts = shifts.sortedWith(compareByDescending<ConsonantSpineShift> { it.intensity }.thenBy { it.boundaryOrdinal }),
            dominantSpine = dominantSpine,
            strongestShift = strongestShift,
            narrative = consonantSpineNarrative(segments, strongestShift, dominantSpine),
        )
    }

    private fun classifyConsonantSpineShift(
        previous: ConsonantSpineSegment,
        current: ConsonantSpineSegment,
        shareDelta: Double,
        vowelSlotDelta: Double,
        distance: Int,
    ): String {
        if (previous.dominantSpine == current.dominantSpine) {
            return when {
                shareDelta >= 18.0 -> "spine lock-in"
                shareDelta <= -18.0 -> "spine release"
                vowelSlotDelta >= 0.75 -> "vowel filling"
                vowelSlotDelta <= -0.75 -> "vowel thinning"
                else -> "spine persistence"
            }
        }

        val previousSpine = previous.dominantSpine
        val currentSpine = current.dominantSpine
        val sharedPrefix = commonPrefixLength(previousSpine, currentSpine)
        val sharedSuffix = commonSuffixLength(previousSpine, currentSpine)
        return when {
            currentSpine.length > previousSpine.length && (currentSpine.startsWith(previousSpine) || currentSpine.endsWith(previousSpine)) ->
                "spine extension"
            previousSpine.length > currentSpine.length && (previousSpine.startsWith(currentSpine) || previousSpine.endsWith(currentSpine)) ->
                "spine pruning"
            sharedPrefix >= 2 && sharedSuffix < 2 -> "tail mutation"
            sharedSuffix >= 2 && sharedPrefix < 2 -> "head mutation"
            sharedPrefix >= 2 || sharedSuffix >= 2 -> "spine mutation"
            distance <= 1 -> "consonant substitution"
            distance <= 2 -> "consonant drift"
            else -> "spine replacement"
        }
    }

    private fun consonantSpineNarrative(
        segments: List<ConsonantSpineSegment>,
        strongestShift: ConsonantSpineShift?,
        dominantSpine: String?,
    ): String? =
        when {
            strongestShift != null ->
                "${strongestShift.fromSpine} gives way to ${strongestShift.toSpine} near word ${strongestShift.boundaryOrdinal}: ${strongestShift.relationship}"
            dominantSpine != null ->
                "$dominantSpine anchors the consonant frame across ${segments.size} windows"
            else -> null
        }

    private fun buildVowelPaletteMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): VowelPaletteMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }.filter { entry -> entry.normalizedWord.any { it.isVowel() } }
        if (sessionEntries.size < VOWEL_PALETTE_MIN_WORDS) return VowelPaletteMetrics()

        val windowCount = minOf(
            VOWEL_PALETTE_WINDOWS,
            (sessionEntries.size / 4).coerceAtLeast(2),
        )
        val segments = (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * sessionEntries.size / windowCount
            val endExclusive = (windowIndex + 1) * sessionEntries.size / windowCount
            val window = sessionEntries.subList(start, endExclusive)
            val counts = vowelCounts(window)
            val totalVowels = counts.values.sum()
            if (window.isEmpty() || totalVowels == 0) {
                null
            } else {
                val dominant = counts.entries.maxWithOrNull(
                    compareBy<Map.Entry<Char, Int>> { it.value }.thenBy { it.key },
                ) ?: return@mapNotNull null
                val shares = vowelInventory().map { vowel ->
                    VowelShare(
                        vowel = vowel.toString(),
                        percentage = totalVowels.percentOf(counts[vowel] ?: 0),
                    )
                }
                val examples = window
                    .asSequence()
                    .filter { entry -> entry.normalizedWord.any { it.lowercaseChar() == dominant.key } }
                    .map { it.originalWord }
                    .distinct()
                    .take(4)
                    .toList()

                VowelPaletteSegment(
                    startOrdinal = start + 1,
                    endOrdinal = endExclusive,
                    dominantVowel = dominant.key.toString(),
                    paletteLabel = classifyVowelPalette(counts, totalVowels),
                    dominantSharePercentage = totalVowels.percentOf(dominant.value),
                    shares = shares,
                    examples = examples,
                )
            }
        }
        if (segments.isEmpty()) return VowelPaletteMetrics()

        val shifts = segments.zipWithNext().mapNotNull { (previous, current) ->
            val distance = vowelDistributionDistance(previous, current)
            val shareDelta = current.dominantSharePercentage - previous.dominantSharePercentage
            if (previous.paletteLabel == current.paletteLabel && distance < 20.0 && abs(shareDelta) < 18.0) {
                null
            } else {
                VowelPaletteShift(
                    fromPalette = previous.paletteLabel,
                    toPalette = current.paletteLabel,
                    boundaryOrdinal = current.startOrdinal,
                    intensity = (distance * 0.75 + if (previous.paletteLabel == current.paletteLabel) 0.0 else 25.0)
                        .coerceIn(0.0, 100.0),
                    relationship = classifyVowelPaletteShift(previous, current, shareDelta),
                )
            }
        }
        val dominantPalette = segments
            .groupBy { it.paletteLabel }
            .maxWithOrNull(
                compareBy<Map.Entry<String, List<VowelPaletteSegment>>> { (_, groupedSegments) ->
                    groupedSegments.sumOf { it.endOrdinal - it.startOrdinal + 1 }
                }.thenBy { it.key },
            )
            ?.key
        val strongestShift = shifts.maxWithOrNull(compareBy<VowelPaletteShift> { it.intensity }.thenBy { it.boundaryOrdinal })

        return VowelPaletteMetrics(
            segments = segments,
            shifts = shifts.sortedWith(compareByDescending<VowelPaletteShift> { it.intensity }.thenBy { it.boundaryOrdinal }),
            dominantPalette = dominantPalette,
            strongestShift = strongestShift,
            narrative = vowelPaletteNarrative(segments, strongestShift, dominantPalette),
        )
    }

    private fun vowelCounts(entries: List<WordEntry>): Map<Char, Int> {
        val counts = vowelInventory().associateWith { 0 }.toMutableMap()
        entries.forEach { entry ->
            entry.normalizedWord.forEach { char ->
                val vowel = char.lowercaseChar()
                if (vowel.isVowel()) {
                    counts[vowel] = counts.getValue(vowel) + 1
                }
            }
        }
        return counts
    }

    private fun classifyVowelPalette(counts: Map<Char, Int>, totalVowels: Int): String {
        if (totalVowels == 0) return "silent frame"
        val share = { vowel: Char -> totalVowels.percentOf(counts[vowel] ?: 0) }
        val frontShare = share('e') + share('i')
        val roundShare = share('o') + share('u')
        val openShare = share('a')
        val dominant = counts.entries.maxWithOrNull(
            compareBy<Map.Entry<Char, Int>> { it.value }.thenBy { it.key },
        )?.key ?: return "silent frame"
        val dominantShare = share(dominant)

        return when {
            frontShare >= 55.0 -> "front-bright"
            roundShare >= 55.0 -> "round-back"
            openShare >= 45.0 -> "open-a"
            dominantShare < 35.0 -> "mixed wash"
            dominant == 'i' -> "high-i"
            dominant == 'e' -> "clear-e"
            dominant == 'o' -> "deep-o"
            dominant == 'u' -> "dark-u"
            else -> "open-a"
        }
    }

    private fun vowelDistributionDistance(previous: VowelPaletteSegment, current: VowelPaletteSegment): Double {
        val left = previous.shares.associate { it.vowel to it.percentage }
        val right = current.shares.associate { it.vowel to it.percentage }
        return vowelInventory().sumOf { vowel ->
            abs((right[vowel.toString()] ?: 0.0) - (left[vowel.toString()] ?: 0.0))
        } / 2.0
    }

    private fun classifyVowelPaletteShift(
        previous: VowelPaletteSegment,
        current: VowelPaletteSegment,
        shareDelta: Double,
    ): String =
        when {
            previous.paletteLabel == current.paletteLabel && shareDelta >= 18.0 -> "palette lock-in"
            previous.paletteLabel == current.paletteLabel && shareDelta <= -18.0 -> "palette release"
            previous.paletteLabel == current.paletteLabel -> "palette persistence"
            previous.paletteLabel == "front-bright" && current.paletteLabel == "round-back" -> "front to round drift"
            previous.paletteLabel == "round-back" && current.paletteLabel == "front-bright" -> "round to front drift"
            previous.paletteLabel == "open-a" && current.paletteLabel == "round-back" -> "open to round drift"
            previous.paletteLabel == "round-back" && current.paletteLabel == "open-a" -> "round to open drift"
            current.paletteLabel == "open-a" -> "opening"
            previous.paletteLabel == "open-a" -> "closing"
            previous.paletteLabel == "mixed wash" -> "palette crystallization"
            current.paletteLabel == "mixed wash" -> "palette diffusion"
            previous.dominantVowel == current.dominantVowel -> "vowel shade shift"
            else -> "palette replacement"
        }

    private fun vowelPaletteNarrative(
        segments: List<VowelPaletteSegment>,
        strongestShift: VowelPaletteShift?,
        dominantPalette: String?,
    ): String? =
        when {
            strongestShift != null ->
                "${strongestShift.fromPalette} gives way to ${strongestShift.toPalette} near word ${strongestShift.boundaryOrdinal}: ${strongestShift.relationship}"
            dominantPalette != null ->
                "$dominantPalette is the recurring vowel color across ${segments.size} windows"
            else -> null
        }

    private fun buildSoundShapeDriftMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): SoundShapeDriftMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }.filter { it.normalizedWord.length >= 3 }
        if (sessionEntries.size < SOUND_SHAPE_DRIFT_MIN_WORDS) return SoundShapeDriftMetrics()

        val windowCount = minOf(
            SOUND_SHAPE_DRIFT_WINDOWS,
            (sessionEntries.size / 5).coerceAtLeast(2),
        )
        val segments = (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * sessionEntries.size / windowCount
            val endExclusive = (windowIndex + 1) * sessionEntries.size / windowCount
            val window = sessionEntries.subList(start, endExclusive)
            if (window.isEmpty()) {
                null
            } else {
                val skeletonCounts = window
                    .groupingBy { entry -> entry.normalizedWord.toCvSkeleton() }
                    .eachCount()
                val dominant = skeletonCounts.entries.maxWithOrNull(
                    compareBy<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { it.key.length }
                        .thenBy { it.key },
                ) ?: return@mapNotNull null
                val dominantExamples = window
                    .asSequence()
                    .filter { it.normalizedWord.toCvSkeleton() == dominant.key }
                    .map { it.originalWord }
                    .distinct()
                    .take(4)
                    .toList()

                SoundShapeSegment(
                    startOrdinal = start + 1,
                    endOrdinal = endExclusive,
                    dominantSkeleton = dominant.key,
                    sharePercentage = window.size.percentOf(dominant.value),
                    averageLength = window.map { it.normalizedWord.length }.average(),
                    examples = dominantExamples,
                )
            }
        }
        if (segments.isEmpty()) return SoundShapeDriftMetrics()

        val shifts = segments.zipWithNext().mapNotNull { (previous, current) ->
            val shareDelta = current.sharePercentage - previous.sharePercentage
            val lengthDelta = current.averageLength - previous.averageLength
            val skeletonDistance = Levenshtein.distance(previous.dominantSkeleton, current.dominantSkeleton)
            val skeletonScale = maxOf(previous.dominantSkeleton.length, current.dominantSkeleton.length).coerceAtLeast(1)
            val intensity = (
                skeletonDistance.toDouble() / skeletonScale.toDouble() * 45.0 +
                    (abs(lengthDelta) / 6.0 * 25.0).coerceAtMost(25.0) +
                    (abs(shareDelta) / 100.0 * 30.0).coerceAtMost(30.0)
                ).coerceIn(0.0, 100.0)

            if (
                previous.dominantSkeleton == current.dominantSkeleton &&
                abs(shareDelta) < 18.0 &&
                abs(lengthDelta) < 1.0
            ) {
                null
            } else {
                SoundShapeShift(
                    fromSkeleton = previous.dominantSkeleton,
                    toSkeleton = current.dominantSkeleton,
                    boundaryOrdinal = current.startOrdinal,
                    intensity = intensity,
                    relationship = classifySoundShapeShift(
                        previous = previous,
                        current = current,
                        shareDelta = shareDelta,
                        lengthDelta = lengthDelta,
                        skeletonDistance = skeletonDistance,
                    ),
                )
            }
        }
        val dominantShape = segments
            .groupBy { it.dominantSkeleton }
            .maxWithOrNull(
                compareBy<Map.Entry<String, List<SoundShapeSegment>>> { (_, groupedSegments) ->
                    groupedSegments.sumOf { it.endOrdinal - it.startOrdinal + 1 }
                }.thenBy { it.key },
            )
            ?.key
        val stableLinks = segments.zipWithNext().count { (previous, current) ->
            previous.dominantSkeleton == current.dominantSkeleton
        }
        val stability = if (segments.size < 2) {
            null
        } else {
            (segments.size - 1).percentOf(stableLinks)
        }

        return SoundShapeDriftMetrics(
            segments = segments,
            shifts = shifts.sortedWith(compareByDescending<SoundShapeShift> { it.intensity }.thenBy { it.boundaryOrdinal }),
            dominantShape = dominantShape,
            shapeStabilityPercentage = stability,
            narrative = soundShapeDriftNarrative(segments, shifts, dominantShape, stability),
        )
    }

    private fun classifySoundShapeShift(
        previous: SoundShapeSegment,
        current: SoundShapeSegment,
        shareDelta: Double,
        lengthDelta: Double,
        skeletonDistance: Int,
    ): String {
        if (previous.dominantSkeleton == current.dominantSkeleton) {
            return when {
                shareDelta >= 18.0 -> "shape lock-in"
                shareDelta <= -18.0 -> "shape release"
                lengthDelta >= 1.0 -> "length expansion"
                lengthDelta <= -1.0 -> "length compression"
                else -> "shape persistence"
            }
        }

        val sharedPrefix = commonPrefixLength(previous.dominantSkeleton, current.dominantSkeleton)
        val sharedSuffix = commonSuffixLength(previous.dominantSkeleton, current.dominantSkeleton)
        return when {
            current.dominantSkeleton.length > previous.dominantSkeleton.length + 1 -> "shape expansion"
            current.dominantSkeleton.length + 1 < previous.dominantSkeleton.length -> "shape compression"
            sharedPrefix >= 3 && sharedSuffix < 3 -> "coda frame shift"
            sharedSuffix >= 3 && sharedPrefix < 3 -> "onset frame shift"
            sharedPrefix >= 2 || sharedSuffix >= 2 -> "frame mutation"
            skeletonDistance >= 3 -> "shape replacement"
            else -> "shape handoff"
        }
    }

    private fun soundShapeDriftNarrative(
        segments: List<SoundShapeSegment>,
        shifts: List<SoundShapeShift>,
        dominantShape: String?,
        stability: Double?,
    ): String? {
        val strongestShift = shifts.maxWithOrNull(compareBy<SoundShapeShift> { it.intensity }.thenBy { it.boundaryOrdinal })
        return when {
            strongestShift != null ->
                "${strongestShift.fromSkeleton} gives way to ${strongestShift.toSkeleton} near word ${strongestShift.boundaryOrdinal}: ${strongestShift.relationship}"
            dominantShape != null && stability != null && stability >= 70.0 ->
                "$dominantShape remains the stable sound frame across ${segments.size} windows"
            dominantShape != null ->
                "$dominantShape is the recurring sound frame, with local departures"
            else -> null
        }
    }

    private fun buildSessionContrastMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): SessionContrastMetrics {
        if (currentSessionId == null) return SessionContrastMetrics()
        val bySession = entries.groupBy { it.sessionId }
        val currentEntries = bySession[currentSessionId].orEmpty()
        if (currentEntries.size < 20) return SessionContrastMetrics()

        val historicalProfiles = bySession
            .filterKeys { it != currentSessionId }
            .mapNotNull { (sessionId, sessionEntries) ->
                sessionEntries.takeIf { it.size >= 20 }?.let { buildSessionProfile(sessionId, it) }
            }
        if (historicalProfiles.isEmpty()) return SessionContrastMetrics()

        val currentProfile = buildSessionProfile(currentSessionId, currentEntries)
        val labels = currentProfile.dimensions.keys.toList()
        val dimensions = labels.map { label ->
            val current = currentProfile.dimensions.getValue(label)
            val historicalValues = historicalProfiles.mapNotNull { it.dimensions[label] }
            val historical = if (historicalValues.isEmpty()) 0.0 else historicalValues.average()
            val delta = current - historical
            SessionContrastDimension(
                label = label,
                currentValue = current,
                historicalValue = historical,
                deltaPoints = delta,
                interpretation = interpretSessionContrast(label, delta),
            )
        }
        val nearest = historicalProfiles.maxWithOrNull(
            compareBy<InternalSessionProfile> { sessionSimilarity(currentProfile, it) }
                .thenBy { it.sessionId },
        )
        val strongest = dimensions.maxByOrNull { abs(it.deltaPoints) }

        return SessionContrastMetrics(
            dimensions = dimensions.sortedByDescending { abs(it.deltaPoints) },
            nearestSessionLabel = nearest?.sessionId?.let { "Session ${it.take(8)}" },
            similarityPercentage = nearest?.let { sessionSimilarity(currentProfile, it) },
            strongestDifference = strongest,
            historicalSessionsCompared = historicalProfiles.size,
        )
    }

    private fun buildSessionProfile(sessionId: String, entries: List<WordEntry>): InternalSessionProfile {
        val total = entries.size.coerceAtLeast(1)
        val averageDistance = averageConsecutiveEditDistance(entries) ?: 0.0
        val topFragmentShare = dominantFragment(entries)?.documentSharePercentage ?: 0.0
        val pace = phasePaceWordsPerMinute(entries).percentileLikeScale()
        val averageLength = entries.map { it.normalizedWord.length }.average()
        val skeletonShare = entries
            .groupingBy { it.normalizedWord.toCvSkeleton() }
            .eachCount()
            .values
            .maxOrNull()
            ?.let { it.toDouble() / total.toDouble() * 100.0 }
            ?: 0.0

        return InternalSessionProfile(
            sessionId = sessionId,
            dimensions = linkedMapOf(
                "Exploration" to entries.distinctBy { it.normalizedWord }.size.toDouble() / total.toDouble() * 100.0,
                "Motif pull" to topFragmentShare,
                "Drift" to (averageDistance / 8.0 * 100.0).coerceIn(0.0, 100.0),
                "Looping" to duplicatePercentage(entries).coerceIn(0.0, 100.0),
                "Pace" to pace.coerceIn(0.0, 100.0),
                "Length shape" to (averageLength / 12.0 * 100.0).coerceIn(0.0, 100.0),
                "Skeleton lock" to skeletonShare.coerceIn(0.0, 100.0),
            ),
        )
    }

    private fun sessionSimilarity(left: InternalSessionProfile, right: InternalSessionProfile): Double {
        val labels = left.dimensions.keys.intersect(right.dimensions.keys)
        if (labels.isEmpty()) return 0.0
        val averageDistance = labels.map { label ->
            abs(left.dimensions.getValue(label) - right.dimensions.getValue(label))
        }.average()
        return (100.0 - averageDistance).coerceIn(0.0, 100.0)
    }

    private fun interpretSessionContrast(label: String, delta: Double): String {
        if (abs(delta) < 8.0) return "near usual"
        val higher = delta > 0.0
        return when (label) {
            "Exploration" -> if (higher) "more exploratory" else "more familiar"
            "Motif pull" -> if (higher) "more motif-locked" else "more open"
            "Drift" -> if (higher) "larger jumps" else "closer mutations"
            "Looping" -> if (higher) "more repetitive" else "less repetitive"
            "Pace" -> if (higher) "faster tempo" else "slower tempo"
            "Length shape" -> if (higher) "longer forms" else "shorter forms"
            "Skeleton lock" -> if (higher) "more shape-locked" else "looser shapes"
            else -> if (higher) "higher than usual" else "lower than usual"
        }
    }

    private fun buildMotifRouteMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): MotifRouteMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }.filter { it.normalizedWord.length >= 3 }
        if (sessionEntries.size < 30) return MotifRouteMetrics()

        val candidates = buildMotifRouteCandidates(sessionEntries)
        if (candidates.isEmpty()) return MotifRouteMetrics()
        val labels = sessionEntries.map { entry ->
            assignMotifRouteLabel(entry.normalizedWord, candidates)
        }
        val transitions = labels.zipWithNext()
        val visibleTransitions = transitions.filterNot { (from, to) -> from == "open" && to == "open" }
        if (visibleTransitions.isEmpty()) return MotifRouteMetrics()

        val examplesByRoute = mutableMapOf<Pair<String, String>, MutableList<String>>()
        labels.zipWithNext().forEachIndexed { index, (from, to) ->
            if (from == "open" && to == "open") return@forEachIndexed
            examplesByRoute.getOrPut(from to to) { mutableListOf() }
                .add("${sessionEntries[index].originalWord} -> ${sessionEntries[index + 1].originalWord}")
        }
        val routeCounts = visibleTransitions.groupingBy { it }.eachCount()
        val routes = routeCounts.map { (route, count) ->
            MotifRoute(
                fromMotif = route.first,
                toMotif = route.second,
                count = count,
                sharePercentage = count.toDouble() / visibleTransitions.size.toDouble() * 100.0,
                relationship = classifyMotifRoute(route.first, route.second),
                examples = examplesByRoute[route].orEmpty().distinct().take(3),
            )
        }.sortedWith(
            compareByDescending<MotifRoute> { it.count }
                .thenBy { it.fromMotif }
                .thenBy { it.toMotif },
        ).take(10)

        val loopCount = visibleTransitions.count { (from, to) -> from == to }
        return MotifRouteMetrics(
            routes = routes,
            segments = buildMotifRouteSegments(labels),
            dominantRouteLabel = routes.firstOrNull()?.let { "${it.fromMotif} -> ${it.toMotif}" },
            handoffCount = visibleTransitions.count { (from, to) -> from != to },
            loopPercentage = loopCount.toDouble() / visibleTransitions.size.toDouble() * 100.0,
        )
    }

    private fun buildMotifRouteCandidates(entries: List<WordEntry>): List<MotifRouteCandidate> {
        val minimumDocuments = maxOf(3, (entries.size * 0.06).roundToInt())
        val counts = mutableMapOf<String, Int>()
        entries.forEach { entry ->
            entry.normalizedWord.fragmentsForDiscovery().forEach { fragment ->
                counts[fragment] = (counts[fragment] ?: 0) + 1
            }
        }
        return counts.mapNotNull { (fragment, count) ->
            if (count < minimumDocuments) return@mapNotNull null
            MotifRouteCandidate(
                motif = fragment,
                documentCount = count,
                strength = count * ln(fragment.length + 1.0),
            )
        }.sortedWith(
            compareByDescending<MotifRouteCandidate> { it.strength }
                .thenByDescending { it.motif.length }
                .thenBy { it.motif },
        ).take(24)
    }

    private fun assignMotifRouteLabel(
        word: String,
        candidates: List<MotifRouteCandidate>,
    ): String =
        candidates.firstOrNull { candidate -> word.contains(candidate.motif) }?.motif ?: "open"

    private fun buildMotifRouteSegments(labels: List<String>): List<MotifRouteSegment> {
        if (labels.size < 2) return emptyList()
        val windowCount = minOf(MOTIF_ROUTE_WINDOWS, labels.size)
        return (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * labels.size / windowCount
            val end = (windowIndex + 1) * labels.size / windowCount
            val windowLabels = labels.subList(start, end)
            if (windowLabels.isEmpty()) return@mapNotNull null
            val dominant = windowLabels.groupingBy { it }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                ?: return@mapNotNull null
            MotifRouteSegment(
                startOrdinal = start + 1,
                endOrdinal = end,
                dominantMotif = dominant.key,
                sharePercentage = dominant.value.toDouble() / windowLabels.size.toDouble() * 100.0,
            )
        }
    }

    private fun classifyMotifRoute(from: String, to: String): String =
        when {
            from == to -> "motif loop"
            from == "open" -> "motif birth"
            to == "open" -> "motif release"
            else -> "motif handoff"
        }

    private fun buildMotifAttractorMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): MotifAttractorMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }.filter { it.normalizedWord.length >= 3 }
            .takeLast(MOTIF_ATTRACTOR_RECENT_ENTRIES)
        if (sessionEntries.size < 36) return MotifAttractorMetrics()

        val candidates = buildMotifRouteCandidates(sessionEntries)
        if (candidates.isEmpty()) return MotifAttractorMetrics()
        val labels = sessionEntries.map { entry -> assignMotifRouteLabel(entry.normalizedWord, candidates) }
        val motifLabels = labels.filterNot { it == "open" }
        if (motifLabels.size < 12) return MotifAttractorMetrics()

        val runs = buildMotifAttractorRuns(labels)
        val attractors = motifLabels.toSet().mapNotNull { motif ->
            val motifRuns = runs.filter { it.motif == motif }
            if (motifRuns.isEmpty()) return@mapNotNull null
            val occupancy = labels.count { it == motif }.toDouble() / labels.size.toDouble() * 100.0
            val returnCount = motifRuns.drop(1).count { run ->
                runs.getOrNull(run.runIndex - 1)?.motif != motif
            }
            val longestRun = motifRuns.maxOf { it.endIndex - it.startIndex + 1 }
            val entryDrifts = motifRuns.mapNotNull { run ->
                if (run.startIndex == 0 || labels[run.startIndex - 1] == motif) {
                    null
                } else {
                    Levenshtein.distance(
                        sessionEntries[run.startIndex - 1].normalizedWord,
                        sessionEntries[run.startIndex].normalizedWord,
                    )
                }
            }
            val averageEntryDrift = entryDrifts.takeIf { it.isNotEmpty() }?.average()
            val pullStrength = motifAttractorPullStrength(
                occupancyPercentage = occupancy,
                returnCount = returnCount,
                longestRun = longestRun,
                totalEntries = labels.size,
                averageEntryDrift = averageEntryDrift,
            )

            MotifAttractor(
                motif = motif,
                occupancyPercentage = occupancy,
                returnCount = returnCount,
                longestRun = longestRun,
                averageEntryDrift = averageEntryDrift,
                pullStrength = pullStrength,
                interpretation = interpretMotifAttractor(occupancy, returnCount, longestRun, labels.size),
                examples = sessionEntries
                    .zip(labels)
                    .filter { (_, label) -> label == motif }
                    .map { (entry, _) -> entry.originalWord }
                    .distinct()
                    .take(5),
            )
        }.sortedWith(
            compareByDescending<MotifAttractor> { it.pullStrength }
                .thenByDescending { it.occupancyPercentage }
                .thenBy { it.motif },
        ).take(8)

        if (attractors.isEmpty()) return MotifAttractorMetrics()
        val strongest = attractors.first()
        return MotifAttractorMetrics(
            attractors = attractors,
            timeline = buildMotifAttractorTimeline(labels),
            strongestAttractor = strongest,
            narrative = "${strongest.motif} pulls ${strongest.occupancyPercentage.formatWholePercent()} of recent generation and returns ${strongest.returnCount} times",
        )
    }

    private fun buildMotifAttractorRuns(labels: List<String>): List<InternalMotifRun> {
        if (labels.isEmpty()) return emptyList()
        val runs = mutableListOf<InternalMotifRun>()
        var start = 0
        labels.indices.drop(1).forEach { index ->
            if (labels[index] != labels[start]) {
                runs += InternalMotifRun(
                    motif = labels[start],
                    startIndex = start,
                    endIndex = index - 1,
                    runIndex = runs.size,
                )
                start = index
            }
        }
        runs += InternalMotifRun(
            motif = labels[start],
            startIndex = start,
            endIndex = labels.lastIndex,
            runIndex = runs.size,
        )
        return runs
    }

    private fun buildMotifAttractorTimeline(labels: List<String>): List<MotifAttractorSegment> {
        if (labels.isEmpty()) return emptyList()
        val windowCount = minOf(MOTIF_ATTRACTOR_WINDOWS, labels.size)
        return (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * labels.size / windowCount
            val end = (windowIndex + 1) * labels.size / windowCount
            val windowLabels = labels.subList(start, end)
            if (windowLabels.isEmpty()) return@mapNotNull null
            val dominant = windowLabels.groupingBy { it }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                ?: return@mapNotNull null
            MotifAttractorSegment(
                startOrdinal = start + 1,
                endOrdinal = end,
                dominantMotif = dominant.key,
                sharePercentage = dominant.value.toDouble() / windowLabels.size.toDouble() * 100.0,
            )
        }
    }

    private fun motifAttractorPullStrength(
        occupancyPercentage: Double,
        returnCount: Int,
        longestRun: Int,
        totalEntries: Int,
        averageEntryDrift: Double?,
    ): Double {
        val returnScore = (returnCount * 25.0).coerceIn(0.0, 100.0)
        val runScore = longestRun.toDouble() / totalEntries.toDouble() * 100.0
        val entryStability = averageEntryDrift?.let { drift ->
            (100.0 - (drift / 8.0 * 100.0)).coerceIn(0.0, 100.0)
        } ?: 0.0
        return (occupancyPercentage * 0.45 + returnScore * 0.25 + runScore * 0.20 + entryStability * 0.10)
            .coerceIn(0.0, 100.0)
    }

    private fun interpretMotifAttractor(
        occupancyPercentage: Double,
        returnCount: Int,
        longestRun: Int,
        totalEntries: Int,
    ): String =
        when {
            occupancyPercentage >= 55.0 && returnCount >= 2 -> "dominant returning center"
            occupancyPercentage >= 55.0 -> "dominant basin"
            returnCount >= 2 -> "returning anchor"
            longestRun.toDouble() / totalEntries.toDouble() >= 0.25 -> "temporary orbit"
            else -> "brief motif flare"
        }

    private fun buildMemoryEchoMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): MemoryEchoMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }.filter { it.normalizedWord.length >= 3 }
            .takeLast(MEMORY_ECHO_RECENT_ENTRIES)
        if (sessionEntries.size < 36) return MemoryEchoMetrics()

        val links = sessionEntries.indices.mapNotNull { currentIndex ->
            val latestPriorIndex = currentIndex - MEMORY_ECHO_MIN_GAP
            if (latestPriorIndex < 0) return@mapNotNull null
            val startIndex = maxOf(0, currentIndex - MEMORY_ECHO_LOOKBACK_LIMIT)
            val current = sessionEntries[currentIndex]
            (startIndex..latestPriorIndex).mapNotNull { priorIndex ->
                val previous = sessionEntries[priorIndex]
                val distance = Levenshtein.distance(previous.normalizedWord, current.normalizedWord)
                val threshold = memoryEchoDistanceLimit(previous.normalizedWord, current.normalizedWord)
                val motifEcho = sharesDiscoveryFragment(previous.normalizedWord, current.normalizedWord)
                if (distance > threshold && !(motifEcho && distance <= threshold + 1)) {
                    return@mapNotNull null
                }
                val gap = currentIndex - priorIndex
                MemoryEchoCandidate(
                    priorIndex = priorIndex,
                    distance = distance,
                    gap = gap,
                    sharedPrefix = commonPrefixLength(previous.normalizedWord, current.normalizedWord),
                    sharedSuffix = commonSuffixLength(previous.normalizedWord, current.normalizedWord),
                )
            }.maxWithOrNull(
                compareBy<MemoryEchoCandidate> { memoryEchoStrength(it.distance, it.gap, sessionEntries[it.priorIndex].normalizedWord, current.normalizedWord) }
                    .thenByDescending { it.gap }
                    .thenByDescending { it.sharedPrefix + it.sharedSuffix },
            )?.let { candidate ->
                val previous = sessionEntries[candidate.priorIndex]
                MemoryEchoLink(
                    previousWord = previous.originalWord,
                    currentWord = current.originalWord,
                    previousOrdinal = candidate.priorIndex + 1,
                    currentOrdinal = currentIndex + 1,
                    gap = candidate.gap,
                    distance = candidate.distance,
                    relationship = classifyMemoryEcho(previous.normalizedWord, current.normalizedWord, candidate.distance),
                    strength = memoryEchoStrength(candidate.distance, candidate.gap, previous.normalizedWord, current.normalizedWord),
                )
            }
        }.sortedWith(
            compareByDescending<MemoryEchoLink> { it.strength }
                .thenByDescending { it.gap }
                .thenBy { it.currentOrdinal },
        ).take(24)

        if (links.isEmpty()) return MemoryEchoMetrics()
        val strongest = links.first()
        return MemoryEchoMetrics(
            links = links.sortedBy { it.currentOrdinal },
            segments = buildMemoryEchoSegments(sessionEntries.size, links),
            strongestEcho = strongest,
            echoDensityPercentage = links.size.toDouble() / sessionEntries.size.toDouble() * 100.0,
            narrative = "${strongest.currentWord} echoes ${strongest.previousWord} after ${strongest.gap} words",
        )
    }

    private fun buildMemoryEchoSegments(
        entryCount: Int,
        links: List<MemoryEchoLink>,
    ): List<MemoryEchoSegment> {
        if (entryCount == 0) return emptyList()
        val windowCount = minOf(MEMORY_ECHO_WINDOWS, entryCount)
        return (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * entryCount / windowCount
            val end = (windowIndex + 1) * entryCount / windowCount
            val windowLinks = links.filter { link -> (link.currentOrdinal - 1) in start until end }
            MemoryEchoSegment(
                startOrdinal = start + 1,
                endOrdinal = end,
                echoCount = windowLinks.size,
                averageStrength = windowLinks.takeIf { it.isNotEmpty() }?.map { it.strength }?.average() ?: 0.0,
            )
        }
    }

    private fun memoryEchoDistanceLimit(left: String, right: String): Int {
        val longest = maxOf(left.length, right.length)
        return when {
            longest <= 4 -> 1
            longest <= 7 -> 2
            else -> 3
        }
    }

    private fun memoryEchoStrength(
        distance: Int,
        gap: Int,
        previous: String,
        current: String,
    ): Double {
        val longest = maxOf(previous.length, current.length).coerceAtLeast(1)
        val closeness = (1.0 - distance.toDouble() / longest.toDouble()).coerceIn(0.0, 1.0) * 100.0
        val gapScore = (gap.toDouble() / 48.0 * 100.0).coerceIn(0.0, 100.0)
        return (closeness * 0.65 + gapScore * 0.35).coerceIn(0.0, 100.0)
    }

    private fun classifyMemoryEcho(
        previous: String,
        current: String,
        distance: Int,
    ): String =
        when {
            distance == 0 -> "exact resurfacing"
            previous.startsWith(current) || current.startsWith(previous) -> "stem memory"
            commonPrefixLength(previous, current) >= 3 -> "prefix echo"
            commonSuffixLength(previous, current) >= 3 -> "suffix echo"
            sharesDiscoveryFragment(previous, current) -> "motif echo"
            else -> "near echo"
        }

    private fun buildWordConstellationMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): WordConstellationMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }
        val nodes = sessionEntries
            .takeLast(WORD_CONSTELLATION_RECENT_ENTRIES)
            .distinctBy { it.normalizedWord }
            .takeLast(WORD_CONSTELLATION_UNIQUE_LIMIT)
            .map { entry -> InternalConstellationNode(entry.originalWord, entry.normalizedWord) }
        if (nodes.size < 12) return WordConstellationMetrics()

        val edges = mutableListOf<InternalConstellationEdge>()
        for (leftIndex in 0 until nodes.lastIndex) {
            for (rightIndex in (leftIndex + 1)..nodes.lastIndex) {
                val left = nodes[leftIndex]
                val right = nodes[rightIndex]
                val distance = Levenshtein.distance(left.normalizedWord, right.normalizedWord)
                val threshold = constellationDistanceThreshold(left.normalizedWord, right.normalizedWord)
                if (distance <= threshold) {
                    edges += InternalConstellationEdge(
                        leftIndex = leftIndex,
                        rightIndex = rightIndex,
                        distance = distance,
                    )
                }
            }
        }
        if (edges.isEmpty()) return WordConstellationMetrics()

        val parent = IntArray(nodes.size) { it }
        fun find(index: Int): Int {
            var cursor = index
            while (parent[cursor] != cursor) {
                parent[cursor] = parent[parent[cursor]]
                cursor = parent[cursor]
            }
            return cursor
        }
        fun union(left: Int, right: Int) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
        }
        edges.forEach { edge -> union(edge.leftIndex, edge.rightIndex) }

        val components = nodes.indices.groupBy { find(it) }.values
            .filter { it.size >= 3 }
        if (components.isEmpty()) return WordConstellationMetrics()

        val constellations = components.map { component ->
            val componentSet = component.toSet()
            val componentEdges = edges.filter { it.leftIndex in componentSet && it.rightIndex in componentSet }
            val possibleEdges = component.size * (component.size - 1) / 2
            val averageDistance = componentEdges.map { it.distance }.average()
            WordConstellation(
                label = nodes[component.minOrNull() ?: component.first()].originalWord,
                words = component.map { nodes[it].originalWord }.take(14),
                edgeCount = componentEdges.size,
                densityPercentage = if (possibleEdges == 0) 0.0 else componentEdges.size.toDouble() / possibleEdges.toDouble() * 100.0,
                averageDistance = averageDistance,
            )
        }.sortedWith(
            compareByDescending<WordConstellation> { it.words.size }
                .thenByDescending { it.densityPercentage }
                .thenBy { it.label },
        ).take(8)

        val publicEdges = edges
            .sortedWith(compareBy<InternalConstellationEdge> { it.distance }.thenBy { it.leftIndex }.thenBy { it.rightIndex })
            .take(20)
            .map { edge ->
                WordConstellationEdge(
                    fromWord = nodes[edge.leftIndex].originalWord,
                    toWord = nodes[edge.rightIndex].originalWord,
                    distance = edge.distance,
                    relationship = classifyConstellationEdge(nodes[edge.leftIndex].normalizedWord, nodes[edge.rightIndex].normalizedWord, edge.distance),
                )
            }
        val connectedNodes = edges.flatMap { listOf(it.leftIndex, it.rightIndex) }.toSet().size
        val densest = constellations.maxWithOrNull(
            compareBy<WordConstellation> { it.densityPercentage }
                .thenBy { it.label },
        )

        return WordConstellationMetrics(
            constellations = constellations,
            edges = publicEdges,
            densestConstellationLabel = densest?.label,
            connectedWordPercentage = connectedNodes.toDouble() / nodes.size.toDouble() * 100.0,
        )
    }

    private fun buildFamilyMigrationMetrics(
        entries: List<WordEntry>,
        currentSessionId: String?,
    ): FamilyMigrationMetrics {
        val sessionEntries = when {
            currentSessionId == null -> entries
            entries.all { it.sessionId == currentSessionId } -> entries
            else -> entries.filter { it.sessionId == currentSessionId }
        }
            .takeLast(FAMILY_MIGRATION_RECENT_ENTRIES)
            .filter { it.normalizedWord.length >= 2 }
        if (sessionEntries.size < 36) return FamilyMigrationMetrics()

        val nodes = sessionEntries
            .distinctBy { it.normalizedWord }
            .takeLast(FAMILY_MIGRATION_UNIQUE_LIMIT)
            .map { entry -> InternalConstellationNode(entry.originalWord, entry.normalizedWord) }
        if (nodes.size < 12) return FamilyMigrationMetrics()

        val edges = mutableListOf<InternalConstellationEdge>()
        for (leftIndex in 0 until nodes.lastIndex) {
            for (rightIndex in (leftIndex + 1)..nodes.lastIndex) {
                val left = nodes[leftIndex]
                val right = nodes[rightIndex]
                val distance = Levenshtein.distance(left.normalizedWord, right.normalizedWord)
                if (distance <= constellationDistanceThreshold(left.normalizedWord, right.normalizedWord)) {
                    edges += InternalConstellationEdge(leftIndex, rightIndex, distance)
                }
            }
        }
        if (edges.isEmpty()) return FamilyMigrationMetrics()

        val parent = IntArray(nodes.size) { it }
        fun find(index: Int): Int {
            var cursor = index
            while (parent[cursor] != cursor) {
                parent[cursor] = parent[parent[cursor]]
                cursor = parent[cursor]
            }
            return cursor
        }
        fun union(left: Int, right: Int) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
        }
        edges.forEach { edge -> union(edge.leftIndex, edge.rightIndex) }

        val components = nodes.indices.groupBy { find(it) }.values
            .filter { it.size >= 3 }
        if (components.isEmpty()) return FamilyMigrationMetrics()

        val internalFamilies = components.map { component ->
            val componentSet = component.toSet()
            val componentEdges = edges.filter { it.leftIndex in componentSet && it.rightIndex in componentSet }
            val labelIndex = component.minOrNull() ?: component.first()
            InternalLatentWordFamily(
                label = nodes[labelIndex].originalWord,
                normalizedWords = component.map { nodes[it].normalizedWord }.toSet(),
                words = component.map { nodes[it].originalWord }.take(10),
                averageDistance = componentEdges.map { it.distance }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            )
        }
        val familyByWord = internalFamilies.flatMap { family ->
            family.normalizedWords.map { normalizedWord -> normalizedWord to family.label }
        }.toMap()
        val labels = sessionEntries.mapIndexed { index, entry ->
            FamilyMigrationLabel(
                ordinal = index + 1,
                originalWord = entry.originalWord,
                family = familyByWord[entry.normalizedWord] ?: "open",
            )
        }
        val labelsWithoutOpen = labels.filter { it.family != "open" }
        if (labelsWithoutOpen.isEmpty()) return FamilyMigrationMetrics()

        val transitions = buildFamilyMigrationTransitions(labels)
        val segments = buildFamilyMigrationSegments(labels)
        val compressedPath = labels
            .map { it.family }
            .filter { it != "open" }
            .fold(mutableListOf<String>()) { path, family ->
                if (path.lastOrNull() != family) path += family
                path
            }
        val dominantPath = compressedPath
            .take(6)
            .takeIf { it.size >= 2 }
            ?.joinToString(" -> ")
        val families = internalFamilies.map { family ->
            val occupancy = labels.count { it.family == family.label }
            LatentWordFamily(
                label = family.label,
                words = family.words,
                occupancyPercentage = labels.size.percentOf(occupancy),
                averageDistance = family.averageDistance,
            )
        }.sortedWith(
            compareByDescending<LatentWordFamily> { it.occupancyPercentage }
                .thenBy { it.label },
        )

        return FamilyMigrationMetrics(
            families = families,
            transitions = transitions,
            segments = segments,
            dominantPath = dominantPath,
            narrative = familyMigrationNarrative(transitions, families),
        )
    }

    private fun buildFamilyMigrationTransitions(labels: List<FamilyMigrationLabel>): List<FamilyMigrationTransition> {
        val adjacent = labels.zipWithNext()
        if (adjacent.isEmpty()) return emptyList()
        return adjacent
            .groupBy { (previous, current) -> previous.family to current.family }
            .map { (route, pairs) ->
                val firstBoundary = pairs.minOf { (_, current) -> current.ordinal }
                FamilyMigrationTransition(
                    fromFamily = route.first,
                    toFamily = route.second,
                    count = pairs.size,
                    sharePercentage = adjacent.size.percentOf(pairs.size),
                    boundaryOrdinal = firstBoundary,
                    relationship = classifyFamilyMigration(route.first, route.second),
                    examples = pairs
                        .map { (previous, current) -> "${previous.originalWord} -> ${current.originalWord}" }
                        .distinct()
                        .take(4),
                )
            }
            .sortedWith(
                compareByDescending<FamilyMigrationTransition> { it.fromFamily != it.toFamily }
                    .thenByDescending { it.count }
                    .thenBy { it.boundaryOrdinal },
            )
            .take(10)
    }

    private fun buildFamilyMigrationSegments(labels: List<FamilyMigrationLabel>): List<FamilyMigrationSegment> {
        if (labels.size < 12) return emptyList()
        val windowCount = minOf(
            FAMILY_MIGRATION_WINDOWS,
            (labels.size / 6).coerceAtLeast(2),
        )
        return (0 until windowCount).mapNotNull { windowIndex ->
            val start = windowIndex * labels.size / windowCount
            val endExclusive = (windowIndex + 1) * labels.size / windowCount
            val window = labels.subList(start, endExclusive)
            val dominant = window
                .groupingBy { it.family }
                .eachCount()
                .entries
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            dominant?.let { entry ->
                FamilyMigrationSegment(
                    startOrdinal = start + 1,
                    endOrdinal = endExclusive,
                    dominantFamily = entry.key,
                    sharePercentage = window.size.percentOf(entry.value),
                )
            }
        }
    }

    private fun classifyFamilyMigration(fromFamily: String, toFamily: String): String =
        when {
            fromFamily == toFamily && fromFamily == "open" -> "open drift"
            fromFamily == toFamily -> "family orbit"
            fromFamily == "open" -> "family birth"
            toFamily == "open" -> "family release"
            else -> "family migration"
        }

    private fun familyMigrationNarrative(
        transitions: List<FamilyMigrationTransition>,
        families: List<LatentWordFamily>,
    ): String? {
        val migration = transitions.firstOrNull { it.fromFamily != it.toFamily }
        return when {
            migration != null ->
                "${migration.fromFamily} gives way to ${migration.toFamily} near word ${migration.boundaryOrdinal}: ${migration.relationship}"
            families.isNotEmpty() ->
                "${families.first().label} remains the main latent family"
            else -> null
        }
    }

    private fun constellationDistanceThreshold(left: String, right: String): Int {
        val longest = maxOf(left.length, right.length)
        return when {
            longest <= 4 -> 1
            longest <= 7 -> 2
            else -> 3
        }
    }

    private fun classifyConstellationEdge(left: String, right: String, distance: Int): String =
        when {
            distance == 0 -> "same form"
            left.startsWith(right) || right.startsWith(left) -> "stem growth"
            commonPrefixLength(left, right) >= 3 -> "prefix neighbor"
            commonSuffixLength(left, right) >= 3 -> "suffix neighbor"
            else -> "near mutation"
        }

    private fun buildPhonotacticLane(
        role: String,
        entries: List<WordEntry>,
        extractor: (String) -> Set<String>,
    ): PhonotacticLane? {
        val minimumCoverage = maxOf(4, (entries.size * 0.10).roundToInt())
        val counts = mutableMapOf<String, Int>()
        val examples = mutableMapOf<String, MutableList<String>>()
        entries.forEach { entry ->
            extractor(entry.normalizedWord).forEach { motif ->
                counts[motif] = (counts[motif] ?: 0) + 1
                examples.getOrPut(motif) { mutableListOf() }.add(entry.originalWord)
            }
        }
        val motifs = counts.mapNotNull { (motif, count) ->
            if (count < minimumCoverage) return@mapNotNull null
            val coverage = count.toDouble() / entries.size.toDouble() * 100.0
            PhonotacticMotif(
                motif = motif,
                coveragePercentage = coverage,
                strength = coverage * ln(motif.length + 1.0),
                examples = examples[motif].orEmpty().distinct().take(4),
            )
        }.sortedWith(
            compareByDescending<PhonotacticMotif> { it.strength }
                .thenByDescending { it.coveragePercentage }
                .thenBy { it.motif },
        ).take(5)

        return motifs.takeIf { it.isNotEmpty() }?.let { PhonotacticLane(role = role, motifs = it) }
    }

    private fun buildPhonotacticTransitions(entries: List<WordEntry>): List<PhonotacticTransition> {
        val counts = mutableMapOf<String, Int>()
        val examples = mutableMapOf<String, MutableList<String>>()
        var totalTransitions = 0
        entries.forEach { entry ->
            val word = entry.normalizedWord
            word.zipWithNext { left, right ->
                val transition = "$left$right"
                counts[transition] = (counts[transition] ?: 0) + 1
                examples.getOrPut(transition) { mutableListOf() }.add(entry.originalWord)
                totalTransitions += 1
            }
        }
        if (totalTransitions == 0) return emptyList()
        val minimumCount = maxOf(5, (totalTransitions * 0.04).roundToInt())
        return counts.mapNotNull { (transition, count) ->
            if (count < minimumCount || transition.length < 2) return@mapNotNull null
            PhonotacticTransition(
                from = transition.first().toString(),
                to = transition.last().toString(),
                coveragePercentage = count.toDouble() / totalTransitions.toDouble() * 100.0,
                examples = examples[transition].orEmpty().distinct().take(4),
            )
        }.sortedWith(
            compareByDescending<PhonotacticTransition> { it.coveragePercentage }
                .thenBy { it.from }
                .thenBy { it.to },
        ).take(8)
    }

    private fun buildPhonotacticSkeletons(entries: List<WordEntry>): List<PhonotacticSkeleton> {
        val counts = mutableMapOf<String, Int>()
        val examples = mutableMapOf<String, MutableList<String>>()
        entries.forEach { entry ->
            val skeleton = entry.normalizedWord.toCvSkeleton()
            counts[skeleton] = (counts[skeleton] ?: 0) + 1
            examples.getOrPut(skeleton) { mutableListOf() }.add(entry.originalWord)
        }
        val minimumCount = maxOf(4, (entries.size * 0.08).roundToInt())
        return counts.mapNotNull { (pattern, count) ->
            if (count < minimumCount) return@mapNotNull null
            PhonotacticSkeleton(
                pattern = pattern,
                coveragePercentage = count.toDouble() / entries.size.toDouble() * 100.0,
                examples = examples[pattern].orEmpty().distinct().take(4),
            )
        }.sortedWith(
            compareByDescending<PhonotacticSkeleton> { it.coveragePercentage }
                .thenBy { it.pattern },
        ).take(5)
    }

    private fun buildPhonotacticRules(
        entries: List<WordEntry>,
        lanes: List<PhonotacticLane>,
        transitions: List<PhonotacticTransition>,
        skeletons: List<PhonotacticSkeleton>,
    ): List<PhonotacticRule> {
        if (entries.isEmpty()) return emptyList()
        val ruleComparator = compareByDescending<PhonotacticRule> { it.confidencePercentage }
            .thenByDescending { it.pattern.length }
            .thenBy { it.scope }
            .thenBy { it.pattern }
        val positionalRules = lanes.flatMap { lane ->
            lane.motifs.mapNotNull { motif ->
                if (motif.coveragePercentage < 18.0) return@mapNotNull null
                val exceptionWords = entries
                    .filterNot { entry -> wordMatchesPhonotacticScope(entry.normalizedWord, lane.role, motif.motif) }
                val exceptions = exceptionWords
                    .map { it.originalWord }
                    .distinct()
                    .take(4)
                PhonotacticRule(
                    scope = lane.role,
                    pattern = motif.motif,
                    confidencePercentage = motif.coveragePercentage,
                    exceptionCount = exceptionWords.size,
                    interpretation = phonotacticRuleInterpretation(lane.role, motif.motif, motif.coveragePercentage),
                    examples = motif.examples,
                    exceptions = exceptions,
                )
            }
        }
        val transitionRules = transitions.mapNotNull { transition ->
            if (transition.coveragePercentage < 7.0) return@mapNotNull null
            val pattern = "${transition.from}${transition.to}"
            val exceptionWords = entries
                .filterNot { entry -> entry.normalizedWord.contains(pattern) }
            val exceptions = exceptionWords
                .map { it.originalWord }
                .distinct()
                .take(4)
            PhonotacticRule(
                scope = "Sound link",
                pattern = "${transition.from} -> ${transition.to}",
                confidencePercentage = transition.coveragePercentage,
                exceptionCount = exceptionWords.size,
                interpretation = "letters often move from ${transition.from} to ${transition.to}",
                examples = transition.examples,
                exceptions = exceptions,
            )
        }
        val skeletonRules = skeletons.mapNotNull { skeleton ->
            if (skeleton.coveragePercentage < 18.0) return@mapNotNull null
            val exceptionWords = entries
                .filterNot { entry -> entry.normalizedWord.toCvSkeleton() == skeleton.pattern }
            val exceptions = exceptionWords
                .map { it.originalWord }
                .distinct()
                .take(4)
            PhonotacticRule(
                scope = "Shape",
                pattern = skeleton.pattern,
                confidencePercentage = skeleton.coveragePercentage,
                exceptionCount = exceptionWords.size,
                interpretation = "word bodies tend to follow ${skeleton.pattern}",
                examples = skeleton.examples,
                exceptions = exceptions,
            )
        }
        val diversePositionalRules = positionalRules
            .groupBy { it.scope }
            .flatMap { (_, rules) -> rules.sortedWith(ruleComparator).take(2) }
        return (diversePositionalRules + skeletonRules.sortedWith(ruleComparator).take(2) + transitionRules.sortedWith(ruleComparator).take(2))
            .sortedWith(ruleComparator)
            .take(8)
    }

    private fun wordMatchesPhonotacticScope(word: String, scope: String, pattern: String): Boolean =
        when (scope) {
            "Onset" -> onsetFragmentsForPhonotactics(word).contains(pattern)
            "Core" -> coreFragmentsForPhonotactics(word).contains(pattern)
            "Coda" -> codaFragmentsForPhonotactics(word).contains(pattern)
            else -> word.contains(pattern)
        }

    private fun phonotacticRuleInterpretation(scope: String, pattern: String, confidencePercentage: Double): String {
        val strength = when {
            confidencePercentage >= 75.0 -> "strong"
            confidencePercentage >= 50.0 -> "stable"
            else -> "emerging"
        }
        return when (scope) {
            "Onset" -> "$strength opening constraint around $pattern"
            "Core" -> "$strength middle-body constraint around $pattern"
            "Coda" -> "$strength closing constraint around $pattern"
            else -> "$strength phonotactic constraint around $pattern"
        }
    }

    private fun onsetFragmentsForPhonotactics(word: String): Set<String> =
        buildSet {
            if (word.length >= 2) add(word.take(2))
            if (word.length >= 3) add(word.take(3))
            if (word.length >= 4) add(word.take(4))
        }

    private fun coreFragmentsForPhonotactics(word: String): Set<String> =
        buildSet {
            (3..5).forEach { length ->
                if (word.length > length + 2) {
                    (1..(word.length - length - 1)).forEach { start ->
                        add(word.substring(start, start + length))
                    }
                }
            }
        }

    private fun codaFragmentsForPhonotactics(word: String): Set<String> =
        buildSet {
            if (word.length >= 2) add(word.takeLast(2))
            if (word.length >= 3) add(word.takeLast(3))
            if (word.length >= 4) add(word.takeLast(4))
        }

    private fun dominantFragment(entries: List<WordEntry>): DominantFragment? {
        if (entries.isEmpty()) return null
        val documentCounts = mutableMapOf<String, Int>()
        entries.forEach { entry ->
            entry.normalizedWord.fragmentsForDiscovery().forEach { fragment ->
                documentCounts[fragment] = (documentCounts[fragment] ?: 0) + 1
            }
        }

        return documentCounts.asSequence()
            .filter { it.value >= 2 }
            .map { (fragment, count) ->
                DominantFragment(
                    fragment = fragment,
                    documentCount = count,
                    documentSharePercentage = count.toDouble() / entries.size.toDouble() * 100.0,
                )
            }
            .maxWithOrNull(compareBy<DominantFragment> { it.documentCount }.thenBy { it.fragment })
    }

    private fun averageConsecutiveEditDistance(entries: List<WordEntry>): Double? {
        if (entries.size < 2) return null
        var total = 0L
        var count = 0
        entries.zipWithNext { previous, current ->
            total += Levenshtein.distance(previous.normalizedWord, current.normalizedWord)
            count += 1
        }
        return if (count == 0) null else total.toDouble() / count.toDouble()
    }

    private fun phasePaceWordsPerMinute(entries: List<WordEntry>): Double {
        if (entries.isEmpty()) return 0.0
        val durationMs = (entries.last().createdAtUtcMs - entries.first().createdAtUtcMs).coerceAtLeast(1_000L)
        return entries.size.toDouble() / (durationMs.toDouble() / ONE_MINUTE_MS.toDouble())
    }

    private fun phaseIntensity(
        noveltyPercentage: Double,
        repetitionPercentage: Double,
        dominantFragmentShare: Double,
        averageEditDistance: Double?,
    ): Double {
        val drift = ((averageEditDistance ?: 0.0) / 8.0 * 100.0).coerceIn(0.0, 100.0)
        return maxOf(noveltyPercentage, repetitionPercentage, dominantFragmentShare, drift)
            .coerceIn(0.0, 100.0)
    }

    private fun classifyEvolution(transition: ConsecutiveTransition): String {
        val previous = transition.previousNormalizedWord
        val current = transition.currentNormalizedWord
        if (previous == current) return "Repeat"

        val minimumLength = minOf(previous.length, current.length)
        val sharedPrefix = commonPrefixLength(previous, current)
        val sharedSuffix = commonSuffixLength(previous, current)
        val familyThreshold = max(3, minimumLength / 2)

        return when {
            current.startsWith(previous) && current.length > previous.length -> "Stem extension"
            previous.startsWith(current) && previous.length > current.length -> "Stem contraction"
            previous.length == current.length && transition.distance <= 2 -> "Substitution drift"
            sharedPrefix >= familyThreshold -> "Prefix family drift"
            sharedSuffix >= familyThreshold -> "Suffix family drift"
            current.length > previous.length && transition.similarity >= 0.45 -> "Expansion"
            current.length < previous.length && transition.similarity >= 0.45 -> "Compression"
            transition.similarity >= 0.55 -> "Soft drift"
            else -> "Jump"
        }
    }

    private fun buildSimilarityDistribution(similarities: List<Double>): List<SimilarityBucket> {
        val labels = listOf("0-20", "20-40", "40-60", "60-80", "80-100")
        val counts = IntArray(labels.size)
        similarities.forEach { similarity ->
            val index = when {
                similarity < 0.2 -> 0
                similarity < 0.4 -> 1
                similarity < 0.6 -> 2
                similarity < 0.8 -> 3
                else -> 4
            }
            counts[index] += 1
        }
        return labels.mapIndexed { index, label -> SimilarityBucket(label, counts[index]) }
    }

    private fun buildScores(
        entries: List<WordEntry>,
        timing: TimingMetrics,
        rhythm: RhythmMetrics,
    ): List<ScoreMetric> {
        val total = entries.size
        val unique = entries.distinctBy { it.normalizedWord }.size
        val duplicateCount = total - unique
        return listOf(
            ScoreMetric(
                name = "Novelty",
                value = clampScore(if (total == 0) 0.0 else unique.toDouble() / total.toDouble() * 100.0),
                formula = "unique words / total submitted words * 100",
            ),
            ScoreMetric(
                name = "Flow",
                value = clampScore(rhythm.rhythmStability ?: if (total > 1) 50.0 else 0.0),
                formula = "100 / (1 + coefficient of variation of submission intervals)",
            ),
            ScoreMetric(
                name = "Repetition Risk",
                value = clampScore(if (total == 0) 0.0 else duplicateCount.toDouble() / total.toDouble() * 100.0),
                formula = "duplicate submissions / total submitted words * 100",
            ),
            ScoreMetric(
                name = "Activity",
                value = clampScore(timing.wordsPerMinute.percentileLikeScale()),
                formula = "words per minute / (words per minute + 20) * 100",
            ),
        )
    }

    private fun buildBaselines(
        entries: List<WordEntry>,
        nowUtcMs: Long,
        zoneId: ZoneId,
    ): List<BaselineComparison> {
        val today = Instant.ofEpochMilli(nowUtcMs).atZone(zoneId).toLocalDate()
        val byDate = entries.groupBy { entry ->
            Instant.ofEpochMilli(entry.createdAtUtcMs).atZone(zoneId).toLocalDate()
        }
        val todayEntries = byDate[today].orEmpty()

        return listOf(
            baselineFor(
                name = "Words per day",
                today = todayEntries.size.toDouble(),
                byDate = byDate,
                todayDate = today,
                extractor = { it.size.toDouble() },
            ),
            baselineFor(
                name = "Unique words per day",
                today = todayEntries.distinctBy { it.normalizedWord }.size.toDouble(),
                byDate = byDate,
                todayDate = today,
                extractor = { dayEntries -> dayEntries.distinctBy { it.normalizedWord }.size.toDouble() },
            ),
            baselineFor(
                name = "Duplicate percentage",
                today = duplicatePercentage(todayEntries),
                byDate = byDate,
                todayDate = today,
                extractor = ::duplicatePercentage,
            ),
        )
    }

    private fun baselineFor(
        name: String,
        today: Double,
        byDate: Map<LocalDate, List<WordEntry>>,
        todayDate: LocalDate,
        extractor: (List<WordEntry>) -> Double,
    ): BaselineComparison {
        val historicalValues = byDate
            .filterKeys { it.isBefore(todayDate) }
            .values
            .map(extractor)
        val sevenDayAverage = rollingAverage(byDate, todayDate, 7, extractor)
        val thirtyDayAverage = rollingAverage(byDate, todayDate, 30, extractor)
        val allTimeAverage = historicalValues.takeIf { it.isNotEmpty() }?.average()

        return BaselineComparison(
            name = name,
            todayValue = today,
            sevenDayAverage = sevenDayAverage,
            thirtyDayAverage = thirtyDayAverage,
            allTimeAverage = allTimeAverage,
            sevenDayDeviationPercentage = deviation(today, sevenDayAverage),
            thirtyDayDeviationPercentage = deviation(today, thirtyDayAverage),
            allTimeDeviationPercentage = deviation(today, allTimeAverage),
            isUnusual = listOfNotNull(
                deviation(today, sevenDayAverage),
                deviation(today, thirtyDayAverage),
                deviation(today, allTimeAverage),
            ).any { abs(it) >= 50.0 },
        )
    }

    private fun rollingAverage(
        byDate: Map<LocalDate, List<WordEntry>>,
        todayDate: LocalDate,
        days: Long,
        extractor: (List<WordEntry>) -> Double,
    ): Double? {
        val historicalValues = byDate
            .filterKeys { date ->
                date.isBefore(todayDate) && ChronoUnit.DAYS.between(date, todayDate) <= days
            }
            .values
            .map(extractor)

        return historicalValues.takeIf { it.isNotEmpty() }?.average()
    }

    private fun buildDashboard(
        general: GeneralMetrics,
        timing: TimingMetrics,
        wordStats: WordStats,
        scores: List<ScoreMetric>,
        timeline: List<TimelineEntry>,
        baselines: List<BaselineComparison>,
        nowUtcMs: Long,
    ): DashboardMetrics {
        val currentSessionStart = timeline.firstOrNull()?.createdAtUtcMs
        val lastWord = timeline.lastOrNull()?.createdAtUtcMs
        return DashboardMetrics(
            currentSessionWords = general.currentSessionWords,
            wordsToday = general.todaysWords,
            currentStreak = currentNoveltyStreak(timeline),
            wordsPerMinute = timing.wordsPerMinute,
            flowScore = scores.firstOrNull { it.name == "Flow" }?.value ?: 0.0,
            noveltyScore = scores.firstOrNull { it.name == "Novelty" }?.value ?: 0.0,
            repetitionScore = scores.firstOrNull { it.name == "Repetition Risk" }?.value ?: 0.0,
            averageWordLength = wordStats.averageLength,
            timeSinceLastWordMs = lastWord?.let { nowUtcMs - it },
            currentSessionDurationMs = currentSessionStart?.let { start -> lastWord?.let { it - start } },
            todayVsSevenDayDeviationPercentage = baselines
                .firstOrNull { it.name == "Words per day" }
                ?.sevenDayDeviationPercentage,
        )
    }

    private fun buildSessionStoryline(
        dashboard: DashboardMetrics,
        entropy: EntropyMetrics,
        vocabulary: VocabularyMetrics,
        rhythm: RhythmMetrics,
        phases: GenerativePhaseMetrics,
        creativeEpisodes: CreativeEpisodeMetrics,
        languageEpochs: LanguageEpochMetrics,
    ): SessionStorylineMetrics {
        val segments = phases.segments.map { phase ->
            val episode = bestOverlapping(
                startOrdinal = phase.startOrdinal,
                endOrdinal = phase.endOrdinal,
                items = creativeEpisodes.episodes,
                itemStart = { it.startOrdinal },
                itemEnd = { it.endOrdinal },
            )
            val epoch = bestOverlapping(
                startOrdinal = phase.startOrdinal,
                endOrdinal = phase.endOrdinal,
                items = languageEpochs.epochs,
                itemStart = { it.startOrdinal },
                itemEnd = { it.endOrdinal },
            )
            SessionStorylineSegment(
                startOrdinal = phase.startOrdinal,
                endOrdinal = phase.endOrdinal,
                phaseLabel = phase.label,
                dominantMotif = phase.dominantFragment ?: episode?.dominantMotif ?: epoch?.dominantMotif,
                episodeLabel = episode?.label,
                epochLabel = epoch?.label,
                noveltyPercentage = phase.noveltyPercentage,
                repetitionPercentage = phase.repetitionPercentage,
                averageEditDistance = phase.averageEditDistance,
                paceWordsPerMinute = phase.paceWordsPerMinute,
                intensity = maxOf(phase.intensity, episode?.intensity ?: 0.0, epoch?.intensity ?: 0.0),
                reading = buildStorylineSegmentReading(phase, episode, epoch),
            )
        }

        val turningPoints = buildSessionStorylineMarkers(creativeEpisodes, languageEpochs)
        return SessionStorylineMetrics(
            headline = buildSessionStorylineHeadline(phases, languageEpochs, segments),
            narrative = buildSessionStorylineNarrative(
                dashboard = dashboard,
                entropy = entropy,
                vocabulary = vocabulary,
                rhythm = rhythm,
                phases = phases,
                creativeEpisodes = creativeEpisodes,
                languageEpochs = languageEpochs,
                segments = segments,
            ),
            segments = segments,
            turningPoints = turningPoints,
            evidence = buildSessionStorylineSignals(dashboard, entropy, vocabulary, rhythm, phases),
        )
    }

    private fun buildSessionStorylineHeadline(
        phases: GenerativePhaseMetrics,
        languageEpochs: LanguageEpochMetrics,
        segments: List<SessionStorylineSegment>,
    ): String {
        if (segments.isEmpty()) return "Keep writing to reveal the session storyline."
        val first = segments.first().phaseLabel.readableLabel()
        val last = segments.last().phaseLabel.readableLabel()
        val dominant = phases.dominantLabel?.readableLabel() ?: first
        val strongestShift = languageEpochs.strongestShift
        return when {
            strongestShift != null -> {
                "The session moved through $dominant and hit ${strongestShift.label.readableLabel()} near word ${strongestShift.boundaryOrdinal}."
            }
            phases.phaseSwitchCount > 0 -> {
                "The session changed mode ${phases.phaseSwitchCount} times, from $first to $last."
            }
            else -> "The session stayed mostly in $dominant."
        }
    }

    private fun buildSessionStorylineNarrative(
        dashboard: DashboardMetrics,
        entropy: EntropyMetrics,
        vocabulary: VocabularyMetrics,
        rhythm: RhythmMetrics,
        phases: GenerativePhaseMetrics,
        creativeEpisodes: CreativeEpisodeMetrics,
        languageEpochs: LanguageEpochMetrics,
        segments: List<SessionStorylineSegment>,
    ): String {
        if (segments.isEmpty()) {
            return "Current pace, novelty, repetition, and entropy are being tracked; a temporal story appears after the session has enough material."
        }

        val sentences = mutableListOf<String>()
        sentences += "It opens in ${segments.first().phaseLabel.readableLabel()} and ends in ${segments.last().phaseLabel.readableLabel()}."
        creativeEpisodes.highlightedEpisode?.let { episode ->
            sentences += "The most revealing creative pocket is ${episode.label.readableLabel()} at words ${episode.startOrdinal}-${episode.endOrdinal}."
        }
        languageEpochs.strongestShift?.let { shift ->
            sentences += "The sharpest language boundary is ${shift.label.readableLabel()} near word ${shift.boundaryOrdinal}: ${shift.motifChange}."
        }
        sentences += when {
            dashboard.noveltyScore >= 80.0 -> "Novelty is high, so the session is still expanding its form-space."
            dashboard.noveltyScore <= 45.0 -> "Novelty is low, so the session is consolidating around familiar forms."
            else -> "Novelty is balanced between new forms and returning material."
        }
        sentences += when {
            dashboard.repetitionScore >= 35.0 -> "Repetition is strong enough that looping is shaping the run."
            dashboard.repetitionScore <= 10.0 -> "Repetition is light, so resurfacing is not the main force today."
            else -> "Repetition is present, but not yet dominant."
        }
        entropy.compressionRatio?.let { compression ->
            sentences += if (compression >= 1.6) {
                "Compression suggests the stream is reusing a compact internal vocabulary."
            } else {
                "Compression is still loose, suggesting a more exploratory surface."
            }
        }
        rhythm.rhythmStability?.let { stability ->
            sentences += if (stability >= 65.0) {
                "The rhythm is stable enough to read the shifts as linguistic rather than just timing noise."
            } else {
                "The rhythm is uneven, so bursts and pauses are part of the story."
            }
        }
        if (phases.phaseSwitchCount == 0 && phases.dominantLabel != null) {
            sentences += "There is no major mode switch yet; the dominant mode carries the session."
        }
        if (vocabulary.longestDuplicateStreak >= 3) {
            sentences += "A duplicate streak shows one form briefly taking over production."
        }
        return sentences.joinToString(" ")
    }

    private fun buildSessionStorylineMarkers(
        creativeEpisodes: CreativeEpisodeMetrics,
        languageEpochs: LanguageEpochMetrics,
    ): List<SessionStorylineMarker> {
        val markers = mutableListOf<SessionStorylineMarker>()
        creativeEpisodes.highlightedEpisode?.let { episode ->
            markers += SessionStorylineMarker(
                label = episode.label,
                ordinal = episode.startOrdinal,
                detail = episode.interpretation,
                tone = if (episode.repetitionPercentage >= 35.0) InsightTone.Attention else InsightTone.Positive,
            )
        }
        languageEpochs.strongestShift?.let { shift ->
            markers += SessionStorylineMarker(
                label = shift.label,
                ordinal = shift.boundaryOrdinal,
                detail = shift.motifChange,
                tone = InsightTone.Attention,
            )
        }
        return markers.distinctBy { it.label to it.ordinal }.take(4)
    }

    private fun buildSessionStorylineSignals(
        dashboard: DashboardMetrics,
        entropy: EntropyMetrics,
        vocabulary: VocabularyMetrics,
        rhythm: RhythmMetrics,
        phases: GenerativePhaseMetrics,
    ): List<SessionStorylineSignal> =
        listOf(
            SessionStorylineSignal(
                label = "Mode",
                value = phases.dominantLabel ?: "Pending",
                detail = "${phases.phaseSwitchCount} switches",
            ),
            SessionStorylineSignal(
                label = "Novelty",
                value = dashboard.noveltyScore.roundedPercent(),
                detail = "Longest new-form run ${vocabulary.longestNoveltyStreak}",
                tone = if (dashboard.noveltyScore >= 75.0) InsightTone.Positive else InsightTone.Neutral,
            ),
            SessionStorylineSignal(
                label = "Looping",
                value = dashboard.repetitionScore.roundedPercent(),
                detail = "Longest duplicate run ${vocabulary.longestDuplicateStreak}",
                tone = if (dashboard.repetitionScore >= 35.0) InsightTone.Attention else InsightTone.Neutral,
            ),
            SessionStorylineSignal(
                label = "Pace",
                value = "${dashboard.wordsPerMinute.formatOneDecimal()} wpm",
                detail = rhythm.rhythmStability?.let { "stability ${it.roundedPercent()}" } ?: "stability pending",
            ),
            SessionStorylineSignal(
                label = "Entropy",
                value = entropy.lexicalEntropyBits?.let { "${it.formatOneDecimal()} bits" } ?: "Pending",
                detail = entropy.compressionRatio?.let { "compression ${it.formatOneDecimal()}" } ?: "compression pending",
            ),
            SessionStorylineSignal(
                label = "Words",
                value = dashboard.currentSessionWords.toString(),
                detail = "current session",
            ),
        )

    private fun buildStorylineSegmentReading(
        phase: GenerativePhaseSegment,
        episode: CreativeEpisode?,
        epoch: LanguageEpoch?,
    ): String {
        val parts = mutableListOf<String>()
        parts += when {
            phase.repetitionPercentage >= 35.0 -> "looping is visible"
            phase.noveltyPercentage >= 80.0 -> "new forms dominate"
            phase.dominantFragment != null -> "motif ${phase.dominantFragment} anchors the window"
            else -> "the window stays open"
        }
        episode?.let { parts += it.interpretation }
        epoch?.dominantMotif?.let { motif -> parts += "epoch motif $motif" }
        return parts.joinToString("; ")
    }

    private fun <T> bestOverlapping(
        startOrdinal: Int,
        endOrdinal: Int,
        items: List<T>,
        itemStart: (T) -> Int,
        itemEnd: (T) -> Int,
    ): T? =
        items.maxByOrNull { item ->
            overlapSize(startOrdinal, endOrdinal, itemStart(item), itemEnd(item))
        }?.takeIf { item ->
            overlapSize(startOrdinal, endOrdinal, itemStart(item), itemEnd(item)) > 0
        }

    private fun overlapSize(
        leftStart: Int,
        leftEnd: Int,
        rightStart: Int,
        rightEnd: Int,
    ): Int =
        (minOf(leftEnd, rightEnd) - maxOf(leftStart, rightStart) + 1).coerceAtLeast(0)

    private fun buildDisclosure(
        entries: List<WordEntry>,
        baselines: List<BaselineComparison>,
        families: FamilyMetrics,
        fragments: FragmentMetrics,
        evolution: EvolutionMetrics,
        phases: GenerativePhaseMetrics,
        creativeEpisodes: CreativeEpisodeMetrics,
        genealogy: WordGenealogyMetrics,
        languageEpochs: LanguageEpochMetrics,
        phonotactics: PhonotacticMetrics,
        consonantSpine: ConsonantSpineMetrics,
        vowelPalette: VowelPaletteMetrics,
        soundShapeDrift: SoundShapeDriftMetrics,
        sessionContrast: SessionContrastMetrics,
        motifRoutes: MotifRouteMetrics,
        motifAttractors: MotifAttractorMetrics,
        memoryEchoes: MemoryEchoMetrics,
        constellations: WordConstellationMetrics,
        familyMigrations: FamilyMigrationMetrics,
    ): ProgressiveDisclosure =
        ProgressiveDisclosure(
            showSimilarity = entries.size >= 50,
            showFragments = fragments.usefulFragments.isNotEmpty() && entries.size >= 75,
            showBaselines = baselines.any { it.sevenDayAverage != null || it.thirtyDayAverage != null } && entries.size >= 30,
            showFamilies = families.families.isNotEmpty() && entries.size >= 50,
            showEvolution = evolution.transitions.isNotEmpty() && entries.size >= 20,
            showGenerativePhases = phases.segments.isNotEmpty(),
            showCreativeEpisodes = creativeEpisodes.episodes.isNotEmpty(),
            showGenealogy = genealogy.lineages.isNotEmpty() && genealogy.links.isNotEmpty(),
            showLanguageEpochs = languageEpochs.shifts.isNotEmpty(),
            showPhonotactics = phonotactics.lanes.isNotEmpty() && phonotactics.transitions.isNotEmpty(),
            showConsonantSpine = consonantSpine.segments.isNotEmpty() && consonantSpine.shifts.isNotEmpty(),
            showVowelPalette = vowelPalette.segments.isNotEmpty() && vowelPalette.shifts.isNotEmpty(),
            showSoundShapeDrift = soundShapeDrift.segments.isNotEmpty(),
            showSessionContrast = sessionContrast.dimensions.isNotEmpty(),
            showMotifRoutes = motifRoutes.routes.isNotEmpty() && motifRoutes.segments.isNotEmpty(),
            showMotifAttractors = motifAttractors.attractors.isNotEmpty() && motifAttractors.timeline.isNotEmpty(),
            showMemoryEchoes = memoryEchoes.links.isNotEmpty() && memoryEchoes.segments.isNotEmpty(),
            showConstellations = constellations.constellations.isNotEmpty() && constellations.edges.isNotEmpty(),
            showFamilyMigrations = familyMigrations.segments.isNotEmpty() &&
                familyMigrations.transitions.any { it.fromFamily != it.toFamily },
        )

    private fun buildEntropyMetrics(entries: List<WordEntry>, fragments: FragmentMetrics): EntropyMetrics {
        val lexicalCounts = entries.groupingBy { it.normalizedWord }.eachCount().values
        val chars = entries.flatMap { it.normalizedWord.toList() }
        val fragmentCounts = fragments.usefulFragments.map { it.count }
        val rawChars = entries.sumOf { it.normalizedWord.length }
        val dictionaryChars = entries.distinctBy { it.normalizedWord }.sumOf { it.normalizedWord.length } + entries.size

        return EntropyMetrics(
            lexicalEntropyBits = lexicalCounts.shannonEntropy(),
            characterEntropyBits = chars.groupingBy { it }.eachCount().values.shannonEntropy(),
            fragmentEntropyBits = fragmentCounts.takeIf { it.isNotEmpty() }?.shannonEntropy(),
            compressionRatio = if (dictionaryChars > 0) rawChars.toDouble() / dictionaryChars.toDouble() else null,
        )
    }

    private fun buildVocabularyMetrics(
        entries: List<WordEntry>,
        occurrences: List<WordOccurrenceSummary>,
        families: FamilyMetrics,
    ): VocabularyMetrics {
        val seen = mutableSetOf<String>()
        var longestNovelty = 0
        var currentNovelty = 0
        var longestDuplicate = 0
        var currentDuplicate = 0
        entries.forEach { entry ->
            if (seen.add(entry.normalizedWord)) {
                currentNovelty += 1
                currentDuplicate = 0
            } else {
                currentDuplicate += 1
                currentNovelty = 0
            }
            longestNovelty = maxOf(longestNovelty, currentNovelty)
            longestDuplicate = maxOf(longestDuplicate, currentDuplicate)
        }

        val firstHalf = entries.take(entries.size / 2)
        val secondHalf = entries.drop(entries.size / 2)
        val firstNovelty = firstHalf.noveltyRate()
        val secondNovelty = secondHalf.noveltyRate()
        val noveltyDecay = firstNovelty.takeIf { it > 0.0 }?.let { (it - secondNovelty) / it * 100.0 }

        return VocabularyMetrics(
            vocabularyGrowthRate = if (entries.isEmpty()) 0.0 else occurrences.size.toDouble() / entries.size.toDouble() * 100.0,
            noveltyDecayPercentage = noveltyDecay,
            longestNoveltyStreak = longestNovelty,
            longestDuplicateStreak = longestDuplicate,
            wordFamilyDiversity = families.families.size,
        )
    }

    private fun buildRhythmMetrics(
        entries: List<WordEntry>,
        similarity: SimilarityMetrics,
        zoneId: ZoneId,
    ): RhythmMetrics {
        val intervalValues = intervals(entries)
        val averageInterval = intervalValues.takeIf { it.isNotEmpty() }?.average()
        val cv = intervalValues.coefficientOfVariation()
        val recurrenceIntervals = entries.groupBy { it.normalizedWord }
            .values
            .flatMap { group ->
                group.sortedBy { it.createdAtUtcMs }
                    .zipWithNext { previous, current -> current.createdAtUtcMs - previous.createdAtUtcMs }
            }
        val hours = entries.groupingBy { Instant.ofEpochMilli(it.createdAtUtcMs).atZone(zoneId).hour }.eachCount()
        val days = entries.groupingBy {
            Instant.ofEpochMilli(it.createdAtUtcMs).atZone(zoneId).dayOfWeek
        }.eachCount()

        return RhythmMetrics(
            sessionDensity = averageInterval?.let { ONE_MINUTE_MS / it } ?: 0.0,
            burstCount = detectBursts(entries),
            rhythmStability = cv?.let { 100.0 / (1.0 + it) },
            wordEvolutionSpeed = similarity.averageEditDistance,
            averageRecurrenceLatencyMs = recurrenceIntervals.takeIf { it.isNotEmpty() }?.average(),
            longestRecurrenceLatencyMs = recurrenceIntervals.maxOrNull(),
            shortestRecurrenceLatencyMs = recurrenceIntervals.minOrNull(),
            circadianPeakHour = hours.maxByOrNull { it.value }?.key,
            weeklyPeakDay = days.maxByOrNull { it.value }?.key
                ?.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
        )
    }

    private fun detectBursts(entries: List<WordEntry>): Int {
        val intervalValues = intervals(entries)
        val average = intervalValues.takeIf { it.isNotEmpty() }?.average() ?: return 0
        val burstThreshold = average / 2.0
        var bursts = 0
        var inBurst = false
        intervalValues.forEach { interval ->
            if (interval <= burstThreshold) {
                if (!inBurst) bursts += 1
                inBurst = true
            } else {
                inBurst = false
            }
        }
        return bursts
    }

    private fun buildFamilyMetrics(occurrences: List<WordOccurrenceSummary>): FamilyMetrics {
        val families = occurrences
            .filter { it.normalizedWord.length >= 3 }
            .groupBy { it.normalizedWord.take(3) }
            .mapNotNull { (prefix, words) ->
                val totalOccurrences = words.sumOf { it.count }
                if (words.size < 2 || totalOccurrences < 3) return@mapNotNull null
                WordFamily(
                    label = prefix,
                    words = words.sortedByDescending { it.count }.map { it.sampleOriginalWord }.take(8),
                    totalOccurrences = totalOccurrences,
                    latestOccurrenceUtcMs = words.maxOf { it.latestOccurrenceUtcMs },
                )
            }
            .sortedWith(compareByDescending<WordFamily> { it.totalOccurrences }.thenBy { it.label })
            .take(12)

        val recurringOccurrences = families.sumOf { it.totalOccurrences }
        val allOccurrences = occurrences.sumOf { it.count }
        return FamilyMetrics(
            families = families,
            recurringFamilyScore = if (allOccurrences == 0) 0.0 else recurringOccurrences.toDouble() / allOccurrences * 100.0,
        )
    }

    private fun buildTimeline(entries: List<WordEntry>, currentSessionId: String?): List<TimelineEntry> {
        val sessionEntries = entries
            .filter { currentSessionId == null || it.sessionId == currentSessionId }
            .takeLast(MAX_TIMELINE_ROWS)
        val seen = mutableMapOf<String, Int>()

        return sessionEntries.mapIndexed { index, entry ->
            val previous = sessionEntries.getOrNull(index - 1)
            val duplicateOrdinal = (seen[entry.normalizedWord] ?: 0) + 1
            seen[entry.normalizedWord] = duplicateOrdinal
            val editDistance = previous?.let { Levenshtein.distance(it.normalizedWord, entry.normalizedWord) }
            TimelineEntry(
                id = entry.id,
                originalWord = entry.originalWord,
                normalizedWord = entry.normalizedWord,
                createdAtUtcMs = entry.createdAtUtcMs,
                elapsedSincePreviousMs = previous?.let { entry.createdAtUtcMs - it.createdAtUtcMs },
                wordLength = entry.originalWord.length,
                duplicateOrdinal = duplicateOrdinal,
                similarityToPrevious = previous?.let { Levenshtein.similarity(it.normalizedWord, entry.normalizedWord) },
                editDistanceToPrevious = editDistance,
            )
        }
    }

    private fun buildInsights(
        dashboard: DashboardMetrics,
        baselines: List<BaselineComparison>,
        vocabulary: VocabularyMetrics,
        rhythm: RhythmMetrics,
        disclosure: ProgressiveDisclosure,
    ): List<Insight> {
        val insights = mutableListOf<Insight>()
        val wordsBaseline = baselines.firstOrNull { it.name == "Words per day" }
        if (wordsBaseline?.sevenDayDeviationPercentage != null) {
            val deviation = wordsBaseline.sevenDayDeviationPercentage
            insights += Insight(
                title = "Today vs usual",
                value = "${deviation.signedPercent()}",
                detail = "Compared with the 7-day average",
                tone = if (abs(deviation) >= 50.0) InsightTone.Attention else InsightTone.Neutral,
            )
        }
        insights += Insight(
            title = "Repetition",
            value = dashboard.repetitionScore.roundedPercent(),
            detail = if (dashboard.repetitionScore >= 25.0) "Duplicates are shaping this session" else "Duplicates are low",
            tone = if (dashboard.repetitionScore >= 25.0) InsightTone.Attention else InsightTone.Positive,
        )
        insights += Insight(
            title = "Flow",
            value = dashboard.flowScore.roundedPercent(),
            detail = rhythm.rhythmStability?.let { "Interval stability" } ?: "Waiting for rhythm",
            tone = if (dashboard.flowScore >= 65.0) InsightTone.Positive else InsightTone.Neutral,
        )
        if (disclosure.showFamilies) {
            insights += Insight(
                title = "Families",
                value = vocabulary.wordFamilyDiversity.toString(),
                detail = "Recurring word-family groups found",
                tone = InsightTone.Neutral,
            )
        }
        return insights.take(4)
    }

    private fun currentNoveltyStreak(timeline: List<TimelineEntry>): Int {
        var streak = 0
        for (entry in timeline.asReversed()) {
            if (entry.duplicateOrdinal == 1) {
                streak += 1
            } else {
                break
            }
        }
        return streak
    }

    private fun List<WordEntry>.noveltyRate(): Double {
        if (isEmpty()) return 0.0
        return distinctBy { it.normalizedWord }.size.toDouble() / size.toDouble()
    }

    private fun duplicatePercentage(entries: List<WordEntry>): Double {
        if (entries.isEmpty()) return 0.0
        val duplicateCount = entries.size - entries.distinctBy { it.normalizedWord }.size
        return entries.size.percentOf(duplicateCount)
    }

    private fun deviation(today: Double, average: Double?): Double? =
        average?.takeIf { it != 0.0 }?.let { (today - it) / it * 100.0 }

    private fun intervals(entries: List<WordEntry>): List<Long> =
        entries.zipWithNext { previous, current -> current.createdAtUtcMs - previous.createdAtUtcMs }
            .filter { it >= 0L }

    private fun commonPrefixLength(left: String, right: String): Int {
        val end = minOf(left.length, right.length)
        var index = 0
        while (index < end && left[index] == right[index]) index += 1
        return index
    }

    private fun commonSuffixLength(left: String, right: String): Int {
        val end = minOf(left.length, right.length)
        var index = 0
        while (index < end && left[left.lastIndex - index] == right[right.lastIndex - index]) index += 1
        return index
    }

    private fun List<String>.toFragmentCounts(limit: Int = 12): List<FragmentCount> =
        groupingBy { it }
            .eachCount()
            .filter { it.value > 1 }
            .map { (fragment, count) -> FragmentCount(fragment, count) }
            .sortedWith(compareByDescending<FragmentCount> { it.count }.thenBy { it.fragment })
            .take(limit)

    private fun String.windowedOrEmpty(length: Int): List<String> =
        if (this.length >= length) windowed(length) else emptyList()

    private fun String.fragmentsForDiscovery(): Set<String> =
        (3..5).flatMap { length -> windowedOrEmpty(length) }.toSet()

    private fun sharesDiscoveryFragment(left: String, right: String): Boolean {
        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length <= right.length) right else left
        return shorter.fragmentsForDiscovery().any { fragment -> longer.contains(fragment) }
    }

    private fun vowelInventory(): List<Char> = listOf('a', 'e', 'i', 'o', 'u')

    private fun Char.isVowel(): Boolean = lowercaseChar() in vowelInventory()

    private fun String.toCvSkeleton(): String =
        map { char ->
            when (char.lowercaseChar()) {
                'a', 'e', 'i', 'o', 'u' -> 'V'
                else -> 'C'
            }
        }.joinToString("")

    private fun String.consonantFrame(): String =
        filter { char -> char.isLetter() && !char.isVowel() }

    private fun Collection<Int>.shannonEntropy(): Double? {
        val total = sum()
        if (total == 0) return null
        return map { count ->
            val probability = count.toDouble() / total.toDouble()
            -probability * (ln(probability) / ln(2.0))
        }.sum()
    }

    private fun List<Long>.coefficientOfVariation(): Double? {
        if (size < 2) return null
        val average = average()
        if (average == 0.0) return null
        val variance = map { value -> (value - average) * (value - average) }.average()
        return sqrt(variance) / average
    }

    private fun List<Int>.medianInt(): Double {
        if (isEmpty()) return 0.0
        return if (size % 2 == 0) {
            (this[size / 2 - 1] + this[size / 2]) / 2.0
        } else {
            this[size / 2].toDouble()
        }
    }

    private fun List<Double>.medianDouble(): Double {
        if (isEmpty()) return 0.0
        return if (size % 2 == 0) {
            (this[size / 2 - 1] + this[size / 2]) / 2.0
        } else {
            this[size / 2]
        }
    }

    private fun List<Int>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else average()

    private fun Int.percentOf(part: Int): Double =
        if (this == 0) 0.0 else part.toDouble() / this.toDouble() * 100.0

    private fun Double.percentileLikeScale(): Double =
        if (this <= 0.0) 0.0 else this / (this + 20.0) * 100.0

    private fun clampScore(value: Double): Double =
        value.coerceIn(0.0, 100.0).roundToInt().toDouble()

    private fun Double.roundedPercent(): String =
        "${roundToInt()}%"

    private fun Double.signedPercent(): String =
        String.format(Locale.US, "%+.0f%%", this)

    private fun Double.formatWholePercent(): String =
        String.format(Locale.US, "%.0f%%", this)

    private fun Double.formatOneDecimal(): String =
        String.format(Locale.US, "%.1f", this)

    private fun String.readableLabel(): String =
        lowercase(Locale.US)

    private data class MutableFragmentStats(
        var count: Int = 0,
        var documentFrequency: Int = 0,
        var firstHalfDocuments: Int = 0,
        var secondHalfDocuments: Int = 0,
    )

    private data class ConsecutiveTransition(
        val previousOriginalWord: String,
        val currentOriginalWord: String,
        val previousNormalizedWord: String,
        val currentNormalizedWord: String,
        val distance: Int,
        val similarity: Double,
    )

    private data class MutationOperatorEvent(
        val index: Int,
        val label: String,
        val distance: Int,
        val example: String,
    )

    private data class DominantFragment(
        val fragment: String,
        val documentCount: Int,
        val documentSharePercentage: Double,
    )

    private data class GenealogyNode(
        val index: Int,
        val originalWord: String,
        val normalizedWord: String,
    )

    private data class GenealogyCandidate(
        val parentIndex: Int,
        val distance: Int,
        val sharedPrefix: Int,
        val sharedSuffix: Int,
        val relationship: String,
    )

    private data class InternalGenealogyLink(
        val parentIndex: Int,
        val childIndex: Int,
        val distance: Int,
        val relationship: String,
        val ordinal: Int,
    )

    private data class EpochFragmentProfile(
        val fragmentProfile: Map<String, Int>,
        val dominantFragment: String?,
        val dominantSharePercentage: Double,
    )

    private data class InternalLanguageEpochProfile(
        val epoch: LanguageEpoch,
        val fragmentProfile: Map<String, Int>,
    )

    private data class InternalSessionProfile(
        val sessionId: String,
        val dimensions: Map<String, Double>,
    )

    private data class MotifRouteCandidate(
        val motif: String,
        val documentCount: Int,
        val strength: Double,
    )

    private data class InternalMotifRun(
        val motif: String,
        val startIndex: Int,
        val endIndex: Int,
        val runIndex: Int,
    )

    private data class MemoryEchoCandidate(
        val priorIndex: Int,
        val distance: Int,
        val gap: Int,
        val sharedPrefix: Int,
        val sharedSuffix: Int,
    )

    private data class InternalConstellationNode(
        val originalWord: String,
        val normalizedWord: String,
    )

    private data class InternalConstellationEdge(
        val leftIndex: Int,
        val rightIndex: Int,
        val distance: Int,
    )

    private data class InternalLatentWordFamily(
        val label: String,
        val normalizedWords: Set<String>,
        val words: List<String>,
        val averageDistance: Double,
    )

    private data class FamilyMigrationLabel(
        val ordinal: Int,
        val originalWord: String,
        val family: String,
    )
}
