package com.wordpulse.app.ui

import com.wordpulse.app.data.SessionSummaryRow
import com.wordpulse.app.domain.SearchMode
import com.wordpulse.app.domain.SearchSort
import com.wordpulse.app.domain.WordDetail
import com.wordpulse.app.domain.WordSearchResult
import com.wordpulse.app.domain.WordMetrics

data class WordPulseUiState(
    val captureText: String = "",
    val searchQuery: String = "",
    val searchMode: SearchMode = SearchMode.Substring,
    val searchSort: SearchSort = SearchSort.Frequency,
    val currentSessionId: String? = null,
    val sessions: List<SessionSummaryRow> = emptyList(),
    val searchResults: List<WordSearchResult> = emptyList(),
    val selectedWordDetail: WordDetail? = null,
    val metrics: WordMetrics = WordMetrics(),
    val isReady: Boolean = false,
)

data class WordPulseEvent(
    val message: String,
    val actionLabel: String? = null,
    val action: WordPulseEventAction? = null,
)

sealed interface WordPulseEventAction {
    data class EditSubmission(
        val insertedEntryId: Long,
        val correctionToken: Long,
    ) : WordPulseEventAction
}
