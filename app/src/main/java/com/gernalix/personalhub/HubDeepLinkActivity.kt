@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package com.gernalix.personalhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.HubContextView
import com.gernalix.personalhub.core.ui.HubLoadingPane
import com.gernalix.personalhub.core.ui.HubMessagePane
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime

/** Exported, read-only router for PersonalHub v1 permalinks. */
class HubDeepLinkActivity : ComponentActivity() {
    private var currentUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        currentUri = intent?.data
        enableEdgeToEdge()
        setContent {
            PersonalHubTheme {
                Surface(Modifier.fillMaxSize()) {
                    HubDeepLinkEntry(currentUri, onBack = ::finish)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentUri = intent.data
    }
}

@Composable
private fun HubDeepLinkEntry(uri: Uri?, onBack: () -> Unit) {
    val parsed = remember(uri) { HubDeepLinkContract.parse(uri) }
    when (val target = parsed.target) {
        is HubDeepLinkContract.EntityTarget -> EntityDeepLink(target, onBack)
        is HubDeepLinkContract.ContextTarget -> ContextDeepLink(target.contextId, onBack)
        is HubDeepLinkContract.EventTarget -> HubActivityRegisterScreen(onBack = onBack, initialEventId = target.eventId)
        is HubDeepLinkContract.SearchTarget -> SearchDeepLink(target, onBack)
        null -> DeepLinkErrorScreen(
            messageRes = when (parsed.error) {
                HubDeepLinkContract.ParseError.UNSUPPORTED_VERSION -> R.string.deep_link_unsupported_version
                HubDeepLinkContract.ParseError.UNSUPPORTED_ACTION -> R.string.deep_link_unsupported_action
                else -> R.string.deep_link_invalid
            },
            onBack = onBack,
        )
    }
}

@Composable
private fun EntityDeepLink(target: HubDeepLinkContract.EntityTarget, onBack: () -> Unit) {
    val context = LocalContext.current
    var errorRes by remember(target) { mutableStateOf<Int?>(null) }
    LaunchedEffect(target) {
        val launch = resolveEntityIntent(context, target.ref, target.action)
        if (launch == null) {
            errorRes = R.string.deep_link_entity_unavailable
            return@LaunchedEffect
        }
        if (runCatching { context.startActivity(launch) }.isSuccess) onBack()
        else errorRes = R.string.deep_link_cannot_open
    }
    errorRes?.let { DeepLinkErrorScreen(it, onBack) } ?: LoadingScreen()
}

@Composable
private fun SearchDeepLink(target: HubDeepLinkContract.SearchTarget, onBack: () -> Unit) {
    val parsedRange = remember(target) {
        runCatching {
            val from = target.fromIso?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            val to = target.toIso?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            require(from == null || to == null || from <= to)
            from to to
        }
    }
    if (parsedRange.isFailure) {
        DeepLinkErrorScreen(R.string.deep_link_invalid_time_range, onBack)
        return
    }
    val (from, to) = parsedRange.getOrThrow()
    HubTemporalSearchScreen(
        onBack = onBack,
        initialFromMs = from,
        initialToMs = to,
        initialModules = target.modules.toSet(),
        autoSearch = target.fromIso != null || target.toIso != null || target.modules.isNotEmpty(),
    )
}

@Composable
private fun ContextDeepLink(contextId: String, onBack: () -> Unit) {
    val androidContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember(contextId) { mutableStateOf(false) }
    var view by remember(contextId) { mutableStateOf<HubContextView?>(null) }

    LaunchedEffect(contextId) {
        view = withContext(Dispatchers.IO) { HubContextRuntime.context(contextId) }
        loaded = true
    }

    if (!loaded) {
        LoadingScreen()
        return
    }
    val resolved = view
    if (resolved == null) {
        DeepLinkErrorScreen(R.string.deep_link_context_unavailable, onBack)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.deep_link_back)) }
            Text(
                text = resolved.context.title ?: stringResource(R.string.deep_link_context_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedButton(onClick = {
                copyText(
                    androidContext,
                    androidContext.getString(R.string.deep_link_context_title),
                    HubDeepLinkContract.contextUri(contextId).toString(),
                )
            }) { Text(stringResource(R.string.deep_link_copy)) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(resolved.members, key = { "${it.ref.moduleId}/${it.ref.entityKind}/${it.ref.canonicalId}" }) { summary ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                val intent = resolveEntityIntent(androidContext, summary.ref, HubDeepLinkContract.ACTION_VIEW)
                                if (intent == null || runCatching { androidContext.startActivity(intent) }.isFailure) {
                                    Toast.makeText(androidContext, R.string.deep_link_cannot_open, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(summary.label, style = MaterialTheme.typography.titleMedium)
                        summary.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

private suspend fun resolveEntityIntent(
    context: Context,
    ref: HubEntityRef,
    action: String,
): Intent? = withContext(Dispatchers.IO) {
    val adapter = runCatching {
        HubContextRuntime.adapters().firstOrNull { it.moduleId == ref.moduleId && it.entityKind == ref.entityKind }
    }.getOrNull() ?: return@withContext null
    if (!runCatching { adapter.exists(ref.canonicalId) }.getOrDefault(false)) return@withContext null
    val target = runCatching { adapter.openTarget(ref.canonicalId) }.getOrNull() ?: return@withContext null
    Intent(
        if (action == HubDeepLinkContract.ACTION_EDIT) Intent.ACTION_EDIT else Intent.ACTION_VIEW,
        Uri.parse(target.uri),
    ).apply {
        putExtra(HubDeepLinkContract.EXTRA_ACTION, action)
        target.activityClassName?.let { setClassName(context.packageName, it) }
    }
}

@Composable
private fun LoadingScreen() {
    HubLoadingPane()
}

@Composable
private fun DeepLinkErrorScreen(messageRes: Int, onBack: () -> Unit) {
    HubMessagePane(
        message = stringResource(messageRes),
        actionLabel = stringResource(R.string.deep_link_back),
        onAction = onBack,
        modifier = Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing),
    )
}

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, R.string.deep_link_copied, Toast.LENGTH_SHORT).show()
}
