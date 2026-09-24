// v471
// v389
package com.example.multitimetracker.capsules.timeline.ui

import android.content.Intent
import com.example.multitimetracker.ui.components.SessionEditDialog
import com.example.multitimetracker.ui.util.TagSelectionOrder
import com.example.multitimetracker.ui.components.InlineHelpAction
import com.example.multitimetracker.ui.components.ScreenEmptyStateCard
import com.example.multitimetracker.ui.components.ScreenHelpAction
import com.example.multitimetracker.ui.components.ScreenIntroCard
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.multitimetracker.ui.components.SingleSubmitTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import kotlin.math.absoluteValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.capsules.timeline.state.TimelineUiState
import com.example.multitimetracker.ui.util.formatDuration
import com.example.multitimetracker.ui.components.ScreenScaffold
import com.example.multitimetracker.ui.components.MttDatePickerDialog
import com.example.multitimetracker.core.session.TimelineStatsCore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.example.multitimetracker.persistence.UiPrefsStore
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.SinceWhenSourceDescriptor
import com.gernalix.personalhub.contracts.database.SinceWhenTimestampSource
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.example.multitimetracker.ui.util.TagHierarchy
import com.example.multitimetracker.model.Tag
import java.time.ZoneOffset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager

import androidx.compose.runtime.collectAsState
import com.example.multitimetracker.capsules.timeline.controller.TimelineCapsuleViewModel

/**
 * Timeline screen using the v339 UI, backed by the Timeline feature capsule.
 */
@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    capsule: TimelineCapsuleViewModel,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean
) {
    val state by capsule.uiState.collectAsState()
    TimelineScreenContent(
        modifier = modifier,
        state = state,
        showSeconds = showSeconds,
        hideHoursIfZero = hideHoursIfZero,
        onUpdateSession = capsule::updateSession,
        onUpdateSessionTimes = capsule::updateSessionTimes,
        onDeleteSession = capsule::deleteSession,
        onAddTag = capsule::addTag
    )
}

private data class TimelineSession(
    val id: Long,
    val title: String,
    val startTs: Long,
    /** Always non-null: running sessions use nowMs as endTs. */
    val endTs: Long,
    val tagIds: Set<Long>,
    val isRunning: Boolean
)

private enum class TimelineSortKey { START, END, DURATION, TASK }
private enum class TimelineDatePickTarget { FROM, TO }

private enum class TimelineContentFilter { ALL, SESSIONS, EVENTS }

private data class TimelineStats(
    val totalMs: Long,
    val avgTaskMs: Long,
    val periodMs: Long,
    val elapsedPeriodMs: Long
)


private enum class DurationMinOption(val minMs: Long?) {
    ANY(null),
    M1(60_000L),
    M5(5 * 60_000L),
    M15(15 * 60_000L),
    M30(30 * 60_000L),
    M60(60 * 60_000L)
}

private enum class DurationMaxOption(val maxMs: Long?) {
    ANY(null),
    M1(60_000L),
    M5(5 * 60_000L),
    M15(15 * 60_000L),
    M30(30 * 60_000L),
    M60(60 * 60_000L)
}

private sealed class TimelineRowItem {
    data class Header(val date: LocalDate, val label: String) : TimelineRowItem()
    data class Session(
        val session: TimelineSession,
        val overlapsPrev: Boolean,
        val overlapsNext: Boolean,
        val connectPrev: Boolean,
        val connectNext: Boolean,
        val isRunning: Boolean
    ) : TimelineRowItem()
    data class Marker(val marker: TimelineMarker) : TimelineRowItem()
}

private enum class TimelineMarkerKind { EVENT, SESSION_START, SESSION_STOP }

