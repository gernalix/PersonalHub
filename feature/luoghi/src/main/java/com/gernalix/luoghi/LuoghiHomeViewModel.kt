package com.gernalix.luoghi

import android.net.Uri
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gernalix.luoghi.capsules.addressautocomplete.AddressAutocompleteLatestGate
import com.gernalix.luoghi.capsules.addressautocomplete.AddressAutocompletePolicy
import com.gernalix.luoghi.capsules.addressautocomplete.AddressSuggestion
import com.gernalix.luoghi.capsules.checkin.CheckInCandidate
import com.gernalix.luoghi.capsules.checkin.CheckInMatchDecision
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.capsules.checkin.HistoryMutationResult
import com.gernalix.luoghi.capsules.checkin.HistoryValidationError
import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.capsules.places.PlaceMutation
import com.gernalix.luoghi.capsules.places.PlaceListUiMapper
import com.gernalix.luoghi.capsules.places.PlaceListUiModel
import com.gernalix.luoghi.capsules.routedistance.RouteDistanceStatsUi
import com.gernalix.luoghi.capsules.stats.StatsSnapshot
import com.gernalix.luoghi.capsules.visits.VisitMapper
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.backup.BackupValidationCode
import com.gernalix.luoghi.backup.BackupValidationException
import com.gernalix.luoghi.backup.RestoreResult
import com.gernalix.luoghi.backup.RestoreSummary
import com.gernalix.luoghi.backup.ValidatedBackup
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.export.BackupFolderStore
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

data class PlaceFormState(
    val uuid: String? = null,
    val nickname: String = "",
    val address: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val radiusM: String = CheckInPolicy.DEFAULT_RADIUS_M.toInt().toString(),
    val notes: String = "",
    val sourceApp: String = "Luoghi",
) {
    companion object {
        fun from(place: PlaceEntity) = PlaceFormState(
            uuid = place.uuid,
            nickname = place.nickname,
            address = place.address.orEmpty(),
            lat = place.lat,
            lon = place.lon,
            radiusM = place.radiusM?.toString() ?: CheckInPolicy.DEFAULT_RADIUS_M.toInt().toString(),
            notes = place.notes.orEmpty(),
            sourceApp = place.sourceApp.orEmpty().ifBlank { "Luoghi" },
        )
    }
}

data class SafGateState(
    val status: BackupFolderStore.ValidationStatus = BackupFolderStore.ValidationStatus.MISSING,
    val loading: Boolean = true,
    val folderLabel: String? = null,
)

data class AddressAutocompleteState(
    val configured: Boolean,
    val loading: Boolean = false,
    val suggestions: List<AddressSuggestion> = emptyList(),
    val message: AddressMessage? = null,
)

enum class AddressMessage {
    CONFIG_MISSING,
    SEARCH_TIMEOUT,
    SEARCH_FAILED,
    RESOLUTION_TIMEOUT,
    RESOLUTION_FAILED,
}

enum class CheckInMessage {
    LOCATION_PERMISSION_DENIED,
    LOCATION_UNAVAILABLE,
    CHECKED_IN,
    CHECKED_OUT,
    UNKNOWN_PLACE,
    AMBIGUOUS_PLACE,
}

enum class HistoryMessage {
    EVENT_UPDATED,
    EVENT_DELETED,
    SESSION_DELETED,
    UNDONE,
    REDONE,
    EVENT_NOT_FOUND,
    INVALID_TIMESTAMP,
    CHECKOUT_BEFORE_CHECKIN,
    OVERLAP,
    ORPHAN_CHECKOUT,
    SESSION_NOT_FOUND,
    NOTHING_TO_UNDO,
    NOTHING_TO_REDO,
}

data class ActiveVisitUi(
    val placeUuid: String,
    val placeName: String,
    val sinceMs: Long,
)

data class CheckInHomeState(
    val activeVisit: ActiveVisitUi? = null,
    val events: List<PlaceEventEntity> = emptyList(),
    val working: Boolean = false,
    val message: CheckInMessage? = null,
    val messagePlaceName: String? = null,
    val ambiguousCandidates: List<CheckInCandidate> = emptyList(),
    val pendingCheckInLocation: LocationSample? = null,
)

data class HistoryUiState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: HistoryMessage? = null,
)

enum class RestoreFlowPhase {
    IDLE,
    DISCOVERING,
    INSPECTING,
    READY,
    RESTORING,
    DEFERRING,
    DEFERRED,
    SUCCESS,
    ERROR,
}

