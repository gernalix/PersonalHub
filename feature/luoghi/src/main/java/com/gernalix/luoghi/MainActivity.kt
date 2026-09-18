package com.gernalix.luoghi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
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
import com.gernalix.luoghi.export.BackupFolderStore
import com.gernalix.luoghi.data.PlaceGeofenceConfigEntity
import com.gernalix.luoghi.ui.history.HistoryScreen
import com.gernalix.luoghi.ui.history.formatHistoryTimestampForInput
import com.gernalix.luoghi.ui.history.parseHistoryTimestampInput
import com.gernalix.luoghi.ui.backup.RestoreBackupDialog
import com.gernalix.luoghi.ui.home.HomeScreen
import com.gernalix.luoghi.ui.place.PlaceDetailScreen
import com.gernalix.luoghi.ui.place.PlaceEditorDialog
import com.gernalix.luoghi.ui.place.PlaceAlertsDialog
import com.gernalix.luoghi.ui.settings.SettingsScreen
import com.gernalix.luoghi.ui.theme.LuoghiTheme

class MainActivity : ComponentActivity() {
    private var hubPlaceId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hubPlaceId = intent.hubPlaceId()
        setContent {
            LuoghiTheme {
                Surface(modifier = Modifier.fillMaxSize()) { LuoghiHome(hubPlaceId) }
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

private enum class AppDestination { HOME, PLACE_DETAIL, HISTORY, SETTINGS }

@Composable
fun LuoghiHome(initialPlaceId: String? = null) {
    val context = LocalContext.current
    val vm: LuoghiHomeViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LuoghiHomeViewModel(
                    container = LuoghiAppContainer(context.applicationContext),
                    context = context.applicationContext,
                ) as T
        },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) {
        withFrameNanos { }
        vm.refreshSafGate()
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if ((result.resultCode == Activity.RESULT_OK) && (uri != null)) {
            val flags = result.data?.flags
                ?.and(FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
                ?.takeIf { it != 0 }
                ?: (FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                .onSuccess { vm.saveSelectedSafFolder(uri) }
                .onFailure { vm.refreshSafGate() }
        } else {
            vm.refreshSafGate()
        }
    }
    val backupFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if ((result.resultCode == Activity.RESULT_OK) && (uri != null)) {
            val flags = result.data?.flags
                ?.and(FLAG_GRANT_READ_URI_PERMISSION)
                ?.takeIf { it != 0 }
                ?: FLAG_GRANT_READ_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            vm.inspectBackup(uri)
        }
    }
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
    val allowPersonalHubLocalDataWithoutSaf =
        context.packageName in setOf("com.gernalix.personalhub", "com.gernalix.personalhub.qa") && state.dataLoaded

    when {
        state.safGate.loading && !allowPersonalHubLocalDataWithoutSaf -> LoadingGate()
        state.safGate.status != BackupFolderStore.ValidationStatus.READY && !allowPersonalHubLocalDataWithoutSaf -> SafRequiredGate(
            status = state.safGate.status,
        ) {
            folderLauncher.launch(openDocumentTreeIntent())
        }
        else -> LuoghiNavigation(
            state = state,
            vm = vm,
            initialPlaceId = initialPlaceId,
            onCheckAction = onCheckAction,
            onChooseSafFolder = {
                folderLauncher.launch(openDocumentTreeIntent())
            },
        ) {
            backupFileLauncher.launch(openBackupDocumentIntent())
        }
    }
    RestoreBackupDialog(
        state = state.restore,
        onRestore = vm::restoreSelectedBackup,
        onNotNow = vm::continueWithoutRestore,
        onChooseAnother = { backupFileLauncher.launch(openBackupDocumentIntent()) },
        onCloseStatus = vm::closeRestoreStatus,
    )
}

@Composable
private fun LuoghiNavigation(
    state: HomeUiState,
    vm: LuoghiHomeViewModel,
    initialPlaceId: String?,
    onCheckAction: () -> Unit,
    onChooseSafFolder: () -> Unit,
    onChooseBackupFile: () -> Unit,
) {
    val context = LocalContext.current
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
    var selectedPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyFilterPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var initialVisitId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(value = false) }
    var globalStatsOpen by rememberSaveable { mutableStateOf(value = false) }
    var alertPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeletePlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var manualVisitPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var geofencePlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    val historyListState: LazyListState = rememberLazyListState()
    val destination = runCatching { AppDestination.valueOf(destinationName) }.getOrDefault(AppDestination.HOME)
    val selectedItem = selectedPlaceId?.let { id -> state.placeItems.firstOrNull { it.place.uuid == id } }
    val pendingDelete = pendingDeletePlaceId?.let { id -> state.placeItems.firstOrNull { it.place.uuid == id } }

    LaunchedEffect(initialPlaceId, state.dataLoaded) {
        if (state.dataLoaded && initialPlaceId != null && state.placeItems.any { it.place.uuid == initialPlaceId }) {
            selectedPlaceId = initialPlaceId
            destinationName = AppDestination.PLACE_DETAIL.name
        }
    }

    fun openHome() {
        destinationName = AppDestination.HOME.name
        selectedPlaceId = null
        historyFilterPlaceId = null
        initialVisitId = null
    }
    fun openDetail(item: PlaceListUiModel) {
        selectedPlaceId = item.place.uuid
        destinationName = AppDestination.PLACE_DETAIL.name
    }
    fun openHistory(placeId: String? = null, visitId: String? = null) {
        historyFilterPlaceId = placeId
        initialVisitId = visitId
        destinationName = AppDestination.HISTORY.name
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
            AppDestination.PLACE_DETAIL,
            AppDestination.SETTINGS,
            -> openHome()

            AppDestination.HISTORY -> {
                val detail = historyFilterPlaceId?.let { id -> state.placeItems.firstOrNull { it.place.uuid == id } }
                if (detail != null) openDetail(detail) else openHome()
            }
        }
    }

