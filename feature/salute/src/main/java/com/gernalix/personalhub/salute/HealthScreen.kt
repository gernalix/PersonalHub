package com.gernalix.personalhub.salute

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.max

private enum class HealthTab { TIMELINE, EXAMS, JOURNAL }
private enum class TimelineFilter { ALL, EXAMS, JOURNAL, ABNORMAL }
private sealed interface HealthDetail {
    data class Measurement(val sourceId: Long) : HealthDetail
    data class Journal(val sourceId: Long) : HealthDetail
}

@Composable
fun HealthScreen(
    repository: HealthRepository,
    onBack: () -> Unit,
) {
    var result by remember { mutableStateOf<HealthLoadResult?>(null) }
    var tab by remember { mutableStateOf(HealthTab.TIMELINE) }
    var filter by remember { mutableStateOf(TimelineFilter.ALL) }
    var detail by remember { mutableStateOf<HealthDetail?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload(force: Boolean) {
        refreshing = true
        scope.launch {
            result = repository.load(forceRefresh = force)
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        result = repository.load(forceRefresh = false)
    }

    BackHandler {
        if (detail != null) detail = null else onBack()
    }

    when (val selected = detail) {
        is HealthDetail.Measurement -> MeasurementDetail(
            repository = repository,
            sourceId = selected.sourceId,
            onBack = { detail = null },
        )
        is HealthDetail.Journal -> JournalDetail(
            repository = repository,
            sourceId = selected.sourceId,
            onBack = { detail = null },
        )
        null -> HealthHome(
            result = result,
            tab = tab,
            filter = filter,
            refreshing = refreshing,
            onTab = { tab = it },
            onFilter = { filter = it },
            onRefresh = { reload(true) },
            onRetry = { reload(true) },
            onBack = onBack,
            onOpen = { item ->
                detail = if (item.sourceKind == "journal") {
                    HealthDetail.Journal(item.sourceId)
                } else {
                    HealthDetail.Measurement(item.sourceId)
                }
            },
        )
    }
}

@Composable
private fun HealthHome(
    result: HealthLoadResult?,
    tab: HealthTab,
    filter: TimelineFilter,
    refreshing: Boolean,
    onTab: (HealthTab) -> Unit,
    onFilter: (TimelineFilter) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpen: (HealthTimelineItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Indietro") }
            Text(
                text = "Salute",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "Aggiorno…" else "Aggiorna")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = tab == HealthTab.TIMELINE,
                onClick = { onTab(HealthTab.TIMELINE) },
                label = { Text("Timeline") },
            )
            FilterChip(
                selected = tab == HealthTab.EXAMS,
                onClick = { onTab(HealthTab.EXAMS) },
                label = { Text("Esami") },
            )
            FilterChip(
                selected = tab == HealthTab.JOURNAL,
                onClick = { onTab(HealthTab.JOURNAL) },
                label = { Text("Diario") },
            )
        }

        when (result) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is HealthLoadResult.Error -> ErrorState(result.message, onRetry)
            is HealthLoadResult.Ready -> {
                val snapshot = result.snapshot
                snapshot.sync.warning?.let {
                    Text(
                        "Aggiornamento Salute non riuscito · dati precedenti ancora disponibili",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (tab == HealthTab.TIMELINE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TimelineFilter.entries.forEach { item ->
                            val label = when (item) {
                                TimelineFilter.ALL -> "Tutto"
                                TimelineFilter.EXAMS -> "Esami"
                                TimelineFilter.JOURNAL -> "Diario"
                                TimelineFilter.ABNORMAL -> "Anomalie"
                            }
                            FilterChip(
                                selected = filter == item,
                                onClick = { onFilter(item) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                if (tab == HealthTab.EXAMS) {
                    val summary = snapshot.turnaround
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Text(
                            text = if (summary == null) {
                                "Tempo prelievo → ricezione: dati con orario esatto ancora insufficienti"
                            } else {
                                "Tempo medio prelievo → ricezione: ${summary.formatted} · ${summary.count} risultati"
                            },
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                val visible = snapshot.timeline.filter { item ->
                    when (tab) {
                        HealthTab.TIMELINE -> when (filter) {
                            TimelineFilter.ALL -> true
                            TimelineFilter.EXAMS -> item.sourceKind == "measurement"
                            TimelineFilter.JOURNAL -> item.sourceKind == "journal"
                            TimelineFilter.ABNORMAL -> !item.flag.isNullOrBlank()
                        }
                        HealthTab.EXAMS -> item.sourceKind == "measurement"
                        HealthTab.JOURNAL -> item.sourceKind == "journal"
                    }
                }
                TimelineList(visible, onOpen)
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Sincronizza") }
    }
}

@Composable
private fun TimelineList(
    items: List<HealthTimelineItem>,
    onOpen: (HealthTimelineItem) -> Unit,
) {
    if (items.isEmpty()) {
        Text("Nessun dato disponibile.")
        return
    }
    val grouped = items.groupBy { it.data ?: "Data sconosciuta" }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, dateItems) ->
            item(key = "date-$date") {
                Text(
                    date.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(
                items = dateItems,
                key = { "${it.sourceKind}-${it.sourceId}" },
            ) { item ->
                HealthTimelineCard(item, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun HealthTimelineCard(
    item: HealthTimelineItem,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.titolo,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                item.valore?.let {
                    Text(
                        buildString {
                            append(it)
                            item.flag?.let { flag -> append(" ").append(flag) }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            item.sottotitolo?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            item.tempoReferto?.let {
                Text(
                    "Prelievo → ricezione: $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (item.sourceKind == "journal") {
                item.dettaglio?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!item.commentoAi.isNullOrBlank()) {
                    Text(
                        "Analisi AI disponibile",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                item.dettaglio?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MeasurementDetail(
    repository: HealthRepository,
    sourceId: Long,
    onBack: () -> Unit,
) {
    var history by remember(sourceId) { mutableStateOf<List<HealthMeasurementHistoryItem>?>(null) }
    var error by remember(sourceId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sourceId) {
        runCatching { repository.measurementHistory(sourceId) }
            .onSuccess { history = it }
            .onFailure { error = it.message }
    }

    DetailScaffold(onBack = onBack, title = history?.firstOrNull()?.esame ?: "Esame") {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val rows = history
        if (rows == null) {
            CircularProgressIndicator()
        } else if (rows.isEmpty()) {
            Text("Storico non disponibile.")
        } else {
            val current = rows.firstOrNull { it.sourceId == sourceId } ?: rows.first()
            Text(
                buildString {
                    append(current.risultato ?: "—")
                    current.unita?.let { append(" ").append(it) }
                    current.flag?.let { append(" ").append(it) }
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            current.tempoReferto?.let {
                Text("Prelievo → ricezione: $it", color = MaterialTheme.colorScheme.primary)
            }
            val numeric = rows.filter { it.numericValue != null }.sortedBy { it.dataMs }
            if (numeric.size >= 2) {
                Text("Trend", style = MaterialTheme.typography.titleMedium)
                TrendChart(numeric)
            }
            HorizontalDivider()
            Text("Storico", style = MaterialTheme.typography.titleMedium)
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.data ?: "—")
                    Text(
                        buildString {
                            append(row.risultato ?: "—")
                            row.unita?.let { append(" ").append(it) }
                            row.flag?.let { append(" ").append(it) }
                        },
                    )
                }
                row.tempoReferto?.let {
                    Text(
                        "Referto in $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendChart(rows: List<HealthMeasurementHistoryItem>) {
    val values = rows.mapNotNull { it.numericValue }
    if (values.size < 2) return
    val minValue = values.minOrNull() ?: return
    val maxValue = values.maxOrNull() ?: return
    val range = max(maxValue - minValue, 0.000001)
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(vertical = 8.dp),
    ) {
        val points = rows.mapIndexedNotNull { index, row ->
            val value = row.numericValue ?: return@mapIndexedNotNull null
            val x = if (rows.size == 1) size.width / 2f
            else index.toFloat() / (rows.size - 1).toFloat() * size.width
            val y = size.height - ((value - minValue) / range).toFloat() * size.height
            Offset(x, y)
        }
        points.zipWithNext().forEach { (a, b) ->
            drawLine(
                color = lineColor,
                start = a,
                end = b,
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

@Composable
private fun JournalDetail(
    repository: HealthRepository,
    sourceId: Long,
    onBack: () -> Unit,
) {
    var detail by remember(sourceId) { mutableStateOf<HealthJournalDetail?>(null) }
    var evidence by remember(sourceId) { mutableStateOf<List<HealthEvidence>>(emptyList()) }
    var error by remember(sourceId) { mutableStateOf<String?>(null) }
    var showOriginal by remember(sourceId) { mutableStateOf(false) }

    LaunchedEffect(sourceId) {
        runCatching {
            detail = repository.journalDetail(sourceId)
            evidence = repository.journalEvidence(sourceId)
        }.onFailure { error = it.message }
    }

    DetailScaffold(onBack = onBack, title = detail?.titolo ?: "Diario clinico") {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val row = detail
        if (row == null && error == null) {
            CircularProgressIndicator()
        } else if (row != null) {
            listOfNotNull(
                row.data,
                row.reparto,
                row.struttura,
                row.clinico,
                row.ruolo,
            ).joinToString(" · ").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            SectionTitle("NOTA CLINICA")
            Text(row.nota)

            SectionTitle("ANALISI AI")
            if (row.commentoAi.isNullOrBlank()) {
                Text("Nessuna analisi AI disponibile per questa nota.")
            } else {
                Text(row.commentoAi)
            }
            row.incertezzeAi?.takeIf(String::isNotBlank)?.let {
                Text(
                    "Incertezze: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("EVIDENZE")
            if (evidence.isEmpty()) {
                Text("Nessuna evidenza collegata.")
            } else {
                evidence.forEach { item ->
                    Text("• ${item.label ?: item.kind}: ${item.relevance}")
                }
            }

            HorizontalDivider()
            TextButton(onClick = { showOriginal = !showOriginal }) {
                Text(if (showOriginal) "Nascondi originale danese" else "Mostra originale danese")
            }
            if (showOriginal) {
                SectionTitle("ORIGINALE DANESE")
                Text(row.originaleDa)
            }
        }
    }
}

@Composable
private fun DetailScaffold(
    onBack: () -> Unit,
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Indietro") }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}
