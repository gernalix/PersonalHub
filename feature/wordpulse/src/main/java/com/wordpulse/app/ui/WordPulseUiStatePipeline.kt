package com.wordpulse.app.ui

import com.wordpulse.app.data.DashboardSnapshot
import com.wordpulse.app.data.SessionSummaryRow
import com.wordpulse.app.data.TimeProvider
import com.wordpulse.app.data.WordEntry
import com.wordpulse.app.domain.CorrectionMetrics
import com.wordpulse.app.domain.MetricsCalculator
import com.wordpulse.app.domain.SearchMode
import com.wordpulse.app.domain.SearchSort
import com.wordpulse.app.domain.TimelineEntry
import com.wordpulse.app.domain.WordDetail
import com.wordpulse.app.domain.WordExplorer
import com.wordpulse.app.domain.WordMetrics
import com.wordpulse.app.domain.WordSearchResult
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

internal class WordPulseUiStatePipeline(
    private val timeProvider: TimeProvider,
    private val zoneId: ZoneId,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val metricsCalculator: (List<WordEntry>, String?, Long, ZoneId) -> WordMetrics = { entries, sessionId, now, zone ->
        MetricsCalculator.calculate(
            entries = entries,
            currentSessionId = sessionId,
            nowUtcMs = now,
            zoneId = zone,
        )
    },
    private val detailProvider: (List<WordEntry>, String) -> WordDetail? = WordExplorer::detailFor,
) {
    fun create(
        captureText: Flow<String>,
        searchQuery: Flow<String>,
        searchMode: Flow<SearchMode>,
        searchSort: Flow<SearchSort>,
        selectedNormalizedWord: Flow<String?>,
        currentSessionId: Flow<String?>,
        entries: Flow<List<WordEntry>>,
        sessions: Flow<List<SessionSummaryRow>>,
        dashboardSnapshot: Flow<DashboardSnapshot>,
        correctionMetrics: Flow<CorrectionMetrics>,
        timeline: Flow<List<TimelineEntry>>,
        searchResults: Flow<List<WordSearchResult>>,
    ): Flow<WordPulseUiState> {
        val captureState = combine(
            captureText,
            searchQuery,
            searchMode,
            searchSort,
        ) { capture, search, mode, sort ->
            CaptureState(
                captureText = capture,
                searchQuery = search,
                searchMode = mode,
                searchSort = sort,
            )
        }

        val repositoryState = combine(
            currentSessionId,
            sessions,
        ) { sessionId, sessionRows ->
            RepositoryState(
                currentSessionId = sessionId,
                sessions = sessionRows,
            )
        }

        val metricsState = combine(
            entries,
            currentSessionId,
        ) { wordEntries, sessionId ->
            metricsCalculator(wordEntries, sessionId, timeProvider.nowUtcMs(), zoneId)
        }.flowOn(analysisDispatcher)

        val selectedWordDetailState = combine(
            entries,
            selectedNormalizedWord,
        ) { wordEntries, selectedWord ->
            selectedWord?.let { detailProvider(wordEntries, it) }
        }.flowOn(analysisDispatcher)

        val dashboardState = combine(
            dashboardSnapshot,
            correctionMetrics,
        ) { snapshot, corrections ->
            DashboardState(snapshot, corrections)
        }

        val analysisState = combine(
            metricsState,
            searchResults,
            selectedWordDetailState,
            dashboardState,
            timeline,
        ) { metrics, searchResults, selectedWordDetail, dashboard, timelineEntries ->
            AnalysisState(
                metrics = metrics.withDashboardSnapshot(
                    snapshot = dashboard.snapshot,
                    timeline = timelineEntries,
                    nowUtcMs = timeProvider.nowUtcMs(),
                ).copy(corrections = dashboard.corrections),
                searchResults = searchResults,
                selectedWordDetail = selectedWordDetail,
            )
        }

        return combine(
            captureState,
            repositoryState,
            analysisState,
        ) { capture, repository, analysis ->
            WordPulseUiState(
                captureText = capture.captureText,
                searchQuery = capture.searchQuery,
                searchMode = capture.searchMode,
                searchSort = capture.searchSort,
                currentSessionId = repository.currentSessionId,
                sessions = repository.sessions,
                searchResults = analysis.searchResults,
                selectedWordDetail = analysis.selectedWordDetail,
                metrics = analysis.metrics,
                isReady = repository.currentSessionId != null,
            )
        }
    }
}

private data class CaptureState(
    val captureText: String,
    val searchQuery: String,
    val searchMode: SearchMode,
    val searchSort: SearchSort,
)

private data class RepositoryState(
    val currentSessionId: String?,
    val sessions: List<SessionSummaryRow>,
)

private data class AnalysisState(
    val metrics: WordMetrics,
    val searchResults: List<WordSearchResult>,
    val selectedWordDetail: WordDetail?,
)

private data class DashboardState(
    val snapshot: DashboardSnapshot,
    val corrections: CorrectionMetrics,
)

private fun WordMetrics.withDashboardSnapshot(
    snapshot: DashboardSnapshot,
    timeline: List<TimelineEntry>,
    nowUtcMs: Long,
): WordMetrics {
    val duplicateCount = (snapshot.totalWords - snapshot.uniqueWords).coerceAtLeast(0)
    val duplicatePercentage = if (snapshot.totalWords == 0) {
        0.0
    } else {
        duplicateCount.toDouble() / snapshot.totalWords.toDouble() * 100.0
    }
    val timeSinceLastWordMs = snapshot.currentSessionLatestWordUtcMs
        ?.let { (nowUtcMs - it).coerceAtLeast(0L) }
    val sessionDurationMs = snapshot.currentSessionStartedAtUtcMs
        ?.let { startedAt ->
            snapshot.currentSessionLatestWordUtcMs?.let { latestAt ->
                (latestAt - startedAt).coerceAtLeast(0L)
            }
        }

    return copy(
        general = general.copy(
            totalSubmittedWords = snapshot.totalWords,
            uniqueWords = snapshot.uniqueWords,
            duplicateCount = duplicateCount,
            duplicatePercentage = duplicatePercentage,
            todaysWords = snapshot.todaysWords,
            lastSevenDaysWords = snapshot.lastSevenDaysWords,
            currentSessionWords = snapshot.currentSessionWords,
        ),
        dashboard = dashboard.copy(
            currentSessionWords = snapshot.currentSessionWords,
            wordsToday = snapshot.todaysWords,
            currentStreak = timeline.currentNoveltyStreak(),
            repetitionScore = duplicatePercentage.roundToInt().toDouble().coerceIn(0.0, 100.0),
            timeSinceLastWordMs = timeSinceLastWordMs,
            currentSessionDurationMs = sessionDurationMs,
        ),
        timeline = timeline,
    )
}

private fun List<TimelineEntry>.currentNoveltyStreak(): Int {
    var streak = 0
    for (entry in asReversed()) {
        if (entry.duplicateOrdinal == 1) {
            streak += 1
        } else {
            break
        }
    }
    return streak
}
