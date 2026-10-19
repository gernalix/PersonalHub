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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val homePreferences = remember(context) { context.getSharedPreferences("home_layout", android.content.Context.MODE_PRIVATE) }
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
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                compact = !compact
                homePreferences.edit { putBoolean("compact", compact)}
            }, modifier = Modifier.testTag("home-layout-switch")) {
                Text(stringResource(if (compact) com.gernalix.personalhub.core.ui.R.string.home_layout_extended else com.gernalix.personalhub.core.ui.R.string.home_layout_compact))
            }
            TextButton(
                onClick = { topDestination = "audit" },
                modifier = Modifier.testTag("home-audit-button"),
            ) {
                Text(stringResource(R.string.audit_title))
            }
            HomeAutoExportStatusIndicator()
        }
        LazyVerticalGrid(
            columns = if (compact) GridCells.Fixed(4) else GridCells.Adaptive(minSize = 240.dp),
            modifier = Modifier.weight(1f).testTag("home-grid"),
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
            item(key = "home-context") {
                HomeActionTile("🔗", R.string.home_context, R.string.home_context_help) { topDestination = "composer" }
            }
            item(key = "home-tags") {
                HomeActionTile("🏷️", R.string.home_tags, R.string.home_tags_help) {
                    context.startActivity(Intent(context, HubTagsActivity::class.java))
                }
            }
            item(key = "home-alerts") {
                HomeActionTile(
                    "🔔",
                    AlertText.get(context, R.string.home_alerts, R.string.home_alerts_it),
                    AlertText.get(context, R.string.home_alerts_help, R.string.home_alerts_help_it),
                ) {
                    context.startActivity(UnifiedAlertsRoute.intent(context))
                }
            }
            item(key = "home-notifications") {
                HomeActionTile("📥", AlertText.get(context, R.string.home_notifications, R.string.home_notifications_it), AlertText.get(context, R.string.home_notifications_help, R.string.home_notifications_help_it)) {
                    context.startActivity(NotificationsRoute.intent(context))
                }
            }
            item(key = "home-history") {
                HomeActionTile("📜", R.string.home_history_search, R.string.home_history_search_help) { topDestination = "history" }
            }
            item(key = "home-since-when") {
                HomeActionTile("⏱️", R.string.since_when_title, R.string.since_when_home_help) { topDestination = "since_when" }
            }
            item(key = "home-countdown") {
                HomeActionTile("⏳", com.gernalix.personalhub.core.ui.R.string.countdown_title, com.gernalix.personalhub.core.ui.R.string.countdown_help) { topDestination = "countdown" }
            }
            item(key = "home-settings") {
                HomeActionTile("⚙️", R.string.settings_title, R.string.home_settings_help) { showSettings = true }
            }
        }
        Text(
            text = BuildConfig.NV.toString(),
            modifier = Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

@Composable
private fun CompactHomeTile(title: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(1f).semantics(mergeDescendants = true) { contentDescription = title }, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) { icon() }
    }
}
