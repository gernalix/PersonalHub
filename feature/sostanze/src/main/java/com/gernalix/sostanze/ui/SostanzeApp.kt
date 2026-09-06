@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.gernalix.sostanze.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gernalix.sostanze.BuildConfig
import com.gernalix.sostanze.R
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.SubstanceTypes
import com.gernalix.sostanze.data.IntakeOutcome
import com.gernalix.sostanze.data.SubstanceSaveOutcome
import com.gernalix.sostanze.data.PrescriptionEntity
import com.gernalix.sostanze.data.PrescriptionDraft
import com.gernalix.sostanze.data.InteractionRuleEntity
import com.gernalix.sostanze.data.DoctorChoice
import com.gernalix.sostanze.data.CostChoice
import com.gernalix.sostanze.domain.DoseButtonState
import com.gernalix.sostanze.domain.DoseSection
import com.gernalix.sostanze.domain.SostanzeEngine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppTab {
    Home,
    History,
    Stock,
    Prescriptions,
    Interactions,
}

@Composable
fun SostanzeApp(viewModel: SostanzeViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        viewModel.loadSecondaryState()
    }
    val tabPreferences = remember(context) { context.getSharedPreferences("sostanze_ui", android.content.Context.MODE_PRIVATE) }
    var tab by rememberSaveable {
        mutableStateOf(
            runCatching { AppTab.valueOf(tabPreferences.getString("last_tab", AppTab.Home.name)!!) }
                .getOrDefault(AppTab.Home)
        )
    }
    var homeQuery by rememberSaveable { mutableStateOf("") }
    var stockQuery by rememberSaveable { mutableStateOf("") }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var editingSubstance by remember { mutableStateOf<SubstanceEntity?>(null) }
    var deleteSubstance by remember { mutableStateOf<SubstanceEntity?>(null) }
    var historySubstanceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var stockDialog by remember { mutableStateOf<SubstanceEntity?>(null) }
    var creatingPrescription by remember { mutableStateOf(false) }
    var prescriptionInitialName by remember { mutableStateOf("") }
    var editingPrescription by remember { mutableStateOf<PrescriptionUi?>(null) }
    var editingHistory by remember { mutableStateOf<HistoryUi?>(null) }
    var deletingHistory by remember { mutableStateOf<HistoryUi?>(null) }
    var interactionDialog by remember { mutableStateOf<SubstanceEntity?>(null) }
    var interactionRuleDraft by remember { mutableStateOf<InteractionRuleEntity?>(null) }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val exportFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setExportFolder(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImport = uri
    }
    val undoLabel = stringResource(R.string.undo)
    val recordedMessage = stringResource(R.string.recorded)
    val macroRecordedMessage = stringResource(R.string.macro_recorded)
    val blockedMessage = stringResource(R.string.intake_blocked)
    val earlyMessage = stringResource(R.string.intake_early)
    val stockMessage = stringResource(R.string.intake_insufficient_stock)
    val duplicateMessage = stringResource(R.string.intake_duplicate)
    val duplicateNameMessage = stringResource(R.string.duplicate_name)
    val restoreNameMessage = stringResource(R.string.restore_existing_name)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        TextButton(onClick = { exportFolderLauncher.launch(null) }) {
                            Text(stringResource(R.string.export_action))
                        }
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/vnd.sqlite3", "application/octet-stream", "*/*")) }) {
                            Text(stringResource(R.string.import_action))
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            IconButton(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text(stringResource(R.string.notifications_short))
                            }
                        }
                    }
                )
                ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                    AppTab.values().forEach { item ->
                        Tab(
                            selected = tab == item,
                            onClick = { tab = item; tabPreferences.edit().putString("last_tab", item.name).apply() },
                            text = { Text(tabTitle(item), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                ImportExportStatusBar(state)
            }
        },
        floatingActionButton = {
            if (tab != AppTab.History) {
                FloatingActionButton(onClick = {
                    when (tab) {
                        AppTab.Home -> editingSubstance = viewModel.defaultNewSubstance()
                        AppTab.Stock -> state.substances.firstOrNull { !it.archived }?.let { stockDialog = it }
                        AppTab.Prescriptions -> { prescriptionInitialName = ""; creatingPrescription = true }
                        AppTab.Interactions -> state.substances.firstOrNull { !it.archived }?.let { interactionRuleDraft = null; interactionDialog = it }
                        AppTab.History -> Unit
                    }
                }) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.Home -> HomeScreen(
                    states = state.doseStates.filter { it.substance.name.contains(homeQuery, true) },
                    macros = state.macros,
                    query = homeQuery,
                    onQueryChange = { homeQuery = it },
                    onRecord = { substanceId ->
                        viewModel.recordIntake(substanceId) { outcome, token ->
                            scope.launch {
                                val message = when (outcome) {
                                    is IntakeOutcome.Recorded -> recordedMessage
                                    is IntakeOutcome.Blocked -> blockedMessage
                                    is IntakeOutcome.Early -> earlyMessage
                                    is IntakeOutcome.Warning -> blockedMessage
                                    IntakeOutcome.InsufficientStock, IntakeOutcome.UnsupportedUnits -> stockMessage
                                    IntakeOutcome.InvalidQuantity -> stockMessage
                                    IntakeOutcome.Duplicate -> duplicateMessage
                                    IntakeOutcome.Archived, IntakeOutcome.NotFound -> blockedMessage
                                }
                                val result = snackbarHostState.showSnackbar(message, actionLabel = if (token != null) undoLabel else null)
                                if (result == SnackbarResult.ActionPerformed && token != null) viewModel.undoToken(token)
                            }
                        }
                    },
                    onMacro = { macroId ->
                        viewModel.recordMacro(macroId) { outcomes, token ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar("$macroRecordedMessage ${outcomes.count { it is IntakeOutcome.Recorded }}/${outcomes.size}", actionLabel = if (token != null) undoLabel else null)
                                if (result == SnackbarResult.ActionPerformed && token != null) viewModel.undoToken(token)
                            }
                        }
                    },
                    onUndo = viewModel::undoLastIntake,
                    onEdit = { editingSubstance = it },
                    onRestore = viewModel::restoreSubstance,
                    onStock = { stockDialog = it },
                    onHistory = { historySubstanceId = it.id },
                )
                AppTab.History -> HistoryScreen(
                    rows = state.history.filter { it.substanceName.contains(historyQuery, true) },
                    query = historyQuery,
                    onQueryChange = { historyQuery = it },
                    onEdit = { editingHistory = it },
                    onDelete = { deletingHistory = it },
                )
                AppTab.Stock -> StockScreen(
                    rows = state.stockRows.filter { it.substance.name.contains(stockQuery, true) },
                    query = stockQuery,
                    onQueryChange = { stockQuery = it },
                    onAdjust = { stockDialog = it },
                )
                AppTab.Prescriptions -> PrescriptionScreen(
                    substances = state.substances.filter { !it.archived },
                    rows = state.prescriptions,
                    onAdd = { prescriptionInitialName = it.name; creatingPrescription = true },
                    onEdit = { editingPrescription = it },
                    onDelete = { viewModel.deletePrescription(it.prescription.id) },
                )
                AppTab.Interactions -> InteractionScreen(
                    substances = state.substances.filter { !it.archived },
                    states = state.doseStates,
                    rules = state.interactionRules,
                    onAddRule = { interactionRuleDraft = null; interactionDialog = it },
                    onEditRule = { rule ->
                        interactionRuleDraft = rule
                        interactionDialog = state.substances.firstOrNull { it.id == rule.sourceSubstanceId }
                    },
                    onDeleteRule = viewModel::deleteInteraction,
                )
            }
        }
    }

    editingSubstance?.let { draft ->
        SubstanceDialog(
            initial = draft,
            onDismiss = { editingSubstance = null },
            onDelete = if (draft.id == 0L) null else ({ deleteSubstance = draft }),
            onSave = {
                viewModel.saveSubstance(it) { outcome ->
                    scope.launch {
                        when (outcome) {
                            is SubstanceSaveOutcome.Saved -> editingSubstance = null
                            is SubstanceSaveOutcome.Duplicate -> snackbarHostState.showSnackbar(duplicateNameMessage)
                            is SubstanceSaveOutcome.RestoreRequired -> snackbarHostState.showSnackbar(restoreNameMessage)
                            is SubstanceSaveOutcome.Invalid -> snackbarHostState.showSnackbar(duplicateNameMessage)
                        }
                    }
                }
            }
        )
    }
    stockDialog?.let { substance ->
        StockAdjustDialog(
            substance = substance,
            onDismiss = { stockDialog = null },
            onSave = { delta, note ->
                viewModel.adjustStock(substance.id, delta, note)
                stockDialog = null
            }
        )
    }
    if (creatingPrescription) {
        PrescriptionCreateDialog(
            initialName = prescriptionInitialName,
            suggestions = state.substances.map { it.name }.distinct(),
            viewModel = viewModel,
            onDismiss = { creatingPrescription = false },
            onSave = { draft -> viewModel.createPrescription(draft) { if (it) creatingPrescription = false } },
        )
    }
    editingPrescription?.let { row ->
        PrescriptionEditDialog(
            row = row,
            onDismiss = { editingPrescription = null },
            onSave = { value -> viewModel.savePrescription(value) { if (it) editingPrescription = null } },
        )
    }
    editingHistory?.let { row ->
        IntakeEditDialog(row, { editingHistory = null }) { timestamp, quantity ->
            viewModel.editIntake(row.id, timestamp, quantity) { editingHistory = null }
        }
    }
    deletingHistory?.let { row ->
        AlertDialog(
            onDismissRequest = { deletingHistory = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(row.substanceName) },
            confirmButton = { Button(onClick = { viewModel.deleteIntake(row.id); deletingHistory = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deletingHistory = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    interactionDialog?.let { source ->
        InteractionRuleDialog(
            source = source,
            initial = interactionRuleDraft,
            onDismiss = { interactionDialog = null; interactionRuleDraft = null },
            onSave = { before, after ->
                viewModel.saveAllFutureInteraction(interactionRuleDraft?.id ?: 0, source.id, before, after)
                interactionDialog = null
                interactionRuleDraft = null
            }
        )
    }
    historySubstanceId?.let { id ->
        SubstanceHistoryDialog(
            title = state.substances.firstOrNull { it.id == id }?.name.orEmpty(),
            rows = viewModel.historyFor(id, state),
            onDismiss = { historySubstanceId = null }
        )
    }
    deleteSubstance?.let { substance ->
        ConfirmDeleteDialog(
            name = substance.name,
            onDismiss = { deleteSubstance = null },
            onConfirm = {
                viewModel.archiveSubstance(substance.id)
                deleteSubstance = null
                editingSubstance = null
            }
        )
    }
    pendingImport?.let { uri ->
        ConfirmImportDialog(
            onDismiss = { pendingImport = null },
            onConfirm = {
                viewModel.importDatabase(uri)
                pendingImport = null
            }
        )
    }
}

@Composable
private fun ImportExportStatusBar(state: SostanzeUiState) {
    val hasError = state.lastImportError != null || state.exportStatus == ExportStatusUi.Error
    Surface(
        color = if (hasError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (hasError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val exportText = when (state.exportStatus) {
                ExportStatusUi.NotConfigured -> stringResource(R.string.export_status_not_configured)
                ExportStatusUi.Ready -> stringResource(R.string.export_status_ready)
                ExportStatusUi.Error -> stringResource(R.string.export_status_failed, state.lastExportError.orEmpty())
            }
            Text(exportText, style = MaterialTheme.typography.labelMedium)
            state.lastImportError?.let { error ->
                Text(
                    stringResource(R.string.import_status_failed, error),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            } ?: Text(
                stringResource(R.string.export_folder_hint),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun tabTitle(tab: AppTab): String = when (tab) {
    AppTab.Home -> stringResource(R.string.tab_home)
    AppTab.History -> stringResource(R.string.tab_history)
    AppTab.Stock -> stringResource(R.string.tab_stock)
    AppTab.Prescriptions -> stringResource(R.string.tab_prescriptions)
    AppTab.Interactions -> stringResource(R.string.tab_interactions)
}

@Composable
private fun HomeScreen(
    states: List<DoseButtonState>,
    macros: List<MacroUi>,
    query: String,
    onQueryChange: (String) -> Unit,
    onRecord: (Long) -> Unit,
    onMacro: (Long) -> Unit,
    onUndo: (Long) -> Unit,
    onEdit: (SubstanceEntity) -> Unit,
    onRestore: (Long) -> Unit,
    onStock: (SubstanceEntity) -> Unit,
    onHistory: (SubstanceEntity) -> Unit,
) {
    val sections = listOf(
        DoseSection.DUE_TODAY to stringResource(R.string.section_due_today),
        DoseSection.LATER to stringResource(R.string.section_later),
        DoseSection.BLOCKED to stringResource(R.string.section_blocked),
        DoseSection.PRN to stringResource(R.string.section_prn),
        DoseSection.ARCHIVED to stringResource(R.string.archive),
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SearchBox(query = query, onQueryChange = onQueryChange)
        }
        if (macros.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle(stringResource(R.string.section_macros))
            }
            items(macros, key = { "macro-${it.macro.id}" }) { macro ->
                MacroButton(macro = macro, onClick = { onMacro(macro.macro.id) })
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        sections.forEach { (section, title) ->
            val sectionItems = states.filter { it.section == section }
            if (sectionItems.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(title = title)
                }
                items(sectionItems, key = { "dose-${it.substance.id}" }) { state ->
                    val entity = state.toEntity()
                    DoseActionButton(
                        state = state,
                        onRecord = { onRecord(state.substance.id) },
                        onUndo = { onUndo(state.substance.id) },
                        onEdit = { onEdit(entity) },
                        onRestore = { onRestore(state.substance.id) },
                        onStock = { onStock(entity) },
                        onHistory = { onHistory(entity) },
                    )
                }
            }
        }
    }
}

private fun DoseButtonState.toEntity(): SubstanceEntity =
    SubstanceEntity(
        id = substance.id,
        name = substance.name,
        type = substance.type,
        stockCurrent = substance.stockCurrent,
        stockUnit = substance.stockUnit,
        dosePerIntake = substance.dosePerIntake,
        doseUnit = substance.doseUnit,
        dailyFrequency = substance.dailyFrequency,
        startEpochDay = substance.startEpochDay,
        endEpochDay = substance.endEpochDay,
        forever = substance.forever,
        archived = substance.archived,
        prn = substance.prn,
        doseTimesCsv = substance.doseTimesCsv,
        daysMask = substance.daysMask,
    )

@Composable
private fun DoseActionButton(
    state: DoseButtonState,
    onRecord: () -> Unit,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
    onStock: () -> Unit,
    onHistory: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(pulse) {
        if (pulse) {
            delay(160)
            pulse = false
        }
    }
    val next = state.nextIdealMs?.let { formatTime(it) } ?: "-"
    val blocked = state.section == DoseSection.BLOCKED
    val baseColor = when {
        blocked -> MaterialTheme.colorScheme.errorContainer
        state.section == DoseSection.PRN -> MaterialTheme.colorScheme.tertiaryContainer
        state.section == DoseSection.TAKEN -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val color by animateColorAsState(if (pulse) MaterialTheme.colorScheme.secondaryContainer else baseColor, label = "dose-pulse")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .combinedClickable(
                enabled = true,
                onClick = {
                    if (state.canRecord) {
                        pulse = true
                        onRecord()
                    }
                },
                onLongClick = onHistory
            ),
        shape = RoundedCornerShape(8.dp),
        color = color,
        tonalElevation = 1.dp,
        border = if (blocked) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null
    ) {
        Column(Modifier.padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.substance.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Text("...")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menuOpen = false; onEdit() })
                        if (state.section == DoseSection.ARCHIVED) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.restore)) }, onClick = { menuOpen = false; onRestore() })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.stock)) }, onClick = { menuOpen = false; onStock() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.history)) }, onClick = { menuOpen = false; onHistory() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.undo_last_tap)) }, onClick = { menuOpen = false; onUndo() })
                    }
                }
            }
            Text(
                stringResource(R.string.dose_counter_next, state.dosesDoneToday, state.dosesPlannedToday, next),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.blocks.isNotEmpty()) {
                state.blocks.take(2).forEach { active ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("!")
                        Text(
                        "${active.sourceName} ${SostanzeEngine.countdownText(active.remainingMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    }
                }
            } else {
                Text(
                    "${state.substance.stockCurrent.clean()} ${state.substance.stockUnit}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun MacroButton(macro: MacroUi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .combinedClickable(onClick = onClick, onLongClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(macro.macro.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(macro.items.joinToString { it.name }, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StockScreen(
    rows: List<StockUi>,
    query: String,
    onQueryChange: (String) -> Unit,
    onAdjust: (SubstanceEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SearchBox(query = query, onQueryChange = onQueryChange) }
        items(rows, key = { it.substance.id }) { row ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onAdjust(row.substance) }, onLongClick = { onAdjust(row.substance) }),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.substance.name, fontWeight = FontWeight.SemiBold)
                        Text("${row.substance.stockCurrent.clean()} ${row.substance.stockUnit}", color = MaterialTheme.colorScheme.primary)
                    }
                    Text(stringResource(R.string.daily_consumption, row.coverage.dailyConsumption.clean(), row.substance.stockUnit))
                    Text(stringResource(R.string.covered_days, row.coverage.daysCovered?.let { "%.1f".format(Locale.US, it) } ?: stringResource(R.string.prn)))
                    Text(stringResource(R.string.exhaustion, row.coverage.exhaustionDate?.let { formatDateOnly(it) } ?: "-"))
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    rows: List<HistoryUi>,
    query: String,
    onQueryChange: (String) -> Unit,
    onEdit: (HistoryUi) -> Unit,
    onDelete: (HistoryUi) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SearchBox(query, onQueryChange) }
        items(rows, key = { it.id }) { row ->
            HistoryRow(row, { onEdit(row) }, { onDelete(row) })
        }
    }
}

@Composable
private fun SubstanceHistoryDialog(title: String, rows: List<HistoryUi>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_for, title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(rows, key = { index, row -> "${row.substanceName}:${row.timestampMs}:${row.ghost}:$index" }) { _, row ->
                    HistoryRow(row)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun HistoryRow(row: HistoryUi, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val color = if (row.ghost) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = color)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(row.substanceName, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.doseText, maxLines = 1)
            Text(formatDateTime(row.timestampMs), maxLines = 1)
            onEdit?.let { IconButton(onClick = it, modifier = Modifier.size(48.dp)) { Text("✎") } }
            onDelete?.let { IconButton(onClick = it, modifier = Modifier.size(48.dp)) { Text("🗑") } }
        }
    }
}

@Composable
private fun PrescriptionScreen(
    substances: List<SubstanceEntity>,
    rows: List<PrescriptionUi>,
    onAdd: (SubstanceEntity) -> Unit,
    onEdit: (PrescriptionUi) -> Unit,
    onDelete: (PrescriptionUi) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionTitle(stringResource(R.string.new_prescription))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                substances.take(3).forEach { substance ->
                    FilterChip(selected = false, onClick = { onAdd(substance) }, label = { Text(substance.name, maxLines = 1) })
                }
            }
        }
        item { SectionTitle(stringResource(R.string.prescription_history)) }
        items(rows, key = { it.prescription.id }) { row ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.substanceName, fontWeight = FontWeight.SemiBold)
                        Text("${row.prescription.remainingDoses}/${row.prescription.packageDoseCount} × ${row.prescription.doseMg.clean()} mg")
                        Text("${row.prescription.frequencyCount} / ${if (row.prescription.frequencyPeriod == "WEEK") "week" else "day"}")
                        Text(formatDateOnly(LocalDate.ofEpochDay(row.prescription.prescriptionEpochDay)))
                        row.doctorName?.let { Text(it) }
                        row.costAmount?.let { Text(it) }
                    }
                    IconButton(onClick = { onEdit(row) }, modifier = Modifier.size(48.dp)) { Text("✎") }
                    IconButton(onClick = { onDelete(row) }, modifier = Modifier.size(48.dp)) { Text("🗑") }
                }
            }
        }
    }
}

@Composable
private fun InteractionScreen(
    substances: List<SubstanceEntity>,
    states: List<DoseButtonState>,
    rules: List<InteractionRuleEntity>,
    onAddRule: (SubstanceEntity) -> Unit,
    onEditRule: (InteractionRuleEntity) -> Unit,
    onDeleteRule: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionTitle(stringResource(R.string.rules))
            Text(stringResource(R.string.user_rules_only), style = MaterialTheme.typography.bodySmall)
        }
        items(rules, key = { "rule-${it.id}" }) { rule ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(substances.firstOrNull { it.id == rule.sourceSubstanceId }?.name.orEmpty(), Modifier.weight(1f))
                    Text("${rule.avoidBeforeHours.clean()}h / ${rule.avoidAfterHours.clean()}h")
                    IconButton(onClick = { onEditRule(rule) }, modifier = Modifier.size(48.dp)) { Text("✎") }
                    IconButton(onClick = { onDeleteRule(rule.id) }, modifier = Modifier.size(48.dp)) { Text("🗑") }
                }
            }
        }
        items(substances, key = { "interaction-substance-${it.id}" }) { substance ->
            ElevatedCard(Modifier.fillMaxWidth().combinedClickable(onClick = { onAddRule(substance) }, onLongClick = { onAddRule(substance) })) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(substance.name, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.add_interaction_rule))
                    }
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item { SectionTitle(stringResource(R.string.active_blocks)) }
        items(states.filter { it.block != null }, key = { "interaction-block-${it.substance.id}" }) { state ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.substance.name, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.blocked_by, state.block?.sourceName.orEmpty()))
                    Text(stringResource(R.string.countdown, SostanzeEngine.countdownText(state.block?.remainingMs ?: 0)))
                }
            }
        }
    }
}

@Composable
private fun SearchBox(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.search)) }
    )
}

@Composable
private fun SectionTitle(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        trailing?.invoke()
    }
}

@Composable
private fun SubstanceDialog(initial: SubstanceEntity, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (SubstanceEntity) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var type by remember(initial) { mutableStateOf(initial.type) }
    var stock by remember(initial) { mutableStateOf(initial.stockCurrent.clean()) }
    var dose by remember(initial) { mutableStateOf(initial.dosePerIntake.clean()) }
    var frequency by remember(initial) { mutableStateOf(initial.dailyFrequency.toString()) }
    var prn by remember(initial) { mutableStateOf(initial.prn) }
    var forever by remember(initial) { mutableStateOf(initial.forever) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) stringResource(R.string.new_substance) else stringResource(R.string.edit_substance)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == SubstanceTypes.FARMACO, onClick = { type = SubstanceTypes.FARMACO }, label = { Text(stringResource(R.string.drug)) })
                    FilterChip(selected = type == SubstanceTypes.INTEGRATORE, onClick = { type = SubstanceTypes.INTEGRATORE }, label = { Text(stringResource(R.string.supplement)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(stock, { stock = it.numberText() }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.stock_mg)) }, singleLine = true)
                    OutlinedTextField("mg", {}, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.unit)) }, singleLine = true, enabled = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(dose, { dose = it.numberText() }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.dose_mg)) }, singleLine = true)
                    OutlinedTextField("mg", {}, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.dose_unit)) }, singleLine = true, enabled = false)
                }
                OutlinedTextField(frequency, { frequency = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.daily_frequency)) }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.as_needed))
                    Switch(prn, { prn = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.forever))
                    Switch(forever, { forever = it })
                }
            }
        },
        confirmButton = {
            Button(enabled = name.trim().isNotBlank(), onClick = {
                onSave(
                    initial.copy(
                        name = name.trim(),
                        type = type,
                        stockCurrent = stock.toDoubleOrNull() ?: 0.0,
                        stockUnit = "mg",
                        dosePerIntake = dose.toDoubleOrNull() ?: 0.0,
                        doseUnit = "mg",
                        dailyFrequency = if (prn) 0 else frequency.toIntOrNull()?.coerceAtLeast(0) ?: 1,
                        prn = prn,
                        forever = forever,
                    )
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

@Composable
private fun StockAdjustDialog(substance: SubstanceEntity, onDismiss: () -> Unit, onSave: (Double, String?) -> Unit) {
    var sign by remember { mutableStateOf(-1.0) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_stock_for, substance.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.current_stock, substance.stockCurrent.clean(), substance.stockUnit))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sign > 0, onClick = { sign = 1.0 }, label = { Text(stringResource(R.string.refill_plus)) })
                    FilterChip(selected = sign < 0, onClick = { sign = -1.0 }, label = { Text(stringResource(R.string.consumption_minus)) })
                }
                OutlinedTextField(amount, { amount = it.numberText() }, label = { Text(stringResource(R.string.quantity_unit, substance.stockUnit)) }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.optional_note)) })
            }
        },
        confirmButton = {
            Button(enabled = amount.toDoubleOrNull() != null, onClick = { onSave(sign * (amount.toDoubleOrNull() ?: 0.0), note) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun PrescriptionCreateDialog(initialName: String, suggestions: List<String>, viewModel: SostanzeViewModel, onDismiss: () -> Unit, onSave: (PrescriptionDraft) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var packages by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("1") }
    var weekly by remember { mutableStateOf(false) }
    var doctorQuery by remember { mutableStateOf("") }
    var doctorId by remember { mutableStateOf<Long?>(null) }
    var doctorChoices by remember { mutableStateOf<List<DoctorChoice>>(emptyList()) }
    var costId by remember { mutableStateOf<Long?>(null) }
    var costChoices by remember { mutableStateOf<List<CostChoice>>(emptyList()) }
    val today = LocalDate.now().toEpochDay()
    LaunchedEffect(doctorQuery) {
        if (doctorQuery.length >= 2) viewModel.doctorChoices(doctorQuery) { doctorChoices = it }
        else doctorChoices = emptyList()
    }
    LaunchedEffect(name) {
        if (name.isNotBlank()) viewModel.costChoices(name) { costChoices = it } else costChoices = emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_prescription)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) })
                suggestions.filter { it.contains(name, true) && !it.equals(name, true) }.take(4).forEach { suggestion ->
                    TextButton(onClick = {
                        name = suggestion
                        viewModel.prescriptionPrefill(suggestion) { latest ->
                            latest?.let {
                                packages = it.packageDoseCount.toString()
                                dose = it.doseMg.clean()
                                frequency = it.frequencyCount.toString()
                                weekly = it.frequencyPeriod == "WEEK"
                                doctorId = it.doctorContactId
                                costId = it.financeTransactionId
                            }
                        }
                    }) { Text(suggestion) }
                }
                OutlinedTextField(packages, { packages = it.filter(Char::isDigit) }, label = { Text("Package doses / remaining") })
                OutlinedTextField(dose, { dose = it.numberText() }, label = { Text(stringResource(R.string.dose_mg)) })
                OutlinedTextField(frequency, { frequency = it.filter(Char::isDigit) }, label = { Text("Frequency X") })
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (weekly) "X per week" else "X per day"); Switch(weekly, { weekly = it }) }
                Text("Order date: ${formatDateOnly(LocalDate.ofEpochDay(today))}")
                Text("Prescription date: ${formatDateOnly(LocalDate.ofEpochDay(today))}")
                val projected = SostanzeEngine.depletionDate(packages.toIntOrNull() ?: -1, frequency.toIntOrNull() ?: 0, if (weekly) "WEEK" else "DAY", LocalDate.now())
                Text("Estimated depletion: ${projected?.let(::formatDateOnly) ?: "-"}")
                OutlinedTextField(doctorQuery, { doctorQuery = it; doctorId = null }, label = { Text("Doctor") })
                doctorChoices.take(5).forEach { choice ->
                    FilterChip(selected = doctorId == choice.id, onClick = { doctorId = choice.id; doctorQuery = choice.name }, label = { Text(choice.name) })
                }
                if (costChoices.isEmpty()) Text("No matching Soldi entries")
                costChoices.take(5).forEach { choice ->
                    FilterChip(
                        selected = costId == choice.id,
                        onClick = { costId = choice.id },
                        label = { Column { Text(choice.title); Text(choice.occurredAt, style = MaterialTheme.typography.labelSmall) } },
                    )
                }
            }
        },
        confirmButton = {
            val packageCount = packages.toIntOrNull()
            val mg = dose.toDoubleOrNull()
            val count = frequency.toIntOrNull()
            Button(enabled = name.isNotBlank() && packageCount != null && packageCount > 0 && mg != null && mg > 0 && count != null && count > 0, onClick = {
                onSave(PrescriptionDraft(name.trim(), packageCount!!, packageCount, mg!!, if (weekly) "WEEK" else "DAY", count!!, today, today, doctorId, costId))
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun PrescriptionEditDialog(row: PrescriptionUi, onDismiss: () -> Unit, onSave: (PrescriptionEntity) -> Unit) {
    val initial = row.prescription
    var packages by remember(initial.id) { mutableStateOf(initial.packageDoseCount.toString()) }
    var remaining by remember(initial.id) { mutableStateOf(initial.remainingDoses.toString()) }
    var dose by remember(initial.id) { mutableStateOf(initial.doseMg.clean()) }
    var frequency by remember(initial.id) { mutableStateOf(initial.frequencyCount.toString()) }
    var weekly by remember(initial.id) { mutableStateOf(initial.frequencyPeriod == "WEEK") }
    var orderDate by remember(initial.id) { mutableStateOf(initial.orderEpochDay.toString()) }
    var prescriptionDate by remember(initial.id) { mutableStateOf(initial.prescriptionEpochDay.toString()) }
    var doctorId by remember(initial.id) { mutableStateOf(initial.doctorContactId?.toString().orEmpty()) }
    var costId by remember(initial.id) { mutableStateOf(initial.financeTransactionId?.toString().orEmpty()) }
    val depletion = SostanzeEngine.depletionDate(
        remaining.toIntOrNull() ?: -1,
        frequency.toIntOrNull() ?: 0,
        if (weekly) "WEEK" else "DAY",
        LocalDate.now(),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.substanceName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(packages, { packages = it.filter(Char::isDigit) }, label = { Text("Package doses") })
                OutlinedTextField(remaining, { remaining = it.filter(Char::isDigit) }, label = { Text("Remaining doses") })
                OutlinedTextField(dose, { dose = it.numberText() }, label = { Text(stringResource(R.string.dose_mg)) })
                OutlinedTextField(frequency, { frequency = it.filter(Char::isDigit) }, label = { Text("Frequency X") })
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Weekly"); Switch(weekly, { weekly = it }) }
                OutlinedTextField(orderDate, { orderDate = it.filter { c -> c.isDigit() || c == '-' } }, label = { Text("Order epoch day") })
                OutlinedTextField(prescriptionDate, { prescriptionDate = it.filter { c -> c.isDigit() || c == '-' } }, label = { Text("Prescription epoch day") })
                Text("Estimated depletion: ${depletion?.let(::formatDateOnly) ?: "-"}")
                OutlinedTextField(doctorId, { doctorId = it.filter(Char::isDigit) }, label = { Text("People contact ID") })
                OutlinedTextField(costId, { costId = it.filter(Char::isDigit) }, label = { Text("Soldi transaction ID") })
            }
        },
        confirmButton = {
            val packageCount = packages.toIntOrNull()
            val left = remaining.toIntOrNull()
            val mg = dose.toDoubleOrNull()
            val count = frequency.toIntOrNull()
            Button(enabled = packageCount != null && packageCount > 0 && left != null && left in 0..packageCount && mg != null && mg > 0 && count != null && count > 0,
                onClick = {
                    onSave(initial.copy(
                        packageDoseCount = packageCount!!, remainingDoses = left!!, doseMg = mg!!,
                        frequencyCount = count!!, frequencyPeriod = if (weekly) "WEEK" else "DAY",
                        orderEpochDay = orderDate.toLongOrNull() ?: initial.orderEpochDay,
                        prescriptionEpochDay = prescriptionDate.toLongOrNull() ?: initial.prescriptionEpochDay,
                        doctorContactId = doctorId.toLongOrNull(), financeTransactionId = costId.toLongOrNull(),
                    ))
                }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun IntakeEditDialog(row: HistoryUi, onDismiss: () -> Unit, onSave: (Long, Double) -> Unit) {
    var timestamp by remember(row.id) { mutableStateOf(row.timestampMs.toString()) }
    var quantity by remember(row.id) { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.substanceName)
            OutlinedTextField(timestamp, { timestamp = it.filter(Char::isDigit) }, label = { Text("Timestamp ms") })
            OutlinedTextField(quantity, { quantity = it.numberText() }, label = { Text("Quantity") })
        } },
        confirmButton = { Button(onClick = { onSave(timestamp.toLongOrNull() ?: row.timestampMs, quantity.toDoubleOrNull() ?: 1.0) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun InteractionRuleDialog(source: SubstanceEntity, initial: InteractionRuleEntity?, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var before by remember(initial?.id) { mutableStateOf(initial?.avoidBeforeHours?.clean() ?: "2") }
    var after by remember(initial?.id) { mutableStateOf(initial?.avoidAfterHours?.clean() ?: "2") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.interaction_for, source.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.target_all_future))
                OutlinedTextField(before, { before = it.numberText() }, label = { Text(stringResource(R.string.avoid_before_hours)) }, singleLine = true)
                OutlinedTextField(after, { after = it.numberText() }, label = { Text(stringResource(R.string.avoid_after_hours)) }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(before.toDoubleOrNull() ?: 0.0, after.toDoubleOrNull() ?: 0.0) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun ConfirmDeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_substance_title)) },
        text = { Text(stringResource(R.string.delete_substance_message, name)) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.archive)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun ConfirmImportDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_confirm_title)) },
        text = { Text(stringResource(R.string.import_confirm_message)) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.import_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private fun formatTime(ms: Long): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun formatDateTime(ms: Long): String {
    val local = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val pattern = if (local.toLocalDate() == today) "HH:mm" else "d/M/yy - HH:mm"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(local)
}

private fun formatDateOnly(date: LocalDate): String =
    DateTimeFormatter.ofPattern("d/M/yy", Locale.getDefault()).format(date)

private fun Double.clean(): String =
    if (this % 1.0 == 0.0) toLong().toString() else "%.2f".format(Locale.US, this).trimEnd('0').trimEnd('.')

private fun String.numberText(): String =
    filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')
