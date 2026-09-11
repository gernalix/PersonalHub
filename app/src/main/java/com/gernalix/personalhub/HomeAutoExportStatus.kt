package com.gernalix.personalhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.database.AutoExportStatus
import com.gernalix.personalhub.core.database.DatabaseVault
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HomeAutoExportUiStatus(
    val healthy: Boolean,
    val folderConfigured: Boolean,
    val currentGeneration: Long,
    val exportedGeneration: Long,
    val lastSuccessfulExportAt: Long,
    val lastError: String?,
    val stale: Boolean,
)

internal fun homeAutoExportUiStatus(status: AutoExportStatus?): HomeAutoExportUiStatus =
    HomeAutoExportUiStatus(
        healthy = status != null && status.folderConfigured && !status.stale && status.lastError.isNullOrBlank(),
        folderConfigured = status?.folderConfigured == true,
        currentGeneration = status?.currentGeneration ?: -1,
        exportedGeneration = status?.exportedGeneration ?: -1,
        lastSuccessfulExportAt = status?.lastSuccessfulExportAt ?: 0,
        lastError = status?.lastError,
        stale = status?.stale ?: true,
    )

@Composable
internal fun HomeAutoExportStatusIndicator() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<AutoExportStatus?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(detailsOpen) {
        status = withContext(Dispatchers.IO) {
            runCatching { DatabaseVault.autoExportStatus(context.applicationContext) }.getOrNull()
        }
        loaded = true
    }

    val ui = homeAutoExportUiStatus(status)
    val semanticLabel = stringResource(
        if (ui.healthy) R.string.home_autoexport_status_ok else R.string.home_autoexport_status_problem,
    )
    TextButton(
        onClick = { detailsOpen = true },
        modifier = Modifier.semantics { contentDescription = semanticLabel },
    ) {
        Text(if (!loaded) "…" else if (ui.healthy) "✅" else "❌")
    }

    if (detailsOpen) {
        val yes = stringResource(R.string.home_autoexport_yes)
        val no = stringResource(R.string.home_autoexport_no)
        val never = stringResource(R.string.home_autoexport_never)
        val none = stringResource(R.string.home_autoexport_no_error)
        val lastSuccess = if (ui.lastSuccessfulExportAt > 0) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(ui.lastSuccessfulExportAt))
        } else {
            never
        }
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text(stringResource(R.string.home_autoexport_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(semanticLabel)
                    Text(stringResource(R.string.home_autoexport_folder, if (ui.folderConfigured) yes else no))
                    Text(stringResource(R.string.home_autoexport_generation, ui.currentGeneration, ui.exportedGeneration))
                    Text(stringResource(R.string.home_autoexport_stale, if (ui.stale) yes else no))
                    Text(stringResource(R.string.home_autoexport_last_success, lastSuccess))
                    Text(stringResource(R.string.home_autoexport_error, ui.lastError?.takeIf { it.isNotBlank() } ?: none))
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsOpen = false }) {
                    Text(stringResource(R.string.home_autoexport_close))
                }
            },
        )
    }
}
