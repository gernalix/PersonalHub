package com.gernalix.personalhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.migration.MigrationMappingStore
import com.gernalix.personalhub.core.migration.PersonalHubLocalMigrationRunner
import com.gernalix.personalhub.core.model.SourceApp
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MigrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PersonalHubTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MigrationScreen(
                        store = MigrationMappingStore(this),
                        runner = PersonalHubLocalMigrationRunner(this),
                    )
                }
            }
        }
    }
}

@Composable
private fun MigrationScreen(
    store: MigrationMappingStore,
    runner: PersonalHubLocalMigrationRunner,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val integrity = store.integrityCheck()
    var runSummary by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.module_migration),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.migration_sources, SourceApp.entries.size),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.migration_integrity, integrity),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = !isRunning,
            onClick = {
                isRunning = true
                runSummary = context.getString(R.string.migration_run_running)
                scope.launch {
                    runSummary = withContext(Dispatchers.IO) {
                        runCatching { runner.run() }
                            .fold(
                                onSuccess = { result ->
                                    val report = result.report
                                    context.getString(
                                        R.string.migration_run_passed,
                                        report.sourceCount,
                                        report.mappingCount,
                                        result.mappingDatabaseIntegrity,
                                    )
                                },
                                onFailure = { error ->
                                    context.getString(R.string.migration_run_failed, error.message.orEmpty())
                                },
                            )
                    }
                    isRunning = false
                }
            },
        ) {
            Text(text = stringResource(R.string.migration_run_action))
        }
        runSummary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