    LaunchedEffect(state.checkIn.pendingCheckInLocation) {
        if (state.checkIn.pendingCheckInLocation != null) editorOpen = true
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
            onOpenPlace = ::openDetail,
            onEditPlace = ::edit,
            onPlaceHistory = { openHistory(it.place.uuid) },
            onPlaceMap = ::showMap,
            onDeletePlace = { pendingDeletePlaceId = it.place.uuid },
            onSortPlaces = vm::updatePlaceSort,
            onRefreshLocation = vm::refreshListLocation,
            onGlobalMap = {
                context.startActivity(MapViewerCapsule.globalMapIntent(context, state.places))
            },
            onGlobalStats = {
                globalStatsOpen = true
                vm.refreshRouteDistanceStats()
            },
            onOpenHistory = { openHistory(visitId = it) },
            onNewPlace = {
                vm.newPlace()
                editorOpen = true
            },
        ) {
            destinationName = AppDestination.SETTINGS.name
        }

        AppDestination.PLACE_DETAIL -> selectedItem?.let { item ->
            PlaceDetailScreen(
                item = item,
                stats = state.stats.places.firstOrNull { it.place.uuid == item.place.uuid },
                onBack = ::openHome,
                onEdit = { edit(item) },
                onMap = { showMap(item) },
                onHistory = {
                    openHistory(item.place.uuid)
                },
                onCheckInNow = { vm.manualCheckIn(item.place.uuid) },
                onAddManualVisit = { manualVisitPlaceId = item.place.uuid },
                onAlerts = {
                    alertPlaceId = item.place.uuid
                    vm.loadPlaceAlertData()
                },
                onGeofenceSettings = { geofencePlaceId = item.place.uuid },
            )
        }
        AppDestination.HISTORY -> {
            val filterName = historyFilterPlaceId?.let { id ->
                state.placeItems.firstOrNull { it.place.uuid == id }?.place?.nickname
            }
            HistoryScreen(
                visits = state.visits,
                events = state.checkIn.events,
                historyState = state.history,
                isLoading = !state.dataLoaded,
                filterPlaceId = historyFilterPlaceId,
                filterPlaceName = filterName,
                places = state.places,
                initialVisitId = initialVisitId,
                listState = historyListState,
                onBack = {
                    navigateBack()
                },
                onEditEvent = vm::editHistoryEvent,
                onDeleteEvent = vm::deleteHistoryEvent,
                onDeleteVisit = vm::deleteHistorySession,
                onUndo = vm::undoHistory,
                onRedo = vm::redoHistory,
                onClearMessage = vm::clearHistoryMessage,
            )
        }
        AppDestination.SETTINGS -> SettingsScreen(
            folderLabel = state.safGate.folderLabel,
            restoreState = state.restore,
            onBack = ::openHome,
            onImportBackup = { com.gernalix.personalhub.core.database.DatabaseNavigation.open(context) },
            onReviewDeferredBackup = vm::showDeferredRestore,
            onChangeFolder = onChooseSafFolder,
        )
    }

    if (editorOpen) {
        PlaceEditorDialog(
            form = state.form,
            addressState = state.addressAutocomplete,
            onAddressChange = vm::updateAddress,
            onAddressSuggestion = vm::selectAddressSuggestion,
            onNicknameChange = vm::updateNickname,
            onRadiusChange = vm::updateRadius,
            onNotesChange = vm::updateNotes,
            onTagsChange = vm::updatePlaceTags,
            onSave = {
                vm.savePlace()
                editorOpen = false
            },
            onDismiss = {
                editorOpen = false
                vm.newPlace()
            },
        )
    }
    alertPlaceId?.let { placeId ->
        val place = state.places.firstOrNull { it.uuid == placeId }
        if (place != null) {
            PlaceAlertsDialog(
                placeId = place.uuid,
                placeName = place.nickname.ifBlank { place.address.orEmpty() },
                tags = state.placeTags,
                rules = state.placeAlertRules,
                tagTargets = state.placeAlertTagTargets,
                onCreate = vm::createPlaceAlert,
                onUpdate = vm::updatePlaceAlert,
                onDelete = vm::deletePlaceAlert,
                onSetEnabled = vm::setPlaceAlertEnabled,
                onDismiss = { alertPlaceId = null },
            )
        } else {
            alertPlaceId = null
        }
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
        mutableStateOf(formatHistoryTimestampForInput(System.currentTimeMillis()))
    }
    var checkOutText by rememberSaveable {
        mutableStateOf(formatHistoryTimestampForInput(System.currentTimeMillis() + 3_600_000L))
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
                val checkIn = parseHistoryTimestampInput(checkInText)
                val checkOut = checkOutText.trim().takeIf { it.isNotEmpty() }?.let { parseHistoryTimestampInput(it) }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckRow(checked = enabled, label = stringResource(R.string.geofence_enabled), onCheckedChange = { enabled = it })
                Text(
                    stringResource(R.string.geofence_background_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CheckRow(checked = enterEnabled, label = stringResource(R.string.geofence_enter_enabled), onCheckedChange = { enterEnabled = it })
                ActionRow(
                    automatic = enterAutomatic,
                    onAutomaticChange = { enterAutomatic = it },
                )
                CheckRow(checked = exitEnabled, label = stringResource(R.string.geofence_exit_enabled), onCheckedChange = { exitEnabled = it })
                ActionRow(
                    automatic = exitAutomatic,
                    onAutomaticChange = { exitAutomatic = it },
                )
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
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun ActionRow(automatic: Boolean, onAutomaticChange: (Boolean) -> Unit) {
    Column {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected = !automatic, onClick = { onAutomaticChange(false) })
            Text(stringResource(R.string.geofence_action_notify))
        }
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected = automatic, onClick = { onAutomaticChange(true) })
            Text(stringResource(R.string.geofence_action_automatic))
        }
    }
}

private fun openDocumentTreeIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
        FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION or
            FLAG_GRANT_PERSISTABLE_URI_PERMISSION or FLAG_GRANT_PREFIX_URI_PERMISSION,
    )

private fun openBackupDocumentIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        addFlags(FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

@Composable
private fun LoadingGate() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.saf_gate_checking))
    }
}

@Composable
private fun SafRequiredGate(
    status: BackupFolderStore.ValidationStatus,
    onChooseFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.saf_gate_required))
        Text(
            when (status) {
                BackupFolderStore.ValidationStatus.MISSING -> stringResource(R.string.saf_gate_missing)
                BackupFolderStore.ValidationStatus.PERMISSION_REVOKED -> stringResource(R.string.saf_gate_permission_revoked)
                BackupFolderStore.ValidationStatus.NOT_WRITABLE -> stringResource(R.string.saf_gate_not_writable)
                BackupFolderStore.ValidationStatus.READY -> stringResource(R.string.export_status_configured)
            }
        )
        Button(onClick = onChooseFolder) { Text(stringResource(R.string.choose_saf_folder)) }
    }
}

private fun hasAnyLocationPermission(context: android.content.Context): Boolean =
    (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ||
        (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
