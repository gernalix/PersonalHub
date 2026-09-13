// v479
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package com.example.multitimetracker.capsules.now.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.RandomTimerStore
import com.example.multitimetracker.capsules.now.controller.NowCapsuleViewModel
import com.example.multitimetracker.capsules.now.controller.rankQuickStartTags
import com.example.multitimetracker.isTimedTag
import com.example.multitimetracker.model.HomeLoadState
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.perf.StartupPerfTrace
import com.example.multitimetracker.ui.components.AppTopBar
import com.example.multitimetracker.ui.components.ScreenHelpAction
import com.example.multitimetracker.ui.components.SessionEditDialog
import com.example.multitimetracker.ui.util.TagHierarchy
import kotlinx.coroutines.launch

internal const val NEW_SESSION_DRAFT_ID: Long = -1L

internal fun newSessionDraft(nowMs: Long): SessionUi =
    SessionUi(
        id = NEW_SESSION_DRAFT_ID,
        title = "",
        startMs = nowMs,
        endMs = null,
        tagIds = emptySet(),
        deletedAtMs = null
    )

internal fun updateNewSessionDraftTimes(draft: SessionUi, startMs: Long, endMs: Long?): SessionUi =
    draft.copy(startMs = startMs, endMs = endMs)

internal fun shouldShowNowLoadingState(loadState: HomeLoadState): Boolean =
    loadState == HomeLoadState.Loading

internal fun shouldShowNowEmptyState(loadState: HomeLoadState, hasRunningRows: Boolean): Boolean =
    loadState == HomeLoadState.ReadyEmpty && !hasRunningRows

/**
 * NOW screen (session-only).
 *
 * Acceptance:
 * - Tap a running session -> STOP.
 * - Long-press -> Edit session.
 * - "Active tags" section always visible under sessions.
 * - Quick-start tags fill the body when idle and the lower half while sessions are running.
 */
