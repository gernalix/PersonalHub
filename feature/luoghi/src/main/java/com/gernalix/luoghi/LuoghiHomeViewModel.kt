package com.gernalix.luoghi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gernalix.luoghi.capsules.addressautocomplete.AddressAutocompleteLatestGate
import com.gernalix.luoghi.capsules.addressautocomplete.AddressAutocompletePolicy
import com.gernalix.luoghi.capsules.addressautocomplete.AddressSuggestion
import com.gernalix.luoghi.capsules.checkin.CheckInAttemptOutcomes
import com.gernalix.luoghi.capsules.checkin.CheckInCandidate
import com.gernalix.luoghi.capsules.checkin.CheckInMatchDecision
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.capsules.checkin.HistoryMutationResult
import com.gernalix.luoghi.capsules.checkin.HistoryValidationError
import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.capsules.places.PlaceMutation
import com.gernalix.luoghi.capsules.places.PlaceListUiMapper
import com.gernalix.luoghi.capsules.places.PlaceListLocation
import com.gernalix.luoghi.capsules.places.PlaceSortCriterion
import com.gernalix.luoghi.capsules.places.PlaceSortDirection
import com.gernalix.luoghi.capsules.places.PlaceSortState
import com.gernalix.luoghi.capsules.places.PlaceListUiModel
import com.gernalix.luoghi.capsules.routedistance.RouteDistanceStatsUi
import com.gernalix.luoghi.capsules.stats.StatsSnapshot
import com.gernalix.luoghi.capsules.visits.VisitMapper
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceDeleteResult
import com.gernalix.luoghi.data.CheckInAttemptCandidateEntity
import com.gernalix.luoghi.data.CheckInAttemptDiagnostic
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.data.PlaceGeofenceConfigEntity
import com.gernalix.personalhub.contracts.database.HubTagEntity
import com.gernalix.personalhub.alerts.AlertRuleEntity
import com.gernalix.personalhub.core.alerts.PlaceAlertDraft
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
    val tagsText: String = "",
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
    CHECKED_IN,
    VISIT_CREATED,
    UNDONE,
    REDONE,
    EVENT_NOT_FOUND,
    INVALID_TIMESTAMP,
    CHECKOUT_BEFORE_CHECKIN,
    OVERLAP,
    DUPLICATE,
    ORPHAN_CHECKOUT,
    SESSION_NOT_FOUND,
    NOTHING_TO_UNDO,
    NOTHING_TO_REDO,
}

enum class GeofenceMessage {
    SAVED,
    PERMISSION_MISSING,
}

enum class PlaceDeleteMessage {
    DELETED,
    ARCHIVED_REFERENCED,
    NOT_FOUND,
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
    val pendingAttemptId: String? = null,
    val recentAttempts: List<CheckInAttemptDiagnostic> = emptyList(),
)

data class HistoryUiState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: HistoryMessage? = null,
)

data class HomeUiState(
    val dataLoaded: Boolean = false,
    val places: List<PlaceEntity> = emptyList(),
    val placeItems: List<PlaceListUiModel> = emptyList(),
    val visits: List<VisitUiModel> = emptyList(),
    val form: PlaceFormState = PlaceFormState(),
    val addressAutocomplete: AddressAutocompleteState = AddressAutocompleteState(configured = false),
    val checkIn: CheckInHomeState = CheckInHomeState(),
    val nowMs: Long = System.currentTimeMillis(),
    val stats: StatsSnapshot = StatsSnapshot(),
    val routeDistances: RouteDistanceStatsUi = RouteDistanceStatsUi(),
    val history: HistoryUiState = HistoryUiState(),
    val placeSort: PlaceSortState = PlaceSortState(),
    val currentListLocation: PlaceListLocation? = null,
    val listLocationUnavailable: Boolean = false,
    val geofenceConfigs: Map<String, PlaceGeofenceConfigEntity> = emptyMap(),
    val geofenceMessage: GeofenceMessage? = null,
    val placeDeleteMessage: PlaceDeleteMessage? = null,
    val placeTags: List<HubTagEntity> = emptyList(),
    val placeTagIds: Map<String, Set<String>> = emptyMap(),
    val selectedPlaceTagIds: Set<String> = emptySet(),
    val placeAlertRules: List<AlertRuleEntity> = emptyList(),
    val placeAlertTagTargets: Map<String, Set<String>> = emptyMap(),
)

