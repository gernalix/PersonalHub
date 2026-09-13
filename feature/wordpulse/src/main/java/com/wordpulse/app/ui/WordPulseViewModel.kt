package com.wordpulse.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wordpulse.app.data.HealthConnectAvailability
import com.wordpulse.app.data.NoSleepContextSource
import com.wordpulse.app.data.PvtResultEntity
import com.wordpulse.app.data.SleepContextSource
import com.wordpulse.app.data.SleepIntegrationState
import com.wordpulse.app.data.SubmissionResult
import com.wordpulse.app.data.TimeProvider
import com.wordpulse.app.data.WordRepository
import com.wordpulse.app.domain.CaptureInputValidation
import com.wordpulse.app.domain.CaptureTextValue
import com.wordpulse.app.domain.CaptureWordPolicy
import com.wordpulse.app.domain.DuplicateAlertInput
import com.wordpulse.app.domain.NotificationCoordinator
import com.wordpulse.app.domain.PvtTestState
import com.wordpulse.app.domain.SearchMode
import com.wordpulse.app.domain.SearchSort
import com.wordpulse.app.domain.TypingAlert
import com.wordpulse.app.domain.TypingAlertAction
import com.wordpulse.app.domain.TypingAnomalyDetector
import com.wordpulse.app.domain.TypingInsightFormatter
import com.wordpulse.app.domain.TypingMetrics
import com.wordpulse.app.domain.TypingPerformanceEvaluation
import com.wordpulse.app.domain.TypingPerformanceSample
import com.wordpulse.app.domain.TypingSessionTracker
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private val sleepContextSource: SleepContextSource = NoSleepContextSource,
) : ViewModel() {
    private val captureFieldValue = MutableStateFlow(CaptureTextValue())
    private val searchQuery = MutableStateFlow("")
    private val searchMode = MutableStateFlow(SearchMode.Substring)
    private val searchSort = MutableStateFlow(SearchSort.Frequency)
    private val selectedNormalizedWord = MutableStateFlow<String?>(null)
    private val submissions = Channel<PendingSubmission>(capacity = Channel.UNLIMITED)
    private val mutableEvents = MutableSharedFlow<WordPulseEvent>(extraBufferCapacity = 8)
    private val mutableTypingAlert = MutableStateFlow<TypingAlert?>(null)
    private val mutableLatestPerformance = MutableStateFlow<TypingPerformanceEvaluation?>(null)
    private val mutableSleepIntegration = MutableStateFlow(
        SleepIntegrationState(availability = sleepContextSource.availability()),
    )
    private val mutablePvtTest = MutableStateFlow(PvtTestState())
    private val typingSessionTracker = TypingSessionTracker(timeProvider)
    private val typingAnomalyDetector = TypingAnomalyDetector()
    private val notificationCoordinator = NotificationCoordinator(
        formatter = TypingInsightFormatter(zoneId),
    )
    private var latestCorrectableSubmission: CorrectableSubmission? = null
    private var nextCorrectionToken = 0L

    private var pvtJob: Job? = null
    private var pvtStimulusAtUtcMs: Long? = null
    private var pvtPendingResponse: CompletableDeferred<Long>? = null
    private val pvtReactionTimesMs = mutableListOf<Long>()
    private var pvtTrialCount = 0
    private var pvtLapseCount = 0
    private var pvtFalseStartCount = 0

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
    val latestPerformanceState = mutableLatestPerformance.asStateFlow()
    val sleepIntegrationState = mutableSleepIntegration.asStateFlow()
    val pvtTestState = mutablePvtTest.asStateFlow()
    val healthConnectPermissions: Set<String>
        get() = sleepContextSource.requiredPermissions
    val latestPvtSummary = repository.observeLatestPvtSummary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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
        refreshSleepContext()
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
            refreshSleepContextNow()
        }
    }

    fun deleteAllData() {
        clearInput()
        cancelPvtTest()
        viewModelScope.launch {
            repository.deleteAllData()
            mutableLatestPerformance.value = null
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

    fun onHealthConnectPermissionsResult(grantedPermissions: Set<String>) {
        val granted = healthConnectPermissions.isNotEmpty() &&
            grantedPermissions.containsAll(healthConnectPermissions)
        mutableSleepIntegration.value = mutableSleepIntegration.value.copy(
            availability = sleepContextSource.availability(),
            permissionGranted = granted,
            sleepContext = if (granted) mutableSleepIntegration.value.sleepContext else null,
            error = null,
        )
        if (granted) refreshSleepContext()
    }

    fun refreshSleepContext() {
        viewModelScope.launch {
            refreshSleepContextNow()
        }
    }

    fun startPvtTest() {
        if (mutablePvtTest.value.active) return
        pvtJob?.cancel()
        pvtReactionTimesMs.clear()
        pvtTrialCount = 0
        pvtLapseCount = 0
        pvtFalseStartCount = 0
        pvtPendingResponse = null
        pvtStimulusAtUtcMs = null
        val startedAt = timeProvider.nowUtcMs()
        val endsAt = startedAt + PVT_DURATION_MS
        mutablePvtTest.value = PvtTestState(
            active = true,
            startedAtUtcMs = startedAt,
            endsAtUtcMs = endsAt,
        )
        pvtJob = viewModelScope.launch {
            while (timeProvider.nowUtcMs() < endsAt) {
                val remainingBeforeStimulus = (endsAt - timeProvider.nowUtcMs()).coerceAtLeast(0L)
                if (remainingBeforeStimulus <= PVT_MIN_FOREPERIOD_MS) break
                val foreperiod = Random.nextLong(PVT_MIN_FOREPERIOD_MS, PVT_MAX_FOREPERIOD_MS + 1)
                    .coerceAtMost(remainingBeforeStimulus)
                delay(foreperiod)
                if (timeProvider.nowUtcMs() >= endsAt) break

                val response = CompletableDeferred<Long>()
                pvtPendingResponse = response
                pvtStimulusAtUtcMs = timeProvider.nowUtcMs()
                updatePvtState(stimulusVisible = true)
                val reactionTime = withTimeoutOrNull(PVT_RESPONSE_TIMEOUT_MS) {
                    response.await()
                }
                pvtTrialCount += 1
                if (reactionTime == null) {
                    pvtLapseCount += 1
                } else {
                    pvtReactionTimesMs += reactionTime
                    if (reactionTime >= PVT_LAPSE_THRESHOLD_MS) pvtLapseCount += 1
                }
                pvtPendingResponse = null
                pvtStimulusAtUtcMs = null
                updatePvtState(stimulusVisible = false)
            }
            completePvtTest(startedAt)
        }
    }

    fun onPvtTap() {
        if (!mutablePvtTest.value.active) return
        val stimulusAt = pvtStimulusAtUtcMs
        val response = pvtPendingResponse
        if (stimulusAt != null && response != null && !response.isCompleted) {
            val reactionTime = (timeProvider.nowUtcMs() - stimulusAt).coerceAtLeast(0L)
            response.complete(reactionTime)
        } else {
            pvtFalseStartCount += 1
            updatePvtState(stimulusVisible = false)
        }
    }

    fun cancelPvtTest() {
        pvtJob?.cancel()
        pvtJob = null
        pvtPendingResponse?.cancel()
        pvtPendingResponse = null
        pvtStimulusAtUtcMs = null
        pvtReactionTimesMs.clear()
        pvtTrialCount = 0
        pvtLapseCount = 0
        pvtFalseStartCount = 0
        mutablePvtTest.value = PvtTestState()
    }

    suspend fun exportCsv(): String = repository.exportCsv()

    suspend fun exportBackupCsv(): String = repository.exportBackupCsv()

    fun importCsv(csv: String) {
        viewModelScope.launch {
            val result = repository.importCsv(csv)
            mutableEvents.emit(
                WordPulseEvent(
                    "Imported ${result.wordsImported} words, ${result.sessionsImported} sessions, " +
                        "${result.correctionsImported} corrections and ${result.pvtResultsImported} vigilance tests",
                ),
            )
        }
    }

    fun notify(message: String) {
        mutableEvents.tryEmit(WordPulseEvent(message))
    }

    override fun onCleared() {
        pvtJob?.cancel()
        super.onCleared()
    }

    private suspend fun refreshSleepContextNow() {
        val availability = sleepContextSource.availability()
        if (availability != HealthConnectAvailability.Available) {
            mutableSleepIntegration.value = SleepIntegrationState(availability = availability)
            return
        }
        runCatching {
            val granted = sleepContextSource.hasPermissions()
            val context = if (granted) {
                sleepContextSource.readSleepContext(timeProvider.nowUtcMs())
            } else {
                null
            }
            SleepIntegrationState(
                availability = availability,
                permissionGranted = granted,
                sleepContext = context,
            )
        }.onSuccess { state ->
            mutableSleepIntegration.value = state
        }.onFailure { error ->
            mutableSleepIntegration.value = SleepIntegrationState(
                availability = availability,
                permissionGranted = false,
                error = error.message ?: error::class.java.simpleName,
            )
        }
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
            val sessionHistory = repository.getSessionTypingSamples(
                sessionId = result.sessionId,
                excludedEntryId = result.insertedEntryId,
            )
            val calibration = repository.getPvtCalibrationSamples()
            typingAnomalyDetector.evaluate(
                current = current,
                history = history,
                sessionHistory = sessionHistory,
                sleepContext = mutableSleepIntegration.value.sleepContext,
                pvtCalibrationSamples = calibration,
                zoneId = zoneId,
            )
        }
        repository.saveFatigueScore(result.insertedEntryId, evaluation?.fatigueScore)
        mutableLatestPerformance.value = evaluation
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

    private fun updatePvtState(stimulusVisible: Boolean) {
        val sorted = pvtReactionTimesMs.sorted()
        mutablePvtTest.value = mutablePvtTest.value.copy(
            stimulusVisible = stimulusVisible,
            trialsCompleted = pvtTrialCount,
            medianReactionTimeMs = percentile(sorted, 0.50),
            p90ReactionTimeMs = percentile(sorted, 0.90),
            lapseCount = pvtLapseCount,
            falseStartCount = pvtFalseStartCount,
        )
    }

    private suspend fun completePvtTest(startedAtUtcMs: Long) {
        val completedAtUtcMs = timeProvider.nowUtcMs()
        val sorted = pvtReactionTimesMs.sorted()
        val evaluation = mutableLatestPerformance.value
        val result = PvtResultEntity(
            startedAtUtcMs = startedAtUtcMs,
            completedAtUtcMs = completedAtUtcMs,
            durationMs = (completedAtUtcMs - startedAtUtcMs).coerceAtLeast(0L),
            trialCount = pvtTrialCount,
            medianReactionTimeMs = percentile(sorted, 0.50),
            p90ReactionTimeMs = percentile(sorted, 0.90),
            lapseCount = pvtLapseCount,
            falseStartCount = pvtFalseStartCount,
            pairedFatigueScore = evaluation?.rawFatigueScore,
            speedDomainScore = evaluation?.domains?.speed,
            rhythmDomainScore = evaluation?.domains?.rhythm,
            controlDomainScore = evaluation?.domains?.control,
            sessionDriftDomainScore = evaluation?.domains?.sessionDrift,
            sleepContextDomainScore = evaluation?.domains?.sleepContext,
            hoursAwake = evaluation?.context?.hoursAwake,
        )
        repository.savePvtResult(result)
        mutablePvtTest.value = PvtTestState(
            active = false,
            startedAtUtcMs = startedAtUtcMs,
            endsAtUtcMs = completedAtUtcMs,
            trialsCompleted = result.trialCount,
            medianReactionTimeMs = result.medianReactionTimeMs,
            p90ReactionTimeMs = result.p90ReactionTimeMs,
            lapseCount = result.lapseCount,
            falseStartCount = result.falseStartCount,
        )
        pvtJob = null
        pvtPendingResponse = null
        pvtStimulusAtUtcMs = null
        mutableEvents.emit(WordPulseEvent("3-minute vigilance test saved"))
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Double? {
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted.first().toDouble()
        val position = (sorted.lastIndex * fraction).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = position.toInt()
        val upper = ceil(position).toInt().coerceAtMost(sorted.lastIndex)
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private companion object {
        const val PVT_DURATION_MS = 180_000L
        const val PVT_MIN_FOREPERIOD_MS = 2_000L
        const val PVT_MAX_FOREPERIOD_MS = 10_000L
        const val PVT_RESPONSE_TIMEOUT_MS = 2_000L
        const val PVT_LAPSE_THRESHOLD_MS = 500L
    }
}

class WordPulseViewModelFactory(
    private val repository: WordRepository,
    private val timeProvider: TimeProvider,
    private val sleepContextSource: SleepContextSource = NoSleepContextSource,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WordPulseViewModel::class.java)) {
            return WordPulseViewModel(
                repository = repository,
                timeProvider = timeProvider,
                sleepContextSource = sleepContextSource,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
