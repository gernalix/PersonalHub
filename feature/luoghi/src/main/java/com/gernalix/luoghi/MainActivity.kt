package com.gernalix.luoghi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gernalix.luoghi.capsules.mapviewer.MapViewerCapsule
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceAction
import com.gernalix.luoghi.capsules.places.PlaceListUiModel
import com.gernalix.luoghi.data.PlaceGeofenceConfigEntity
import com.gernalix.luoghi.ui.visits.VisitsScreen
import com.gernalix.luoghi.ui.visits.formatVisitTimestampForInput
import com.gernalix.luoghi.ui.visits.parseVisitTimestampInput
import com.gernalix.luoghi.ui.home.HomeScreen
import com.gernalix.luoghi.ui.place.PlaceDetailScreen
import com.gernalix.luoghi.ui.place.PlaceEditorDialog
import com.gernalix.personalhub.core.alerts.UnifiedAlertsRoute
import com.gernalix.luoghi.ui.theme.LuoghiTheme
import com.gernalix.luoghi.hub.PlacesHubAdapter
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.core.ui.launchSinceWhenCreate
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var hubPlaceId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        hubPlaceId = intent.hubPlaceId()
        setContent {
            LuoghiTheme {
                Surface(modifier = Modifier.fillMaxSize()) { com.gernalix.personalhub.core.ui.ModuleHistoryHost("places", hubPlaceId) { LuoghiHome(hubPlaceId) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hubPlaceId = intent.hubPlaceId()
    }
}

private fun Intent?.hubPlaceId(): String? = this?.data
    ?.takeIf { it.scheme == "personalhub" && it.host == "module" && it.path == "/places" }
    ?.getQueryParameter("placeId")?.takeIf(String::isNotBlank)

private enum class AppDestination { HOME, PLACE_DETAIL, VISITS, EXPLORE }

@Composable
fun LuoghiHome(initialPlaceId: String? = null) {
    val context = LocalContext.current
    val vm: LuoghiHomeViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LuoghiHomeViewModel(
                    container = LuoghiAppContainer(context.applicationContext),
                ) as T
        },
    )
    val state by vm.state.collectAsState()
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) vm.onPrimaryCheckAction() else vm.onLocationPermissionDenied()
    }
    val onCheckAction = {
        if ((state.checkIn.activeVisit == null) && (!hasAnyLocationPermission(context))) {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        } else {
            vm.onPrimaryCheckAction()
        }
    }
    LuoghiNavigation(
        state = state,
        vm = vm,
        initialPlaceId = initialPlaceId,
        onCheckAction = onCheckAction,
    )

}

