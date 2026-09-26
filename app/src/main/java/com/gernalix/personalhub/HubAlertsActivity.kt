package com.gernalix.personalhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.rememberScrollState
import androidx.compose.foundation.layout.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.api.TimerAlertsApi
import com.gernalix.luoghi.api.PlacesAlertsApi
import com.gernalix.personalhub.core.alerts.AlertDomain
import com.gernalix.personalhub.core.alerts.AlertMatchMode
import com.gernalix.personalhub.core.alerts.AlertScope
import com.gernalix.personalhub.core.alerts.AlertTargetKind
import com.gernalix.personalhub.core.alerts.AlertTrigger
import com.gernalix.personalhub.core.alerts.ManagedAlertCatalog
import com.gernalix.personalhub.core.alerts.ManagedAlertDraft
import com.gernalix.personalhub.core.alerts.ManagedAlertOption
import com.gernalix.personalhub.core.alerts.ManagedAlertProvider
import com.gernalix.personalhub.core.alerts.ManagedAlertRandomWindow
import com.gernalix.personalhub.core.alerts.ManagedAlertRule
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import kotlinx.coroutines.launch

class HubAlertsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        enableEdgeToEdge()
        val initialDomain = intent.data?.getQueryParameter("domain")
            ?.let { runCatching { AlertDomain.valueOf(it) }.getOrNull() }
        val initialPlaceId = intent.data?.getQueryParameter("placeId")
        setContent {
            PersonalHubTheme {
                HubAlertsScreen(
                    initialDomain = initialDomain,
                    initialPlaceId = initialPlaceId,
                    onBack = ::finish,
                )
            }
        }
    }
}

