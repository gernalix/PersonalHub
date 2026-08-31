package com.wordpulse.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wordpulse.app.data.SubmissionResult
import com.wordpulse.app.data.TimeProvider
import com.wordpulse.app.data.WordRepository
import com.wordpulse.app.domain.CaptureInputValidation
import com.wordpulse.app.domain.CaptureTextValue
import com.wordpulse.app.domain.CaptureWordPolicy
import com.wordpulse.app.domain.DuplicateAlertInput
import com.wordpulse.app.domain.NotificationCoordinator
import com.wordpulse.app.domain.SearchMode
import com.wordpulse.app.domain.SearchSort
import com.wordpulse.app.domain.TypingAlert
import com.wordpulse.app.domain.TypingAlertAction
import com.wordpulse.app.domain.TypingAnomalyDetector
import com.wordpulse.app.domain.TypingInsightFormatter
import com.wordpulse.app.domain.TypingMetrics
import com.wordpulse.app.domain.TypingPerformanceSample
import com.wordpulse.app.domain.TypingSessionTracker
import java.time.ZoneId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class SearchRequest(
    val query: String,
    val mode: SearchMode,
    val sort: SearchSort,
)

private data class CorrectableSubmission(
    val wordEntryId: Long,
    val originalWord: String,
    val normalizedWord: String,
    val sessionId: String,
    val submittedAtUtcMs: Long,
    val correctionToken: Long,
)

private data class PendingSubmission(
    val word: String,
    val typingMetrics: TypingMetrics,
)