private data class TimelineMarker(
    val id: String,
    val timestampMs: Long,
    val title: String,
    val tagIds: Set<Long>,
    val kind: TimelineMarkerKind,
    val sessionId: Long? = null,
    val sessionColorKey: Long? = null,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimelineScreenContent(
    modifier: Modifier = Modifier,
    state: TimelineUiState,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean,
    onUpdateSession: (Long, String, Set<Long>) -> Unit,
    onUpdateSessionTimes: (Long, Long, Long?) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onAddTag: (String) -> Unit
) {
    // === FEATURE CAPSULE: TimelineScreen (UI) START ===
    val context = LocalContext.current
    val effectiveTime = remember(state.nowMs) { state.effectiveTimeContext() }
    val isDarkTheme = isSystemInDarkTheme()
    val zone = remember { ZoneId.systemDefault() }
    val locale = remember { Locale.getDefault() }
    val timeFmt = remember(showSeconds, locale) {
        DateTimeFormatter.ofPattern(if (showSeconds) "HH:mm:ss" else "HH:mm", locale)
    }
    val dayFmt = remember(locale) {
        DateTimeFormatter.ofPattern("EEE d MMM uuuu", locale)
    }

    val tagsById = remember(state.tags) { state.tags.associateBy { it.id } }
    val hasTrackedSessions = remember(state.runningSessions, state.chronologySessions) {
        state.runningSessions.any { it.deletedAtMs == null } ||
            state.chronologySessions.any { it.deletedAtMs == null }
    }


val sessionUiById = remember(state.chronologySessions) { state.chronologySessions.associateBy { it.id } }
var editingSessionId by rememberSaveable { mutableStateOf<Long?>(null) }

    var sortKey by rememberSaveable { mutableStateOf(TimelineSortKey.START) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var contentFilter by rememberSaveable { mutableStateOf(TimelineContentFilter.ALL) }

    val effectiveTodayEpochDay = remember(effectiveTime.nowMs, zone) {
        Instant.ofEpochMilli(effectiveTime.nowMs).atZone(zone).toLocalDate().toEpochDay()
    }
    var selectedTagIds by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    var fromEpochDay by rememberSaveable { mutableStateOf(effectiveTodayEpochDay) }
    var toEpochDay by rememberSaveable { mutableStateOf(effectiveTodayEpochDay) }
    var filtersDialog by rememberSaveable { mutableStateOf(false) }
    var legendDialog by rememberSaveable { mutableStateOf(false) }

    // Timeline v2: show/hide tags column in the list. Persisted in UiPrefsStore.
    var showTagsInList by rememberSaveable {
        mutableStateOf(UiPrefsStore.getTimelineShowTagsInList(context))
    }
    LaunchedEffect(effectiveTodayEpochDay) {
        fromEpochDay = effectiveTodayEpochDay
        toEpochDay = effectiveTodayEpochDay
    }

val sessions by remember(
    state.chronologySessions,
    effectiveTime.nowMs,
    sortKey,
    ascending,
    selectedTagIds,
    fromEpochDay,
    toEpochDay,
    zone
) {
    derivedStateOf {
        fun endTs(s: SessionUi): Long = s.endMs ?: effectiveTime.nowMs

        fun durationMs(s: SessionUi): Long = (endTs(s) - s.startMs).coerceAtLeast(0L)

        val fromD0 = LocalDate.ofEpochDay(fromEpochDay)
        val toD0 = LocalDate.ofEpochDay(toEpochDay)
        val fromD = if (fromD0 <= toD0) fromD0 else toD0
        val toD = if (fromD0 <= toD0) toD0 else fromD0

        val lowerMs = fromD.atStartOfDay(zone).toInstant().toEpochMilli()
        val upperMsExclusive = toD.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val filtered = state.chronologySessions
            .asSequence()
            .filter { s -> endTs(s) > s.startMs }
            .filter { s ->
                // Date-range overlap: keep sessions that intersect [lowerMs, upperMsExclusive)
                s.startMs < upperMsExclusive && endTs(s) > lowerMs
            }
            .filter { s ->
                if (selectedTagIds.isEmpty()) true else selectedTagIds.any { s.tagIds.contains(it) }
            }
            .toList()

        val comparator: Comparator<SessionUi> = when (sortKey) {
            TimelineSortKey.START -> compareBy { it.startMs }
            TimelineSortKey.END -> compareBy { endTs(it) }
            TimelineSortKey.DURATION -> compareBy { durationMs(it) }
            TimelineSortKey.TASK -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        }

        val sorted = if (ascending) filtered.sortedWith(comparator) else filtered.sortedWith(comparator.reversed())

        sorted.map { s ->
            TimelineSession(
                id = s.id,
                title = s.title,
                startTs = s.startMs,
                endTs = endTs(s),
                tagIds = s.tagIds,
                isRunning = (s.endMs == null)
            )
        }
    }
}

val stats by remember(sessions, fromEpochDay, toEpochDay, zone, effectiveTime.nowMs) {
    derivedStateOf {
        val fromD0 = LocalDate.ofEpochDay(fromEpochDay)
        val toD0 = LocalDate.ofEpochDay(toEpochDay)
        val fromD = if (fromD0 <= toD0) fromD0 else toD0
        val toD = if (fromD0 <= toD0) toD0 else fromD0

        val lowerMs = fromD.atStartOfDay(zone).toInstant().toEpochMilli()
        val upperMsExclusive = toD.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val nowMs = effectiveTime.nowMs
        val rawIntervals = sessions.map { it.startTs to it.endTs }
        val coreStats = TimelineStatsCore.compute(
            rawIntervals = rawIntervals,
            lowerMs = lowerMs,
            upperMsExclusive = upperMsExclusive,
            nowMs = nowMs
        )

        TimelineStats(
            totalMs = coreStats.totalMs,
            avgTaskMs = coreStats.avgSessionMs,
            periodMs = coreStats.periodMs,
            elapsedPeriodMs = coreStats.elapsedPeriodMs
        )
    }
}

val rows by remember(sessions, state.quickEventEntries, contentFilter, selectedTagIds, fromEpochDay, toEpochDay, ascending, dayFmt, zone) {
    derivedStateOf {
        val out = ArrayList<TimelineRowItem>()
        var lastDay: LocalDate? = null
        val fromD0 = LocalDate.ofEpochDay(fromEpochDay)
        val toD0 = LocalDate.ofEpochDay(toEpochDay)
        val fromD = if (fromD0 <= toD0) fromD0 else toD0
        val toD = if (fromD0 <= toD0) toD0 else fromD0
        val lowerMs = fromD.atStartOfDay(zone).toInstant().toEpochMilli()
        val upperMsExclusive = toD.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val markers = ArrayList<TimelineMarker>()
        if (contentFilter != TimelineContentFilter.EVENTS) {
            sessions.forEach { s ->
                markers.add(
                    TimelineMarker(
                        id = "session_${s.id}_start",
                        timestampMs = s.startTs,
                        title = s.title,
                        tagIds = s.tagIds,
                        kind = TimelineMarkerKind.SESSION_START,
                        sessionId = s.id,
                        sessionColorKey = s.id
                    )
                )
                if (!s.isRunning) {
                    markers.add(
                        TimelineMarker(
                            id = "session_${s.id}_stop",
                            timestampMs = s.endTs,
                            title = s.title,
                            tagIds = s.tagIds,
                            kind = TimelineMarkerKind.SESSION_STOP,
                            sessionId = s.id,
                            sessionColorKey = s.id
                        )
                    )
                }
            }
        }
        if (contentFilter != TimelineContentFilter.SESSIONS) {
            state.quickEventEntries
                .asSequence()
                .filter { it.deletedAtMs == null }
                .filter { it.timestampMs in lowerMs until upperMsExclusive }
                .filter { entry -> selectedTagIds.isEmpty() || selectedTagIds.any { it in entry.tagIds } }
                .forEach { entry ->
                    markers.add(
                        TimelineMarker(
                            id = "event_${entry.id}",
                            timestampMs = entry.timestampMs,
                            title = entry.title,
                            tagIds = entry.tagIds,
                            kind = TimelineMarkerKind.EVENT
                        )
                    )
                }
        }
        val sorted = if (ascending) markers.sortedBy { it.timestampMs } else markers.sortedByDescending { it.timestampMs }
        sorted.forEach { marker ->
            val day = Instant.ofEpochMilli(marker.timestampMs).atZone(zone).toLocalDate()
            if (lastDay == null || day != lastDay) {
                out.add(TimelineRowItem.Header(date = day, label = dayFmt.format(day)))
                lastDay = day
            }
            out.add(TimelineRowItem.Marker(marker))
        }
        out
    }
}

val hasTimelineItems = rows.any { it is TimelineRowItem.Marker }
val hasTrackedEvents = remember(state.quickEventEntries) { state.quickEventEntries.any { it.deletedAtMs == null } }

    val selectedRangeLabel = remember(fromEpochDay, toEpochDay, dayFmt) {
        val fromD0 = LocalDate.ofEpochDay(fromEpochDay)
        val toD0 = LocalDate.ofEpochDay(toEpochDay)
        val fromD = if (fromD0 <= toD0) fromD0 else toD0
        val toD = if (fromD0 <= toD0) toD0 else fromD0
        if (fromD == toD) {
            dayFmt.format(fromD)
        } else {
            "${dayFmt.format(fromD)} - ${dayFmt.format(toD)}"
        }
    }

ScreenScaffold(
        title = stringResource(R.string.chronology),
        modifier = modifier,
        actions = {
            ScreenHelpAction(
                title = stringResource(R.string.timeline_intro_title),
                body = stringResource(R.string.timeline_intro_body)
            )
            IconButton(onClick = { filtersDialog = true }) {
                Icon(imageVector = Icons.Default.FilterList, contentDescription = stringResource(R.string.timeline_filters))
            }
            IconButton(onClick = { legendDialog = true }) {
                Icon(imageVector = Icons.Default.Info, contentDescription = stringResource(R.string.timeline_legend))
            }
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxWidth().padding(inner)) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Sort row (compact, Material 3 friendly)
                    var sortExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )

                            Box {
                                TextButton(onClick = { sortExpanded = true }) {
                                    val label = when (sortKey) {
                                        TimelineSortKey.START -> stringResource(R.string.timeline_sort_start)
                                        TimelineSortKey.END -> stringResource(R.string.timeline_sort_end)
                                        TimelineSortKey.DURATION -> stringResource(R.string.timeline_sort_duration)
                                        TimelineSortKey.TASK -> stringResource(R.string.timeline_sort_task)
                                    }
                                    Text(label)
                                }
                                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.timeline_sort_start)) },
                                        onClick = { sortKey = TimelineSortKey.START; sortExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.timeline_sort_end)) },
                                        onClick = { sortKey = TimelineSortKey.END; sortExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.timeline_sort_duration)) },
                                        onClick = { sortKey = TimelineSortKey.DURATION; sortExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.timeline_sort_task)) },
                                        onClick = { sortKey = TimelineSortKey.TASK; sortExpanded = false }
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { ascending = !ascending }) {
                            Icon(
                                imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = null
                            )
                        }
                    }

                    TimelineStatsBlock(
                        stats = stats,
                        showSeconds = showSeconds,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = contentFilter == TimelineContentFilter.ALL,
                            onClick = { contentFilter = TimelineContentFilter.ALL },
                            label = { Text(stringResource(R.string.timeline_filter_all)) }
                        )
                        FilterChip(
                            selected = contentFilter == TimelineContentFilter.SESSIONS,
                            onClick = { contentFilter = TimelineContentFilter.SESSIONS },
                            label = { Text(stringResource(R.string.timeline_filter_sessions)) }
                        )
                        FilterChip(
                            selected = contentFilter == TimelineContentFilter.EVENTS,
                            onClick = { contentFilter = TimelineContentFilter.EVENTS },
                            label = { Text(stringResource(R.string.timeline_filter_events)) }
                        )
                    }
                }
            }

        if (!hasTimelineItems) {
            if (!hasTrackedSessions && !hasTrackedEvents) {
                ScreenIntroCard(
                    title = stringResource(R.string.timeline_first_run_intro_title),
                    body = stringResource(R.string.timeline_first_run_intro_body),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            ScreenEmptyStateCard(
                title = stringResource(R.string.timeline_empty_title),
                body = stringResource(R.string.timeline_empty_body),
                modifier = Modifier.padding(12.dp)
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(
                items = rows,
                key = { item ->
                    when (item) {
                        is TimelineRowItem.Header -> "h_${item.date.toEpochDay()}"
                        is TimelineRowItem.Session -> "s_${item.session.id}"
                        is TimelineRowItem.Marker -> item.marker.id
                    }
                }
            ) { item ->
                when (item) {
                    is TimelineRowItem.Header -> {
                        DayHeader(
                            label = item.label,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is TimelineRowItem.Session -> {
                        SessionRow(
                            session = item.session,
                            overlapsPrev = item.overlapsPrev,
                            overlapsNext = item.overlapsNext,
                            connectPrev = item.connectPrev,
                            connectNext = item.connectNext,
                            isRunning = item.isRunning,
                            timeFmt = timeFmt,
                            zone = zone,
                            showSeconds = showSeconds,
                            hideHoursIfZero = hideHoursIfZero,
                            showTagsInList = showTagsInList,
                            isDarkTheme = isDarkTheme,
                            tagsById = tagsById,
                            onClick = {
                                if (!state.isReadOnly) {
                                    editingSessionId = item.session.id
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        )
                    }
                    is TimelineRowItem.Marker -> {
                        TimelineMarkerRow(
                            marker = item.marker,
                            timeFmt = timeFmt,
                            zone = zone,
                            tagsById = tagsById,
                            isDarkTheme = isDarkTheme,
                            onClick = {
                                item.marker.sessionId?.let { sessionId ->
                                    if (!state.isReadOnly) editingSessionId = sessionId
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }

        }
    }


val editingSessionUi = editingSessionId?.let { sessionUiById[it] }
if (editingSessionUi != null) {
    SessionEditDialog(
        session = editingSessionUi,
        isNewSession = false,
        tags = state.tags.filter { !it.isDeleted && !it.isArchived },
        tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
        tagParentsByChild = state.tagParentsByChild,
        showSeconds = showSeconds,
        referenceNowMs = effectiveTime.nowMs,
        readOnly = state.isReadOnly,
        onAddTag = onAddTag,
        onSaveMeta = { id, title, tagIds ->
            onUpdateSession(id, title, tagIds)
            editingSessionId = null
        },
        onSaveTimes = { id, startMs, endMs ->
            onUpdateSessionTimes(id, startMs, endMs)
            editingSessionId = null
        },
        onDelete = { id ->
            onDeleteSession(id)
            editingSessionId = null
        },
        onDismiss = { editingSessionId = null }
    )
}

if (filtersDialog) {

        FiltersDialog(
            tags = TagSelectionOrder.sortForPicker(
                tags = tagsById.values.filter { !it.isDeleted },
                selectedIds = selectedTagIds,
                lastUsedMsByTagId = state.tagLastUsedMsByTagId
            ),
            selectedTagIds = selectedTagIds,
            fromEpochDay = fromEpochDay,
            toEpochDay = toEpochDay,
            showTagsInList = showTagsInList,
            onDismiss = { filtersDialog = false },
            onApply = { tagIds, fromDay, toDay, showTags ->
                selectedTagIds = tagIds
                fromEpochDay = fromDay
                toEpochDay = toDay
                showTagsInList = showTags
                if (!state.isReadOnly) {
                    UiPrefsStore.setTimelineShowTagsInList(context, showTags)
                }
                filtersDialog = false
            },
            onReset = {
                selectedTagIds = emptySet()
                fromEpochDay = effectiveTodayEpochDay
                toEpochDay = effectiveTodayEpochDay
            }
        )
    }

    if (legendDialog) {
        AlertDialog(
            onDismissRequest = { legendDialog = false },
            title = { Text(stringResource(R.string.timeline_legend_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.timeline_legend_overlap))
                    Text(stringResource(R.string.timeline_legend_no_overlap))
                    Text(stringResource(R.string.timeline_legend_running))
                }
            },
            confirmButton = {
                TextButton(onClick = { legendDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
    // === FEATURE CAPSULE: TimelineScreen (UI) END ===
}

private fun overlaps(a: TimelineSession, b: TimelineSession): Boolean {
    return a.startTs < b.endTs && b.startTs < a.endTs
}

@Composable
private fun TimelineStatsBlock(
    stats: TimelineStats,
    showSeconds: Boolean,
    modifier: Modifier = Modifier
) {
    val locale = remember { Locale.getDefault() }
    val pctSelected = if (stats.periodMs > 0L) (stats.totalMs.toDouble() / stats.periodMs.toDouble()) else 0.0
    val pctSelectedLabel = String.format(locale, "%.1f%%", pctSelected * 100.0)

    val pctElapsed = if (stats.elapsedPeriodMs > 0L) (stats.totalMs.toDouble() / stats.elapsedPeriodMs.toDouble()) else 0.0
    val pctElapsedLabel = String.format(locale, "%.1f%%", pctElapsed * 100.0)

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
            text = stringResource(
                R.string.timeline_stats_total,
                formatDurationWithDays(stats.totalMs, showSeconds = showSeconds),
                pctSelectedLabel,
                pctElapsedLabel
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
            text = stringResource(
                R.string.timeline_stats_avg_task,
				formatDurationWithDays(stats.avgTaskMs, showSeconds = showSeconds)
            ),
            style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun formatDurationWithDays(ms: Long, showSeconds: Boolean): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)

    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L

    val dU = stringResource(R.string.duration_unit_day_short)
    val hU = stringResource(R.string.duration_unit_hour_short)
    val mU = stringResource(R.string.duration_unit_minute_short)
    val sU = stringResource(R.string.duration_unit_second_short)

    val parts = ArrayList<String>(4)
    if (days > 0L) parts.add("${days}${dU}")
    if (days > 0L || hours > 0L) parts.add("${hours}${hU}")
    parts.add("${minutes}${mU}")
    if (showSeconds) parts.add("${seconds}${sU}")
    return parts.joinToString(" ")
}

@Composable
private fun DayHeader(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SessionRow(
    session: TimelineSession,
    overlapsPrev: Boolean,
    overlapsNext: Boolean,
    connectPrev: Boolean,
    connectNext: Boolean,
    isRunning: Boolean,
    timeFmt: DateTimeFormatter,
    zone: ZoneId,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean,
    showTagsInList: Boolean,
    isDarkTheme: Boolean,
    tagsById: Map<Long, com.example.multitimetracker.model.Tag>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val start = Instant.ofEpochMilli(session.startTs).atZone(zone).toLocalTime()
    val context = LocalContext.current
    val sinceWhenSessionStarted = stringResource(R.string.since_when_session_started)
    var sinceWhenMenuOpen by rememberSaveable(session.id) { mutableStateOf(false) }
    val end = Instant.ofEpochMilli(session.endTs).atZone(zone).toLocalTime()
    val durMs = (session.endTs - session.startTs).coerceAtLeast(0L)

        val dominantTagName = remember(session.tagIds, tagsById) {
        val tid = session.tagIds.minOrNull()
        tid?.let { tagsById[it]?.name }
    }
    val roles = remember(dominantTagName, isDarkTheme) {
        dominantTagName?.let { com.example.multitimetracker.ui.theme.taskRolesFromTagName(it, isDarkTheme) }
    }
    val railColor = roles?.rail ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
        // v394: remove the vertical track/rails (they add a lot of noise in dense Chronology lists).
        // Keep a small dot as a stable "anchor" so the row still reads as a timeline.
        Canvas(
            modifier = Modifier
                .padding(start = 10.dp, top = 12.dp, bottom = 12.dp)
                .width(26.dp)
                .height(20.dp)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = (size.minDimension * 0.16f).coerceAtLeast(2.5f)
            drawCircle(color = railColor, radius = r, center = Offset(cx, cy))
        }
        val needsTagNames = remember(showTagsInList, session.title) {
            showTagsInList || session.title.trim().isBlank()
        }

        // Two lists:
        // - allTagNamesSorted: for fallback title when session.title is blank
        // - visibleTagNamesSorted: for Timeline tag line (respects per-tag 'showInTimeline')
        val allTagNamesSorted = remember(needsTagNames, session.tagIds, tagsById) {
            if (!needsTagNames) {
                emptyList<String>()
            } else {
                session.tagIds
                    .mapNotNull { id ->
                        tagsById[id]
                            ?.takeIf { !it.isDeleted }
                            ?.name
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
        }

        val visibleTagNamesSorted = remember(showTagsInList, needsTagNames, session.tagIds, tagsById) {
            if (!showTagsInList || !needsTagNames) {
                emptyList<String>()
            } else {
                session.tagIds
                    .mapNotNull { id ->
                        tagsById[id]
                            ?.takeIf { !it.isDeleted && it.showInTimeline }
                            ?.name
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
        }

        val titleFromTags = remember(session.title, allTagNamesSorted) {
            session.title.trim().isBlank() && allTagNamesSorted.isNotEmpty()
        }

        val titleTagNameOrNull = remember(titleFromTags, allTagNamesSorted) {
            if (titleFromTags) allTagNamesSorted.firstOrNull() else null
        }

        val resolvedTitle = remember(session.title, allTagNamesSorted) {
            val t = session.title.trim()
            if (t.isNotBlank()) t
            else allTagNamesSorted.firstOrNull() ?: "—"
        }

        val durationText = remember(durMs, showSeconds, hideHoursIfZero) {
            formatDuration(durMs, showSeconds = showSeconds, hideHoursIfZero = hideHoursIfZero)
        }

        val timeRangeText = if (isRunning) {
            "${timeFmt.format(start)} – ${stringResource(R.string.tab_now)}"
        } else {
            "${timeFmt.format(start)} – ${timeFmt.format(end)}"
        }
        val tagsLine = if (showTagsInList) {
            remember(visibleTagNamesSorted, titleTagNameOrNull) {
                val names = if (titleTagNameOrNull != null) {
                    // We used the first tag as a fallback title when the session title is blank.
                    // UX invariant: if a tag is marked "Show in timeline", it should still appear
                    // next to the session. So we only remove the title-tag when *other* visible tags
                    // remain; otherwise we keep it (avoids a "no tags" look).
                    val filtered = visibleTagNamesSorted.filterNot { it.equals(titleTagNameOrNull, ignoreCase = true) }
                    if (filtered.isEmpty()) visibleTagNamesSorted else filtered
                } else {
                    visibleTagNamesSorted
                }

                if (names.isEmpty()) {
                    null
                } else {
                    // Guardrails: keep this cheap & predictable on slower devices.
                    val maxTags = 4
                    val charBudget = 38

                    val shown = ArrayList<String>(maxTags)
                    var used = 0

                    for (name in names) {
                        if (shown.size >= maxTags) break

                        val sep = if (shown.isEmpty()) "" else ", "
                        val addLen = sep.length + name.length

                        if (shown.isEmpty()) {
                            shown.add(name)
                            used = name.length
                        } else if (used + addLen <= charBudget) {
                            shown.add(name)
                            used += addLen
                        } else {
                            break
                        }
                    }

                    if (shown.isEmpty()) shown.add(names.first())

                    val remaining = (names.size - shown.size).coerceAtLeast(0)
                    if (remaining > 0) {
                        shown.joinToString(", ") + ", +" + remaining
                    } else {
                        shown.joinToString(", ")
                    }
                }
            }
        } else {
            null
        }

        // Timeline layout: keep all text in ONE column.
        // This makes it immediately obvious which tags belong to which session.
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = resolvedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "$timeRangeText · $durationText",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (showTagsInList && tagsLine != null) {
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocalOffer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 6.dp)
                    )
                    Text(
                        text = "${stringResource(R.string.timeline_tags_prefix)} $tagsLine",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { sinceWhenMenuOpen = true }) {
                Text("⋮", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = sinceWhenMenuOpen, onDismissRequest = { sinceWhenMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.since_when_create_counter)) },
                    onClick = {
                        sinceWhenMenuOpen = false
                        val source = SinceWhenSourceDescriptor(
                            entityType = "timer/session",
                            entityId = session.id.toString(),
                            defaultCounterTitle = resolvedTitle,
                            timestampSources = listOf(SinceWhenTimestampSource(
                                "session_started",
                                sinceWhenSessionStarted,
                                session.startTs,
                                true,
                            )),
                        )
                        context.startActivity(Intent(Intent.ACTION_VIEW, HubDeepLinkContract.sinceWhenCreateUri(source)))
                    },
                )
            }
        }
        }

        // IMPORTANT: the separator must be OUTSIDE the Row.
        // If it stays inside the Row, `fillMaxWidth()` can steal all remaining width
        // and make the text column measure at 0px (Timeline looks “empty”).
        TimelineHairlineSeparator()
    }
}

@Composable
private fun TimelineMarkerRow(
    marker: TimelineMarker,
    timeFmt: DateTimeFormatter,
    zone: ZoneId,
    tagsById: Map<Long, com.example.multitimetracker.model.Tag>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val time = Instant.ofEpochMilli(marker.timestampMs).atZone(zone).toLocalTime()
    val dominantTagName = remember(marker.tagIds, tagsById) {
        marker.tagIds.minOrNull()?.let { tagsById[it]?.name }
    }
    val roles = remember(dominantTagName, isDarkTheme) {
        dominantTagName?.let { com.example.multitimetracker.ui.theme.taskRolesFromTagName(it, isDarkTheme) }
    }
    val railColor = roles?.rail ?: when (marker.kind) {
        TimelineMarkerKind.EVENT -> MaterialTheme.colorScheme.primary
        TimelineMarkerKind.SESSION_START -> MaterialTheme.colorScheme.tertiary
        TimelineMarkerKind.SESSION_STOP -> MaterialTheme.colorScheme.tertiary
    }
    val kindLabel = when (marker.kind) {
        TimelineMarkerKind.EVENT -> stringResource(R.string.timeline_marker_event)
        TimelineMarkerKind.SESSION_START -> stringResource(R.string.timeline_marker_start)
        TimelineMarkerKind.SESSION_STOP -> stringResource(R.string.timeline_marker_stop)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Canvas(
                modifier = Modifier
                    .padding(start = 10.dp, top = 10.dp, bottom = 10.dp)
                    .width(26.dp)
                    .height(26.dp)
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawCircle(color = railColor.copy(alpha = if (marker.kind == TimelineMarkerKind.EVENT) 0.72f else 0.38f), radius = size.minDimension * 0.36f, center = Offset(cx, cy))
                drawCircle(color = railColor, radius = size.minDimension * 0.16f, center = Offset(cx, cy))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeFmt.format(time),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 56.dp)
                    )
                    Text(
                        text = marker.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(onClick = {}, label = { Text(kindLabel) })
                }
            }
        }
        TimelineHairlineSeparator()
    }
}

@Composable
private fun TimelineHairlineSeparator(modifier: Modifier = Modifier) {
    // v387 lock: stable custom separator (avoid HorizontalDivider API churn).
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    )
}
@Composable
private fun TimelineTopBar(
    sortKey: TimelineSortKey,
    ascending: Boolean,
    onToggleOrder: () -> Unit,
    onSortChange: (TimelineSortKey) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenLegend: () -> Unit
) {
    var sortExpanded by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            OutlinedButton(
                onClick = { sortExpanded = true },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = stringResource(R.string.timeline_sort),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.timeline_sort),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = sortLabel(sortKey),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.timeline_sort_open),
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = sortExpanded,
                onDismissRequest = { sortExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.timeline_sort_start)) },
                    onClick = { onSortChange(TimelineSortKey.START); sortExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.timeline_sort_end)) },
                    onClick = { onSortChange(TimelineSortKey.END); sortExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.timeline_sort_duration)) },
                    onClick = { onSortChange(TimelineSortKey.DURATION); sortExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.timeline_sort_task)) },
                    onClick = { onSortChange(TimelineSortKey.TASK); sortExpanded = false }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onToggleOrder) {
            Icon(
                imageVector = if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = if (ascending) stringResource(R.string.timeline_order_asc) else stringResource(R.string.timeline_order_desc)
            )
        }

        IconButton(onClick = onOpenFilters) {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.timeline_filters)
            )
        }

        IconButton(onClick = onOpenLegend) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = stringResource(R.string.timeline_legend)
            )
        }
    }
}