@Composable
@Suppress("LongParameterList")
fun NowScreen(
    modifier: Modifier,
    capsule: NowCapsuleViewModel,
    onOpenTag: (Long) -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    showSeconds: Boolean,
    onShowSecondsChange: (Boolean) -> Unit,
    hideHoursIfZero: Boolean,
    onHideHoursIfZeroChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
) {
    val state by capsule.uiState.collectAsState()
    val listState = rememberLazyListState()
    val effectiveTime = remember(state.nowMs, state.timeMachineTargetMs) { state.effectiveTimeContext() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var editingSession by remember { mutableStateOf<SessionUi?>(null) }
    var editingSessionIsNew by remember { mutableStateOf(false) }
    var elapsedModeSessionIds by remember { mutableStateOf(setOf<Long>()) }
    var randomMaxMinutes by rememberSaveable { mutableStateOf("60") }

    fun openNewSessionDraft() {
        editingSession = newSessionDraft(effectiveTime.nowMs)
        editingSessionIsNew = true
    }

    // v309 HOTFIX: defensive de-dup to avoid Compose LazyColumn key collisions.
    val visibleTags = remember(state.tags) {
        state.tags.filter { !it.isDeleted && !it.isArchived }.distinctBy { it.id }
    }
    val tagNameById = remember(visibleTags) { visibleTags.associate { it.id to it.name } }
    val runningRows = remember(state.runningSessions) {
        state.runningSessions
            .asSequence()
            .filter { it.endMs == null && it.deletedAtMs == null }
            .distinctBy { it.id }
            .toList()
    }
    val randomRunningSessionIds = remember(context, runningRows) {
        runningRows.filter { RandomTimerStore.isRandomSession(context, it.id) }.mapTo(mutableSetOf<Long>()) { it.id }
    }
    val regularRows = remember(runningRows, randomRunningSessionIds) {
        runningRows.filter { it.expectedEndMs == null && it.id !in randomRunningSessionIds }
    }
    val timedRows = remember(runningRows, randomRunningSessionIds) {
        runningRows.filter { it.expectedEndMs != null && it.id !in randomRunningSessionIds }
    }
    val randomCompletedSession = remember(context, state.chronologySessions) {
        RandomTimerStore.unansweredCompletedSession(context, state.chronologySessions)
    }
    val runningRowCount = regularRows.size + timedRows.size
    val activeTags = remember(visibleTags, state.runningMinStartByTagId, state.tagLastUsedMsByTagId) {
        NowCapsuleViewModel.computeActiveTags(
            visibleTags = visibleTags,
            runningMinStartByTagId = state.runningMinStartByTagId,
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
        )
    }
    val rankedQuickStartTags = remember(
        visibleTags,
        state.chronologySessions,
        state.tagLastUsedMsByTagId,
    ) {
        rankQuickStartTags(
            visibleTags = visibleTags,
            chronologySessions = state.chronologySessions,
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
        )
    }

    fun startQuickSession(tags: List<Tag>) {
        if (state.isReadOnly || tags.isEmpty()) return

        val selectedTagIds = tags.mapTo(linkedSetOf()) { it.id }
        val effectiveTagIds = TagHierarchy.closure(selectedTagIds, state.tagParentsByChild)
        val timedTagCount = visibleTags.count { it.id in effectiveTagIds && it.isTimedTag() }
        if (timedTagCount > 1) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.timed_tag_single_per_session),
                    duration = SnackbarDuration.Short,
                )
            }
            return
        }

        val label = tags.joinToString(" · ") { it.name }
        capsule.createNewSession(
            title = "",
            startMs = effectiveTime.nowMs,
            tagIds = effectiveTagIds,
        ) { created ->
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.quick_start_session_started, label),
                    actionLabel = context.getString(R.string.undo),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    capsule.deleteSession(created.id)
                }
            }
        }
    }

    LaunchedEffect(state.homeLoadState, runningRowCount) {
        if (state.homeLoadState == HomeLoadState.Loading) return@LaunchedEffect
        withFrameNanos { }
        StartupPerfTrace.homeReady(
            state = state.homeLoadState.name,
            runningSessions = runningRowCount,
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.now),
                actions = {
                    ScreenHelpAction(
                        title = stringResource(R.string.now_intro_title),
                        body = stringResource(R.string.now_intro_body)
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { openNewSessionDraft() },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.new_session))
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                if (state.homeLoadState == HomeLoadState.Loading || state.homeLoadState == HomeLoadState.Error) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 24.dp, bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        NowCapsuleUi.run {
                            render(
                                nowMs = effectiveTime.nowMs,
                                regularRows = regularRows,
                                timedRows = timedRows,
                                tagNameById = tagNameById,
                                activeTags = activeTags,
                                activeTagTotalsMsByTagId = state.activeTagTotalsMsByTagId,
                                runningMinStartByTagId = state.runningMinStartByTagId,
                                homeLoadState = state.homeLoadState,
                                showSeconds = showSeconds,
                                hideHoursIfZero = hideHoursIfZero,
                                showOnboardingIntro = false,
                                onOpenTag = onOpenTag,
                                onCreateSession = { openNewSessionDraft() },
                                onEditSession = {},
                                onStopSession = {},
                                onDeleteSession = {},
                                elapsedModeSessionIds = elapsedModeSessionIds,
                                onToggleTimedDisplayMode = {},
                            )
                        }
                        item {
                            RandomTimerCard(
                                maxMinutes = randomMaxMinutes,
                                onMaxMinutesChange = { randomMaxMinutes = it.filter(Char::isDigit).take(4) },
                                enabled = !state.isReadOnly,
                                onStart = {
                                    capsule.createRandomTimer(
                                        maxMinutes = randomMaxMinutes.toIntOrNull() ?: 60,
                                        startMs = effectiveTime.nowMs,
                                    )
                                },
                            )
                        }
                    }
                } else if (runningRowCount > 0) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        NowCapsuleUi.run {
                            render(
                                nowMs = effectiveTime.nowMs,
                                regularRows = regularRows,
                                timedRows = timedRows,
                                tagNameById = tagNameById,
                                activeTags = activeTags,
                                activeTagTotalsMsByTagId = state.activeTagTotalsMsByTagId,
                                runningMinStartByTagId = state.runningMinStartByTagId,
                                homeLoadState = state.homeLoadState,
                                showSeconds = showSeconds,
                                hideHoursIfZero = hideHoursIfZero,
                                showOnboardingIntro = false,
                                onOpenTag = onOpenTag,
                                onCreateSession = { openNewSessionDraft() },
                                onEditSession = { sid ->
                                    if (state.isReadOnly) return@render
                                    val found = state.runningSessions.firstOrNull { it.id == sid }
                                        ?: state.chronologySessions.firstOrNull { it.id == sid }
                                    if (found != null) {
                                        editingSession = found
                                        editingSessionIsNew = false
                                    }
                                },
                                onStopSession = { sid ->
                                    if (state.isReadOnly) return@render
                                    val s = state.runningSessions.firstOrNull { it.id == sid }
                                    if (s != null) capsule.updateSessionTimes(sid, s.startMs, effectiveTime.nowMs)
                                },
                                onDeleteSession = { sid ->
                                    if (!state.isReadOnly) {
                                        capsule.deleteSession(sid)
                                    }
                                },
                                elapsedModeSessionIds = elapsedModeSessionIds,
                                onToggleTimedDisplayMode = { sid ->
                                    elapsedModeSessionIds = if (sid in elapsedModeSessionIds) {
                                        elapsedModeSessionIds - sid
                                    } else {
                                        elapsedModeSessionIds + sid
                                    }
                                }
                            )
                        }
                        item {
                            RandomTimerCard(
                                maxMinutes = randomMaxMinutes,
                                onMaxMinutesChange = { randomMaxMinutes = it.filter(Char::isDigit).take(4) },
                                enabled = !state.isReadOnly,
                                onStart = {
                                    capsule.createRandomTimer(
                                        maxMinutes = randomMaxMinutes.toIntOrNull() ?: 60,
                                        startMs = effectiveTime.nowMs,
                                    )
                                },
                            )
                        }
                    }

                    QuickStartTagLauncher(
                        tags = rankedQuickStartTags,
                        enabled = !state.isReadOnly,
                        onStartSession = ::startQuickSession,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 4.dp, bottom = 80.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item {
                            RandomTimerCard(
                                maxMinutes = randomMaxMinutes,
                                onMaxMinutesChange = { randomMaxMinutes = it.filter(Char::isDigit).take(4) },
                                enabled = !state.isReadOnly,
                                onStart = {
                                    capsule.createRandomTimer(
                                        maxMinutes = randomMaxMinutes.toIntOrNull() ?: 60,
                                        startMs = effectiveTime.nowMs,
                                    )
                                },
                            )
                        }
                        item {
                            QuickStartTagLauncher(
                                tags = rankedQuickStartTags,
                                enabled = !state.isReadOnly,
                                onStartSession = ::startQuickSession,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            randomCompletedSession?.let { session ->
                RandomTimerResultDialog(
                    session = session,
                    onDismiss = { RandomTimerStore.markAnswered(context, session.id) },
                    onSubmit = { RandomTimerStore.markAnswered(context, session.id) },
                )
            }

            editingSession?.let { s ->
                SessionEditDialog(
                    session = s,
                    isNewSession = editingSessionIsNew,
                    onAddTag = capsule::addTag,
                    tags = visibleTags,
                    tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
                    tagParentsByChild = state.tagParentsByChild,
                    showSeconds = showSeconds,
                    referenceNowMs = effectiveTime.nowMs,
                    readOnly = state.isReadOnly,
                    onSaveMeta = { id, newTitle, newTagIds ->
                        if (!editingSessionIsNew) {
                            capsule.updateSession(id, newTitle, newTagIds)
                        }
                        editingSession = null
                    },
                    onCreateNewSession = { newTitle, startMs, newTagIds, onCreated ->
                        capsule.createNewSession(newTitle, startMs, newTagIds, onCreated)
                    },
                    onSaveTimes = { id, startMs, endMsOrNull ->
                        if (editingSessionIsNew) {
                            editingSession = updateNewSessionDraftTimes(editingSession ?: s, startMs, endMsOrNull)
                        } else {
                            capsule.updateSessionTimes(id, startMs, endMsOrNull)
                            editingSession = null
                        }
                    },
                    onDelete = { id ->
                        capsule.deleteSession(id)
                        editingSession = null
                    },
                    onDismiss = { editingSession = null }
                )
            }
        }
    }
}

