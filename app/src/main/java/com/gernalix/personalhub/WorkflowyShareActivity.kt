package com.gernalix.personalhub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import com.gernalix.personalhub.core.hubcontext.HubContextComposerScreen
import com.gernalix.personalhub.core.hubcontext.WorkflowyHubBridge
import com.gernalix.personalhub.core.hubcontext.WorkflowyIntegrationSettings
import com.gernalix.personalhub.core.hubcontext.WorkflowyLinkPolicy
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import kotlinx.coroutines.launch

class WorkflowyShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        val sharedText = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        val workflowyUrl = WorkflowyLinkPolicy.extractFromSharedText(sharedText)
        enableEdgeToEdge()
        setContent {
            PersonalHubTheme {
                Surface(Modifier.fillMaxSize()) {
                    WorkflowyShareEntry(
                        enabled = WorkflowyIntegrationSettings.isEnabled(this),
                        workflowyUrl = workflowyUrl,
                        onDone = ::finish,
                    )
                }
            }
        }
    }

    companion object {
        fun setEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, WorkflowyShareActivity::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        fun syncEnabled(context: Context) {
            setEnabled(context, WorkflowyIntegrationSettings.isEnabled(context))
        }
    }
}

@Composable
private fun WorkflowyShareEntry(
    enabled: Boolean,
    workflowyUrl: String?,
    onDone: () -> Unit,
) {
    if (!enabled) {
        WorkflowyShareMessage(R.string.workflowy_share_disabled, onDone)
        return
    }
    if (workflowyUrl == null) {
        WorkflowyShareMessage(R.string.workflowy_share_invalid, onDone)
        return
    }

    var resourceId by rememberSaveable(workflowyUrl) { mutableStateOf<String?>(null) }
    var failed by rememberSaveable(workflowyUrl) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(workflowyUrl, resourceId) {
        if (resourceId != null) return@LaunchedEffect
        runCatching {
            WorkflowyHubBridge.createDetachedResource(workflowyUrl)
        }.onSuccess {
            resourceId = it.ref.canonicalId
        }.onFailure {
            failed = true
        }
    }

    val id = resourceId
    when {
        failed -> WorkflowyShareMessage(R.string.workflowy_share_invalid, onDone)
        id == null -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            val anchor = HubEntityRef("hub", "resource", id)
            HubContextComposerScreen(
                anchor = anchor,
                onBack = {
                    scope.launch {
                        WorkflowyHubBridge.deleteDetachedResource(anchor)
                        onDone()
                    }
                },
                onSaved = onDone,
            )
        }
    }
}

@Composable
private fun WorkflowyShareMessage(messageRes: Int, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.workflowy_share_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(messageRes))
        OutlinedButton(onClick = onDone) { Text(stringResource(R.string.settings_back)) }
    }
}