enum class RestoreFlowOrigin {
    FIRST_RUN,
    MANUAL,
}

enum class RestoreUiError {
    DISCOVERY_FAILED,
    NO_COMPATIBLE_BACKUP,
    INVALID_BACKUP,
    RESTORE_FAILED,
    PRESERVE_FAILED,
}

data class RestoreUiState(
    val phase: RestoreFlowPhase = RestoreFlowPhase.IDLE,
    val origin: RestoreFlowOrigin = RestoreFlowOrigin.FIRST_RUN,
    val candidate: ValidatedBackup? = null,
    val validationCode: BackupValidationCode? = null,
    val error: RestoreUiError? = null,
    val summary: RestoreSummary? = null,
    val preservedFileName: String? = null,
) {
    val dialogVisible: Boolean
        get() = phase in setOf(
            RestoreFlowPhase.DISCOVERING,
            RestoreFlowPhase.INSPECTING,
            RestoreFlowPhase.READY,
            RestoreFlowPhase.RESTORING,
            RestoreFlowPhase.DEFERRING,
            RestoreFlowPhase.SUCCESS,
            RestoreFlowPhase.ERROR,
        )

    val hasDeferredBackup: Boolean
        get() = (phase == RestoreFlowPhase.DEFERRED) && (candidate != null)
}

data class HomeUiState(
    val dataLoaded: Boolean = false,
    val places: List<PlaceEntity> = emptyList(),
    val placeItems: List<PlaceListUiModel> = emptyList(),
    val visits: List<VisitUiModel> = emptyList(),
    val form: PlaceFormState = PlaceFormState(),
    val safGate: SafGateState = SafGateState(),
    val addressAutocomplete: AddressAutocompleteState = AddressAutocompleteState(configured = false),
    val checkIn: CheckInHomeState = CheckInHomeState(),
    val nowMs: Long = System.currentTimeMillis(),
    val stats: StatsSnapshot = StatsSnapshot(),
    val routeDistances: RouteDistanceStatsUi = RouteDistanceStatsUi(),
    val history: HistoryUiState = HistoryUiState(),
    val restore: RestoreUiState = RestoreUiState(),
)

