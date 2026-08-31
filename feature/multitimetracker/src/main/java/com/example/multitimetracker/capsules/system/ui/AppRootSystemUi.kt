package com.example.multitimetracker.capsules.system.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.AppPatchVersion
import com.example.multitimetracker.R
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.session.SessionMirrorCore
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.persistence.UiPrefsStore
import com.example.multitimetracker.ui.components.AppDivider
import com.example.multitimetracker.ui.components.SingleSubmitButton
import com.example.multitimetracker.ui.components.SingleSubmitOutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AppRootSystemPrefs {
    fun getShowSeconds(context: Context): Boolean = UiPrefsStore.getShowSeconds(context)
    fun setShowSeconds(context: Context, value: Boolean) = UiPrefsStore.setShowSeconds(context, value)
    fun getHideHoursIfZero(context: Context): Boolean = UiPrefsStore.getHideHoursIfZero(context)
    fun setHideHoursIfZero(context: Context, value: Boolean) = UiPrefsStore.setHideHoursIfZero(context, value)
    fun getKeepScreenOn(context: Context): Boolean = UiPrefsStore.getKeepScreenOn(context)
    fun setKeepScreenOn(context: Context, value: Boolean) = UiPrefsStore.setKeepScreenOn(context, value)
    fun isDeveloperSurfaceAvailable(): Boolean = UiPrefsStore.isDeveloperSurfaceAvailable()
    fun isDevModeEnabled(context: Context): Boolean = UiPrefsStore.isDevModeEnabled(context)
}

data class DevToolsRuntimeState(
    val sessionOnlyModeEnabled: Boolean,
    val tasks: List<Task>,
    val tags: List<Tag>,
    val closedSessions: List<ClosedSessionRecord>,
    val effectiveNowMs: Long,
)

suspend fun buildSessionDiagnosticsReport(
    context: Context,
    state: DevToolsRuntimeState,
    nowMs: Long,
): String = withContext(Dispatchers.Default) {
    SessionMirrorCore.buildDiagnosticsReport(
        context = context,
        sessionOnlyModeEnabled = state.sessionOnlyModeEnabled,
        tasks = state.tasks,
        tags = state.tags,
        closedSessions = state.closedSessions,
        nowMs = nowMs,
        maxRows = 200
    )
}

@Composable
fun DevToolsDialog(
    state: DevToolsRuntimeState,
    onDismiss: () -> Unit,
    onShowReport: (String) -> Unit,
    onReloadFromSnapshot: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val patchVersion = remember(context) { AppPatchVersion.current(context) }

    var useSessionsReader by remember { mutableStateOf(UiPrefsStore.getDevChronologyUseSessions(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dev_tools_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.versione_patch_v, patchVersion),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AppDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dev_tools_use_sessions_reader))
                    Switch(
                        checked = useSessionsReader,
                        onCheckedChange = { checked ->
                            useSessionsReader = checked
                            UiPrefsStore.setDevChronologyUseSessions(context, checked)
                        }
                    )
                }

                Text(
                    text = stringResource(R.string.dev_tools_hint_longpress),
                    style = MaterialTheme.typography.bodySmall
                )

                SingleSubmitButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                SessionMirrorCore.mirrorFromLegacySnapshot(
                                    context = context,
                                    tasks = state.tasks,
                                    tags = state.tags,
                                    closedSessions = state.closedSessions,
                                    nowMs = state.effectiveNowMs
                                )
                            }.onFailure { err ->
                                withContext(Dispatchers.Main) {
                                    onShowReport("Force mirror ERROR: ${err.message ?: err.toString()}")
                                }
                                return@launch
                            }

                            val report = SessionMirrorCore.buildDeveloperIntegrityReport(context, state.effectiveNowMs)
                            withContext(Dispatchers.Main) { onShowReport(report) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dev_tools_force_mirror_now))
                }

                SingleSubmitOutlinedButton(
                    onClick = onReloadFromSnapshot,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dev_tools_reload_now))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