class WordPulseViewModel(
    private val repository: WordRepository,
    private val timeProvider: TimeProvider,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val captureFieldValue = MutableStateFlow(CaptureTextValue())
    private val searchQuery = MutableStateFlow("")
    private val searchMode = MutableStateFlow(SearchMode.Substring)
    private val searchSort = MutableStateFlow(SearchSort.Frequency)
    private val selectedNormalizedWord = MutableStateFlow<String?>(null)
    private val submissions = Channel<PendingSubmission>(capacity = Channel.UNLIMITED)
    private val mutableEvents = MutableSharedFlow<WordPulseEvent>(extraBufferCapacity = 8)
    private val mutableTypingAlert = MutableStateFlow<TypingAlert?>(null)
    private val typingSessionTracker = TypingSessionTracker(timeProvider)
    private val typingAnomalyDetector = TypingAnomalyDetector()
    private val notificationCoordinator = NotificationCoordinator(
        formatter = TypingInsightFormatter(zoneId),
    )
    private var latestCorrectableSubmission: CorrectableSubmission? = null
    private var nextCorrectionToken = 0L

    private val currentSessionId = repository.observeCurrentSessionId()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val entries = repository.observeEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val sessions = repository.observeSessionSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val dashboardSnapshot = repository.observeDashboardSnapshot(zoneId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.wordpulse.app.data.DashboardSnapshot())

    private val correctionMetrics = repository.observeCorrectionMetrics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.wordpulse.app.domain.CorrectionMetrics())

    private val timeline = repository.observeCurrentSessionTimeline()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val searchResults = combine(
        searchQuery,
        searchMode,
        searchSort,
    ) { query, mode, sort ->
        SearchRequest(query, mode, sort)
    }.flatMapLatest { request ->
        repository.observeSearchResults(
            query = request.query,
            mode = request.mode,
            sort = request.sort,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val captureFieldState = captureFieldValue.asStateFlow()
    val typingAlertState = mutableTypingAlert.asStateFlow()
    val events = mutableEvents.asSharedFlow()

    val uiState = WordPulseUiStatePipeline(
        timeProvider = timeProvider,
        zoneId = zoneId,
    ).create(
        captureText = captureFieldValue.map { it.text },
        searchQuery = searchQuery,
        searchMode = searchMode,
        searchSort = searchSort,
        selectedNormalizedWord = selectedNormalizedWord,
        currentSessionId = currentSessionId,
        entries = entries,
        sessions = sessions,
        dashboardSnapshot = dashboardSnapshot,
        correctionMetrics = correctionMetrics,
        timeline = timeline,
        searchResults = searchResults,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WordPulseUiState(),
    )

    init {
        viewModelScope.launch {
            repository.ensureCurrentSession()
        }
        viewModelScope.launch {
            for (submission in submissions) {
                val sessionId = currentSessionId.value ?: repository.ensureCurrentSession().id
                val result = repository.submitWord(
                    rawWord = submission.word,
                    requestedSessionId = sessionId,
                    typingMetrics = submission.typingMetrics,
                )
                val token = ++nextCorrectionToken
                latestCorrectableSubmission = CorrectableSubmission(
                    wordEntryId = result.insertedEntryId,
                    originalWord = result.originalWord,
                    normalizedWord = result.normalizedWord,
                    sessionId = result.sessionId,
                    submittedAtUtcMs = result.submittedAtUtcMs,
                    correctionToken = token,
                )
                publishSubmissionAlert(result, token)
            }
        }
    }

    fun onCaptureValueChanged(value: CaptureTextValue) {
        when (val validation = CaptureWordPolicy.validateFieldText(value.text)) {
            CaptureInputValidation.Valid -> {
                typingSessionTracker.recordAcceptedTransition(value)
                captureFieldValue.value = value
            }
            is CaptureInputValidation.Invalid -> {
                typingSessionTracker.recordInvalidInputAttempt()
                notificationCoordinator.onInvalidInput(validation.reason)?.let {
                    mutableTypingAlert.value = it
                }
            }
        }
    }

    fun submitCurrent() {
        val word = captureFieldValue.value.text
        val metrics = typingSessionTracker.submit()
        captureFieldValue.value = CaptureTextValue()
        if (metrics != null) {
            submissions.trySend(PendingSubmission(word, metrics))
        }
    }

    fun clearInput() {
        typingSessionTracker.reset()
        captureFieldValue.value = CaptureTextValue()
    }

    fun startNewSession() {
        clearInput()
        viewModelScope.launch {
            repository.startNewSession()
            mutableEvents.emit(WordPulseEvent("New session started"))
        }
    }

    fun deleteAllData() {
        clearInput()
        viewModelScope.launch {
            repository.deleteAllData()
            mutableEvents.emit(WordPulseEvent("All local data deleted"))
        }
    }

    fun onSearchChanged(value: String) {
        searchQuery.value = value
    }

    fun onSearchModeChanged(value: SearchMode) {
        searchMode.value = value
    }

    fun onSearchSortChanged(value: SearchSort) {
        searchSort.value = value
    }

    fun selectWord(normalizedWord: String) {
        selectedNormalizedWord.value = normalizedWord
    }

    fun clearSelectedWord() {
        selectedNormalizedWord.value = null
    }

    fun onTypingAlertAction(action: TypingAlertAction) {
        when (action) {
            is TypingAlertAction.EditSubmission -> editSubmittedWord(
                insertedEntryId = action.insertedEntryId,
                correctionToken = action.correctionToken,
            )
        }
    }

    fun dismissTypingAlert(alertId: Long) {
        notificationCoordinator.dismiss(alertId)
        if (mutableTypingAlert.value?.id == alertId) {
            mutableTypingAlert.value = null
        }
    }

    suspend fun exportCsv(): String = repository.exportCsv()

    suspend fun exportBackupCsv(): String = repository.exportBackupCsv()

    fun importCsv(csv: String) {
        viewModelScope.launch {
            val result = repository.importCsv(csv)
            mutableEvents.emit(
                WordPulseEvent(
                    "Imported ${result.wordsImported} words, ${result.sessionsImported} sessions, " +
                        "and ${result.correctionsImported} corrections",
                ),
            )
        }
    }

    fun notify(message: String) {
        mutableEvents.tryEmit(WordPulseEvent(message))
    }

    private fun editSubmittedWord(insertedEntryId: Long, correctionToken: Long) {
        val submission = latestCorrectableSubmission ?: return
        if (submission.wordEntryId != insertedEntryId || submission.correctionToken != correctionToken) return

        viewModelScope.launch {
            val event = repository.correctSubmittedWord(
                wordEntryId = submission.wordEntryId,
                originalWord = submission.originalWord,
                normalizedWord = submission.normalizedWord,
                sessionId = submission.sessionId,
                submittedAtUtcMs = submission.submittedAtUtcMs,
            )
            if (event != null) {
                latestCorrectableSubmission = null
                val restored = CaptureTextValue(submission.originalWord)
                typingSessionTracker.restoreWithoutTracking(restored)
                captureFieldValue.value = restored
                mutableTypingAlert.value?.let { dismissTypingAlert(it.id) }
            } else {
                notificationCoordinator.onError("Impossibile modificare la parola salvata")?.let {
                    mutableTypingAlert.value = it
                }
            }
        }
    }

    private suspend fun publishSubmissionAlert(
        result: SubmissionResult,
        correctionToken: Long,
    ) {
        val evaluation = result.typingMetrics?.let { metrics ->
            val current = TypingPerformanceSample.fromMetrics(result.insertedEntryId, metrics)
            val history = repository.getTypingBaselineSamples(
                excludedEntryId = result.insertedEntryId,
                excludedSessionId = result.sessionId,
                finalCharacterCount = metrics.finalCharacterCount,
            )
            typingAnomalyDetector.evaluate(current, history)
        }
        val editAction = TypingAlertAction.EditSubmission(
            insertedEntryId = result.insertedEntryId,
            correctionToken = correctionToken,
        )
        val duplicate = if (result.wasDuplicate && result.previousOccurrenceUtcMs != null) {
            DuplicateAlertInput(
                totalOccurrences = result.totalOccurrences,
                previousOccurrenceUtcMs = result.previousOccurrenceUtcMs,
                insertedEntryId = result.insertedEntryId,
                correctionToken = correctionToken,
            )
        } else {
            null
        }
        notificationCoordinator.onSubmission(
            duplicate = duplicate,
            evaluation = evaluation,
            successMessage = "Salvata: ${result.originalWord}",
            editAction = editAction,
        )?.let {
            mutableTypingAlert.value = it
        }
    }
}

class WordPulseViewModelFactory(
    private val repository: WordRepository,
    private val timeProvider: TimeProvider,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WordPulseViewModel::class.java)) {
            return WordPulseViewModel(
                repository = repository,
                timeProvider = timeProvider,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