@Composable
private fun sortLabel(key: TimelineSortKey): String {
    return when (key) {
        TimelineSortKey.START -> stringResource(R.string.timeline_sort_start)
        TimelineSortKey.END -> stringResource(R.string.timeline_sort_end)
        TimelineSortKey.DURATION -> stringResource(R.string.timeline_sort_duration)
        TimelineSortKey.TASK -> stringResource(R.string.timeline_sort_task)
    }
}

@Composable
private fun FiltersDialog(
    tags: List<com.example.multitimetracker.model.Tag>,
    selectedTagIds: Set<Long>,
    fromEpochDay: Long,
    toEpochDay: Long,
    showTagsInList: Boolean,
    onDismiss: () -> Unit,
    onApply: (Set<Long>, Long, Long, Boolean) -> Unit,
    onReset: () -> Unit
) {
    val locale = remember { Locale.getDefault() }
    val zone = remember { ZoneId.systemDefault() }
    val dateFmt = remember(locale) { DateTimeFormatter.ofPattern("d MMM uuuu", locale) }

    var tagQuery by rememberSaveable { mutableStateOf("") }
    var sel by rememberSaveable { mutableStateOf(selectedTagIds) }
    var fromDay by rememberSaveable { mutableStateOf(fromEpochDay) }
    var toDay by rememberSaveable { mutableStateOf(toEpochDay) }
    var showTags by rememberSaveable { mutableStateOf(showTagsInList) }
    var datePickTarget by remember { mutableStateOf<TimelineDatePickTarget?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filtri)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = stringResource(R.string.timeline_show_tags_in_list))
                        InlineHelpAction(
                            title = stringResource(R.string.timeline_show_tags_in_list),
                            body = stringResource(R.string.timeline_show_tags_in_list_help)
                        )
                    }
                    Checkbox(checked = showTags, onCheckedChange = { showTags = it })
                }

                com.example.multitimetracker.ui.components.AppDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.timeline_tags), style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = stringResource(R.string.timeline_selected_tags_count_fmt, sel.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = tagQuery,
                        onValueChange = { tagQuery = it },
                        label = { Text(stringResource(R.string.cerca_tag)) },
                        singleLine = true,
                        trailingIcon = {
                            if (tagQuery.isNotBlank()) {
                                IconButton(onClick = { tagQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.reset))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    val q = tagQuery.trim()
                    val base = if (q.isEmpty()) tags else tags.filter { it.name.contains(q, ignoreCase = true) }
                    val ordered = base.sortedWith(
                        compareByDescending<com.example.multitimetracker.model.Tag> { sel.contains(it.id) }
                            .thenBy { it.name.lowercase() }
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    ) {
                        items(ordered, key = { it.id }) { t ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        sel = if (sel.contains(t.id)) sel - t.id else sel + t.id
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = sel.contains(t.id),
                                    onCheckedChange = {
                                        sel = if (sel.contains(t.id)) sel - t.id else sel + t.id
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(text = t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.timeline_date_range), style = MaterialTheme.typography.labelLarge)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { datePickTarget = TimelineDatePickTarget.FROM },
                            modifier = Modifier.weight(1f)
                        ) {
                            val d = LocalDate.ofEpochDay(fromDay)
                            Text(stringResource(R.string.timeline_date_from_fmt, stringResource(R.string.timeline_date_from), dateFmt.format(d)))
                        }

                        OutlinedButton(
                            onClick = { datePickTarget = TimelineDatePickTarget.TO },
                            modifier = Modifier.weight(1f)
                        ) {
                            val d = LocalDate.ofEpochDay(toDay)
                            Text(stringResource(R.string.timeline_date_to_fmt, stringResource(R.string.timeline_date_to), dateFmt.format(d)))
                        }
                    }
                }
            }
        },
        confirmButton = {
            SingleSubmitTextButton(onClick = { onApply(sel, fromDay, toDay, showTags) }) {
                Text(stringResource(R.string.timeline_apply))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
	            TextButton(onClick = onReset) { Text(stringResource(R.string.timeline_reset_filters)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_cancel)) }
            }
        }
    )

    datePickTarget?.let { target ->
        MttDatePickerDialog(
            title = stringResource(
                when (target) {
                    TimelineDatePickTarget.FROM -> R.string.timeline_date_from
                    TimelineDatePickTarget.TO -> R.string.timeline_date_to
                }
            ),
            initialEpochDay = when (target) {
                TimelineDatePickTarget.FROM -> fromDay
                TimelineDatePickTarget.TO -> toDay
            },
            onDismiss = { datePickTarget = null },
            onConfirm = { picked ->
                when (target) {
                    TimelineDatePickTarget.FROM -> fromDay = picked
                    TimelineDatePickTarget.TO -> toDay = picked
                }
                datePickTarget = null
            }
        )
    }
}