@Composable
private fun RandomTimerCard(
    maxMinutes: String,
    onMaxMinutesChange: (String) -> Unit,
    enabled: Boolean,
    onStart: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = maxMinutes,
                onValueChange = onMaxMinutesChange,
                label = { Text(stringResource(R.string.random_timer_max_minutes)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                enabled = enabled && (maxMinutes.toIntOrNull() ?: 0) > 0,
                onClick = onStart,
            ) {
                Text(stringResource(R.string.random_timer_start))
            }
        }
    }
}

@Composable
private fun RandomTimerResultDialog(
    session: SessionUi,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val context = LocalContext.current
    var guess by rememberSaveable(session.id) { mutableStateOf("") }
    var submitted by rememberSaveable(session.id) { mutableStateOf(false) }
    val target = if (submitted) RandomTimerStore.targetMinutes(context, session.id) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.random_timer_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = guess,
                    onValueChange = { guess = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.random_timer_guess_minutes)) },
                    singleLine = true,
                )
                if (submitted && target != null) {
                    val guessed = guess.toIntOrNull() ?: 0
                    val ratio = if (target > 0) guessed.toDouble() / target.toDouble() else 0.0
                    Text(stringResource(R.string.random_timer_result, target, "%.2f".format(java.util.Locale.US, ratio)))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = submitted || (guess.toIntOrNull() ?: 0) > 0,
                onClick = {
                    if (submitted) onSubmit() else submitted = true
                },
            ) {
                Text(stringResource(if (submitted) R.string.ok else R.string.salva))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) } },
    )
}
