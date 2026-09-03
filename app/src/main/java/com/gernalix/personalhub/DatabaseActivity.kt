package com.gernalix.personalhub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DatabaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PersonalHubTheme { TransferScreen() } }
    }

    private fun restartGraph(rolledBack: Boolean) {
        startActivity(Intent(this, DatabaseRestartActivity::class.java).putExtra("rolled_back", rolledBack).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    @Composable private fun TransferScreen() {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var folder by remember { mutableStateOf(DatabaseVault.folder(this)) }
        var lastExport by remember { mutableLongStateOf(DatabaseVault.lastExport(this)) }
        var exportError by remember { mutableStateOf(DatabaseVault.error(this)) }
        val imported = stringResource(R.string.database_imported)
        val failed = stringResource(R.string.database_failed)
        val exported = stringResource(R.string.database_exported)
        fun operation(block: suspend () -> Unit) {
            scope.launch {
                busy = true
                try { block() } catch (error: Exception) {
                    android.util.Log.e("PersonalHubTransfer", "Database transfer failed", error)
                    message = failed
                    if (error is com.gernalix.personalhub.core.database.ImportRolledBack) restartGraph(true)
                }
                finally { busy = false }
            }
        }
        val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) operation {
                withContext(Dispatchers.IO) { DatabaseVault.importDatabase(this@DatabaseActivity, uri) }
                message = imported
                // Start a fresh application graph. Frozen old writers cannot overwrite the import.
                restartGraph(false)
            }
        }
        val exportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) operation {
                withContext(Dispatchers.IO) { DatabaseVault.configureFolder(this@DatabaseActivity, uri) }
                folder = uri.toString()
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                lastExport = DatabaseVault.lastExport(this@DatabaseActivity)
                exportError = DatabaseVault.error(this@DatabaseActivity)
                delay(1000)
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.database_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.database_description))
                Button(enabled = !busy, onClick = { importFile.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.database_import)) }
                Button(enabled = !busy, onClick = { exportFolder.launch(null) }) { Text(stringResource(R.string.database_folder)) }
                Text(stringResource(if (folder == null) R.string.database_folder_missing else R.string.database_folder_ready))
                OutlinedButton(enabled = !busy && folder != null, onClick = { operation {
                    withContext(Dispatchers.IO) { DatabaseVault.exportNow(this@DatabaseActivity) }
                    message = exported
                } }) { Text(stringResource(R.string.database_export)) }
                if (lastExport > 0) Text(stringResource(R.string.database_last_export, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastExport))))
                exportError?.let { Text(failed, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it) }
                if (busy) CircularProgressIndicator()
                TextButton(onClick = { finish() }, enabled = !busy) { Text(stringResource(R.string.database_back)) }
            }
        }
    }
}