class LuoghiHomeViewModel(
    private val container: LuoghiAppContainer,
) : ViewModel() {
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

    private val historyActionState = combine(
        container.checkIns.latestUndoableHistoryAction,
        container.checkIns.latestRedoableHistoryAction,
    ) { undoable, redoable ->
        (undoable != null) to (redoable != null)
    }

    val state: StateFlow<HomeUiState> = combine(
        combine(container.places.places, container.places.tagAssignments, container.places.tags) { places, assignments, tags -> Triple(places, assignments, tags) },
        combine(container.checkIns.events, container.geofences.configs, container.checkIns.recentAttempts) { events, geofenceConfigs, attempts ->
            Triple(events, geofenceConfigs, attempts)
        },
        container.stats.globalStatsState,
        mutableState,
        HubContextRuntime.contextChanges(),
    ) { placeData, eventAndGeofenceConfigs, globalStatsState, state, _ ->
        val (places, tagAssignments, tags) = placeData
        val (events, geofenceConfigs, attempts) = eventAndGeofenceConfigs
        val nowMs = maxOf(state.nowMs, System.currentTimeMillis())
        val visits = VisitMapper.map(events, places, nowMs, HubContextRuntime.temporalFacts())
        val stats = container.stats.snapshot(places, events, globalStatsState, nowMs, visits)
        val placeTagIds = tagAssignments.filter { it.moduleId == "places" && it.entityKind == "place" }
            .groupBy({ it.canonicalId }, { it.tagId }).mapValues { it.value.toSet() }
        val visiblePlaces = if (state.selectedPlaceTagIds.isEmpty()) places else places.filter { place ->
            placeTagIds[place.uuid].orEmpty().containsAll(state.selectedPlaceTagIds)
        }
        state.copy(
            dataLoaded = true,
            places = places,
            geofenceConfigs = geofenceConfigs.associateBy { it.placeUuid },
            visits = visits,
            checkIn = state.checkIn.withDerivedVisit(places, events, visits).copy(recentAttempts = attempts),
            nowMs = nowMs,
            stats = stats,
            routeDistances = state.routeDistances,
            placeItems = PlaceListUiMapper.map(
                places = visiblePlaces,
                stats = stats.places,
                visits = visits,
                sort = state.placeSort,
                currentLocation = state.currentListLocation,
            ),
            placeTagIds = placeTagIds,
            placeTags = tags,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = mutableState.value,
    )

    init {
        observeHistoryActions()
        viewModelScope.launch { container.checkIns.recoverInterruptedAttempts() }
    }

    fun togglePlaceTagFilter(tagId: String) {
        mutableState.update { current ->
            current.copy(selectedPlaceTagIds = if (tagId in current.selectedPlaceTagIds) current.selectedPlaceTagIds - tagId else current.selectedPlaceTagIds + tagId)
        }
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
        viewModelScope.launch {
            val tagText = container.places.tagsForPlace(place.uuid).joinToString(", ") { it.name }
            mutableState.update { current ->
                if (current.form.uuid == place.uuid) current.copy(form = current.form.copy(tagsText = tagText)) else current
            }
        }
    }

    fun newPlace() {
        val pendingCheckIn = mutableState.value.checkIn
        val pendingAttemptId = pendingCheckIn.pendingAttemptId
        val cancellationDiagnostic = when {
            pendingCheckIn.ambiguousCandidates.isNotEmpty() ->
                Triple("AMBIGUOUS_USER_CANCELLED", "AMBIGUOUS_MATCH", "User cancelled ambiguous place selection")
            pendingCheckIn.message == CheckInMessage.UNKNOWN_PLACE ->
                Triple("NO_MATCH_USER_CANCELLED", "NO_MATCH", "User cancelled new-place check-in")
            else ->
                Triple("USER_CANCELLED", "USER_CANCELLED", "User cancelled pending check-in")
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
                    message = null,
                    messagePlaceName = null,
                    ambiguousCandidates = emptyList(),
                    pendingCheckInLocation = null,
                    pendingAttemptId = null,
                ),
            )
        }
        if (pendingAttemptId != null) {
            viewModelScope.launch {
                container.checkIns.finishAttempt(
                    attemptId = pendingAttemptId,
                    outcome = CheckInAttemptOutcomes.USER_CANCELLED,
                    stage = cancellationDiagnostic.first,
                    errorCode = cancellationDiagnostic.second,
                    errorMessage = cancellationDiagnostic.third,
                )
            }
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

    fun updatePlaceTags(value: String) {
        mutableState.update { it.copy(form = it.form.copy(tagsText = value)) }
    }

    fun loadPlaceAlertData() {
        viewModelScope.launch { refreshPlaceAlertDataNow() }
    }

    fun createPlaceAlert(draft: PlaceAlertDraft) {
        viewModelScope.launch {
            container.alerts.create(draft)
            refreshPlaceAlertDataNow()
        }
    }

    fun updatePlaceAlert(ruleId: String, draft: PlaceAlertDraft) {
        viewModelScope.launch {
            container.alerts.update(ruleId, draft)
            refreshPlaceAlertDataNow()
        }
    }

    fun deletePlaceAlert(ruleId: String) {
        viewModelScope.launch {
            container.alerts.delete(ruleId)
            refreshPlaceAlertDataNow()
        }
    }

    fun setPlaceAlertEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            container.alerts.setEnabled(ruleId, enabled)
            refreshPlaceAlertDataNow()
        }
    }

    private suspend fun refreshPlaceAlertDataNow() {
        val tags = container.places.listTags()
        val rules = container.alerts.listRules()
        val targets = container.alerts.placeTagTargets(rules.map { it.id })
        mutableState.update {
            it.copy(
                placeTags = tags,
                placeAlertRules = rules,
                placeAlertTagTargets = targets,
            )
        }
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
            val pendingAttemptId = mutableState.value.checkIn.pendingAttemptId
            val mutation = PlaceMutation(
                uuid = form.uuid,
                nickname = form.nickname,
                address = form.address,
                lat = form.lat,
                lon = form.lon,
                radiusM = form.radiusM.toDoubleOrNull()?.coerceAtLeast(MIN_RADIUS_M),
                notes = form.notes,
                tagNames = parsePlaceTagText(form.tagsText),
                sourceApp = form.sourceApp,
            )
            val createdFromUnknownLocation = (pendingLocation != null) && (form.uuid == null)
            if (createdFromUnknownLocation) {
                val createdUuid = runCatching { container.checkIns.checkInNewPlace(mutation, pendingLocation) }
                    .onFailure { error ->
                        if (pendingAttemptId != null) {
                            container.checkIns.finishAttempt(
                                attemptId = pendingAttemptId,
                                outcome = CheckInAttemptOutcomes.PERSISTENCE_FAILED,
                                stage = "NEW_PLACE_CHECK_IN_FAILED",
                                errorCode = error::class.simpleName ?: "PERSISTENCE_FAILED",
                                errorMessage = error.message,
                            )
                        }
                    }
                    .getOrThrow()
                if (pendingAttemptId != null) {
                    container.checkIns.finishAttempt(
                        attemptId = pendingAttemptId,
                        outcome = CheckInAttemptOutcomes.SUCCESS,
                        stage = "NEW_PLACE_CHECKED_IN",
                        selectedPlaceId = createdUuid,
                        matchedPlaceId = createdUuid,
                    )
                }
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
                        pendingAttemptId = null,
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

    fun updatePlaceSort(criterion: PlaceSortCriterion, direction: PlaceSortDirection) {
        mutableState.update { it.copy(placeSort = PlaceSortState(criterion, direction)) }
        if (criterion == PlaceSortCriterion.DISTANCE) refreshListLocation()
    }

    fun refreshListLocation() {
        viewModelScope.launch {
            val location = runCatching { container.location.currentLocation() }.getOrNull()
            mutableState.update {
                it.copy(
                    currentListLocation = location?.let { sample ->
                        PlaceListLocation(sample.latitude, sample.longitude)
                    },
                    listLocationUnavailable = location == null,
                )
            }
        }
    }

    fun onLocationPermissionDenied() {
        viewModelScope.launch {
            val attempt = container.checkIns.beginAttempt()
            container.checkIns.finishAttempt(
                attemptId = attempt.id,
                outcome = CheckInAttemptOutcomes.PERMISSION_DENIED,
                stage = "PERMISSION_DENIED",
                errorCode = "LOCATION_PERMISSION_DENIED",
                errorMessage = "Location permission denied",
            )
        }
        mutableState.update {
            it.copy(checkIn = it.checkIn.copy(message = CheckInMessage.LOCATION_PERMISSION_DENIED, messagePlaceName = null))
        }
    }

    fun selectAmbiguousCheckIn(placeUuid: String) {
        val checkInState = mutableState.value.checkIn
        val location = checkInState.pendingCheckInLocation ?: return
        val attemptId = checkInState.pendingAttemptId
        val candidate = checkInState.ambiguousCandidates.firstOrNull { it.place.uuid == placeUuid } ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(checkIn = it.checkIn.copy(working = true)) }
            val inserted = runCatching { container.checkIns.checkIn(placeUuid, location) }
            inserted.onSuccess {
                if (attemptId != null) {
                    container.checkIns.finishAttempt(
                        attemptId = attemptId,
                        outcome = CheckInAttemptOutcomes.SUCCESS,
                        stage = "AMBIGUOUS_SELECTION_CHECKED_IN",
                        selectedPlaceId = placeUuid,
                        matchedPlaceId = placeUuid,
                    )
                }
            }.onFailure { error ->
                if (attemptId != null) {
                    container.checkIns.finishAttempt(
                        attemptId = attemptId,
                        outcome = CheckInAttemptOutcomes.PERSISTENCE_FAILED,
                        stage = "AMBIGUOUS_SELECTION_FAILED",
                        selectedPlaceId = placeUuid,
                        errorCode = error::class.simpleName ?: "PERSISTENCE_FAILED",
                        errorMessage = error.message,
                    )
                }
            }.getOrThrow()
            mutableState.update {
                it.copy(
                    checkIn = it.checkIn.copy(
                        working = false,
                        message = CheckInMessage.CHECKED_IN,
                        messagePlaceName = candidate.place.nickname,
                        ambiguousCandidates = emptyList(),
                        pendingCheckInLocation = null,
                        pendingAttemptId = null,
                    )
                )
            }
        }
    }

    fun deletePlace(uuid: String) {
        viewModelScope.launch {
            val result = container.places.delete(uuid)
            if (mutableState.value.form.uuid == uuid) newPlace()
            mutableState.update {
                it.copy(
                    placeDeleteMessage = when (result) {
                        PlaceDeleteResult.Deleted -> PlaceDeleteMessage.DELETED
                        PlaceDeleteResult.ArchivedBecauseReferenced -> PlaceDeleteMessage.ARCHIVED_REFERENCED
                        PlaceDeleteResult.NotFound -> PlaceDeleteMessage.NOT_FOUND
                    }
                )
            }
        }
    }

    fun clearPlaceDeleteMessage() {
        mutableState.update { it.copy(placeDeleteMessage = null) }
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

    fun manualCheckIn(placeUuid: String) {
        viewModelScope.launch {
            val result = container.checkIns.manualCheckIn(placeUuid)
            applyHistoryResult(result, HistoryMessage.CHECKED_IN)
        }
    }

    fun addManualVisit(placeUuid: String, checkInAt: Long, checkOutAt: Long?, notes: String?) {
        viewModelScope.launch {
            val result = container.checkIns.manualVisit(placeUuid, checkInAt, checkOutAt, notes)
            applyHistoryResult(result, HistoryMessage.VISIT_CREATED)
        }
    }

    fun saveGeofenceConfig(config: PlaceGeofenceConfigEntity) {
        viewModelScope.launch {
            val result = container.geofences.save(config)
            mutableState.update {
                it.copy(
                    geofenceMessage = if (result is com.gernalix.luoghi.capsules.geofence.PlaceGeofenceResult.PermissionMissing) {
                        GeofenceMessage.PERMISSION_MISSING
                    } else {
                        GeofenceMessage.SAVED
                    }
                )
            }
        }
    }

    fun clearGeofenceMessage() {
        mutableState.update { it.copy(geofenceMessage = null) }
    }

    private fun checkInAtCurrentLocation() {
        viewModelScope.launch {
            val attempt = container.checkIns.beginAttempt()
            mutableState.update {
                it.copy(
                    checkIn = it.checkIn.copy(
                        working = true,
                        message = null,
                        messagePlaceName = null,
                        ambiguousCandidates = emptyList(),
                        pendingCheckInLocation = null,
                        pendingAttemptId = attempt.id,
                    )
                )
            }
            val location = runCatching { container.location.currentLocation() }.getOrNull()
            if (location == null) {
                container.checkIns.finishAttempt(
                    attemptId = attempt.id,
                    outcome = CheckInAttemptOutcomes.LOCATION_UNAVAILABLE,
                    stage = "LOCATION_UNAVAILABLE",
                    errorCode = "LOCATION_UNAVAILABLE",
                    errorMessage = "Current location unavailable",
                )
                mutableState.update {
                    it.copy(
                        checkIn = it.checkIn.copy(
                            working = false,
                            message = CheckInMessage.LOCATION_UNAVAILABLE,
                            pendingAttemptId = null,
                        )
                    )
                }
                return@launch
            }
            container.checkIns.updateAttemptLocation(attempt.id, location)

            when (val decision = container.checkIns.choosePlace(state.value.places, location)) {
                is CheckInMatchDecision.Matched -> {
                    container.checkIns.replaceAttemptCandidates(
                        attempt.id,
                        attemptCandidateRows(
                            attempt.id,
                            decision.candidate,
                            listOf(decision.candidate),
                            "MATCHED",
                            location,
                        ),
                    )
                    val result = container.checkIns.manualCheckIn(decision.candidate.place.uuid, location = location)
                    if (result is HistoryMutationResult.Failure) {
                        container.checkIns.finishAttempt(
                            attemptId = attempt.id,
                            outcome = CheckInAttemptOutcomes.PERSISTENCE_FAILED,
                            stage = "MATCHED_CHECK_IN_FAILED",
                            matchedPlaceId = decision.candidate.place.uuid,
                            errorCode = result.error.name,
                            errorMessage = result.error.name,
                        )
                        applyHistoryResult(result, HistoryMessage.CHECKED_IN)
                        mutableState.update { it.copy(checkIn = it.checkIn.copy(working = false, pendingAttemptId = null)) }
                        return@launch
                    }
                    container.checkIns.finishAttempt(
                        attemptId = attempt.id,
                        outcome = CheckInAttemptOutcomes.SUCCESS,
                        stage = "MATCHED_CHECKED_IN",
                        selectedPlaceId = decision.candidate.place.uuid,
                        matchedPlaceId = decision.candidate.place.uuid,
                    )
                    mutableState.update {
                        it.copy(
                            checkIn = it.checkIn.copy(
                                working = false,
                                message = CheckInMessage.CHECKED_IN,
                                messagePlaceName = decision.candidate.place.nickname,
                                pendingAttemptId = null,
                            )
                        )
                    }
                }

                is CheckInMatchDecision.Ambiguous -> {
                    container.checkIns.replaceAttemptCandidates(
                        attempt.id,
                        attemptCandidateRows(
                            attempt.id,
                            null,
                            decision.candidates,
                            "AMBIGUOUS",
                            location,
                        ),
                    )
                    container.checkIns.markAttemptStage(
                        attemptId = attempt.id,
                        stage = "AMBIGUOUS_SELECTION_PENDING",
                        outcome = CheckInAttemptOutcomes.IN_PROGRESS,
                        errorCode = "AMBIGUOUS_MATCH",
                        errorMessage = "Multiple places matched the current location",
                    )
                    mutableState.update {
                        it.copy(
                            checkIn = it.checkIn.copy(
                                working = false,
                                message = CheckInMessage.AMBIGUOUS_PLACE,
                                ambiguousCandidates = decision.candidates,
                                pendingCheckInLocation = location,
                                pendingAttemptId = attempt.id,
                            )
                        )
                    }
                }

                CheckInMatchDecision.UnknownPlace -> {
                    container.checkIns.markAttemptStage(
                        attemptId = attempt.id,
                        stage = "NO_MATCH_FORM_OPEN",
                        outcome = CheckInAttemptOutcomes.IN_PROGRESS,
                        errorCode = "NO_MATCH",
                        errorMessage = "No saved place matched the current location",
                    )
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
                                pendingAttemptId = attempt.id,
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
            HistoryValidationError.DUPLICATE -> HistoryMessage.DUPLICATE
            HistoryValidationError.ORPHAN_CHECKOUT -> HistoryMessage.ORPHAN_CHECKOUT
            HistoryValidationError.SESSION_NOT_FOUND -> HistoryMessage.SESSION_NOT_FOUND
            HistoryValidationError.NOTHING_TO_UNDO -> HistoryMessage.NOTHING_TO_UNDO
            HistoryValidationError.NOTHING_TO_REDO -> HistoryMessage.NOTHING_TO_REDO
        }

    private fun String.filterRadiusText(): String =
        filter { it.isDigit() || it == '.' }.let { value ->
            if (value.count { it == '.' } <= 1) value else value.substringBefore('.') + "." + value.substringAfter('.').replace(".", "")
        }

    private fun parsePlaceTagText(value: String): Set<String> =
        value.split(',', ';', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toCollection(linkedSetOf())

    companion object {
        private const val MIN_RADIUS_M = 5.0
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

private fun attemptCandidateRows(
    attemptId: String,
    selected: CheckInCandidate?,
    candidates: List<CheckInCandidate>,
    result: String,
    location: LocationSample,
): List<CheckInAttemptCandidateEntity> =
    candidates.mapIndexed { index, candidate ->
        CheckInAttemptCandidateEntity(
            attemptId = attemptId,
            placeId = candidate.place.uuid,
            placeNameSnapshot = candidate.place.nickname.takeIf { it.isNotBlank() } ?: candidate.place.address,
            distanceM = candidate.distanceM,
            thresholdM = CheckInPolicy.matchThresholdM(candidate.place, location),
            rank = index + 1,
            result = if (candidate.place.uuid == selected?.place?.uuid) "MATCHED" else result,
        )
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
