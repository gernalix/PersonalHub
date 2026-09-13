package com.wordpulse.app.data

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
    private val database: WordPulseDatabase,
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

    suspend fun exportCsv(): String {
        val entries = dao.getEntries()
        return CsvTableCodec.encode(
            header = WORD_EXPORT_COLUMNS,
            rows = entries.map { entry ->
                listOf(
                    entry.id,
                    entry.originalWord,
                    entry.normalizedWord,
                    entry.createdAtUtcMs,
                    entry.sessionId,
                    entry.capturePolicyVersion,
                    entry.typingStartedAtUtcMs,
                    entry.submittedAtUtcMs,
                    entry.typingDurationMs,
                    entry.finalCharacterCount,
                    entry.insertedCharacterCount,
                    entry.deletedCharacterCount,
                    entry.replacementCount,
                    entry.correctionActionCount,
                    entry.longestInterKeyPauseMs,
                    entry.meanInterKeyIntervalMs,
                    entry.interKeyIntervalVariabilityMs,
                    entry.invalidInputAttemptCount,
                    entry.medianInterKeyIntervalMs,
                    entry.p95InterKeyIntervalMs,
                    entry.interKeyIntervalCoefficientOfVariation,
                    entry.microPauseCount,
                    entry.lastEditToSubmitMs,
                    entry.fatigueScore,
                )
            },
        )
    }

    suspend fun exportBackupCsv(): String {
        val sessions = dao.getSessions()
        val entries = dao.getEntries()
        val corrections = dao.getCorrectionEvents()
        val pvtResults = dao.getPvtResults()
        val rows = buildList {
            sessions.forEach { session ->
                add(
                    backupRow(
                        "record_type" to "session",
                        "session_id" to session.id,
                        "session_started_at_utc_ms" to session.startedAtUtcMs,
                        "session_ended_at_utc_ms" to session.endedAtUtcMs,
                    ),
                )
            }
            entries.forEach { entry ->
                add(
                    backupRow(
                        "record_type" to "word",
                        "session_id" to entry.sessionId,
                        "word_id" to entry.id,
                        "original_word" to entry.originalWord,
                        "normalized_word" to entry.normalizedWord,
                        "created_at_utc_ms" to entry.createdAtUtcMs,
                        "capture_policy_version" to entry.capturePolicyVersion,
                        "typing_started_at_utc_ms" to entry.typingStartedAtUtcMs,
                        "submitted_at_utc_ms" to entry.submittedAtUtcMs,
                        "typing_duration_ms" to entry.typingDurationMs,
                        "final_character_count" to entry.finalCharacterCount,
                        "inserted_character_count" to entry.insertedCharacterCount,
                        "deleted_character_count" to entry.deletedCharacterCount,
                        "replacement_count" to entry.replacementCount,
                        "correction_action_count" to entry.correctionActionCount,
                        "longest_inter_key_pause_ms" to entry.longestInterKeyPauseMs,
                        "mean_inter_key_interval_ms" to entry.meanInterKeyIntervalMs,
                        "inter_key_interval_variability_ms" to entry.interKeyIntervalVariabilityMs,
                        "invalid_input_attempt_count" to entry.invalidInputAttemptCount,
                        "median_inter_key_interval_ms" to entry.medianInterKeyIntervalMs,
                        "p95_inter_key_interval_ms" to entry.p95InterKeyIntervalMs,
                        "inter_key_interval_cv" to entry.interKeyIntervalCoefficientOfVariation,
                        "micro_pause_count" to entry.microPauseCount,
                        "last_edit_to_submit_ms" to entry.lastEditToSubmitMs,
                        "fatigue_score" to entry.fatigueScore,
                    ),
                )
            }
            corrections.forEach { correction ->
                add(
                    backupRow(
                        "record_type" to "correction",
                        "session_id" to correction.sessionId,
                        "correction_id" to correction.id,
                        "original_word_entry_id" to correction.originalWordEntryId,
                        "correction_original_word" to correction.originalWord,
                        "correction_normalized_word" to correction.normalizedWord,
                        "correction_submitted_at_utc_ms" to correction.submittedAtUtcMs,
                        "corrected_at_utc_ms" to correction.correctedAtUtcMs,
                        "correction_latency_ms" to correction.correctionLatencyMs,
                        "correction_action_type" to correction.actionType,
                    ),
                )
            }
            pvtResults.forEach { result ->
                add(
                    backupRow(
                        "record_type" to "pvt",
                        "pvt_id" to result.id,
                        "pvt_started_at_utc_ms" to result.startedAtUtcMs,
                        "pvt_completed_at_utc_ms" to result.completedAtUtcMs,
                        "pvt_duration_ms" to result.durationMs,
                        "pvt_trial_count" to result.trialCount,
                        "pvt_median_reaction_time_ms" to result.medianReactionTimeMs,
                        "pvt_p90_reaction_time_ms" to result.p90ReactionTimeMs,
                        "pvt_lapse_count" to result.lapseCount,
                        "pvt_false_start_count" to result.falseStartCount,
                        "pvt_paired_fatigue_score" to result.pairedFatigueScore,
                        "pvt_speed_domain_score" to result.speedDomainScore,
                        "pvt_rhythm_domain_score" to result.rhythmDomainScore,
                        "pvt_control_domain_score" to result.controlDomainScore,
                        "pvt_session_drift_domain_score" to result.sessionDriftDomainScore,
                        "pvt_sleep_context_domain_score" to result.sleepContextDomainScore,
                        "pvt_hours_awake" to result.hoursAwake,
                    ),
                )
            }
        }
        return CsvTableCodec.encode(BACKUP_EXPORT_COLUMNS, rows)
    }

    suspend fun importCsv(csv: String): BackupImportResult =
        database.withTransaction {
            val table = CsvTableCodec.decode(csv)
                ?: return@withTransaction BackupImportResult(0, 0)
            if (table.header.firstOrNull() == "record_type") {
                importBackupRows(table.header, table.rows)
            } else {
                importWordRows(table.header, table.rows)
            }
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

    private suspend fun importBackupRows(header: List<String>, rows: List<List<String>>): BackupImportResult {
        val columns = header.withIndex().associate { it.value to it.index }
        var sessionsImported = 0
        var correctionsImported = 0
        var pvtResultsImported = 0
        val words = mutableListOf<WordEntry>()
        rows.forEach { row ->
            when (row.cell(columns, "record_type")) {
                "session" -> {
                    val id = row.cell(columns, "session_id").ifBlank { idProvider() }
                    val startedAt = row.cell(columns, "session_started_at_utc_ms").toLongOrNull() ?: timeProvider.nowUtcMs()
                    val endedAt = row.cell(columns, "session_ended_at_utc_ms").toLongOrNull()
                    if (dao.insertSessionIfAbsent(WordSession(id, startedAt, endedAt)) != -1L) {
                        sessionsImported += 1
                    }
                }
                "word" -> {
                    val sessionId = row.cell(columns, "session_id").ifBlank { ensureCurrentSessionInsideTransaction().id }
                    dao.insertSessionIfAbsent(
                        WordSession(
                            sessionId,
                            row.cell(columns, "created_at_utc_ms").toLongOrNull() ?: timeProvider.nowUtcMs(),
                        ),
                    )
                    row.toImportedWord(columns, sessionId)?.let(words::add)
                }
                "correction" -> {
                    val sessionId = row.cell(columns, "session_id")
                    val originalWordEntryId = row.cell(columns, "original_word_entry_id").toLongOrNull()
                    val originalWord = row.cell(columns, "correction_original_word")
                    val normalizedWord = row.cell(columns, "correction_normalized_word")
                    val submittedAt = row.cell(columns, "correction_submitted_at_utc_ms").toLongOrNull()
                    val correctedAt = row.cell(columns, "corrected_at_utc_ms").toLongOrNull()
                    val latency = row.cell(columns, "correction_latency_ms").toLongOrNull()
                    val actionType = row.cell(columns, "correction_action_type")
                    if (
                        sessionId.isNotBlank() &&
                        originalWordEntryId != null &&
                        originalWord.isNotBlank() &&
                        normalizedWord.isNotBlank() &&
                        submittedAt != null &&
                        correctedAt != null &&
                        latency != null &&
                        actionType.isNotBlank()
                    ) {
                        dao.insertSessionIfAbsent(WordSession(sessionId, submittedAt))
                        dao.insertCorrectionEvent(
                            CorrectionEvent(
                                originalWordEntryId = originalWordEntryId,
                                originalWord = originalWord,
                                normalizedWord = normalizedWord,
                                sessionId = sessionId,
                                submittedAtUtcMs = submittedAt,
                                correctedAtUtcMs = correctedAt,
                                correctionLatencyMs = latency.coerceAtLeast(0L),
                                actionType = actionType,
                            ),
                        )
                        correctionsImported += 1
                    }
                }
                "pvt" -> row.toImportedPvt(columns)?.let { pvt ->
                    dao.insertPvtResult(pvt)
                    pvtResultsImported += 1
                }
            }
        }
        if (words.isNotEmpty()) dao.insertWords(words)
        ensureCurrentSessionInsideTransaction()
        return BackupImportResult(
            sessionsImported = sessionsImported,
            wordsImported = words.size,
            correctionsImported = correctionsImported,
            pvtResultsImported = pvtResultsImported,
        )
    }

    private suspend fun importWordRows(header: List<String>, rows: List<List<String>>): BackupImportResult {
        val columns = header.withIndex().associate { it.value to it.index }
        val fallbackSession = ensureCurrentSessionInsideTransaction()
        val words = rows.mapNotNull { row ->
            val sessionId = row.cell(columns, "session_id").ifBlank { fallbackSession.id }
            row.toImportedWord(columns, sessionId)
        }
        words.map { it.sessionId }.distinct().forEach { sessionId ->
            dao.insertSessionIfAbsent(WordSession(sessionId, timeProvider.nowUtcMs()))
        }
        if (words.isNotEmpty()) dao.insertWords(words)
        return BackupImportResult(0, words.size)
    }

    private fun List<String>.cell(columns: Map<String, Int>, column: String): String =
        columns[column]?.let { getOrNull(it) }.orEmpty()

    private fun backupRow(vararg values: Pair<String, Any?>): List<Any?> {
        val valueMap = values.toMap()
        return BACKUP_EXPORT_COLUMNS.map(valueMap::get)
    }

    private fun List<String>.toImportedWord(
        columns: Map<String, Int>,
        sessionId: String,
    ): WordEntry? {
        val capturePolicyVersion = cell(columns, "capture_policy_version").ifBlank { null }
        val rawOriginal = cell(columns, "original_word")
        val original = if (capturePolicyVersion == CaptureWordPolicy.VERSION) rawOriginal else rawOriginal.trim()
        val isValid = if (capturePolicyVersion == CaptureWordPolicy.VERSION) {
            CaptureWordPolicy.isValidWord(original)
        } else {
            CaptureWordPolicy.isValidLegacyImportWord(original)
        }
        if (!isValid) return null

        val normalized = if (capturePolicyVersion == CaptureWordPolicy.VERSION) {
            original
        } else {
            cell(columns, "normalized_word").ifBlank { TextNormalizer.normalize(original) }
        }
        if (normalized.isBlank()) return null
        val createdAt = cell(columns, "created_at_utc_ms").toLongOrNull() ?: timeProvider.nowUtcMs()
        return WordEntry(
            originalWord = original,
            normalizedWord = normalized,
            createdAtUtcMs = createdAt,
            sessionId = sessionId,
            capturePolicyVersion = capturePolicyVersion,
            typingStartedAtUtcMs = cell(columns, "typing_started_at_utc_ms").toNullableLong(),
            submittedAtUtcMs = cell(columns, "submitted_at_utc_ms").toNullableLong(),
            typingDurationMs = cell(columns, "typing_duration_ms").toNullableLong(),
            finalCharacterCount = cell(columns, "final_character_count").toNullableInt(),
            insertedCharacterCount = cell(columns, "inserted_character_count").toNullableInt(),
            deletedCharacterCount = cell(columns, "deleted_character_count").toNullableInt(),
            replacementCount = cell(columns, "replacement_count").toNullableInt(),
            correctionActionCount = cell(columns, "correction_action_count").toNullableInt(),
            longestInterKeyPauseMs = cell(columns, "longest_inter_key_pause_ms").toNullableLong(),
            meanInterKeyIntervalMs = cell(columns, "mean_inter_key_interval_ms").toNullableDouble(),
            interKeyIntervalVariabilityMs = cell(
                columns,
                "inter_key_interval_variability_ms",
            ).toNullableDouble(),
            invalidInputAttemptCount = cell(columns, "invalid_input_attempt_count").toNullableInt(),
            medianInterKeyIntervalMs = cell(columns, "median_inter_key_interval_ms").toNullableDouble(),
            p95InterKeyIntervalMs = cell(columns, "p95_inter_key_interval_ms").toNullableDouble(),
            interKeyIntervalCoefficientOfVariation = cell(columns, "inter_key_interval_cv").toNullableDouble(),
            microPauseCount = cell(columns, "micro_pause_count").toNullableInt(),
            lastEditToSubmitMs = cell(columns, "last_edit_to_submit_ms").toNullableLong(),
            fatigueScore = cell(columns, "fatigue_score").toNullableInt()?.takeIf { it in 0..100 },
        )
    }

    private fun List<String>.toImportedPvt(columns: Map<String, Int>): PvtResultEntity? {
        val startedAt = cell(columns, "pvt_started_at_utc_ms").toLongOrNull() ?: return null
        val completedAt = cell(columns, "pvt_completed_at_utc_ms").toLongOrNull() ?: return null
        val duration = cell(columns, "pvt_duration_ms").toLongOrNull() ?: return null
        val trials = cell(columns, "pvt_trial_count").toIntOrNull() ?: return null
        val lapses = cell(columns, "pvt_lapse_count").toIntOrNull() ?: return null
        val falseStarts = cell(columns, "pvt_false_start_count").toIntOrNull() ?: return null
        if (completedAt < startedAt || duration < 0L || trials < 0 || lapses < 0 || falseStarts < 0) return null
        return PvtResultEntity(
            startedAtUtcMs = startedAt,
            completedAtUtcMs = completedAt,
            durationMs = duration,
            trialCount = trials,
            medianReactionTimeMs = cell(columns, "pvt_median_reaction_time_ms").toNullableDouble(),
            p90ReactionTimeMs = cell(columns, "pvt_p90_reaction_time_ms").toNullableDouble(),
            lapseCount = lapses,
            falseStartCount = falseStarts,
            pairedFatigueScore = cell(columns, "pvt_paired_fatigue_score").toNullableInt(),
            speedDomainScore = cell(columns, "pvt_speed_domain_score").toNullableInt(),
            rhythmDomainScore = cell(columns, "pvt_rhythm_domain_score").toNullableInt(),
            controlDomainScore = cell(columns, "pvt_control_domain_score").toNullableInt(),
            sessionDriftDomainScore = cell(columns, "pvt_session_drift_domain_score").toNullableInt(),
            sleepContextDomainScore = cell(columns, "pvt_sleep_context_domain_score").toNullableInt(),
            hoursAwake = cell(columns, "pvt_hours_awake").toNullableDouble(),
        )
    }

    private fun String.toNullableLong(): Long? =
        toLongOrNull()?.takeIf { it >= 0L }

    private fun String.toNullableInt(): Int? =
        toIntOrNull()?.takeIf { it >= 0 }

    private fun String.toNullableDouble(): Double? =
        toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

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
        val WORD_EXPORT_COLUMNS = listOf(
            "id",
            "original_word",
            "normalized_word",
            "created_at_utc_ms",
            "session_id",
            "capture_policy_version",
            "typing_started_at_utc_ms",
            "submitted_at_utc_ms",
            "typing_duration_ms",
            "final_character_count",
            "inserted_character_count",
            "deleted_character_count",
            "replacement_count",
            "correction_action_count",
            "longest_inter_key_pause_ms",
            "mean_inter_key_interval_ms",
            "inter_key_interval_variability_ms",
            "invalid_input_attempt_count",
            "median_inter_key_interval_ms",
            "p95_inter_key_interval_ms",
            "inter_key_interval_cv",
            "micro_pause_count",
            "last_edit_to_submit_ms",
            "fatigue_score",
        )
        val BACKUP_EXPORT_COLUMNS = listOf(
            "record_type",
            "session_id",
            "session_started_at_utc_ms",
            "session_ended_at_utc_ms",
            "word_id",
            "original_word",
            "normalized_word",
            "created_at_utc_ms",
            "capture_policy_version",
            "typing_started_at_utc_ms",
            "submitted_at_utc_ms",
            "typing_duration_ms",
            "final_character_count",
            "inserted_character_count",
            "deleted_character_count",
            "replacement_count",
            "correction_action_count",
            "longest_inter_key_pause_ms",
            "mean_inter_key_interval_ms",
            "inter_key_interval_variability_ms",
            "invalid_input_attempt_count",
            "median_inter_key_interval_ms",
            "p95_inter_key_interval_ms",
            "inter_key_interval_cv",
            "micro_pause_count",
            "last_edit_to_submit_ms",
            "fatigue_score",
            "correction_id",
            "original_word_entry_id",
            "correction_original_word",
            "correction_normalized_word",
            "correction_submitted_at_utc_ms",
            "corrected_at_utc_ms",
            "correction_latency_ms",
            "correction_action_type",
            "pvt_id",
            "pvt_started_at_utc_ms",
            "pvt_completed_at_utc_ms",
            "pvt_duration_ms",
            "pvt_trial_count",
            "pvt_median_reaction_time_ms",
            "pvt_p90_reaction_time_ms",
            "pvt_lapse_count",
            "pvt_false_start_count",
            "pvt_paired_fatigue_score",
            "pvt_speed_domain_score",
            "pvt_rhythm_domain_score",
            "pvt_control_domain_score",
            "pvt_session_drift_domain_score",
            "pvt_sleep_context_domain_score",
            "pvt_hours_awake",
        )
        val QUERY_BACKED_SEARCH_MODES = setOf(
            SearchMode.Substring,
            SearchMode.Prefix,
            SearchMode.Suffix,
        )
    }
}
