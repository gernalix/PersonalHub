package com.gernalix.personalhub

import androidx.core.content.edit
import androidx.compose.runtime.mutableIntStateOf
import com.gernalix.personalhub.notifications.api.NotificationsRoute
import android.content.Intent
import android.os.Bundle
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import com.gernalix.personalhub.capsules.settings.HubSettings
import com.gernalix.personalhub.core.database.DatabaseProfiles
import com.gernalix.personalhub.capsules.shortcuts.HubModule
import com.gernalix.personalhub.capsules.shortcuts.LauncherShortcutsCapsule
import com.gernalix.personalhub.core.hubcontext.HubContextComposerScreen
import com.gernalix.personalhub.core.alerts.UnifiedAlertsRoute
import com.gernalix.personalhub.core.alerts.AlertText

private val LocalCompactHome = staticCompositionLocalOf { false }

class MainActivity : ComponentActivity() {
    private var launcherGeneration by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
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
    val resources = LocalResources.current
    val homePreferences = remember(context) { context.getSharedPreferences("home_layout", android.content.Context.MODE_PRIVATE) }
    var homeQuery by rememberSaveable { mutableStateOf("") }
    var compact by rememberSaveable { mutableStateOf(homePreferences.getBoolean("compact", false)) }
    var showSettings by rememberSaveable(launcherGeneration) { mutableStateOf(false) }
    var topDestination by rememberSaveable(launcherGeneration) { mutableStateOf<String?>(null) }
    BackHandler(enabled = topDestination != null) { topDestination = null }
    if (topDestination == "composer") {
        com.gernalix.personalhub.core.ui.ModuleHistoryHost("hub") {
            HubContextComposerScreen(onBack = { topDestination = null })
        }
        return
    }
    if (topDestination == "since_when" || topDestination == "countdown") {
        val mode = if (topDestination == "countdown") com.gernalix.personalhub.contracts.database.CounterModes.COUNTDOWN else com.gernalix.personalhub.contracts.database.CounterModes.SINCE_WHEN
        com.gernalix.personalhub.core.ui.ModuleHistoryHost(requireNotNull(topDestination)) {
            SinceWhenScreen(onBack = { topDestination = null }, mode = mode)
        }
        return
    }
    if (topDestination == "history") {
        HubHistorySearchScreen(onBack = { topDestination = null })
        return
    }
    if (topDestination == "audit") {
        HubAuditLogScreen(onBack = { topDestination = null })
        return
    }
    if (showSettings) {
        com.gernalix.personalhub.core.ui.ModuleHistoryHost("settings") {
            HubSettings(onBack = { showSettings = false })
        }
        return
    }
    CompositionLocalProvider(LocalCompactHome provides compact) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.home_active_profile, DatabaseProfiles.active(context).name),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { compact = !compact; homePreferences.edit { putBoolean("compact", compact) } },
                    modifier = Modifier.testTag("home-layout-switch")) {
                    Icon(Icons.Filled.ViewModule, contentDescription = stringResource(if (compact)
                        com.gernalix.personalhub.core.ui.R.string.home_layout_extended else com.gernalix.personalhub.core.ui.R.string.home_layout_compact))
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                }
            }
            com.gernalix.personalhub.core.ui.HubSearchField(
                value = homeQuery, onValueChange = { homeQuery = it }, showSearchIcon = true,
                label = AlertText.get(context, R.string.home_find_action, R.string.home_find_action_it), modifier = Modifier.testTag("home-search"),
            )
            fun matches(title: String, subtitle: String = "") = homeQuery.isBlank() ||
                "$title $subtitle".contains(homeQuery.trim(), ignoreCase = true)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (compact) 100.dp else 156.dp),
                modifier = Modifier.weight(1f).testTag("home-grid"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(HubModule.entries.filter { matches(resources.getString(it.titleRes), resources.getString(it.subtitleRes)) }, key = { it.name }) { module ->
                    ModuleTile(
                        module = module,
                        onClick = {
                            context.startActivity(
                                LauncherShortcutsCapsule.moduleIntent(context, module),
                            )
                        },
                    )
                }
                if (matches(resources.getString(R.string.home_manual_check_in))) item(key = "home-manual-check-in") {
                    HomeActionTile("📍", R.string.home_manual_check_in, R.string.home_manual_check_in_help) {
                        context.startActivity(com.gernalix.luoghi.hub.PlacesManualCheckInRoute.intent(context))
                    }
                }
                if (matches(resources.getString(R.string.home_context))) item(key = "home-context") {
                    HomeActionTile("🔗", R.string.home_context, R.string.home_context_help) { topDestination = "composer" }
                }
                if (matches(resources.getString(R.string.home_tags))) item(key = "home-tags") {
                    HomeActionTile("🏷️", R.string.home_tags, R.string.home_tags_help) {
                        context.startActivity(Intent(context, HubTagsActivity::class.java))
                    }
                }
                if (matches(AlertText.get(context, R.string.home_alerts, R.string.home_alerts_it))) item(key = "home-alerts") {
                    HomeActionTile(
                        "🔔",
                        AlertText.get(context, R.string.home_alerts, R.string.home_alerts_it),
                        AlertText.get(context, R.string.home_alerts_help, R.string.home_alerts_help_it),
                    ) {
                        context.startActivity(UnifiedAlertsRoute.intent(context))
                    }
                }
                if (matches(AlertText.get(context, R.string.home_notifications, R.string.home_notifications_it))) item(key = "home-notifications") {
                    HomeActionTile("📥", AlertText.get(context, R.string.home_notifications, R.string.home_notifications_it), AlertText.get(context, R.string.home_notifications_help, R.string.home_notifications_help_it)) {
                        context.startActivity(NotificationsRoute.intent(context))
                    }
                }
                if (matches(resources.getString(R.string.home_history_search))) item(key = "home-history") {
                    HomeActionTile("📜", R.string.home_history_search, R.string.home_history_search_help) { topDestination = "history" }
                }
                if (matches(resources.getString(R.string.since_when_title))) item(key = "home-since-when") {
                    HomeActionTile("⏱️", R.string.since_when_title, R.string.since_when_home_help) { topDestination = "since_when" }
                }
                if (matches(resources.getString(com.gernalix.personalhub.core.ui.R.string.countdown_title))) item(key = "home-countdown") {
                    HomeActionTile("⏳", com.gernalix.personalhub.core.ui.R.string.countdown_title, com.gernalix.personalhub.core.ui.R.string.countdown_help) { topDestination = "countdown" }
                }
                if (matches(resources.getString(R.string.settings_title))) item(key = "home-settings") {
                    HomeActionTile("⚙️", R.string.settings_title, R.string.home_settings_help) { showSettings = true }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { topDestination = "audit" }, modifier = Modifier.testTag("home-audit-button")) {
                    Text(stringResource(R.string.audit_title))
                }
                HomeAutoExportStatusIndicator()
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Text(
                    text = BuildConfig.NV.toString(),
                    modifier = Modifier,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeActionTile(icon: String, titleRes: Int, subtitleRes: Int, onClick: () -> Unit) {
    HomeActionTile(icon, stringResource(titleRes), stringResource(subtitleRes), onClick)
}

@Composable
private fun HomeActionTile(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    if (LocalCompactHome.current) {
        CompactHomeTile(title, onClick) { Text(icon, style = MaterialTheme.typography.headlineSmall) }
        return
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 126.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleTile(module: HubModule, onClick: () -> Unit) {
    if (LocalCompactHome.current) {
        CompactHomeTile(stringResource(module.titleRes), onClick) {
            Icon(painterResource(module.shortcutIconRes), contentDescription = null, tint = androidx.compose.ui.graphics.Color.Unspecified)
        }
        return
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 126.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(module.shortcutIconRes),
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(module.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
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

@Composable
private fun CompactHomeTile(title: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
        .semantics(mergeDescendants = true) { contentDescription = title }, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
