package com.gernalix.personalhub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import com.gernalix.personalhub.capsules.settings.HubSettings
import com.gernalix.personalhub.capsules.settings.GitHistorySettings
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSettings
import com.gernalix.personalhub.core.database.DatabaseProfiles
import com.gernalix.personalhub.capsules.shortcuts.HubModule
import com.gernalix.personalhub.capsules.shortcuts.LauncherShortcutsCapsule
import com.gernalix.personalhub.core.hubcontext.HubContextComposerScreen

class MainActivity : ComponentActivity() {
    private var launcherGeneration by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PersonalHubTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PersonalHubApp(launcherGeneration = launcherGeneration)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.isPersonalHubLauncherIntent()) {
            launcherGeneration += 1
        }
    }
}

internal fun Intent.isPersonalHubLauncherIntent(): Boolean =
    action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_LAUNCHER)

@Composable
fun PersonalHubApp(launcherGeneration: Int = 0) {
    val context = LocalContext.current
    var showSettings by rememberSaveable(launcherGeneration) { mutableStateOf(false) }
    var topDestination by rememberSaveable(launcherGeneration) { mutableStateOf<String?>(null) }
    BackHandler(enabled = topDestination != null) { topDestination = null }
    if (topDestination == "composer") {
        HubContextComposerScreen(onBack = { topDestination = null })
        return
    }
    if (topDestination == "search") {
        HubTemporalSearchScreen(onBack = { topDestination = null })
        return
    }
    if (topDestination == "activity") {
        val gitHistoryEnabled = runCatching {
            GitDataSettings.configuration(context).enabled
        }.getOrDefault(false)
        if (gitHistoryEnabled) {
            GitHistorySettings(onBack = { topDestination = null })
        } else {
            HubActivityRegisterScreen(onBack = { topDestination = null })
        }
        return
    }
    if (showSettings) {
        HubSettings(onBack = { showSettings = false })
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(
                R.string.home_active_profile,
                DatabaseProfiles.active(context).name,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { HomeUtilityButton(R.string.home_context, R.string.home_context_help) { topDestination = "composer" } }
                item {
                    OutlinedButton(onClick = { context.startActivity(Intent(context, HubTagsActivity::class.java)) }) {
                        Text("Tags")
                    }
                }
                item { HomeUtilityButton(R.string.home_search, R.string.home_search_help) { topDestination = "search" } }
                item { HomeUtilityButton(R.string.home_activity_register, R.string.home_activity_help) { topDestination = "activity" } }
                item {
                    HomeUtilityButton(R.string.home_data_explorer, R.string.home_data_explorer_help) {
                        context.startActivity(Intent(context, DataExplorerActivity::class.java))
                    }
                }
                item { OutlinedButton(onClick = { showSettings = true }) { Text(stringResource(R.string.settings_title)) } }
            }
            HomeAutoExportStatusIndicator()
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 240.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(HubModule.entries, key = { it.name }) { module ->
                ModuleTile(
                    module = module,
                    onClick = {
                        context.startActivity(
                            LauncherShortcutsCapsule.moduleIntent(context, module),
                        )
                    },
                )
            }
        }
        Text(
            text = BuildConfig.VERSION_NAME,
            modifier = Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeUtilityButton(titleRes: Int, helpRes: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Column {
            Text(stringResource(titleRes))
            Text(
                stringResource(helpRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleTile(module: HubModule, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                painter = painterResource(module.shortcutIconRes),
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(module.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(module.subtitleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
