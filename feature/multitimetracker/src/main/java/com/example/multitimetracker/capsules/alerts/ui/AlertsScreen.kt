package com.example.multitimetracker.capsules.alerts.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.capsules.alerts.core.RandomAlertWindow
import com.example.multitimetracker.capsules.alerts.state.AlertsUiState
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger

/**
 * Timer keeps this entry point for navigation compatibility, while PersonalHub owns the single
 * alert-management UI for Timer and Places.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AlertsScreen(
    modifier: Modifier = Modifier,
    state: AlertsUiState,
    onAddTimeFenceRule: (String, TimeFenceTrigger, TimeFenceScope, TimeFenceMatchMode, Set<Long>, Long, TimeFenceDelivery, Boolean, Int, RandomAlertWindow) -> Unit,
    onUpdateTimeFenceRule: (Long, String, TimeFenceTrigger, TimeFenceScope, TimeFenceMatchMode, Set<Long>, Long, TimeFenceDelivery, Boolean, Int, RandomAlertWindow) -> Unit,
    onDeleteTimeFenceRule: (Long) -> Unit,
    onRestoreTimeFenceRule: (Long) -> Unit,
    onPurgeTimeFenceRule: (Long) -> Unit,
    onPurgeAllDeletedTimeFenceRules: () -> Unit,
    onSetTimeFenceRuleEnabled: (Long, Boolean) -> Unit,
) {
    val context = LocalContext.current
    fun openUnifiedAlerts() {
        val uri = Uri.Builder()
            .scheme("personalhub")
            .authority("alerts")
            .appendQueryParameter("domain", "TIMER")
            .build()
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).setPackage(context.packageName)
        )
    }

    LaunchedEffect(Unit) { openUnifiedAlerts() }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Alerts are managed from PersonalHub.")
        Button(onClick = ::openUnifiedAlerts) { Text("Open Alerts") }
    }
}
