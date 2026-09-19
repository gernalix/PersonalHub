package com.wordpulse.app.data

import com.gernalix.personalhub.core.database.PersonalHubDatabase

import androidx.room.withTransaction
import com.wordpulse.app.domain.CaptureWordPolicy
import com.wordpulse.app.domain.CorrectionMetrics
import com.wordpulse.app.domain.Levenshtein
import com.wordpulse.app.domain.PvtCalibrationSample
import com.wordpulse.app.domain.PvtSummary
import com.wordpulse.app.domain.SearchMode
import com.wordpulse.app.domain.SearchSort
import com.wordpulse.app.domain.TextNormalizer
import com.wordpulse.app.domain.TimelineEntry
import com.wordpulse.app.domain.TypingMetrics
import com.wordpulse.app.domain.TypingPerformanceSample
import com.wordpulse.app.domain.WordExplorer
import com.wordpulse.app.domain.WordLengthBand
import com.wordpulse.app.domain.WordSearchResult
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class WordRepository(
    private val database: PersonalHubDatabase,
    private val timeProvider: TimeProvider,
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.wordPulseDao()

    fun observeEntries(): Flow<List<WordEntry>> = dao.observeEntries()

    fun observeCurrentSessionId(): Flow<String?> = dao.observeCurrentSessionId()

    fun observeSessionSummaries(): Flow<List<SessionSummaryRow>> = dao.observeSessionSummaries()

    fun observeLatestPvtSummary(): Flow<PvtSummary?> =
        dao.observeLatestPvtResult().map { it?.toSummary() }

    fun observeSearchResults(
        query: String,
        mode: SearchMode,
        sort: SearchSort,
        limit: Int = MAX_SEARCH_RESULTS,
    ): Flow<List<WordSearchResult>> {
        val normalizedQuery = TextNormalizer.normalize(query)
        if (normalizedQuery.isBlank() || mode in QUERY_BACKED_SEARCH_MODES) {
            return observeSearchLikeRows(
                pattern = normalizedQuery.toLikePattern(mode),
                sort = sort,
                limit = limit,
            ).map { rows -> rows.map { it.toSearchResult() } }
        }

        return observeEntries().map { entries ->
            WordExplorer.search(
                entries = entries,
                query = query,
                mode = mode,
                sort = sort,
            ).take(limit)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCurrentSessionTimeline(limit: Int = MAX_TIMELINE_ROWS): Flow<List<TimelineEntry>> =
        observeCurrentSessionId().flatMapLatest { sessionId ->
            if (sessionId == null) {
                flowOf(emptyList())
            } else {
                dao.observeRecentTimelineRows(sessionId, limit)
                    .map(::buildTimelineEntries)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDashboardSnapshot(zoneId: ZoneId): Flow<DashboardSnapshot> {
        val nowUtcMs = timeProvider.nowUtcMs()
        val today = Instant.ofEpochMilli(nowUtcMs).atZone(zoneId).toLocalDate()
        val todayStartUtcMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val tomorrowStartUtcMs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val sevenDaysAgoUtcMs = nowUtcMs - 7 * ONE_DAY_MS
        val sessionSnapshot = observeCurrentSessionId().flatMapLatest { sessionId ->
            if (sessionId == null) {
                flowOf(SessionSnapshot())
            } else {
                combine(
                    dao.observeSessionWordCount(sessionId),
                    dao.observeSessionStartedAtUtcMs(sessionId),
                    dao.observeSessionLatestWordUtcMs(sessionId),
                ) { count, startedAt, latestWordAt ->
                    SessionSnapshot(
                        currentSessionWords = count,
                        currentSessionStartedAtUtcMs = startedAt,
                        currentSessionLatestWordUtcMs = latestWordAt,
                    )
                }
            }
        }

        return combine(
            dao.observeTotalWordCount(),
            dao.observeUniqueWordCount(),
            dao.observeWordCountBetween(todayStartUtcMs, tomorrowStartUtcMs),
            dao.observeWordCountSince(sevenDaysAgoUtcMs),
            sessionSnapshot,
        ) { total, unique, todayCount, lastSevenDaysCount, session ->
            DashboardSnapshot(
                totalWords = total,
                uniqueWords = unique,
                todaysWords = todayCount,
                lastSevenDaysWords = lastSevenDaysCount,
                currentSessionWords = session.currentSessionWords,
                currentSessionStartedAtUtcMs = session.currentSessionStartedAtUtcMs,
                currentSessionLatestWordUtcMs = session.currentSessionLatestWordUtcMs,
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCorrectionMetrics(): Flow<CorrectionMetrics> =
        observeCurrentSessionId().flatMapLatest { sessionId ->
            if (sessionId == null) {
                flowOf(CorrectionMetrics())
            } else {
                val counts = combine(
                    dao.observeTotalCorrectionCount(),
                    dao.observeSessionCorrectionCount(sessionId),
                    dao.observeTotalWordCount(),
                    dao.observeSessionWordCount(sessionId),
                ) { totalCorrections, sessionCorrections, activeWords, sessionActiveWords ->
                    CorrectionCounts(totalCorrections, sessionCorrections, activeWords, sessionActiveWords)
                }
                val latencies = combine(
                    dao.observeLastCorrectionLatencyMs(),
                    dao.observeCorrectionLatenciesMs(),
                ) { lastLatency, allLatencies ->
                    CorrectionLatencies(lastLatency, allLatencies)
                }
                combine(counts, latencies) { correctionCounts, correctionLatencies ->
                    CorrectionMetrics(
                        totalCorrections = correctionCounts.totalCorrections,
                        currentSessionCorrections = correctionCounts.sessionCorrections,
                        correctionRatePercentage = correctionRate(
                            corrections = correctionCounts.totalCorrections,
                            activeWords = correctionCounts.activeWords,
                        ),
                        currentSessionCorrectionRatePercentage = correctionRate(
                            corrections = correctionCounts.sessionCorrections,
                            activeWords = correctionCounts.sessionActiveWords,
                        ),
                        lastCorrectionLatencyMs = correctionLatencies.lastLatencyMs,
                        medianCorrectionLatencyMs = correctionLatencies.latenciesMs.medianOrNull(),
                    )
                }
            }
        }

    suspend fun ensureCurrentSession(): WordSession =
        database.withTransaction {
            ensureCurrentSessionInsideTransaction()
        }

    suspend fun startNewSession(): WordSession =
        database.withTransaction {
            createSessionInsideTransaction(endPrevious = true)
        }

    suspend fun submitWord(
        rawWord: String,
        requestedSessionId: String?,
        typingMetrics: TypingMetrics? = null,
    ): SubmissionResult {
        require(CaptureWordPolicy.isValidWord(rawWord)) {
            "Captured words must match [a-z]+"
        }
        require(typingMetrics == null || typingMetrics.finalCharacterCount == rawWord.length) {
            "Typing metrics must describe the submitted word"
        }

        return database.withTransaction {
            val session = requestedSessionId?.let { dao.getSession(it) } ?: ensureCurrentSessionInsideTransaction()
            val duplicateHistory = dao.getDuplicateHistory(rawWord)
            val submittedAtUtcMs = typingMetrics?.submittedAtUtcMs ?: timeProvider.nowUtcMs()
            val entry = WordEntry(
                originalWord = rawWord,
                normalizedWord = rawWord,
                createdAtUtcMs = submittedAtUtcMs,
                sessionId = session.id,
                capturePolicyVersion = CaptureWordPolicy.VERSION,
                typingStartedAtUtcMs = typingMetrics?.typingStartedAtUtcMs,
                submittedAtUtcMs = submittedAtUtcMs,
                typingDurationMs = typingMetrics?.typingDurationMs,
                finalCharacterCount = typingMetrics?.finalCharacterCount ?: rawWord.length,
                insertedCharacterCount = typingMetrics?.insertedCharacterCount,
                deletedCharacterCount = typingMetrics?.deletedCharacterCount,
                replacementCount = typingMetrics?.replacementCount,
                correctionActionCount = typingMetrics?.correctionActionCount,
                longestInterKeyPauseMs = typingMetrics?.longestInterKeyPauseMs,
                meanInterKeyIntervalMs = typingMetrics?.meanInterKeyIntervalMs,
                interKeyIntervalVariabilityMs = typingMetrics?.interKeyIntervalVariabilityMs,
                invalidInputAttemptCount = typingMetrics?.invalidInputAttemptCount,
                medianInterKeyIntervalMs = typingMetrics?.medianInterKeyIntervalMs,
                p95InterKeyIntervalMs = typingMetrics?.p95InterKeyIntervalMs,
                interKeyIntervalCoefficientOfVariation = typingMetrics?.interKeyIntervalCoefficientOfVariation,
                microPauseCount = typingMetrics?.microPauseCount,
                lastEditToSubmitMs = typingMetrics?.lastEditToSubmitMs,
            )
            val insertedId = dao.insertWord(entry)
            SubmissionResult(
                insertedEntryId = insertedId,
                originalWord = rawWord,
                normalizedWord = rawWord,
                sessionId = session.id,
                submittedAtUtcMs = submittedAtUtcMs,
                previousOccurrences = duplicateHistory.previousOccurrences,
                totalOccurrences = duplicateHistory.previousOccurrences + 1,
                previousOccurrenceUtcMs = duplicateHistory.previousOccurrenceUtcMs,
                typingMetrics = typingMetrics,
            )
        }
    }

    suspend fun getTypingBaselineSamples(
        excludedEntryId: Long,
        excludedSessionId: String,
        finalCharacterCount: Int,
        limit: Int = MAX_TYPING_BASELINE_SAMPLES,
    ): List<TypingPerformanceSample> {
        val (minimumLength, maximumLength) = when (WordLengthBand.fromLength(finalCharacterCount)) {
            WordLengthBand.Short -> 1 to 4
            WordLengthBand.Medium -> 5 to 8
            WordLengthBand.Long -> 9 to Int.MAX_VALUE
        }
        return dao.getRecentTypingPerformanceRows(
            excludedEntryId = excludedEntryId,
            excludedSessionId = excludedSessionId,
            minimumLength = minimumLength,
            maximumLength = maximumLength,
            limit = limit,
        ).mapNotNull(TypingPerformanceRow::toPerformanceSample)
    }

    suspend fun getSessionTypingSamples(
        sessionId: String,
        excludedEntryId: Long,
        limit: Int = MAX_SESSION_TYPING_SAMPLES,
    ): List<TypingPerformanceSample> =
        dao.getSessionTypingPerformanceRows(
            sessionId = sessionId,
            excludedEntryId = excludedEntryId,
            limit = limit,
        ).mapNotNull(TypingPerformanceRow::toPerformanceSample)

    suspend fun getPvtCalibrationSamples(limit: Int = MAX_PVT_CALIBRATION_SAMPLES): List<PvtCalibrationSample> =
        dao.getRecentPvtResults(limit).mapNotNull(PvtResultEntity::toCalibrationSample)

    suspend fun savePvtResult(result: PvtResultEntity): Long = dao.insertPvtResult(result)

    suspend fun saveFatigueScore(entryId: Long, score: Int?) {
        require(score == null || score in 0..100)
        check(dao.updateFatigueScore(entryId, score) == 1) { "Fatigue target disappeared" }
    }

    suspend fun deleteAllData(): WordSession {
        val formerSessionIds = dao.getSessions().map { it.id }
        val created = database.withTransaction {
            dao.deleteCorrectionEvents()
            dao.deletePvtResults()
            dao.deleteWords()
            dao.deleteSessions()
            dao.deleteAppState()
            createSessionInsideTransaction(endPrevious = false)
        }
        formerSessionIds.filter { it != created.id }.forEach {
            com.gernalix.personalhub.core.hubcontext.HubContextRuntime.canonicalDeletedIfInitialized(
                com.gernalix.personalhub.contracts.database.HubEntityRef("wordpulse", "word_session", it),
            )
        }
        return created
    }

    suspend fun correctSubmittedWord(
        wordEntryId: Long,
        originalWord: String,
        normalizedWord: String,
        sessionId: String,
        submittedAtUtcMs: Long,
        actionType: String = CORRECTION_ACTION_EDIT,
    ): CorrectionEvent? =
        database.withTransaction {
            val entry = dao.getWordById(wordEntryId) ?: return@withTransaction null
            if (
                entry.originalWord != originalWord ||
                entry.normalizedWord != normalizedWord ||
                entry.sessionId != sessionId ||
                entry.createdAtUtcMs != submittedAtUtcMs
            ) {
                return@withTransaction null
            }

            val correctedAtUtcMs = timeProvider.nowUtcMs()
            val event = CorrectionEvent(
                originalWordEntryId = entry.id,
                originalWord = entry.originalWord,
                normalizedWord = entry.normalizedWord,
                sessionId = entry.sessionId,
                submittedAtUtcMs = entry.createdAtUtcMs,
                correctedAtUtcMs = correctedAtUtcMs,
                correctionLatencyMs = (correctedAtUtcMs - entry.createdAtUtcMs).coerceAtLeast(0L),
                actionType = actionType,
            )
            val eventId = dao.insertCorrectionEvent(event)
            if (dao.deleteWordById(wordEntryId) == 0) {
                error("Correction target disappeared during transaction")
            }
            event.copy(id = eventId)
        }

    private suspend fun createSessionInsideTransaction(endPrevious: Boolean): WordSession {
        val now = timeProvider.nowUtcMs()
        if (endPrevious) {
            val previousSession = dao.getAppState()?.currentSessionId?.let { dao.getSession(it) }
            if (previousSession != null && previousSession.endedAtUtcMs == null) {
                dao.updateSession(previousSession.copy(endedAtUtcMs = now))
            }
        }

        val session = WordSession(
            id = idProvider(),
            startedAtUtcMs = now,
        )
        dao.insertSession(session)
        dao.upsertAppState(AppStateEntity(currentSessionId = session.id))
        return session
    }

    private suspend fun ensureCurrentSessionInsideTransaction(): WordSession {
        val stateSession = dao.getAppState()?.currentSessionId?.let { dao.getSession(it) }
        if (stateSession != null) return stateSession

        val latestSession = dao.getLatestSession()
        if (latestSession != null) {
            dao.upsertAppState(AppStateEntity(currentSessionId = latestSession.id))
            return latestSession
        }

        return createSessionInsideTransaction(endPrevious = false)
    }

    private data class SessionSnapshot(
        val currentSessionWords: Int = 0,
        val currentSessionStartedAtUtcMs: Long? = null,
        val currentSessionLatestWordUtcMs: Long? = null,
    )

    private data class CorrectionCounts(
        val totalCorrections: Int,
        val sessionCorrections: Int,
        val activeWords: Int,
        val sessionActiveWords: Int,
    )

    private data class CorrectionLatencies(
        val lastLatencyMs: Long?,
        val latenciesMs: List<Long>,
    )

    private fun buildTimelineEntries(rows: List<TimelineEntryRow>): List<TimelineEntry> =
        rows.mapIndexed { index, row ->
            val previous = rows.getOrNull(index - 1)
            val editDistance = previous?.let {
                Levenshtein.distance(it.normalizedWord, row.normalizedWord)
            }
            TimelineEntry(
                id = row.id,
                originalWord = row.originalWord,
                normalizedWord = row.normalizedWord,
                createdAtUtcMs = row.createdAtUtcMs,
                elapsedSincePreviousMs = previous?.let { row.createdAtUtcMs - it.createdAtUtcMs },
                wordLength = row.originalWord.length,
                duplicateOrdinal = row.duplicateOrdinal,
                similarityToPrevious = previous?.let {
                    Levenshtein.similarity(it.normalizedWord, row.normalizedWord)
                },
                editDistanceToPrevious = editDistance,
            )
        }

    private fun observeSearchLikeRows(
        pattern: String,
        sort: SearchSort,
        limit: Int,
    ): Flow<List<SearchResultRow>> =
        when (sort) {
            SearchSort.Frequency -> dao.observeSearchLikeByFrequency(pattern, limit)
            SearchSort.Recency -> dao.observeSearchLikeByRecency(pattern, limit)
            SearchSort.Alphabetical -> dao.observeSearchLikeAlphabetically(pattern, limit)
            SearchSort.Length -> dao.observeSearchLikeByLength(pattern, limit)
        }

    private fun String.toLikePattern(mode: SearchMode): String {
        val escaped = escapeLikePattern()
        return when (mode) {
            SearchMode.Prefix -> "$escaped%"
            SearchMode.Suffix -> "%$escaped"
            SearchMode.Substring,
            SearchMode.Fuzzy,
            SearchMode.Similar,
            SearchMode.Regex -> "%$escaped%"
        }
    }

    private fun String.escapeLikePattern(): String =
        replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun SearchResultRow.toSearchResult(): WordSearchResult =
        WordSearchResult(
            normalizedWord = normalizedWord,
            sampleOriginalWord = sampleOriginalWord,
            occurrences = occurrences,
            latestOccurrenceUtcMs = latestOccurrenceUtcMs,
            length = length,
        )

    private fun correctionRate(corrections: Int, activeWords: Int): Double {
        val attemptedSubmissions = activeWords + corrections
        return if (attemptedSubmissions == 0) {
            0.0
        } else {
            corrections.toDouble() / attemptedSubmissions.toDouble() * 100.0
        }
    }

    private fun List<Long>.medianOrNull(): Double? {
        if (isEmpty()) return null
        return if (size % 2 == 0) {
            (this[size / 2 - 1] + this[size / 2]).toDouble() / 2.0
        } else {
            this[size / 2].toDouble()
        }
    }

    private companion object {
        const val CORRECTION_ACTION_EDIT = "edit"
        const val ONE_DAY_MS = 86_400_000L
        const val MAX_TIMELINE_ROWS = 500
        const val MAX_SEARCH_RESULTS = 100
        const val MAX_TYPING_BASELINE_SAMPLES = 300
        const val MAX_SESSION_TYPING_SAMPLES = 60
        const val MAX_PVT_CALIBRATION_SAMPLES = 50
        val QUERY_BACKED_SEARCH_MODES = setOf(
            SearchMode.Substring,
            SearchMode.Prefix,
            SearchMode.Suffix,
        )
    }
}
