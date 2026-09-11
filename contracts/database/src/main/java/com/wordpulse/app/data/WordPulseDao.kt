package com.wordpulse.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WordPulseDao {
    @Insert
    suspend fun insertWord(entry: WordEntry): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: WordSession)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessionIfAbsent(session: WordSession): Long

    @Insert
    suspend fun insertWords(entries: List<WordEntry>): List<Long>

    @Insert
    suspend fun insertCorrectionEvent(event: CorrectionEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppState(state: AppStateEntity)

    @Update
    suspend fun updateSession(session: WordSession)

    @Query("SELECT * FROM app_state WHERE id = :id LIMIT 1")
    suspend fun getAppState(id: Int = AppStateEntity.SINGLETON_ID): AppStateEntity?

    @Query("SELECT current_session_id FROM app_state WHERE id = :id LIMIT 1")
    fun observeCurrentSessionId(id: Int = AppStateEntity.SINGLETON_ID): Flow<String?>

    @Query("SELECT * FROM wordpulse_sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: String): WordSession?

    @Query("SELECT * FROM wordpulse_sessions WHERE id IN (:ids)")
    suspend fun getSessionsByIds(ids: List<String>): List<WordSession>

    @Query("SELECT * FROM wordpulse_sessions WHERE id LIKE '%' || :query || '%' ORDER BY started_at_utc_ms DESC LIMIT :limit")
    suspend fun searchSessions(query: String, limit: Int): List<WordSession>

    @Query("SELECT * FROM wordpulse_sessions WHERE started_at_utc_ms < :toMs AND (ended_at_utc_ms IS NULL OR ended_at_utc_ms > :fromMs) ORDER BY started_at_utc_ms DESC,id DESC LIMIT :limit OFFSET :offset")
    suspend fun temporalSessions(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<WordSession>

    @Query("SELECT * FROM wordpulse_sessions ORDER BY started_at_utc_ms ASC")
    suspend fun getSessions(): List<WordSession>

    @Query("SELECT * FROM wordpulse_sessions ORDER BY started_at_utc_ms DESC LIMIT 1")
    suspend fun getLatestSession(): WordSession?

    @Query("SELECT * FROM word_entries WHERE id = :id LIMIT 1")
    suspend fun getWordById(id: Long): WordEntry?

    @Query(
        """
        SELECT COUNT(*) AS previous_occurrences,
               MAX(created_at_utc_ms) AS previous_occurrence_utc_ms
        FROM word_entries
        WHERE normalized_word = :normalizedWord
        """,
    )
    suspend fun getDuplicateHistory(normalizedWord: String): DuplicateHistory

    @Query(
        """
        SELECT id,
               typing_started_at_utc_ms,
               submitted_at_utc_ms,
               typing_duration_ms,
               final_character_count,
               inserted_character_count,
               deleted_character_count,
               replacement_count,
               correction_action_count,
               longest_inter_key_pause_ms,
               mean_inter_key_interval_ms,
               inter_key_interval_variability_ms,
               invalid_input_attempt_count
        FROM word_entries
        WHERE id != :excludedEntryId
          AND session_id != :excludedSessionId
          AND final_character_count BETWEEN :minimumLength AND :maximumLength
          AND typing_duration_ms IS NOT NULL
          AND typing_duration_ms > 0
          AND inserted_character_count IS NOT NULL
          AND deleted_character_count IS NOT NULL
          AND correction_action_count IS NOT NULL
          AND invalid_input_attempt_count IS NOT NULL
        ORDER BY COALESCE(submitted_at_utc_ms, created_at_utc_ms) DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentTypingPerformanceRows(
        excludedEntryId: Long,
        excludedSessionId: String,
        minimumLength: Int,
        maximumLength: Int,
        limit: Int,
    ): List<TypingPerformanceRow>

    @Query("SELECT COUNT(*) FROM word_entries")
    fun observeTotalWordCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT normalized_word) FROM word_entries")
    fun observeUniqueWordCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_entries WHERE created_at_utc_ms >= :startUtcMs")
    fun observeWordCountSince(startUtcMs: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_entries WHERE created_at_utc_ms >= :startUtcMs AND created_at_utc_ms < :endUtcMs")
    fun observeWordCountBetween(startUtcMs: Long, endUtcMs: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_entries WHERE session_id = :sessionId")
    fun observeSessionWordCount(sessionId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM correction_events")
    fun observeTotalCorrectionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM correction_events WHERE session_id = :sessionId")
    fun observeSessionCorrectionCount(sessionId: String): Flow<Int>

    @Query("SELECT correction_latency_ms FROM correction_events ORDER BY corrected_at_utc_ms DESC, id DESC LIMIT 1")
    fun observeLastCorrectionLatencyMs(): Flow<Long?>

    @Query("SELECT correction_latency_ms FROM correction_events ORDER BY correction_latency_ms ASC")
    fun observeCorrectionLatenciesMs(): Flow<List<Long>>

    @Query("SELECT * FROM correction_events ORDER BY corrected_at_utc_ms ASC, id ASC")
    suspend fun getCorrectionEvents(): List<CorrectionEvent>

    @Query("SELECT started_at_utc_ms FROM wordpulse_sessions WHERE id = :sessionId LIMIT 1")
    fun observeSessionStartedAtUtcMs(sessionId: String): Flow<Long?>

    @Query("SELECT MAX(created_at_utc_ms) FROM word_entries WHERE session_id = :sessionId")
    fun observeSessionLatestWordUtcMs(sessionId: String): Flow<Long?>

    @Query(
        """
        SELECT recent.id AS id,
               recent.original_word AS original_word,
               recent.normalized_word AS normalized_word,
               recent.created_at_utc_ms AS created_at_utc_ms,
               recent.session_id AS session_id,
               (
                   SELECT COUNT(*)
                   FROM word_entries previous
                   WHERE previous.session_id = recent.session_id
                     AND previous.normalized_word = recent.normalized_word
                     AND (
                         previous.created_at_utc_ms < recent.created_at_utc_ms
                         OR (
                             previous.created_at_utc_ms = recent.created_at_utc_ms
                             AND previous.id <= recent.id
                         )
                     )
               ) AS duplicate_ordinal
        FROM (
            SELECT *
            FROM word_entries
            WHERE session_id = :sessionId
            ORDER BY created_at_utc_ms DESC, id DESC
            LIMIT :limit
        ) AS recent
        ORDER BY recent.created_at_utc_ms ASC, recent.id ASC
        """,
    )
    fun observeRecentTimelineRows(sessionId: String, limit: Int): Flow<List<TimelineEntryRow>>

    @Query(
        """
        WITH grouped AS (
            SELECT normalized_word,
                   COUNT(*) AS occurrences,
                   MAX(created_at_utc_ms) AS latest_occurrence_utc_ms
            FROM word_entries
            WHERE normalized_word LIKE :pattern ESCAPE '\'
            GROUP BY normalized_word
        )
        SELECT grouped.normalized_word AS normalized_word,
               latest.original_word AS sample_original_word,
               grouped.occurrences AS occurrences,
               grouped.latest_occurrence_utc_ms AS latest_occurrence_utc_ms,
               LENGTH(latest.original_word) AS length
        FROM grouped
        JOIN word_entries latest
          ON latest.id = (
              SELECT tie.id
              FROM word_entries tie
              WHERE tie.normalized_word = grouped.normalized_word
                AND tie.created_at_utc_ms = grouped.latest_occurrence_utc_ms
              ORDER BY tie.id DESC
              LIMIT 1
          )
        ORDER BY grouped.occurrences DESC,
                 grouped.latest_occurrence_utc_ms DESC,
                 grouped.normalized_word ASC
        LIMIT :limit
        """,
    )
    fun observeSearchLikeByFrequency(pattern: String, limit: Int): Flow<List<SearchResultRow>>

    @Query(
        """
        WITH grouped AS (
            SELECT normalized_word,
                   COUNT(*) AS occurrences,
                   MAX(created_at_utc_ms) AS latest_occurrence_utc_ms
            FROM word_entries
            WHERE normalized_word LIKE :pattern ESCAPE '\'
            GROUP BY normalized_word
        )
        SELECT grouped.normalized_word AS normalized_word,
               latest.original_word AS sample_original_word,
               grouped.occurrences AS occurrences,
               grouped.latest_occurrence_utc_ms AS latest_occurrence_utc_ms,
               LENGTH(latest.original_word) AS length
        FROM grouped
        JOIN word_entries latest
          ON latest.id = (
              SELECT tie.id
              FROM word_entries tie
              WHERE tie.normalized_word = grouped.normalized_word
                AND tie.created_at_utc_ms = grouped.latest_occurrence_utc_ms
              ORDER BY tie.id DESC
              LIMIT 1
          )
        ORDER BY grouped.latest_occurrence_utc_ms DESC,
                 grouped.normalized_word ASC
        LIMIT :limit
        """,
    )
    fun observeSearchLikeByRecency(pattern: String, limit: Int): Flow<List<SearchResultRow>>

    @Query(
        """
        WITH grouped AS (
            SELECT normalized_word,
                   COUNT(*) AS occurrences,
                   MAX(created_at_utc_ms) AS latest_occurrence_utc_ms
            FROM word_entries
            WHERE normalized_word LIKE :pattern ESCAPE '\'
            GROUP BY normalized_word
        )
        SELECT grouped.normalized_word AS normalized_word,
               latest.original_word AS sample_original_word,
               grouped.occurrences AS occurrences,
               grouped.latest_occurrence_utc_ms AS latest_occurrence_utc_ms,
               LENGTH(latest.original_word) AS length
        FROM grouped
        JOIN word_entries latest
          ON latest.id = (
              SELECT tie.id
              FROM word_entries tie
              WHERE tie.normalized_word = grouped.normalized_word
                AND tie.created_at_utc_ms = grouped.latest_occurrence_utc_ms
              ORDER BY tie.id DESC
              LIMIT 1
          )
        ORDER BY grouped.normalized_word ASC
        LIMIT :limit
        """,
    )
    fun observeSearchLikeAlphabetically(pattern: String, limit: Int): Flow<List<SearchResultRow>>

    @Query(
        """
        WITH grouped AS (
            SELECT normalized_word,
                   COUNT(*) AS occurrences,
                   MAX(created_at_utc_ms) AS latest_occurrence_utc_ms
            FROM word_entries
            WHERE normalized_word LIKE :pattern ESCAPE '\'
            GROUP BY normalized_word
        )
        SELECT grouped.normalized_word AS normalized_word,
               latest.original_word AS sample_original_word,
               grouped.occurrences AS occurrences,
               grouped.latest_occurrence_utc_ms AS latest_occurrence_utc_ms,
               LENGTH(latest.original_word) AS length
        FROM grouped
        JOIN word_entries latest
          ON latest.id = (
              SELECT tie.id
              FROM word_entries tie
              WHERE tie.normalized_word = grouped.normalized_word
                AND tie.created_at_utc_ms = grouped.latest_occurrence_utc_ms
              ORDER BY tie.id DESC
              LIMIT 1
          )
        ORDER BY LENGTH(latest.original_word) ASC,
                 grouped.normalized_word ASC
        LIMIT :limit
        """,
    )
    fun observeSearchLikeByLength(pattern: String, limit: Int): Flow<List<SearchResultRow>>

    @Query("SELECT * FROM word_entries ORDER BY created_at_utc_ms ASC, id ASC")
    fun observeEntries(): Flow<List<WordEntry>>

    @Query("SELECT * FROM word_entries ORDER BY created_at_utc_ms ASC, id ASC")
    suspend fun getEntries(): List<WordEntry>

    @Query(
        """
        SELECT wordpulse_sessions.id AS id,
               wordpulse_sessions.started_at_utc_ms AS started_at_utc_ms,
               wordpulse_sessions.ended_at_utc_ms AS ended_at_utc_ms,
               COUNT(word_entries.id) AS word_count,
               COUNT(DISTINCT word_entries.normalized_word) AS unique_count
        FROM wordpulse_sessions
        LEFT JOIN word_entries ON word_entries.session_id = wordpulse_sessions.id
        GROUP BY wordpulse_sessions.id
        ORDER BY wordpulse_sessions.started_at_utc_ms DESC
        """,
    )
    fun observeSessionSummaries(): Flow<List<SessionSummaryRow>>

    @Query("DELETE FROM word_entries")
    suspend fun deleteWords()

    @Query("DELETE FROM correction_events")
    suspend fun deleteCorrectionEvents()

    @Query("DELETE FROM word_entries WHERE id = :id")
    suspend fun deleteWordById(id: Long): Int

    @Query("DELETE FROM wordpulse_sessions")
    suspend fun deleteSessions()

    @Query("DELETE FROM app_state")
    suspend fun deleteAppState()
}