class LuoghiHomeViewModel(
    private val container: LuoghiAppContainer,
    context: Context,
) : ViewModel() {
    private val restoreUiPrefs = context.applicationContext.getSharedPreferences(RESTORE_UI_PREFS, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        HomeUiState(
            addressAutocomplete = AddressAutocompleteState(
                configured = container.addressAutocomplete.isConfigured,
                message = if (container.addressAutocomplete.isConfigured) null else AddressMessage.CONFIG_MISSING,
            ),
        ),
    )
    private var addressSearchJob: Job? = null
    private val addressLatestGate = AddressAutocompleteLatestGate()
    private var lastAddressSearchQuery: String? = null
    private var safSetupJob: Job? = null

    private val historyActionState = combine(
        container.checkIns.latestUndoableHistoryAction,
        container.checkIns.latestRedoableHistoryAction,
    ) { undoable, redoable ->
        (undoable != null) to (redoable != null)
    }

    val state: StateFlow<HomeUiState> = combine(
        container.places.places,
        container.checkIns.events,
        container.stats.globalStatsState,
        mutableState,
    ) { places, events, globalStatsState, state ->
        val nowMs = maxOf(state.nowMs, System.currentTimeMillis())
        val visits = VisitMapper.map(events, places, nowMs)
        val stats = container.stats.snapshot(places, events, globalStatsState, nowMs)
        state.copy(
            dataLoaded = true,
            places = places,
            placeItems = PlaceListUiMapper.map(places, stats.places, visits),
            visits = visits,
            checkIn = state.checkIn.withDerivedVisit(places, events, visits),
            nowMs = nowMs,
            stats = stats,
            routeDistances = state.routeDistances,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = mutableState.value,
    )

    init {
        refreshSafGate()
        observeHistoryActions()
    }

    private fun observeHistoryActions() {
        viewModelScope.launch {
            historyActionState.collect { (canUndo, canRedo) ->
                mutableState.update {
                    it.copy(history = it.history.copy(canUndo = canUndo, canRedo = canRedo))
                }
            }
        }
    }

    fun refreshSafGate() {
        safSetupJob?.cancel()
        safSetupJob = viewModelScope.launch {
            mutableState.update { it.copy(safGate = it.safGate.copy(loading = true)) }
            val status = withContext(Dispatchers.IO) { container.safExport.verify() }
            val gate = SafGateState(
                status = status,
                loading = false,
                folderLabel = withContext(Dispatchers.IO) { container.safExport.folderLabel() },
            )
            mutableState.update { it.copy(safGate = gate) }
            if ((status == BackupFolderStore.ValidationStatus.READY) && (!mutableState.value.restore.hasDeferredBackup)) {
                resolveReadyBackupFolder()
            }
        }
    }

    fun saveSelectedSafFolder(uri: Uri) {
        safSetupJob?.cancel()
        safSetupJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    safGate = it.safGate.copy(loading = true),
                    restore = RestoreUiState(
                        phase = RestoreFlowPhase.DISCOVERING,
                        origin = RestoreFlowOrigin.FIRST_RUN,
                    ),
                )
            }
            val status = withContext(Dispatchers.IO) {
                container.safExport.saveSelectedFolder(uri, queueInitialExport = false)
            }
            val gate = SafGateState(
                status = status,
                loading = false,
                folderLabel = withContext(Dispatchers.IO) { container.safExport.folderLabel() },
            )
            mutableState.update { it.copy(safGate = gate) }
            if (status == BackupFolderStore.ValidationStatus.READY) {
                container.restore.protectAutoExport()
                resolveReadyBackupFolder()
            } else {
                mutableState.update { it.copy(restore = RestoreUiState()) }
            }
        }
    }

    fun inspectBackup(uri: Uri) {
        val deferredBeforeInspection = mutableState.value.restore.takeIf { it.hasDeferredBackup }
        val inspectionOrigin = if (container.restore.isAutoExportProtected()) {
            RestoreFlowOrigin.FIRST_RUN
        } else {
            RestoreFlowOrigin.MANUAL
        }
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    restore = RestoreUiState(
                        phase = RestoreFlowPhase.INSPECTING,
                        origin = inspectionOrigin,
                    )
                )
            }
            val inspected = withContext(Dispatchers.IO) { container.restore.inspectUri(uri) }
            inspected.onSuccess { candidate ->
                mutableState.update {
                    it.copy(
                        restore = RestoreUiState(
                            phase = RestoreFlowPhase.READY,
                            origin = inspectionOrigin,
                            candidate = candidate,
                        )
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        restore = (deferredBeforeInspection ?: RestoreUiState(origin = inspectionOrigin)).copy(
                            phase = RestoreFlowPhase.ERROR,
                            validationCode = (error as? BackupValidationException)?.code,
                            error = RestoreUiError.INVALID_BACKUP,
                        )
                    )
                }
            }
        }
    }

    fun restoreSelectedBackup() {
        val current = mutableState.value.restore
        val candidate = current.candidate ?: return
        if (current.phase != RestoreFlowPhase.READY) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(restore = current.copy(phase = RestoreFlowPhase.RESTORING, error = null))
            }
            when (val result = withContext(Dispatchers.IO) { container.restore.restore(candidate) }) {
                is RestoreResult.Success -> mutableState.update {
                    clearDeferredRestore()
                    it.copy(
                        restore = RestoreUiState(
                            phase = RestoreFlowPhase.SUCCESS,
                            origin = current.origin,
                            summary = result.summary,
                        )
                    )
                }

                is RestoreResult.InvalidBackup -> mutableState.update {
                    it.copy(
                        restore = current.copy(
                            phase = RestoreFlowPhase.ERROR,
                            validationCode = result.code,
                            error = RestoreUiError.INVALID_BACKUP,
                        )
                    )
                }

                is RestoreResult.Failure -> mutableState.update {
                    it.copy(
                        restore = current.copy(
                            phase = RestoreFlowPhase.ERROR,
                            error = RestoreUiError.RESTORE_FAILED,
                        )
                    )
                }
            }
        }
    }

    fun continueWithoutRestore() {
        val current = mutableState.value.restore
        val candidate = current.candidate ?: return
        if (current.phase != RestoreFlowPhase.READY) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(restore = current.copy(phase = RestoreFlowPhase.DEFERRING, error = null))
            }
            val deferred = withContext(Dispatchers.IO) { container.restore.continueWithoutRestore(candidate) }
            if (deferred.isSuccess) {
                val preservedFileName = deferred.getOrThrow()
                val preservedCandidate = preservingCancellation {
                    withContext(Dispatchers.IO) {
                        container.restore.discoverConfiguredBackups().compatible.firstOrNull {
                            (it.preview.sha256.equals(candidate.preview.sha256, ignoreCase = true)) &&
                                (it.preview.source.displayName == preservedFileName)
                        }
                    }
                }.getOrNull() ?: candidate
                rememberDeferredRestore(preservedCandidate, preservedFileName)
                mutableState.update {
                    it.copy(
                        restore = current.copy(
                            phase = RestoreFlowPhase.DEFERRED,
                            candidate = preservedCandidate,
                            preservedFileName = preservedFileName,
                        )
                    )
                }
            } else {
                mutableState.update {
                    it.copy(
                        restore = current.copy(
                            phase = RestoreFlowPhase.ERROR,
                            error = RestoreUiError.PRESERVE_FAILED,
                        )
                    )
                }
            }
        }
    }

    fun showDeferredRestore() {
        val current = mutableState.value.restore
        if (!current.hasDeferredBackup) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(restore = current.copy(phase = RestoreFlowPhase.INSPECTING))
            }
            val expectedSha = deferredRestoreSha() ?: current.candidate?.preview?.sha256
            val preserved = preservingCancellation {
                withContext(Dispatchers.IO) {
                    container.restore.discoverConfiguredBackups().compatible.firstOrNull { candidate ->
                        (expectedSha != null) && candidate.preview.sha256.equals(expectedSha, ignoreCase = true)
                    }
                }
            }.getOrNull()
            mutableState.update {
                it.copy(
                    restore = if (preserved == null) {
                        current.copy(
                            phase = RestoreFlowPhase.ERROR,
                            error = RestoreUiError.DISCOVERY_FAILED,
                        )
                    } else {
                        current.copy(
                            phase = RestoreFlowPhase.READY,
                            candidate = preserved,
                            error = null,
                        )
                    }
                )
            }
        }
    }

    fun closeRestoreStatus() {
        mutableState.update {
            val current = it.restore
            val next = when (current.phase) {
                RestoreFlowPhase.ERROR -> {
                    if ((current.candidate != null) && (current.preservedFileName != null)) {
                        current.copy(phase = RestoreFlowPhase.DEFERRED, error = null, validationCode = null)
                    } else if (current.candidate != null) {
                        current.copy(phase = RestoreFlowPhase.READY, error = null, validationCode = null)
                    } else {
                        RestoreUiState()
                    }
                }

                RestoreFlowPhase.SUCCESS -> RestoreUiState()
                else -> current
            }
            it.copy(restore = next)
        }
    }

    private suspend fun resolveReadyBackupFolder() {
        val hasData = preservingCancellation {
            withContext(Dispatchers.IO) { container.restore.hasUserData() }
        }.getOrElse {
            mutableState.update {
                it.copy(
                    restore = RestoreUiState(
                        phase = RestoreFlowPhase.ERROR,
                        error = RestoreUiError.DISCOVERY_FAILED,
                    )
                )
            }
            return
        }
        val autoExportProtected = container.restore.isAutoExportProtected()
        if (hasData && !autoExportProtected) {
            container.restore.resumeAutoExport()
            val deferredSha = deferredRestoreSha()
            if (deferredSha == null) {
                mutableState.update { it.copy(restore = RestoreUiState()) }
            } else {
                val discovery = preservingCancellation {
                    withContext(Dispatchers.IO) { container.restore.discoverConfiguredBackups() }
                }.getOrNull()
                val deferred = discovery?.compatible?.firstOrNull {
                    it.preview.sha256.equals(deferredSha, ignoreCase = true)
                }
                if (deferred == null) {
                    clearDeferredRestore()
                    mutableState.update { it.copy(restore = RestoreUiState()) }
                } else {
                    mutableState.update {
                        it.copy(
                            restore = RestoreUiState(
                                phase = RestoreFlowPhase.DEFERRED,
                                origin = RestoreFlowOrigin.FIRST_RUN,
                                candidate = deferred,
                                preservedFileName = deferredRestoreFileName(),
                            )
                        )
                    }
                }
            }
            return
        }

        container.restore.protectAutoExport()
        mutableState.update {
            it.copy(
                restore = RestoreUiState(
                    phase = RestoreFlowPhase.DISCOVERING,
                    origin = RestoreFlowOrigin.FIRST_RUN,
                )
            )
        }
        val discovery = preservingCancellation {
            withContext(Dispatchers.IO) { container.restore.discoverConfiguredBackups() }
        }.getOrElse {
            mutableState.update {
                it.copy(
                    restore = RestoreUiState(
                        phase = RestoreFlowPhase.ERROR,
                        origin = RestoreFlowOrigin.FIRST_RUN,
                        error = RestoreUiError.DISCOVERY_FAILED,
                    )
                )
            }
            return
        }
        val preferred = discovery.preferred
        when {
            preferred != null -> {
                val isDeferred = preferred.preview.sha256.equals(deferredRestoreSha(), ignoreCase = true)
                if (isDeferred) container.restore.resumeAutoExport()
                mutableState.update {
                    it.copy(
                        restore = RestoreUiState(
                            phase = if (isDeferred) RestoreFlowPhase.DEFERRED else RestoreFlowPhase.READY,
                            origin = RestoreFlowOrigin.FIRST_RUN,
                            candidate = preferred,
                            preservedFileName = deferredRestoreFileName().takeIf { isDeferred },
                        )
                    )
                }
            }
            discovery.rejected.any { it.code != BackupValidationCode.EMPTY_BACKUP } -> {
                mutableState.update {
                    it.copy(
                        restore = RestoreUiState(
                            phase = RestoreFlowPhase.ERROR,
                            origin = RestoreFlowOrigin.FIRST_RUN,
                            validationCode = discovery.rejected.first { candidate ->
                                candidate.code != BackupValidationCode.EMPTY_BACKUP
                            }.code,
                            error = RestoreUiError.NO_COMPATIBLE_BACKUP,
                        )
                    )
                }
            }
            else -> {
                clearDeferredRestore()
                container.restore.resumeAutoExport()
                mutableState.update { it.copy(restore = RestoreUiState()) }
            }
        }
    }

    private fun rememberDeferredRestore(candidate: ValidatedBackup, fileName: String) {
        restoreUiPrefs.edit {
            putString(KEY_DEFERRED_SHA256, candidate.preview.sha256)
            putString(KEY_DEFERRED_FILE, fileName)
        }
    }

    private fun clearDeferredRestore() {
        restoreUiPrefs.edit {
            remove(KEY_DEFERRED_SHA256)
            remove(KEY_DEFERRED_FILE)
        }
    }

    private fun deferredRestoreSha(): String? =
        restoreUiPrefs.getString(KEY_DEFERRED_SHA256, null)?.takeIf { it.isNotBlank() }

    private fun deferredRestoreFileName(): String? =
        restoreUiPrefs.getString(KEY_DEFERRED_FILE, null)?.takeIf { it.isNotBlank() }

    fun editPlace(place: PlaceEntity) {
        container.addressAutocomplete.resetSession()
        addressSearchJob?.cancel()
        addressLatestGate.invalidate()
        lastAddressSearchQuery = null
        mutableState.update {
            it.copy(
                form = PlaceFormState.from(place),
                addressAutocomplete = it.addressAutocomplete.copy(loading = false, suggestions = emptyList()),
            )
        }
    }

    fun newPlace() {
        container.addressAutocomplete.resetSession()
        addressSearchJob?.cancel()
        addressLatestGate.invalidate()
        lastAddressSearchQuery = null
        mutableState.update {
            it.copy(
                form = PlaceFormState(),
                addressAutocomplete = resetAddressState(),
                checkIn = it.checkIn.copy(
                    message = null,
                    messagePlaceName = null,
                    ambiguousCandidates = emptyList(),
                    pendingCheckInLocation = null,
                ),
            )
        }
    }

    fun updateNickname(value: String) {
        mutableState.update { it.copy(form = it.form.copy(nickname = value)) }
    }

    fun updateRadius(value: String) {
        mutableState.update { it.copy(form = it.form.copy(radiusM = value.filterRadiusText())) }
    }

    fun updateNotes(value: String) {
        mutableState.update { it.copy(form = it.form.copy(notes = value)) }
    }

    fun updateAddress(value: String) {
        val query = value.trim()
        val shouldSearch = AddressAutocompletePolicy.shouldSearch(query, container.addressAutocomplete.isConfigured)
        val duplicateSearch = shouldSearch && AddressAutocompletePolicy.isDuplicateSearch(lastAddressSearchQuery, value)
        mutableState.update {
            it.copy(
                form = it.form.copy(address = value, lat = null, lon = null),
                addressAutocomplete = if (duplicateSearch) {
                    it.addressAutocomplete
                } else {
                    resetAddressState().copy(
                        message = if (container.addressAutocomplete.isConfigured) null else AddressMessage.CONFIG_MISSING,
                    )
                },
            )
        }
        if (!shouldSearch) {
            addressSearchJob?.cancel()
            lastAddressSearchQuery = null
            return
        }
        if (duplicateSearch) return
        addressSearchJob?.cancel()
        lastAddressSearchQuery = query
        val request = addressLatestGate.next(query)
        addressSearchJob = viewModelScope.launch {
            delay(AddressAutocompletePolicy.DEBOUNCE_MS.milliseconds)
            mutableState.update { it.copy(addressAutocomplete = it.addressAutocomplete.copy(loading = true)) }
            runCatching {
                withTimeoutOrNull(AddressAutocompletePolicy.SEARCH_TIMEOUT_MS.milliseconds) {
                    withContext(Dispatchers.IO) {
                        AddressAutocompletePolicy.limitSuggestions(container.addressAutocomplete.search(query))
                    }
                } ?: throw AddressOperationTimeoutException()
            }
                .onSuccess { suggestions ->
                    if (!addressLatestGate.isLatest(request, mutableState.value.form.address)) return@onSuccess
                    mutableState.update {
                        it.copy(
                            addressAutocomplete = it.addressAutocomplete.copy(
                                loading = false,
                                suggestions = suggestions,
                                message = null,
                            )
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (!addressLatestGate.isLatest(request, mutableState.value.form.address)) {
                        return@onFailure
                    }
                    lastAddressSearchQuery = null
                    mutableState.update {
                        it.copy(
                            addressAutocomplete = it.addressAutocomplete.copy(
                                loading = false,
                                suggestions = emptyList(),
                                message = if (error is AddressOperationTimeoutException) {
                                    AddressMessage.SEARCH_TIMEOUT
                                } else {
                                    AddressMessage.SEARCH_FAILED
                                },
                            ),
                        )
                    }
                }
        }
    }

    fun selectAddressSuggestion(suggestion: AddressSuggestion) {
        addressSearchJob?.cancel()
        addressLatestGate.invalidate()
        lastAddressSearchQuery = null
        viewModelScope.launch {
            mutableState.update { it.copy(addressAutocomplete = it.addressAutocomplete.copy(loading = true)) }
            runCatching {
                withTimeoutOrNull(AddressAutocompletePolicy.RESOLVE_TIMEOUT_MS.milliseconds) {
                    withContext(Dispatchers.IO) {
                        container.addressAutocomplete.resolve(suggestion)
                    }
                } ?: throw AddressOperationTimeoutException()
            }
                .onSuccess { resolved ->
                    mutableState.update {
                        it.copy(
                            form = it.form.copy(
                                address = resolved.address,
                                lat = resolved.latitude,
                                lon = resolved.longitude,
                            ),
                            addressAutocomplete = it.addressAutocomplete.copy(
                                loading = false,
                                suggestions = emptyList(),
                                message = null,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(
                            addressAutocomplete = it.addressAutocomplete.copy(
                                loading = false,
                                message = if (error is AddressOperationTimeoutException) {
                                    AddressMessage.RESOLUTION_TIMEOUT
                                } else {
                                    AddressMessage.RESOLUTION_FAILED
                                },
                            )
                        )
                    }
                }
        }
    }

    fun savePlace() {
        val form = mutableState.value.form
        if (form.nickname.isBlank()) return
        viewModelScope.launch {
            val pendingLocation = mutableState.value.checkIn.pendingCheckInLocation
            val mutation = PlaceMutation(
                uuid = form.uuid,
                nickname = form.nickname,
                address = form.address,
                lat = form.lat,
                lon = form.lon,
                radiusM = form.radiusM.toDoubleOrNull()?.coerceAtLeast(MIN_RADIUS_M),
                notes = form.notes,
                sourceApp = form.sourceApp,
            )
            val createdFromUnknownLocation = (pendingLocation != null) && (form.uuid == null)
            if (createdFromUnknownLocation) {
                container.checkIns.checkInNewPlace(mutation, pendingLocation)
            } else {
                container.places.save(mutation)
            }
            container.addressAutocomplete.resetSession()
            addressSearchJob?.cancel()
            addressLatestGate.invalidate()
            lastAddressSearchQuery = null
            mutableState.update {
                it.copy(
                    form = PlaceFormState(),
                    addressAutocomplete = resetAddressState(),
                    checkIn = it.checkIn.copy(
                        message = if (createdFromUnknownLocation) CheckInMessage.CHECKED_IN else it.checkIn.message,
                        messagePlaceName = if (createdFromUnknownLocation) form.nickname.trim() else it.checkIn.messagePlaceName,
                        pendingCheckInLocation = null,
                        ambiguousCandidates = emptyList(),
                        working = false,
                    ),
                )
            }
        }
    }

    fun onPrimaryCheckAction() {
        val active = state.value.checkIn.activeVisit
        if (active == null) {
            checkInAtCurrentLocation()
        } else {
            checkOut(active)
        }
    }

    fun refreshRouteDistanceStats() {
        val snapshot = state.value
        viewModelScope.launch {
            mutableState.update { it.copy(routeDistances = it.routeDistances.copy(loading = true)) }
            val stats = withContext(Dispatchers.IO) {
                container.routeDistances.stats(
                    places = snapshot.places,
                    events = snapshot.checkIn.events,
                    nowMs = System.currentTimeMillis(),
                )
            }
            mutableState.update { it.copy(routeDistances = stats) }
        }
    }

    fun onLocationPermissionDenied() {
        mutableState.update {
            it.copy(checkIn = it.checkIn.copy(message = CheckInMessage.LOCATION_PERMISSION_DENIED, messagePlaceName = null))
        }
    }

    fun selectAmbiguousCheckIn(placeUuid: String) {
        val checkInState = mutableState.value.checkIn
        val location = checkInState.pendingCheckInLocation ?: return
        val candidate = checkInState.ambiguousCandidates.firstOrNull { it.place.uuid == placeUuid } ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(checkIn = it.checkIn.copy(working = true)) }
            container.checkIns.checkIn(placeUuid, location)
            mutableState.update {
                it.copy(
                    checkIn = it.checkIn.copy(
                        working = false,
                        message = CheckInMessage.CHECKED_IN,
                        messagePlaceName = candidate.place.nickname,
                        ambiguousCandidates = emptyList(),
                        pendingCheckInLocation = null,
                    )
                )
            }
        }
    }

    fun deletePlace(uuid: String) {
        viewModelScope.launch {
            container.places.delete(uuid)
            if (mutableState.value.form.uuid == uuid) newPlace()
        }
    }

    fun editHistoryEvent(eventId: Long, timestamp: Long, notes: String?) {
        viewModelScope.launch {
            val result = container.checkIns.editEvent(eventId, timestamp, notes)
            applyHistoryResult(result, HistoryMessage.EVENT_UPDATED)
        }
    }

    fun deleteHistoryEvent(eventId: Long) {
        viewModelScope.launch {
            val result = container.checkIns.deleteEvent(eventId)
            applyHistoryResult(result, HistoryMessage.EVENT_DELETED)
        }
    }

    fun deleteHistorySession(sessionUuid: String) {
        viewModelScope.launch {
            val result = container.checkIns.deleteSession(sessionUuid)
            applyHistoryResult(result, HistoryMessage.SESSION_DELETED)
        }
    }

    fun undoHistory() {
        viewModelScope.launch {
            val result = container.checkIns.undoHistory()
            applyHistoryResult(result, HistoryMessage.UNDONE)
        }
    }

    fun redoHistory() {
        viewModelScope.launch {
            val result = container.checkIns.redoHistory()
            applyHistoryResult(result, HistoryMessage.REDONE)
        }
    }

    fun clearHistoryMessage() {
        mutableState.update { it.copy(history = it.history.copy(message = null)) }
    }

    private fun checkInAtCurrentLocation() {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    checkIn = it.checkIn.copy(
                        working = true,
                        message = null,
                        messagePlaceName = null,
                        ambiguousCandidates = emptyList(),
                        pendingCheckInLocation = null,
                    )
                )
            }
            val location = runCatching { container.location.currentLocation() }.getOrNull()
            if (location == null) {
                mutableState.update {
                    it.copy(checkIn = it.checkIn.copy(working = false, message = CheckInMessage.LOCATION_UNAVAILABLE))
                }
                return@launch
            }

            when (val decision = container.checkIns.choosePlace(state.value.places, location)) {
                is CheckInMatchDecision.Matched -> {
                    container.checkIns.checkIn(decision.candidate.place.uuid, location)
                    mutableState.update {
                        it.copy(
                            checkIn = it.checkIn.copy(
                                working = false,
                                message = CheckInMessage.CHECKED_IN,
                                messagePlaceName = decision.candidate.place.nickname,
                            )
                        )
                    }
                }

                is CheckInMatchDecision.Ambiguous -> {
                    mutableState.update {
                        it.copy(
                            checkIn = it.checkIn.copy(
                                working = false,
                                message = CheckInMessage.AMBIGUOUS_PLACE,
                                ambiguousCandidates = decision.candidates,
                                pendingCheckInLocation = location,
                            )
                        )
                    }
                }

                CheckInMatchDecision.UnknownPlace -> {
                    container.addressAutocomplete.resetSession()
                    addressSearchJob?.cancel()
                    addressLatestGate.invalidate()
                    lastAddressSearchQuery = null
                    mutableState.update {
                        it.copy(
                            form = PlaceFormState(
                                lat = location.latitude,
                                lon = location.longitude,
                                radiusM = CheckInPolicy.DEFAULT_RADIUS_M.toInt().toString(),
                                notes = "",
                            ),
                            addressAutocomplete = resetAddressState(),
                            checkIn = it.checkIn.copy(
                                working = false,
                                message = CheckInMessage.UNKNOWN_PLACE,
                                ambiguousCandidates = emptyList(),
                                pendingCheckInLocation = location,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun checkOut(active: ActiveVisitUi) {
        viewModelScope.launch {
            mutableState.update {
                it.copy(checkIn = it.checkIn.copy(working = true, message = null, messagePlaceName = null))
            }
            val location = runCatching { container.location.currentLocation() }.getOrNull()
            container.checkIns.checkOut(active.placeUuid, location)
            mutableState.update {
                it.copy(
                    checkIn = it.checkIn.copy(
                        working = false,
                        message = CheckInMessage.CHECKED_OUT,
                        messagePlaceName = active.placeName,
                        ambiguousCandidates = emptyList(),
                        pendingCheckInLocation = null,
                    )
                )
            }
        }
    }

    private fun resetAddressState(): AddressAutocompleteState =
        AddressAutocompleteState(
            configured = container.addressAutocomplete.isConfigured,
            message = if (container.addressAutocomplete.isConfigured) null else AddressMessage.CONFIG_MISSING,
        )

    private fun applyHistoryResult(result: HistoryMutationResult, successMessage: HistoryMessage) {
        val message = when (result) {
            HistoryMutationResult.Success -> successMessage
            is HistoryMutationResult.Failure -> result.error.toHistoryMessage()
        }
        mutableState.update { it.copy(history = it.history.copy(message = message)) }
    }

    private fun HistoryValidationError.toHistoryMessage(): HistoryMessage =
        when (this) {
            HistoryValidationError.EVENT_NOT_FOUND -> HistoryMessage.EVENT_NOT_FOUND
            HistoryValidationError.INVALID_TIMESTAMP -> HistoryMessage.INVALID_TIMESTAMP
            HistoryValidationError.CHECKOUT_BEFORE_CHECKIN -> HistoryMessage.CHECKOUT_BEFORE_CHECKIN
            HistoryValidationError.OVERLAP -> HistoryMessage.OVERLAP
            HistoryValidationError.ORPHAN_CHECKOUT -> HistoryMessage.ORPHAN_CHECKOUT
            HistoryValidationError.SESSION_NOT_FOUND -> HistoryMessage.SESSION_NOT_FOUND
            HistoryValidationError.NOTHING_TO_UNDO -> HistoryMessage.NOTHING_TO_UNDO
            HistoryValidationError.NOTHING_TO_REDO -> HistoryMessage.NOTHING_TO_REDO
        }

    private fun String.filterRadiusText(): String =
        filter { it.isDigit() || it == '.' }.let { value ->
            if (value.count { it == '.' } <= 1) value else value.substringBefore('.') + "." + value.substringAfter('.').replace(".", "")
        }

    companion object {
        private const val MIN_RADIUS_M = 5.0
        private const val RESTORE_UI_PREFS = "luoghi_restore_ui"
        private const val KEY_DEFERRED_SHA256 = "deferred_sha256"
        private const val KEY_DEFERRED_FILE = "deferred_file"
    }
}

private class AddressOperationTimeoutException : RuntimeException()

private suspend fun <T> preservingCancellation(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

private fun CheckInHomeState.withDerivedVisit(
    places: List<PlaceEntity>,
    events: List<PlaceEventEntity>,
    visits: List<VisitUiModel>,
): CheckInHomeState {
    val placesByUuid = places.associateBy { it.uuid }
    val activeVisit = visits.firstOrNull { it.isActive && it.startedAt != null }
    return copy(
        events = events,
        activeVisit = activeVisit?.let { visit ->
            val place = placesByUuid[visit.placeId]
            ActiveVisitUi(
                placeUuid = visit.placeId,
                placeName = place?.nickname?.takeIf { it.isNotBlank() }.orEmpty(),
                sinceMs = requireNotNull(visit.startedAt),
            )
        },
    )
}