@Composable
private fun LuoghiNavigation(
    state: HomeUiState,
    vm: LuoghiHomeViewModel,
    initialPlaceId: String?,
    onCheckAction: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
    var selectedPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var visitsFilterPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var initialVisitId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(value = false) }
    val editorLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (editorOpen && state.form.uuid == null) {
            if (grants.values.any { it }) vm.useCurrentPlaceLocation()
            else vm.onPlaceLocationPermissionDenied()
        }
    }
    var globalStatsOpen by rememberSaveable { mutableStateOf(value = false) }
    var pendingDeletePlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var manualVisitPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var geofencePlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    val visitsListState: LazyListState = rememberLazyListState()
    val destinationState = rememberSaveableStateHolder()
    var detailFromExplore by rememberSaveable { mutableStateOf(false) }
    val destination = runCatching { AppDestination.valueOf(destinationName) }.getOrDefault(AppDestination.HOME)
    fun resolveItem(id: String): PlaceListUiModel? = state.placeItems.firstOrNull { it.place.uuid == id }
        ?: state.places.firstOrNull { it.uuid == id }?.let { place ->
            com.gernalix.luoghi.capsules.places.PlaceListUiMapper.map(listOf(place), state.stats.places,
                state.visits, state.placeSort, state.currentListLocation).single()
        }
    val selectedItem = selectedPlaceId?.let(::resolveItem)
    val pendingDelete = pendingDeletePlaceId?.let { id -> state.placeItems.firstOrNull { it.place.uuid == id } }

    LaunchedEffect(initialPlaceId, state.dataLoaded) {
        if (state.dataLoaded && initialPlaceId != null && state.places.any { it.uuid == initialPlaceId }) {
            selectedPlaceId = initialPlaceId
            destinationName = AppDestination.PLACE_DETAIL.name
        }
    }

    fun openHome() {
        destinationName = AppDestination.HOME.name
        selectedPlaceId = null
        visitsFilterPlaceId = null
        initialVisitId = null
    }
    fun openDetail(item: PlaceListUiModel) {
        detailFromExplore = destination == AppDestination.EXPLORE
        selectedPlaceId = item.place.uuid
        destinationName = AppDestination.PLACE_DETAIL.name
    }
    fun openVisits(placeId: String? = null, visitId: String? = null) {
        visitsFilterPlaceId = placeId
        initialVisitId = visitId
        destinationName = AppDestination.VISITS.name
    }
    fun edit(item: PlaceListUiModel) {
        vm.editPlace(item.place)
        editorOpen = true
    }
    fun showMap(item: PlaceListUiModel) {
        context.startActivity(MapViewerCapsule.globalMapIntent(context, listOf(item.place)))
    }
    fun navigateBack() {
        when (destination) {
            AppDestination.HOME -> Unit
            AppDestination.EXPLORE -> openHome()
            AppDestination.PLACE_DETAIL -> if (detailFromExplore) destinationName = AppDestination.EXPLORE.name else openHome()

            AppDestination.VISITS -> {
                val detail = visitsFilterPlaceId?.let { id -> state.placeItems.firstOrNull { it.place.uuid == id } }
                if (detail != null) openDetail(detail) else openHome()
            }
        }
    }

    LaunchedEffect(state.checkIn.pendingCheckInLocation, state.checkIn.message) {
        if (state.checkIn.pendingCheckInLocation != null && state.checkIn.message == CheckInMessage.UNKNOWN_PLACE) {
            editorOpen = true
        }
    }
    LaunchedEffect(selectedPlaceId, selectedItem, state.dataLoaded) {
        if (state.dataLoaded && (selectedPlaceId != null) && (selectedItem == null)) openHome()
    }
    BackHandler(enabled = destination != AppDestination.HOME, onBack = ::navigateBack)

    when (destination) {
        AppDestination.HOME -> HomeScreen(
            state = state,
            onCheckAction = onCheckAction,
            onAmbiguousCheckIn = vm::selectAmbiguousCheckIn,
            onCancelAmbiguousCheckIn = vm::newPlace,
            onPlaceQueryChange = vm::updatePlaceQuery,
            onOpenPlace = ::openDetail,
            onEditPlace = ::edit,
            onPlaceVisits = { openVisits(it.place.uuid) },
            onPlaceMap = ::showMap,
            onDeletePlace = { pendingDeletePlaceId = it.place.uuid },
            onSortPlaces = vm::updatePlaceSort,
            onRefreshLocation = vm::refreshListLocation,
            onToggleTagFilter = vm::togglePlaceTagFilter,
            onExplore = { destinationName = AppDestination.EXPLORE.name },
            onGlobalMap = {
                context.startActivity(MapViewerCapsule.globalMapIntent(context, state.placeItems.map { it.place }))
            },
            onGlobalStats = {
                globalStatsOpen = true
                vm.refreshRouteDistanceStats()
            },
            onHistorySearch = {
                context.startActivity(Intent(Intent.ACTION_VIEW, HubDeepLinkContract.moduleHistoryUri("places")).setPackage(context.packageName))
            },
            onOpenVisits = { openVisits(visitId = it) },
            onNewPlace = {
                vm.newPlace()
                editorOpen = true
            },
        )

        AppDestination.EXPLORE -> destinationState.SaveableStateProvider("explore") {
            com.gernalix.luoghi.ui.explore.ExplorePlacesScreen(state.places, ::openHome) { place ->
                resolveItem(place.uuid)?.let(::openDetail)
            }
        }
        AppDestination.PLACE_DETAIL -> selectedItem?.let { item ->
            PlaceDetailScreen(
                item = item,
                stats = state.stats.places.firstOrNull { it.place.uuid == item.place.uuid },
                onBack = ::navigateBack,
                onEdit = { edit(item) },
                onMap = { showMap(item) },
                onHistory = {
                    openVisits(item.place.uuid)
                },
                onCheckInNow = { vm.manualCheckIn(item.place.uuid) },
                onAddManualVisit = { manualVisitPlaceId = item.place.uuid },
                onAlerts = {
                    context.startActivity(
                        UnifiedAlertsRoute.intent(context, UnifiedAlertsRoute.Filter.PLACES, item.place.uuid)
                    )
                },
                onGeofenceSettings = { geofencePlaceId = item.place.uuid },
            )
        }
        AppDestination.VISITS -> {
            val filterName = visitsFilterPlaceId?.let { id ->
                state.placeItems.firstOrNull { it.place.uuid == id }?.place?.nickname
            }
            VisitsScreen(
                visits = state.visits,
                events = state.checkIn.events,
                isLoading = !state.dataLoaded,
                filterPlaceId = visitsFilterPlaceId,
                filterPlaceName = filterName,
                places = state.places,
                initialVisitId = initialVisitId,
                listState = visitsListState,
                onBack = ::navigateBack,
                onEditEvent = vm::editHistoryEvent,
                onDeleteEvent = vm::deleteHistoryEvent,
                onDeleteVisit = vm::deleteHistorySession,
            )
        }
    }

    if (editorOpen) {
        PlaceEditorDialog(
            form = state.form,
            addressState = state.addressAutocomplete,
            onAddressChange = vm::updateAddress,
            onAddressSuggestion = vm::selectAddressSuggestion,
            onUseCurrentLocation = {
                if (hasAnyLocationPermission(context)) vm.useCurrentPlaceLocation()
                else editorLocationPermissionLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                ))
            },
            onUseAddressLocation = vm::usePlaceAddressLocation,
            onNicknameChange = vm::updateNickname,
            onRadiusChange = vm::updateRadius,
            onNotesChange = vm::updateNotes,
            onPhotoChange = vm::updatePhotoUri,
            onTagsChange = vm::updatePlaceTags,
            onSave = { createSinceWhen, selectedSourceId ->
                vm.savePlace { uuid ->
                    editorOpen = false
                    if (createSinceWhen) scope.launch {
                        PlacesHubAdapter(context).sinceWhenSource(uuid)
                            ?.let { context.launchSinceWhenCreate(it, selectedSourceId) }
                    }
                }
            },
            onDismiss = {
                editorOpen = false
                vm.newPlace()
            },
        )
    }
    if (globalStatsOpen) {
        GlobalStatsDialog(state = state, onDismiss = { globalStatsOpen = false })
    }
    state.placeDeleteMessage?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearPlaceDeleteMessage,
            title = { Text(stringResource(R.string.delete_place)) },
            text = {
                Text(
                    stringResource(
                        when (message) {
                            PlaceDeleteMessage.DELETED -> R.string.delete_place_deleted
                            PlaceDeleteMessage.ARCHIVED_REFERENCED -> R.string.delete_place_archived_referenced
                            PlaceDeleteMessage.NOT_FOUND -> R.string.delete_place_not_found
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = vm::clearPlaceDeleteMessage) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeletePlaceId = null },
            title = { Text(stringResource(R.string.delete_place)) },
            text = { Text(stringResource(R.string.delete_place_confirm_format, item.place.nickname)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deletePlace(item.place.uuid)
                        pendingDeletePlaceId = null
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePlaceId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    manualVisitPlaceId?.let { placeId ->
        ManualVisitDialog(
            onDismiss = { manualVisitPlaceId = null },
            onSave = { checkInAt, checkOutAt, notes ->
                vm.addManualVisit(placeId, checkInAt, checkOutAt, notes)
                manualVisitPlaceId = null
            },
        )
    }
    geofencePlaceId?.let { placeId ->
        val place = state.places.firstOrNull { it.uuid == placeId }
        if (place != null) {
            GeofenceSettingsDialog(
                place = place,
                initial = state.geofenceConfigs[placeId] ?: PlaceGeofenceConfigEntity(placeUuid = placeId),
                onDismiss = { geofencePlaceId = null },
                onSave = {
                    vm.saveGeofenceConfig(it)
                    geofencePlaceId = null
                },
            )
        }
    }
    state.geofenceMessage?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearGeofenceMessage,
            title = { Text(stringResource(R.string.geofence_settings)) },
            text = {
                Text(
                    stringResource(
                        when (message) {
                            GeofenceMessage.SAVED -> R.string.geofence_saved
                            GeofenceMessage.PERMISSION_MISSING -> R.string.geofence_permission_missing
                            GeofenceMessage.COORDINATES_MISSING -> R.string.geofence_coordinates_required
                            GeofenceMessage.REGISTRATION_FAILED -> R.string.geofence_registration_failed
                        }
                    )
                )
            },
            confirmButton = { TextButton(onClick = vm::clearGeofenceMessage) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun ManualVisitDialog(
    onDismiss: () -> Unit,
    onSave: (Long, Long?, String?) -> Unit,
) {
    var checkInText by rememberSaveable {
        mutableStateOf(formatVisitTimestampForInput(System.currentTimeMillis()))
    }
    var checkOutText by rememberSaveable {
        mutableStateOf(formatVisitTimestampForInput(System.currentTimeMillis() + 3_600_000L))
    }
    var notesText by rememberSaveable { mutableStateOf("") }
    var parseFailed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.place_add_past_visit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = checkInText,
                    onValueChange = {
                        checkInText = it
                        parseFailed = false
                    },
                    label = { Text(stringResource(R.string.manual_visit_check_in)) },
                    supportingText = { Text(stringResource(R.string.history_timestamp_hint)) },
                    isError = parseFailed,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = checkOutText,
                    onValueChange = {
                        checkOutText = it
                        parseFailed = false
                    },
                    label = { Text(stringResource(R.string.manual_visit_check_out)) },
                    supportingText = { Text(stringResource(R.string.manual_visit_open_hint)) },
                    isError = parseFailed,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text(stringResource(R.string.notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val checkIn = parseVisitTimestampInput(checkInText)
                val checkOut = checkOutText.trim().takeIf { it.isNotEmpty() }?.let { parseVisitTimestampInput(it) }
                if (checkIn == null || (checkOutText.isNotBlank() && checkOut == null)) {
                    parseFailed = true
                } else {
                    onSave(checkIn, checkOut, notesText)
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun GeofenceSettingsDialog(
    place: com.gernalix.luoghi.data.PlaceEntity,
    initial: PlaceGeofenceConfigEntity,
    onDismiss: () -> Unit,
    onSave: (PlaceGeofenceConfigEntity) -> Unit,
) {
    var enabled by rememberSaveable(initial.placeUuid) { mutableStateOf(initial.enabled) }
    var enterEnabled by rememberSaveable(initial.placeUuid) { mutableStateOf(initial.enterEnabled) }
    var exitEnabled by rememberSaveable(initial.placeUuid) { mutableStateOf(initial.exitEnabled) }
    var enterAutomatic by rememberSaveable(initial.placeUuid) {
        mutableStateOf(initial.enterAction == PlaceGeofenceAction.AUTOMATIC_VISIT.name)
    }
    var exitAutomatic by rememberSaveable(initial.placeUuid) {
        mutableStateOf(initial.exitAction == PlaceGeofenceAction.AUTOMATIC_VISIT.name)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.geofence_settings)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(place.nickname.ifBlank { stringResource(R.string.unnamed_place) }, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.geofence_radius_help,
                    com.gernalix.luoghi.capsules.checkin.CheckInPolicy.effectiveRadiusM(place).toInt()),
                    style = MaterialTheme.typography.bodySmall)
                if (place.lat == null || place.lon == null) {
                    Text(stringResource(R.string.geofence_coordinates_required), color = MaterialTheme.colorScheme.error)
                }
                CheckRow(checked = enabled, label = stringResource(R.string.geofence_enabled), onCheckedChange = { enabled = it })
                Text(stringResource(R.string.geofence_background_disclosure), style = MaterialTheme.typography.bodySmall)
                if (enabled && !com.gernalix.luoghi.capsules.geofence.PlaceGeofenceRegistrar.hasBackgroundLocationPermission(LocalContext.current)) {
                    val context = LocalContext.current
                    Text(stringResource(R.string.geofence_permission_guidance), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null)))
                    }) { Text(stringResource(R.string.geofence_open_app_settings)) }
                }
                GeofenceTransitionChoices(
                    title = stringResource(R.string.geofence_arrival),
                    automaticLabel = stringResource(R.string.geofence_automatic_checkin),
                    transitionEnabled = enterEnabled,
                    automatic = enterAutomatic,
                    enabled = enabled,
                    onChoice = { active, automatic -> enterEnabled = active; enterAutomatic = automatic },
                )
                GeofenceTransitionChoices(
                    title = stringResource(R.string.geofence_departure),
                    automaticLabel = stringResource(R.string.geofence_automatic_checkout),
                    transitionEnabled = exitEnabled,
                    automatic = exitAutomatic,
                    enabled = enabled,
                    onChoice = { active, automatic -> exitEnabled = active; exitAutomatic = automatic },
                )
                Text(stringResource(R.string.geofence_overlap_help), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.geofence_manual_alerts_help), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        enabled = enabled,
                        enterEnabled = enterEnabled,
                        exitEnabled = exitEnabled,
                        enterAction = if (enterAutomatic) PlaceGeofenceAction.AUTOMATIC_VISIT.name else PlaceGeofenceAction.NOTIFY.name,
                        exitAction = if (exitAutomatic) PlaceGeofenceAction.AUTOMATIC_VISIT.name else PlaceGeofenceAction.NOTIFY.name,
                    )
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CheckRow(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun GeofenceTransitionChoices(
    title: String,
    automaticLabel: String,
    transitionEnabled: Boolean,
    automatic: Boolean,
    enabled: Boolean,
    onChoice: (Boolean, Boolean) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        listOf(
            Triple(stringResource(R.string.geofence_action_off), !transitionEnabled, { onChoice(false, automatic) }),
            Triple(stringResource(R.string.geofence_action_notify), transitionEnabled && !automatic, { onChoice(true, false) }),
            Triple(automaticLabel, transitionEnabled && automatic, { onChoice(true, true) }),
        ).forEach { (label, selected, select) ->
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = select),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, enabled = enabled, onClick = null)
                Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}

private fun hasAnyLocationPermission(context: android.content.Context): Boolean =
    (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ||
        (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