@Composable
private fun SimpleDropdownField(
    label: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    menuContent: @Composable () -> Unit
) {
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = label
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
        )

        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            menuContent()
        }
    }
}

@Composable
private fun taskValueLabel(sessionId: Long?, tasksById: Map<Long, com.example.multitimetracker.model.Task>): String {
    return if (sessionId == null) stringResource(R.string.timeline_any) else tasksById[sessionId]?.name ?: stringResource(R.string.timeline_any)
}

@Composable
private fun tagValueLabel(tagId: Long?, tagsById: Map<Long, com.example.multitimetracker.model.Tag>): String {
    return if (tagId == null) stringResource(R.string.timeline_any) else tagsById[tagId]?.name ?: stringResource(R.string.timeline_any)
}

private fun durationMinItems(): List<DurationMinOption> = listOf(
    DurationMinOption.ANY,
    DurationMinOption.M1,
    DurationMinOption.M5,
    DurationMinOption.M15,
    DurationMinOption.M30,
    DurationMinOption.M60
)

private fun durationMaxItems(): List<DurationMaxOption> = listOf(
    DurationMaxOption.ANY,
    DurationMaxOption.M1,
    DurationMaxOption.M5,
    DurationMaxOption.M15,
    DurationMaxOption.M30,
    DurationMaxOption.M60
)

@Composable
private fun durationMinLabel(opt: DurationMinOption): String {
    return when (opt) {
        DurationMinOption.ANY -> stringResource(R.string.timeline_any)
        DurationMinOption.M1 -> stringResource(R.string.timeline_duration_1m)
        DurationMinOption.M5 -> stringResource(R.string.timeline_duration_5m)
        DurationMinOption.M15 -> stringResource(R.string.timeline_duration_15m)
        DurationMinOption.M30 -> stringResource(R.string.timeline_duration_30m)
        DurationMinOption.M60 -> stringResource(R.string.timeline_duration_60m)
    }
}

@Composable
private fun durationMaxLabel(opt: DurationMaxOption): String {
    return when (opt) {
        DurationMaxOption.ANY -> stringResource(R.string.timeline_any)
        DurationMaxOption.M1 -> stringResource(R.string.timeline_duration_1m)
        DurationMaxOption.M5 -> stringResource(R.string.timeline_duration_5m)
        DurationMaxOption.M15 -> stringResource(R.string.timeline_duration_15m)
        DurationMaxOption.M30 -> stringResource(R.string.timeline_duration_30m)
        DurationMaxOption.M60 -> stringResource(R.string.timeline_duration_60m)
    }
}
