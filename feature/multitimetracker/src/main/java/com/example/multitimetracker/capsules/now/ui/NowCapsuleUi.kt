// v480
package com.example.multitimetracker.capsules.now.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.now.controller.NowCapsuleViewModel
import com.example.multitimetracker.model.HomeLoadState
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.ui.components.RunningSessionRow
import com.example.multitimetracker.ui.components.ScreenEmptyStateCard
import com.example.multitimetracker.ui.components.ScreenIntroCard
import com.example.multitimetracker.ui.components.SectionHeader
import com.example.multitimetracker.ui.util.formatDuration

/**
 * ===== FEATURE CAPSULE: Now.SessionList (UI) — START =====
 *
 * Scope:
 * - All LazyColumn items for NOW tab session-only list.
 * - No business logic here: mapping lives in [NowCapsuleViewModel].
 */
object NowCapsuleUi {

    private data class DurationPresentation(
        val text: String,
        val color: Color
    )

    private data class ActiveTagChipUi(
        val tag: Tag,
        val shownMs: Long
    )

    @androidx.compose.runtime.Composable
    fun SummaryCard(
        modifier: Modifier,
        nowMs: Long,
        runningSessions: List<SessionUi>,
        activeTagsCount: Int,
        showSeconds: Boolean,
        hideHoursIfZero: Boolean,
    ) {
        // Summary derived only from running sessions.
        val runningCount = runningSessions.count { it.endMs == null && it.deletedAtMs == null }
        val runningTotalMs = runningSessions
            .asSequence()
            .filter { it.endMs == null && it.deletedAtMs == null }
            .sumOf { NowCapsuleViewModel.runningDurationMs(nowMs, it) }

        Card(modifier = modifier) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.show_summary),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "• ${stringResource(R.string.active_sessions_header)}: $runningCount",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• ${stringResource(R.string.active_tags_header)}: $activeTagsCount",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• " + stringResource(
                        R.string.tempo_totale_tracciato,
                        formatDuration(runningTotalMs, showSeconds, hideHoursIfZero)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    @Suppress("LongParameterList")
    fun LazyListScope.render(
        nowMs: Long,
        regularRows: List<SessionUi>,
        timedRows: List<SessionUi>,
        tagNameById: Map<Long, String>,
        activeTags: List<Tag>,
        activeTagTotalsMsByTagId: Map<Long, Long>,
        runningMinStartByTagId: Map<Long, Long>,
        homeLoadState: HomeLoadState,
        showSeconds: Boolean,
        hideHoursIfZero: Boolean,
        showOnboardingIntro: Boolean,
        onOpenTag: (Long) -> Unit,
        onCreateSession: () -> Unit,
        onEditSession: (Long) -> Unit,
        onStopSession: (sessionId: Long) -> Unit,
        onDeleteSession: (Long) -> Unit,
        elapsedModeSessionIds: Set<Long>,
        onToggleTimedDisplayMode: (Long) -> Unit
    ) {
        if (homeLoadState == HomeLoadState.Loading) {
            item(key = "now_loading_sessions") {
                ScreenEmptyStateCard(
                    title = stringResource(R.string.loading),
                    body = stringResource(R.string.now_loading_body),
                    icon = Icons.Filled.PlayArrow
                )
            }
            return
        }

        if (homeLoadState == HomeLoadState.Error) {
            item(key = "now_load_error") {
                ScreenEmptyStateCard(
                    title = stringResource(R.string.integrity_gate_title),
                    body = stringResource(R.string.integrity_gate_body)
                )
            }
            return
        }

        if (regularRows.isEmpty() && timedRows.isEmpty() && homeLoadState != HomeLoadState.ReadyEmpty) {
            item(key = "now_waiting_for_ready_state") {
                ScreenEmptyStateCard(
                    title = stringResource(R.string.loading),
                    body = stringResource(R.string.now_loading_body),
                    icon = Icons.Filled.PlayArrow
                )
            }
            return
        }

        if (regularRows.isEmpty() && timedRows.isEmpty()) {
            if (showOnboardingIntro) {
                item(key = "now_onboarding_intro") {
                    ScreenIntroCard(
                        title = stringResource(R.string.now_first_run_intro_title),
                        body = stringResource(R.string.now_first_run_intro_body)
                    )
                }
            }
            item(key = "empty_running_sessions") {
                ScreenEmptyStateCard(
                    title = stringResource(R.string.now_running_empty_title),
                    body = stringResource(R.string.now_running_empty_body),
                    icon = Icons.Filled.PlayArrow
                ) {
                    FilledTonalButton(onClick = onCreateSession) {
                        Text(text = stringResource(R.string.start_session))
                    }
                }
            }
        } else {
            if (regularRows.isNotEmpty()) {
                item(key = "now_regular_sessions_section") {
                    SessionSection(
                        title = stringResource(R.string.active_sessions_header),
                        sessions = regularRows,
                        nowMs = nowMs,
                        tagNameById = tagNameById,
                        elapsedModeSessionIds = elapsedModeSessionIds,
                        showSeconds = showSeconds,
                        hideHoursIfZero = hideHoursIfZero,
                        onStopSession = onStopSession,
                        onEditSession = onEditSession,
                        onDeleteSession = onDeleteSession,
                        onToggleTimedDisplayMode = onToggleTimedDisplayMode
                    )
                }
            }
            if (timedRows.isNotEmpty()) {
                item(key = "now_timed_sessions_section") {
                    SessionSection(
                        title = stringResource(R.string.now_timed_title),
                        sessions = timedRows,
                        nowMs = nowMs,
                        tagNameById = tagNameById,
                        elapsedModeSessionIds = elapsedModeSessionIds,
                        showSeconds = showSeconds,
                        hideHoursIfZero = hideHoursIfZero,
                        onStopSession = onStopSession,
                        onEditSession = onEditSession,
                        onDeleteSession = onDeleteSession,
                        onToggleTimedDisplayMode = onToggleTimedDisplayMode,
                        modifier = Modifier.padding(top = if (regularRows.isNotEmpty()) 8.dp else 0.dp)
                    )
                }
            }
        }

    }

    @Composable
    private fun SessionSection(
        title: String,
        sessions: List<SessionUi>,
        nowMs: Long,
        tagNameById: Map<Long, String>,
        elapsedModeSessionIds: Set<Long>,
        showSeconds: Boolean,
        hideHoursIfZero: Boolean,
        onStopSession: (Long) -> Unit,
        onEditSession: (Long) -> Unit,
        onDeleteSession: (Long) -> Unit,
        onToggleTimedDisplayMode: (Long) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SectionHeader(title = title)
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                sessions.forEach { session ->
                    key(session.id) {
                        val displayTitle = NowCapsuleViewModel.displayTitle(
                            session = session,
                            tagNameById = tagNameById,
                            fallbackNoTitle = stringResource(R.string.senza_titolo)
                        )
                        val tagNames = NowCapsuleViewModel.tagNames(session, tagNameById)
                        val duration = durationPresentation(
                            session = session,
                            nowMs = nowMs,
                            elapsedModeSessionIds = elapsedModeSessionIds,
                            showSeconds = showSeconds,
                            hideHoursIfZero = hideHoursIfZero
                        )

                        RunningSessionRow(
                            sessionId = session.id,
                            title = displayTitle,
                            tagNames = tagNames,
                            durationText = duration.text,
                            durationColor = duration.color,
                            onStop = { onStopSession(session.id) },
                            onEdit = { onEditSession(session.id) },
                            onDelete = { onDeleteSession(session.id) },
                            onDurationClick = if (session.expectedEndMs != null) {
                                { onToggleTimedDisplayMode(session.id) }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun durationPresentation(
        session: SessionUi,
        nowMs: Long,
        elapsedModeSessionIds: Set<Long>,
        showSeconds: Boolean,
        hideHoursIfZero: Boolean
    ): DurationPresentation {
        if (session.expectedEndMs != null) {
            val remainingMs = (session.expectedEndMs ?: nowMs) - nowMs
            val totalMs = ((session.expectedEndMs ?: nowMs) - session.startMs).coerceAtLeast(1L)
            val elapsedMs = NowCapsuleViewModel.runningDurationMs(nowMs, session)
            val durationText = if (session.id in elapsedModeSessionIds) {
                formatDuration(elapsedMs, showSeconds = showSeconds, hideHoursIfZero = hideHoursIfZero)
            } else {
                "-" + formatDuration(
                    remainingMs.coerceAtLeast(0L),
                    showSeconds = showSeconds,
                    hideHoursIfZero = true
                )
            }
            val remainingRatio = remainingMs.toFloat() / totalMs.toFloat()
            val durationColor = when {
                remainingRatio < 0.2f -> MaterialTheme.colorScheme.error
                remainingRatio <= 0.5f -> Color(0xFFFF9800)
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            }
            return DurationPresentation(durationText, durationColor)
        }

        return DurationPresentation(
            text = formatDuration(
                NowCapsuleViewModel.runningDurationMs(nowMs, session),
                showSeconds = showSeconds,
                hideHoursIfZero = hideHoursIfZero
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ActiveTagsSection(
        activeTagChips: List<ActiveTagChipUi>,
        loading: Boolean,
        showSeconds: Boolean,
        hideHoursIfZero: Boolean,
        onOpenTag: (Long) -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(title = stringResource(R.string.active_tags_header))

            if (loading) {
                ScreenEmptyStateCard(
                    title = stringResource(R.string.loading),
                    body = stringResource(R.string.now_loading_body),
                    icon = Icons.Filled.PlayArrow
                )
            } else if (activeTagChips.isEmpty()) {
                ScreenEmptyStateCard(
                    title = stringResource(R.string.now_active_tags_empty_title),
                    body = stringResource(R.string.now_active_tags_empty_body)
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeTagChips.forEach { chip ->
                        ActiveTagChip(
                            label = chip.tag.name,
                            durationText = formatDuration(
                                chip.shownMs,
                                showSeconds = showSeconds,
                                hideHoursIfZero = hideHoursIfZero
                            ),
                            onClick = { onOpenTag(chip.tag.id) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ActiveTagChip(
        label: String,
        durationText: String,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier.clickable(onClick = onClick),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

// ===== FEATURE CAPSULE: Now.SessionList (UI) — END =====
