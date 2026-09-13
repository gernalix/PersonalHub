package com.wordpulse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wordpulse.app.data.HealthConnectAvailability
import com.wordpulse.app.data.SleepIntegrationState
import com.wordpulse.app.domain.FatigueDomainScores
import com.wordpulse.app.domain.PvtSummary
import com.wordpulse.app.domain.PvtTestState
import com.wordpulse.app.domain.TypingDeviationLevel
import com.wordpulse.app.domain.TypingPerformanceEvaluation
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlertnessOverlayHost(
    latestPerformance: TypingPerformanceEvaluation?,
    sleepIntegration: SleepIntegrationState,
    pvtTest: PvtTestState,
    latestPvtSummary: PvtSummary?,
    onRequestSleepPermission: () -> Unit,
    onRefreshSleep: () -> Unit,
    onStartPvt: () -> Unit,
    onPvtTap: () -> Unit,
    onCancelPvt: () -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        ExtendedFloatingActionButton(
            onClick = { open = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 86.dp),
        ) {
            val score = latestPerformance?.alertnessScore?.let { " $it" }.orEmpty()
            Text("Alertness$score")
        }
    }

    if (open) {
        Dialog(
            onDismissRequest = { open = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.90f),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Alertness & fatigue",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { open = false }) {
                            Text("Close")
                        }
                    }
                    Text(
                        text = "Personal behavioral estimate from typing. It is not a medical measurement or diagnosis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    AlertnessScoreBlock(latestPerformance)
                    HorizontalDivider()
                    SleepContextBlock(
                        state = sleepIntegration,
                        onRequestPermission = onRequestSleepPermission,
                        onRefresh = onRefreshSleep,
                    )
                    HorizontalDivider()
                    PvtBlock(
                        state = pvtTest,
                        latest = latestPvtSummary,
                        onStart = onStartPvt,
                        onTap = onPvtTap,
                        onCancel = onCancelPvt,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertnessScoreBlock(evaluation: TypingPerformanceEvaluation?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Current estimate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when {
            evaluation == null -> {
                Text("Type normally to produce a current estimate.")
            }
            evaluation.fatigueScore == null -> {
                val needed = (12 - evaluation.baselineSampleCount).coerceAtLeast(0)
                Text(
                    if (evaluation.level == TypingDeviationLevel.InsufficientData) {
                        "Building your personal baseline: ${evaluation.baselineSampleCount}/12 comparable historical samples. " +
                            if (needed > 0) "$needed more needed." else ""
                    } else {
                        "Current score unavailable for this sample."
                    },
                )
            }
            else -> {
                val alertness = evaluation.alertnessScore ?: 0
                Text(
                    text = "$alertness / 100 alertness",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                LinearProgressIndicator(
                    progress = { alertness / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Fatigue index ${evaluation.fatigueScore}/100",
                    style = MaterialTheme.typography.bodyMedium,
                )
                DomainScores(evaluation.domains)
                val context = evaluation.context
                context.hoursAwake?.let { MetricRow("Time awake", "${it.format1()} h") }
                context.lastSleepDurationHours?.let { MetricRow("Last sleep", "${it.format1()} h") }
                context.sleepDeficitHours?.let { deficit ->
                    MetricRow("Sleep below your recent median", "${deficit.format1()} h")
                }
                context.localHour?.let { MetricRow("Local hour", "%02d:00".format(it)) }
                MetricRow(
                    "Time-of-day baseline",
                    if (context.contextualBaselineUsed) "matched to ±2 h" else "general personal baseline",
                )
                if (evaluation.rawFatigueScore != evaluation.fatigueScore) {
                    MetricRow("PVT calibration", "applied")
                }
            }
        }
    }
}

@Composable
private fun DomainScores(domains: FatigueDomainScores) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Fatigue domains", style = MaterialTheme.typography.labelLarge)
        MetricRow("Speed", domains.speed.scoreText())
        MetricRow("Rhythm / lapses", domains.rhythm.scoreText())
        MetricRow("Session drift", domains.sessionDrift.scoreText())
        MetricRow("Sleep context", domains.sleepContext.scoreText())
        MetricRow("Corrections / control", domains.control.scoreText())
    }
}

@Composable
private fun SleepContextBlock(
    state: SleepIntegrationState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sleep context", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when (state.availability) {
            HealthConnectAvailability.Available -> {
                if (state.permissionGranted) {
                    val context = state.sleepContext
                    if (context == null) {
                        Text("Health Connect is connected, but no completed sleep session was found in the recent window.")
                    } else {
                        Text("Health Connect sleep data is active and used only on-device.")
                        MetricRow(
                            "Last sleep duration",
                            "${(context.lastSleepDurationMs / 3_600_000.0).format1()} h",
                        )
                        context.personalMedianSleepDurationMs?.let {
                            MetricRow("Recent median sleep", "${(it / 3_600_000.0).format1()} h")
                        }
                    }
                    Button(onClick = onRefresh) {
                        Text("Refresh sleep data")
                    }
                } else {
                    Text("Connect Health Connect to include time awake and recent sleep duration in the estimate.")
                    Button(onClick = onRequestPermission) {
                        Text("Connect sleep data")
                    }
                }
            }
            HealthConnectAvailability.ProviderUpdateRequired ->
                Text("Health Connect needs to be installed or updated before sleep context can be used.")
            HealthConnectAvailability.Unavailable ->
                Text("Health Connect is unavailable on this device. Typing-only scoring remains active.")
        }
        state.error?.let {
            Text("Sleep data error: $it", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PvtBlock(
    state: PvtTestState,
    latest: PvtSummary?,
    onStart: () -> Unit,
    onTap: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("3-minute vigilance calibration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Run this occasionally. Tap as soon as the stimulus appears; early taps are counted as false starts. " +
                "After enough paired tests, WordPulse uses the results to personalize the typing score.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.active) {
            Button(
                onClick = onTap,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.stimulusVisible) "TAP NOW" else "Wait…")
            }
            MetricRow("Trials", state.trialsCompleted.toString())
            MetricRow("Median reaction", state.medianReactionTimeMs.msText())
            MetricRow("Lapses ≥500 ms", state.lapseCount.toString())
            MetricRow("False starts", state.falseStartCount.toString())
            TextButton(onClick = onCancel) {
                Text("Cancel test")
            }
        } else {
            Button(onClick = onStart) {
                Text("Start 3-minute test")
            }
            latest?.let {
                Text("Latest saved test", style = MaterialTheme.typography.labelLarge)
                MetricRow("Median reaction", it.medianReactionTimeMs.msText())
                MetricRow("P90 reaction", it.p90ReactionTimeMs.msText())
                MetricRow("Lapses", it.lapseCount.toString())
                MetricRow("False starts", it.falseStartCount.toString())
                MetricRow("Paired fatigue score", it.pairedFatigueScore?.toString() ?: "not available")
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun Int?.scoreText(): String = this?.let { "$it/100" } ?: "—"

private fun Double?.msText(): String = this?.let { "${it.format1()} ms" } ?: "—"

private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)
