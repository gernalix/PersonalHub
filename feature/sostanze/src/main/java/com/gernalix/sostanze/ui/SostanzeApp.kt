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
import androidx.compose.material3.TabRow
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
    LaunchedEffect(Unit) {
        withFrameNanos { }
        viewModel.loadSecondaryState()
    }
    var tab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var query by rememberSaveable { mutableStateOf("") }
    var editingSubstance by remember { mutableStateOf<SubstanceEntity?>(null) }
    var deleteSubstance by remember { mutableStateOf<SubstanceEntity?>(null) }
    var historySubstanceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var stockDialog by remember { mutableStateOf<SubstanceEntity?>(null) }
    var prescriptionDialog by remember { mutableStateOf<SubstanceEntity?>(null) }
    var interactionDialog by remember { mutableStateOf<SubstanceEntity?>(null) }
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
                TabRow(selectedTabIndex = tab.ordinal) {
                    AppTab.values().forEach { item ->
                        Tab(
                            selected = tab == item,
                            onClick = { tab = item },
                            text = { Text(tabTitle(item), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                ImportExportStatusBar(state)
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editingSubstance = viewModel.defaultNewSubstance() }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.Home -> HomeScreen(
                    states = state.doseStates.filter { it.substance.name.contains(query, true) },
                    macros = state.macros,
                    query = query,
                    onQueryChange = { query = it },
                    onRecord = { substanceId ->
                        viewModel.recordIntake(substanceId) { token ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(recordedMessage, actionLabel = undoLabel)
                                if (result == SnackbarResult.ActionPerformed) viewModel.undoToken(token)
                            }
                        }
                    },
                    onMacro = { macroId ->
                        viewModel.recordMacro(macroId) { token ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(macroRecordedMessage, actionLabel = undoLabel)
                                if (result == SnackbarResult.ActionPerformed) viewModel.undoToken(token)
                            }
                        }
                    },
                    onUndo = viewModel::undoLastIntake,
                    onEdit = { editingSubstance = it },
                    onStock = { stockDialog = it },
                    onHistory = { historySubstanceId = it.id },
                )
                AppTab.History -> HistoryScreen(rows = state.history)
                AppTab.Stock -> StockScreen(
                    rows = state.stockRows.filter { it.substance.name.contains(query, true) },
                    query = query,
                    onQueryChange = { query = it },
                    onAdjust = { stockDialog = it },
                )
                AppTab.Prescriptions -> PrescriptionScreen(
                    substances = state.substances.filter { !it.archived },
                    rows = state.prescriptions,
                    onAdd = { prescriptionDialog = it },
                )
                AppTab.Interactions -> InteractionScreen(
                    substances = state.substances.filter { !it.archived },
                    states = state.doseStates,
                    onAddRule = { interactionDialog = it },
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
                viewModel.saveSubstance(it)
                editingSubstance = null
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
    prescriptionDialog?.let { substance ->
        PrescriptionDialog(
            substance = substance,
            onDismiss = { prescriptionDialog = null },
            onSave = { quantity, months, alert ->
                viewModel.addPrescription(substance.id, quantity, months, alert)
                prescriptionDialog = null
            }
        )
    }
    interactionDialog?.let { source ->
        InteractionRuleDialog(
            source = source,
            onDismiss = { interactionDialog = null },
            onSave = { before, after ->
                viewModel.addAllFutureInteraction(source.id, before, after)
                interactionDialog = null
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
    onStock: (SubstanceEntity) -> Unit,
    onHistory: (SubstanceEntity) -> Unit,
) {
    val sections = listOf(
        DoseSection.DUE_TODAY to stringResource(R.string.section_due_today),
        DoseSection.LATER to stringResource(R.string.section_later),
        DoseSection.BLOCKED to stringResource(R.string.section_blocked),
        DoseSection.PRN to stringResource(R.string.section_prn),
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
    )

@Composable
private fun DoseActionButton(
    state: DoseButtonState,
    onRecord: () -> Unit,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
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
            if (state.block != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("!")
                    Text(
                        "${state.block.sourceName} ${SostanzeEngine.countdownText(state.block.remainingMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
private fun HistoryScreen(rows: List<HistoryUi>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(rows, key = { index, row -> "${row.substanceName}:${row.timestampMs}:${row.ghost}:$index" }) { _, row ->
            HistoryRow(row)
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
private fun HistoryRow(row: HistoryUi) {
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
        }
    }
}

@Composable
private fun PrescriptionScreen(
    substances: List<SubstanceEntity>,
    rows: List<PrescriptionUi>,
    onAdd: (SubstanceEntity) -> Unit,
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
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.substanceName, fontWeight = FontWeight.SemiBold)
                    Text("${row.prescription.quantityPrescribed.clean()} mg")
                    Text(formatDateOnly(LocalDate.ofEpochDay(row.prescription.prescriptionEpochDay)))
                }
            }
        }
    }
}

@Composable
private fun InteractionScreen(
    substances: List<SubstanceEntity>,
    states: List<DoseButtonState>,
    onAddRule: (SubstanceEntity) -> Unit,
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
private fun PrescriptionDialog(substance: SubstanceEntity, onDismiss: () -> Unit, onSave: (Double, Int, Boolean) -> Unit) {
    var quantity by remember { mutableStateOf("") }
    var months by remember { mutableStateOf("1") }
    var alert by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prescription_for, substance.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(quantity, { quantity = it.numberText() }, label = { Text(stringResource(R.string.prescribed_quantity_mg)) }, singleLine = true)
                OutlinedTextField(months, { months = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.refill_months)) }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.refill_alert))
                    Switch(alert, { alert = it })
                }
            }
        },
        confirmButton = {
            Button(enabled = quantity.toDoubleOrNull() != null, onClick = { onSave(quantity.toDoubleOrNull() ?: 0.0, months.toIntOrNull() ?: 1, alert) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun InteractionRuleDialog(source: SubstanceEntity, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var before by remember { mutableStateOf("2") }
    var after by remember { mutableStateOf("2") }
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