private enum class AlertListFilter { ALL, TIMER, PLACES }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HubAlertsScreen(
    initialDomain: AlertDomain?,
    initialPlaceId: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val providers = remember(context) {
        mapOf<AlertDomain, ManagedAlertProvider>(
            AlertDomain.TIMER to TimerAlertsApi(context),
            AlertDomain.PLACE to PlacesAlertsApi(context),
        )
    }
    var catalogs by remember { mutableStateOf<Map<AlertDomain, ManagedAlertCatalog>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember(initialDomain) {
        mutableStateOf(
            when (initialDomain) {
                AlertDomain.TIMER -> AlertListFilter.TIMER
                AlertDomain.PLACE -> AlertListFilter.PLACES
                null -> AlertListFilter.ALL
            }
        )
    }
    var editing by remember { mutableStateOf<ManagedAlertRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ManagedAlertRule?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = null
        val loaded = linkedMapOf<AlertDomain, ManagedAlertCatalog>()
        providers.forEach { (domain, provider) ->
            runCatching { provider.load() }
                .onSuccess { loaded[domain] = it }
                .onFailure { error = it.message ?: "Could not load $domain alerts" }
        }
        catalogs = loaded
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    val allRules = catalogs.values
        .flatMap { it.rules }
        .filter {
            when (filter) {
                AlertListFilter.ALL -> true
                AlertListFilter.TIMER -> it.domain == AlertDomain.TIMER
                AlertListFilter.PLACES -> it.domain == AlertDomain.PLACE
            }
        }
        .sortedWith(compareBy<ManagedAlertRule>({ it.domain.name }, { it.message.lowercase() }, { it.id }))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alerts") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add alert")
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = filter == AlertListFilter.ALL,
                    onClick = { filter = AlertListFilter.ALL },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = filter == AlertListFilter.TIMER,
                    onClick = { filter = AlertListFilter.TIMER },
                    label = { Text("Timer") },
                )
                FilterChip(
                    selected = filter == AlertListFilter.PLACES,
                    onClick = { filter = AlertListFilter.PLACES },
                    label = { Text("Places") },
                )
            }
            when {
                loading -> Text("Loading alerts…")
                error != null && catalogs.isEmpty() -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                allRules.isEmpty() -> Text("No alert rules yet. Tap + to add one.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(allRules, key = { "${it.domain}:${it.id}" }) { rule ->
                        UnifiedAlertCard(
                            rule = rule,
                            onEdit = { editing = rule },
                            onDelete = { pendingDelete = rule },
                            onSetEnabled = { enabled ->
                                scope.launch {
                                    val ok = providers.getValue(rule.domain).setEnabled(rule.id, enabled)
                                    if (!ok) error = "Could not update alert"
                                    reload()
                                }
                            },
                        )
                    }
                }
            }
            error?.takeIf { catalogs.isNotEmpty() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (creating) {
        val defaultDomain = when (filter) {
            AlertListFilter.TIMER -> AlertDomain.TIMER
            AlertListFilter.PLACES -> AlertDomain.PLACE
            AlertListFilter.ALL -> initialDomain ?: AlertDomain.TIMER
        }
        UnifiedAlertEditor(
            title = "New alert",
            catalogs = catalogs,
            initial = null,
            initialDomain = defaultDomain,
            initialPlaceId = initialPlaceId.takeIf { defaultDomain == AlertDomain.PLACE },
            onDismiss = { creating = false },
            onSave = { draft ->
                scope.launch {
                    val ok = providers.getValue(draft.domain).create(draft)
                    if (ok) creating = false else error = "Alert could not be saved. Check for a duplicate or missing target."
                    reload()
                }
            },
        )
    }

    editing?.let { rule ->
        UnifiedAlertEditor(
            title = "Edit alert",
            catalogs = catalogs,
            initial = rule,
            initialDomain = rule.domain,
            initialPlaceId = null,
            onDismiss = { editing = null },
            onSave = { draft ->
                scope.launch {
                    val ok = providers.getValue(rule.domain).update(rule.id, draft)
                    if (ok) editing = null else error = "Alert could not be updated. Check for a duplicate or missing target."
                    reload()
                }
            },
        )
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete alert?") },
            text = { Text(rule.message) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = providers.getValue(rule.domain).delete(rule.id)
                        if (!ok) error = "Could not delete alert"
                        pendingDelete = null
                        reload()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun UnifiedAlertCard(
    rule: ManagedAlertRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    val domainLabel = if (rule.domain == AlertDomain.TIMER) "Timer" else "Places"
    val triggerLabel = when (rule.trigger) {
        AlertTrigger.TIMER_START -> "Start"
        AlertTrigger.TIMER_STOP -> "Stop"
        AlertTrigger.PLACE_CHECK_IN -> "Check in"
        AlertTrigger.PLACE_CHECK_OUT -> "Check out"
        AlertTrigger.PLACE_BOTH -> "Check in + out"
    }
    val targetLabel = when (rule.targetKind) {
        AlertTargetKind.ENTITY -> rule.entityLabel ?: "Specific place"
        AlertTargetKind.TAGS -> {
            val tags = rule.tagLabels.joinToString(", ")
            if (tags.isBlank()) "Tags" else {
                val mode = if (rule.matchMode == AlertMatchMode.ALL) "all" else "any"
                "$mode: $tags"
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(rule.message, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$domainLabel · $triggerLabel · $targetLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (rule.scope == AlertScope.ONE_TIME) "Next time only" else "Every time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onSetEnabled)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnifiedAlertEditor(
    title: String,
    catalogs: Map<AlertDomain, ManagedAlertCatalog>,
    initial: ManagedAlertRule?,
    initialDomain: AlertDomain,
    initialPlaceId: String?,
    onDismiss: () -> Unit,
    onSave: (ManagedAlertDraft) -> Unit,
) {
    var domain by remember(initial?.id) { mutableStateOf(initial?.domain ?: initialDomain) }
    var message by remember(initial?.id) { mutableStateOf(initial?.message.orEmpty()) }
    var trigger by remember(initial?.id, domain) {
        mutableStateOf(initial?.trigger?.takeIf { initial.domain == domain } ?: defaultTrigger(domain))
    }
    var targetKind by remember(initial?.id, domain) {
        mutableStateOf(
            initial?.targetKind?.takeIf { initial.domain == domain }
                ?: if (domain == AlertDomain.PLACE && initialPlaceId != null) AlertTargetKind.ENTITY else AlertTargetKind.TAGS
        )
    }
    var entityId by remember(initial?.id, domain) {
        mutableStateOf(initial?.entityId?.takeIf { initial.domain == domain } ?: initialPlaceId.takeIf { domain == AlertDomain.PLACE })
    }
    var selectedTagIds by remember(initial?.id, domain) {
        mutableStateOf(initial?.tagIds?.takeIf { initial.domain == domain }.orEmpty())
    }
    var matchMode by remember(initial?.id) { mutableStateOf(initial?.matchMode ?: AlertMatchMode.ALL) }
    var alertScope by remember(initial?.id) { mutableStateOf(initial?.scope ?: AlertScope.ALWAYS) }
    var cooldownEnabled by remember(initial?.id) { mutableStateOf((initial?.cooldownMs ?: 0L) > 0L) }
    var randomEnabled by remember(initial?.id) { mutableStateOf(initial?.randomAlertsEnabled ?: false) }
    var randomCount by remember(initial?.id) { mutableStateOf((initial?.randomAlertsCount ?: 1).coerceAtLeast(1).toString()) }
    var randomWindow by remember(initial?.id) { mutableStateOf(initial?.randomAlertsWindow ?: ManagedAlertRandomWindow.DAY) }
    var optionQuery by remember(initial?.id, domain, targetKind) { mutableStateOf("") }

    val catalog = catalogs[domain] ?: ManagedAlertCatalog(domain, emptyList())
    val canSave = message.trim().isNotEmpty() && when {
        domain == AlertDomain.TIMER -> selectedTagIds.isNotEmpty()
        targetKind == AlertTargetKind.ENTITY -> !entityId.isNullOrBlank()
        else -> selectedTagIds.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        ManagedAlertDraft(
                            domain = domain,
                            message = message,
                            trigger = trigger,
                            targetKind = if (domain == AlertDomain.TIMER) AlertTargetKind.TAGS else targetKind,
                            entityId = entityId.takeIf { domain == AlertDomain.PLACE && targetKind == AlertTargetKind.ENTITY },
                            tagIds = selectedTagIds.takeIf { domain == AlertDomain.TIMER || targetKind == AlertTargetKind.TAGS }.orEmpty(),
                            matchMode = matchMode,
                            scope = alertScope,
                            cooldownMs = if (cooldownEnabled) 30_000L else 0L,
                            randomAlertsEnabled = domain == AlertDomain.TIMER && randomEnabled,
                            randomAlertsCount = if (domain == AlertDomain.TIMER && randomEnabled) randomCount.toIntOrNull()?.coerceIn(1, 99) ?: 1 else 0,
                            randomAlertsWindow = randomWindow,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (initial == null) {
                    Text("Module", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DomainChip(AlertDomain.TIMER, domain) {
                            domain = AlertDomain.TIMER
                            trigger = defaultTrigger(AlertDomain.TIMER)
                            targetKind = AlertTargetKind.TAGS
                            entityId = null
                            selectedTagIds = emptySet()
                        }
                        DomainChip(AlertDomain.PLACE, domain) {
                            domain = AlertDomain.PLACE
                            trigger = defaultTrigger(AlertDomain.PLACE)
                            targetKind = if (initialPlaceId != null) AlertTargetKind.ENTITY else AlertTargetKind.TAGS
                            entityId = initialPlaceId
                            selectedTagIds = emptySet()
                        }
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Alert text") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("When", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    triggerOptions(domain).forEach { (value, label) ->
                        FilterChip(
                            selected = trigger == value,
                            onClick = { trigger = value },
                            label = { Text(label) },
                        )
                    }
                }

                if (domain == AlertDomain.PLACE) {
                    Text("Where", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = targetKind == AlertTargetKind.ENTITY,
                            onClick = {
                                targetKind = AlertTargetKind.ENTITY
                                selectedTagIds = emptySet()
                            },
                            label = { Text("Specific place") },
                        )
                        FilterChip(
                            selected = targetKind == AlertTargetKind.TAGS,
                            onClick = {
                                targetKind = AlertTargetKind.TAGS
                                entityId = null
                            },
                            label = { Text("Places tags") },
                        )
                    }
                }

                val options = if (domain == AlertDomain.PLACE && targetKind == AlertTargetKind.ENTITY) {
                    catalog.entities
                } else {
                    catalog.tags
                }
                val optionTitle = if (domain == AlertDomain.PLACE && targetKind == AlertTargetKind.ENTITY) "Place" else "Tags"
                Text(optionTitle, style = MaterialTheme.typography.labelLarge)
                if (options.size > 12) {
                    OutlinedTextField(
                        value = optionQuery,
                        onValueChange = { optionQuery = it },
                        label = { Text("Search $optionTitle") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                val visibleOptions = options.filter { optionQuery.isBlank() || it.label.contains(optionQuery, ignoreCase = true) }
                if (visibleOptions.isEmpty()) {
                    Text(if (optionTitle == "Tags") "No tags available" else "No places available")
                } else {
                    OptionChips(
                        options = visibleOptions,
                        selectedIds = if (domain == AlertDomain.PLACE && targetKind == AlertTargetKind.ENTITY) {
                            entityId?.let(::setOf).orEmpty()
                        } else selectedTagIds,
                        onToggle = { id ->
                            if (domain == AlertDomain.PLACE && targetKind == AlertTargetKind.ENTITY) {
                                entityId = id
                            } else {
                                selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id
                            }
                        },
                    )
                }

                if (!(domain == AlertDomain.PLACE && targetKind == AlertTargetKind.ENTITY)) {
                    Text("Tag matching", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = matchMode == AlertMatchMode.ALL,
                            onClick = { matchMode = AlertMatchMode.ALL },
                            label = { Text("All selected tags") },
                        )
                        FilterChip(
                            selected = matchMode == AlertMatchMode.ANY,
                            onClick = { matchMode = AlertMatchMode.ANY },
                            label = { Text("Any selected tag") },
                        )
                    }
                }

                Text("Frequency", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = alertScope == AlertScope.ALWAYS,
                        onClick = { alertScope = AlertScope.ALWAYS },
                        label = { Text("Every time") },
                    )
                    FilterChip(
                        selected = alertScope == AlertScope.ONE_TIME,
                        onClick = { alertScope = AlertScope.ONE_TIME },
                        label = { Text("Next time only") },
                    )
                }
                FilterChip(
                    selected = cooldownEnabled,
                    onClick = { cooldownEnabled = !cooldownEnabled },
                    label = { Text("30 s anti-spam cooldown") },
                )

                if (domain == AlertDomain.TIMER) {
                    FilterChip(
                        selected = randomEnabled,
                        onClick = { randomEnabled = !randomEnabled },
                        label = { Text("Random alerts") },
                    )
                    if (randomEnabled) {
                        OutlinedTextField(
                            value = randomCount,
                            onValueChange = { randomCount = it.filter(Char::isDigit).take(2) },
                            label = { Text("How many") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = randomWindow == ManagedAlertRandomWindow.HOUR,
                                onClick = { randomWindow = ManagedAlertRandomWindow.HOUR },
                                label = { Text("per hour") },
                            )
                            FilterChip(
                                selected = randomWindow == ManagedAlertRandomWindow.DAY,
                                onClick = { randomWindow = ManagedAlertRandomWindow.DAY },
                                label = { Text("per day") },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun DomainChip(value: AlertDomain, selected: AlertDomain, onClick: () -> Unit) {
    FilterChip(
        selected = value == selected,
        onClick = onClick,
        label = { Text(if (value == AlertDomain.TIMER) "Timer" else "Places") },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionChips(
    options: List<ManagedAlertOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option.id in selectedIds,
                onClick = { onToggle(option.id) },
                label = { Text(option.label) },
            )
        }
    }
}

private fun defaultTrigger(domain: AlertDomain): AlertTrigger =
    if (domain == AlertDomain.TIMER) AlertTrigger.TIMER_START else AlertTrigger.PLACE_CHECK_IN

private fun triggerOptions(domain: AlertDomain): List<Pair<AlertTrigger, String>> =
    if (domain == AlertDomain.TIMER) {
        listOf(
            AlertTrigger.TIMER_START to "Start",
            AlertTrigger.TIMER_STOP to "Stop",
        )
    } else {
        listOf(
            AlertTrigger.PLACE_CHECK_IN to "Check in",
            AlertTrigger.PLACE_CHECK_OUT to "Check out",
            AlertTrigger.PLACE_BOTH to "Both",
        )
    }
