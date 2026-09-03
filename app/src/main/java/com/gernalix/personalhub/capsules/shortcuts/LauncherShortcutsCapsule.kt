package com.gernalix.personalhub.capsules.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.R

private const val SHORTCUT_SCHEME = "personalhub"
private const val SHORTCUT_HOST = "module"
private const val SHORTCUT_URI_PREFIX = "$SHORTCUT_SCHEME://$SHORTCUT_HOST"
private const val MAIN_ACTIVITY_CLASS_NAME = "com.gernalix.personalhub.MainActivity"

enum class HubModule(
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
    val activityClassName: String,
    val shortcutPath: String,
    val shortcutIconRes: Int,
) {
    PEOPLE(
        titleRes = R.string.module_people,
        subtitleRes = R.string.module_people_subtitle,
        activityClassName = "com.supercontacts.app.MainActivity",
        shortcutPath = "people",
        shortcutIconRes = R.drawable.ic_shortcut_people,
    ),
    TIMER(
        titleRes = R.string.module_timer,
        subtitleRes = R.string.module_timer_subtitle,
        activityClassName = "com.example.multitimetracker.MainActivity",
        shortcutPath = "timer",
        shortcutIconRes = R.drawable.ic_shortcut_timer,
    ),
    PLACES(
        titleRes = R.string.module_places,
        subtitleRes = R.string.module_places_subtitle,
        activityClassName = "com.gernalix.luoghi.MainActivity",
        shortcutPath = "places",
        shortcutIconRes = R.drawable.ic_shortcut_places,
    ),
    SUBSTANCES(
        titleRes = R.string.module_substances,
        subtitleRes = R.string.module_substances_subtitle,
        activityClassName = "com.gernalix.sostanze.MainActivity",
        shortcutPath = "substances",
        shortcutIconRes = R.drawable.ic_shortcut_substances,
    ),
    WORDPULSE(
        titleRes = R.string.module_wordpulse,
        subtitleRes = R.string.module_wordpulse_subtitle,
        activityClassName = "com.wordpulse.app.MainActivity",
        shortcutPath = "wordpulse",
        shortcutIconRes = R.drawable.ic_shortcut_wordpulse,
    ),
    ;

    val shortcutUri: String
        get() = "$SHORTCUT_URI_PREFIX/$shortcutPath"

    val pinnedShortcutId: String
        get() = "home_$shortcutPath"

    companion object {
        fun fromShortcutIntent(intent: Intent?): HubModule? {
            val data = intent?.data ?: return null
            return fromShortcutParts(intent.action, data.scheme, data.host, data.pathSegments)
        }

        fun fromShortcutParts(
            action: String?,
            scheme: String?,
            host: String?,
            pathSegments: List<String>,
        ): HubModule? {
            if (action != Intent.ACTION_VIEW || scheme != SHORTCUT_SCHEME || host != SHORTCUT_HOST) return null
            val shortcutPath = pathSegments.singleOrNull() ?: return null
            return entries.firstOrNull { it.shortcutPath == shortcutPath }
        }
    }
}

sealed interface PinShortcutResult {
    data object Unsupported : PinShortcutResult
    data object AlreadyPinned : PinShortcutResult
    data object Requested : PinShortcutResult
    data object Rejected : PinShortcutResult
}

object LauncherShortcutsCapsule {
    fun requestPinShortcut(context: Context, module: HubModule): PinShortcutResult {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return PinShortcutResult.Unsupported
        if (!shortcutManager.isRequestPinShortcutSupported) return PinShortcutResult.Unsupported
        if (shortcutManager.pinnedShortcuts.any { it.id == module.pinnedShortcutId || it.id == module.shortcutPath }) {
            return PinShortcutResult.AlreadyPinned
        }

        val shortcut = ShortcutInfo.Builder(context, module.pinnedShortcutId)
            .setShortLabel(context.getString(module.titleRes))
            .setLongLabel(context.getString(module.titleRes))
            .setIcon(Icon.createWithResource(context, module.shortcutIconRes))
            .setIntent(moduleIntent(context, module))
            .build()
        return if (shortcutManager.requestPinShortcut(shortcut, null)) {
            PinShortcutResult.Requested
        } else {
            PinShortcutResult.Rejected
        }
    }

    fun isPinned(context: Context, module: HubModule): Boolean =
        context.getSystemService(ShortcutManager::class.java)
            ?.pinnedShortcuts
            ?.any { it.id == module.pinnedShortcutId || it.id == module.shortcutPath }
            ?: false

    fun moduleIntent(context: Context, module: HubModule): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(module.shortcutUri))
            .setClassName(context.packageName, MAIN_ACTIVITY_CLASS_NAME)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

@Composable
fun HomeShortcutsSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var messageRes by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack) {
            Text(stringResource(R.string.settings_back))
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.home_shortcuts_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.home_shortcuts_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        messageRes?.let { message ->
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(HubModule.entries, key = { it.pinnedShortcutId }) { module ->
                // Read again after a launcher confirmation or a return to this screen.
                val isPinned = remember(context, module, refresh) {
                    LauncherShortcutsCapsule.isPinned(context, module)
                }
                HomeShortcutRow(
                    module = module,
                    isPinned = isPinned,
                    onRequestPin = {
                        messageRes = when (LauncherShortcutsCapsule.requestPinShortcut(context, module)) {
                            PinShortcutResult.Unsupported -> R.string.home_shortcuts_unsupported
                            PinShortcutResult.AlreadyPinned -> R.string.home_shortcuts_already_added
                            PinShortcutResult.Requested -> R.string.home_shortcuts_confirmation_requested
                            PinShortcutResult.Rejected -> R.string.home_shortcuts_request_rejected
                        }
                        refresh += 1
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeShortcutRow(
    module: HubModule,
    isPinned: Boolean,
    onRequestPin: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(module.shortcutIconRes),
                contentDescription = null,
                modifier = Modifier.padding(2.dp),
                tint = androidx.compose.ui.graphics.Color.Unspecified,
            )
            Text(
                text = stringResource(module.titleRes),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            if (isPinned) {
                Text(
                    text = stringResource(R.string.home_shortcuts_added),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Button(onClick = onRequestPin) {
                    Text(stringResource(R.string.home_shortcuts_add))
                }
            }
        }
    }
}
