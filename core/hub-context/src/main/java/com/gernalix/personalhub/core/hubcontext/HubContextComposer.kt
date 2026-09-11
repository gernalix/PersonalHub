package com.gernalix.personalhub.core.hubcontext

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

internal data class ComposerMember(val summary: HubEntitySummary, val role: String = "", val automatic: Boolean = false)

internal class HubComposerState(
    val anchor: HubEntityRef?,
    val editingContextId: String?,
    restoredMembers: List<ComposerMember> = emptyList(),
    restoredTypeId: String? = null,
    restoredQuery: String = "",
    restoredDraft: String = "",
    restoredKind: String? = null,
    restoredResourceKind: String = HubResourceKinds.NOTE,
    restoredResourceValue: String = "",
    restoredResourcePermission: Boolean = false,
    private var initialized: Boolean = false,
    private val initialScope: List<HubEntityRef> = listOfNotNull(anchor),
) {
    var members by mutableStateOf(restoredMembers)
    var typeId by mutableStateOf(restoredTypeId)
    var query by mutableStateOf(restoredQuery)
    var createDraft by mutableStateOf(restoredDraft)
    var selectedKind by mutableStateOf(restoredKind)
    var resourceKind by mutableStateOf(restoredResourceKind)
    var resourceValue by mutableStateOf(restoredResourceValue)
    var resourcePermission by mutableStateOf(restoredResourcePermission)
    var results by mutableStateOf<List<HubEntitySummary>>(emptyList())
    var types by mutableStateOf<List<HubContextType>>(emptyList())
    var fields by mutableStateOf<List<HubContextTypeField>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var savedContextId by mutableStateOf(editingContextId)
    private var rankingMembersKey: List<Pair<HubEntityRef, String>>? = null
    private var rankingCounts: Map<HubEntityRef, Int> = emptyMap()
    private var rankingPlaceId: String? = null

    suspend fun initialize() {
        if (!initialized) {
            members = editingContextId?.let { id ->
                HubContextRuntime.context(id)?.resolvedMembers?.map { ComposerMember(it.summary, it.member.role) }
            } ?: run {
                val refs = initialScope.distinct()
                val resolved = HubContextRuntime.summaries(refs)
                refs.mapNotNull { ref -> resolved[ref]?.let(::ComposerMember) }
            }
            typeId = editingContextId?.let { HubContextRuntime.context(it)?.context?.contextTypeId } ?: typeId
            initialized = true
        }
        types = HubContextRuntime.contextTypes().filterNot { it.locked }
        loadFields()
        refresh()
    }

    suspend fun selectType(id: String?) {
        typeId = id
        loadFields()
        error = null
    }

    private suspend fun loadFields() {
        fields = typeId?.let { HubContextRuntime.contextType(it)?.second }.orEmpty()
    }

    suspend fun refresh() {
        val membersKey = members.map { it.summary.ref to it.role }
        if (membersKey != rankingMembersKey) {
            rankingCounts = if (members.isEmpty()) emptyMap() else runCatching {
                HubContextRuntime.explore(members.map { it.summary.ref }, 200)
                    .facets
                    .flatMap { facet -> facet.candidates }
                    .associate { it.summary.ref to it.compatibleContextCount }
            }.getOrDefault(emptyMap())
            rankingPlaceId = members.firstOrNull {
                it.summary.ref.moduleId == "places" && it.summary.ref.entityKind == "place"
            }?.summary?.ref?.canonicalId
            rankingMembersKey = membersKey
        }
        val adapter = selectedAdapter() ?: run { results = emptyList(); return }
        val candidates = withContext(Dispatchers.IO) { adapter.search(query, 50) }
        results = rankComposerCandidates(candidates, rankingCounts, rankingPlaceId).take(5)
    }

    fun chooseKind(adapter: HubEntityAdapter) {
        selectedKind = key(adapter.moduleId, adapter.entityKind)
        query = ""
        results = emptyList()
        error = null
    }

    fun add(summary: HubEntitySummary, automatic: Boolean = false) {
        val role = roleFor(summary.ref)
        if (members.none { it.summary.ref == summary.ref && it.role == role }) members = members + ComposerMember(summary, role, automatic)
        error = null
    }

    fun remove(member: ComposerMember) {
        if (member.summary.ref != anchor) members = members - member
    }

    suspend fun detect(fromMs: Long, toMs: Long) {
        members = members.filterNot { it.automatic }
        val records = HubContextRuntime.temporal(HubTemporalQuery(fromMs, toMs, 20))
        val refs = records.map { record ->
            record.entityRef ?: HubEntityRef(record.moduleId, record.source, record.stableId)
        }
        val resolved = HubContextRuntime.summaries(refs)
        refs.forEach { ref -> resolved[ref]?.let { add(it, automatic = true) } }
        refresh()
    }

    suspend fun createCanonical() {
        val adapter = selectedAdapter()
        val extras = if (adapter?.moduleId == "hub" && adapter.entityKind == "resource") mapOf(
            "kind" to resourceKind,
            "value" to resourceValue,
            "persisted_permission" to resourcePermission.toString(),
        ) else emptyMap()
        val created = adapter?.create(HubCreateRequest(createDraft, extras))
        if (created == null) error = "create" else {
            add(created)
            createDraft = ""
            resourceValue = ""
            resourcePermission = false
            refresh()
        }
    }

    fun selectedResource() = selectedKind == key("hub", "resource")

    suspend fun save(): String {
        val refs = members.map { it.summary.ref to it.role }
        val id = editingContextId ?: savedContextId
        val result = if (id == null) HubContextRuntime.createContext(refs, typeId) else {
            HubContextRuntime.updateContext(id, refs, typeId)
            id
        }
        savedContextId = result
        return result
    }

    private fun selectedAdapter() = selectedKind?.split('/', limit = 2)?.let { parts ->
        if (parts.size == 2) HubContextRuntime.adapter(parts[0], parts[1]) else null
    }

    private fun roleFor(ref: HubEntityRef): String {
        if (fields.isEmpty()) return ""
        val adapter = HubContextRuntime.adapter(ref.moduleId, ref.entityKind)
        return fields.firstOrNull { field ->
            val kindMatches = field.acceptedModuleId == null || (field.acceptedModuleId == ref.moduleId && field.acceptedEntityKind == ref.entityKind)
            val capabilityMatches = field.acceptedCapability == null || field.acceptedCapability in adapter.capabilities
            val role = field.role.ifBlank { field.fieldId }
            val count = members.count { it.role == role }
            val maximum = field.maxCardinality
            kindMatches && capabilityMatches && (maximum == null || count < maximum)
        }?.let { it.role.ifBlank { it.fieldId } }.orEmpty()
    }

    companion object {
        private fun encodeRef(ref: HubEntityRef) = listOf(ref.moduleId, ref.entityKind, ref.canonicalId)
        private fun decodeRef(values: List<String>) = HubEntityRef(values[0], values[1], values[2])
        val Saver = Saver<HubComposerState, List<Any?>>(
            save = { state ->
                listOf(
                    state.anchor?.let(::encodeRef), state.editingContextId,
                    state.members.map { encodeRef(it.summary.ref) + listOf(it.summary.label, it.summary.description, it.summary.lifecycle, it.role, it.automatic.toString()) },
                    state.typeId, state.query, state.createDraft, state.selectedKind,
                    state.resourceKind, state.resourceValue, state.resourcePermission,
                )
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                HubComposerState(
                    (saved[0] as List<String>?)?.let(::decodeRef), saved[1] as String?,
                    (saved[2] as List<List<String?>>).map { row ->
                        ComposerMember(HubEntitySummary(HubEntityRef(row[0]!!, row[1]!!, row[2]!!), row[3]!!, row[4], row[5]!!), row[6]!!, row.getOrNull(7)?.toBoolean() == true)
                    },
                    saved[3] as String?, saved[4] as String, saved[5] as String, saved[6] as String?,
                    saved[7] as String, saved[8] as String, saved[9] as Boolean, initialized = true,
                )
            },
        )
    }
}

internal fun rankComposerCandidates(
    candidates: List<HubEntitySummary>,
    cooccurrence: Map<HubEntityRef, Int>,
    selectedPlaceId: String?,
): List<HubEntitySummary> = candidates.sortedWith(
    compareByDescending<HubEntitySummary> { cooccurrence[it.ref] ?: 0 }
        .thenByDescending { if (selectedPlaceId != null && it.attributes["place_id"] == selectedPlaceId) 1 else 0 }
        .thenByDescending { it.attributes["time_ms"]?.toLongOrNull() ?: it.attributes["start_ms"]?.toLongOrNull() ?: Long.MIN_VALUE }
        .thenBy { it.label.lowercase() }
        .thenBy { "${it.ref.moduleId}/${it.ref.entityKind}/${it.ref.canonicalId}" },
)

@Composable
fun HubContextComposerScreen(onBack: () -> Unit, onSaved: () -> Unit = onBack) {
    val androidContext = LocalContext.current
    val state = rememberSaveable(saver = HubComposerState.Saver) { HubComposerState(null, null) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var fromText by rememberSaveable { mutableStateOf(formatHubDateTime(System.currentTimeMillis() - 60 * 60 * 1000L)) }
    var toText by rememberSaveable { mutableStateOf(formatHubDateTime(System.currentTimeMillis() + 60_000L)) }
    LaunchedEffect(state) { runCatching { state.initialize(); state.detect(parseHubDateTime(fromText), parseHubDateTime(toText)) }.onFailure { state.error = it.message } }
    LaunchedEffect(Unit) {
        val granted = androidContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val location = if (granted) (androidContext.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager)
            .getProviders(true).mapNotNull { provider -> runCatching { androidContext.getSystemService(android.location.LocationManager::class.java).getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time } else null
        HubContextRuntime.adapters().filterIsInstance<HubPlaceSuggestionProvider>().firstOrNull()?.suggestPlaces(location?.latitude, location?.longitude)?.let { suggestions ->
            suggestions.preselect?.let { state.add(it, automatic = true) }
            if (state.selectedKind == null && suggestions.candidates.isNotEmpty()) {
                HubContextRuntime.adapters().firstOrNull { it.moduleId == "places" && it.entityKind == "place" }?.let(state::chooseKind)
                state.results = suggestions.candidates
            }
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.hub_cancel)) }
            Text(stringResource(R.string.hub_composer_title_new), style = MaterialTheme.typography.titleLarge)
            Button(onClick = { scope.launch { runCatching { state.save() }.onSuccess { onSaved() }.onFailure { state.error = it.message } } }, modifier = Modifier.testTag("hub-composer-save"), enabled = state.members.size >= 2) { Text(stringResource(R.string.hub_save)) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.hub_time_anchor), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(fromText, { fromText = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.hub_from)) })
                    OutlinedTextField(toText, { toText = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.hub_to)) })
                }
                TextButton(onClick = { scope.launch { runCatching { state.detect(parseHubDateTime(fromText), parseHubDateTime(toText)) }.onFailure { state.error = it.message } } }) { Text(stringResource(R.string.hub_detect)) }
            }
            item {
                Text(stringResource(R.string.hub_composer_members), style = MaterialTheme.typography.labelLarge)
                state.members.forEach { member -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text((if (member.automatic) "• " else "") + member.summary.label, Modifier.weight(1f))
                    TextButton(onClick = {
                        state.remove(member)
                        searchJob?.cancel()
                        searchJob = scope.launch { runCatching { state.refresh() }.onFailure { state.error = it.message } }
                    }) { Text(stringResource(R.string.hub_remove)) }
                } }
            }
            item {
                Text(stringResource(R.string.hub_composer_add_kind), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { HubContextRuntime.adapters().forEach { adapter ->
                    FilterChip(state.selectedKind == key(adapter.moduleId, adapter.entityKind), {
                        searchJob?.cancel()
                        state.chooseKind(adapter)
                        searchJob = scope.launch { runCatching { state.refresh() }.onFailure { state.error = it.message } }
                    }, label = { Text(adapter.entityKind.replace('_', ' ').replaceFirstChar(Char::uppercase)) })
                } }
            }
            if (state.selectedKind != null) item {
                OutlinedTextField(state.query, { value ->
                    state.query = value
                    searchJob?.cancel()
                    searchJob = scope.launch {
                        delay(250)
                        runCatching { state.refresh() }.onFailure { state.error = it.message }
                    }
                }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.hub_search)) })
                state.results.filter { result -> state.members.none { it.summary.ref == result.ref } }.forEach { result ->
                    TextButton({
                        state.add(result)
                        searchJob?.cancel()
                        searchJob = scope.launch { runCatching { state.refresh() }.onFailure { state.error = it.message } }
                    }, Modifier.fillMaxWidth()) { Text(result.label) }
                }
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
internal fun HubContextComposerDialog(anchor: HubEntityRef, editingContextId: String?, onDismiss: () -> Unit, onSaved: () -> Unit, prefill: List<HubEntityRef> = listOf(anchor)) {
    val context = LocalContext.current
    val state = rememberSaveable(anchor, editingContextId, saver = HubComposerState.Saver) { HubComposerState(anchor, editingContextId, initialScope = prefill) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var templatesOpen by rememberSaveable { mutableStateOf(false) }
    var templateName by rememberSaveable { mutableStateOf("") }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            }.getOrDefault(false)
            state.resourceKind = HubResourceKinds.SAF_DOCUMENT
            state.resourceValue = it.toString()
            state.resourcePermission = persisted
        }
    }
    LaunchedEffect(state) { runCatching { state.initialize() }.onFailure { state.error = it.message } }
    if (templatesOpen) HubContextTypeEditorDialog({ templatesOpen = false; scope.launch { state.initialize() } })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editingContextId == null) R.string.hub_composer_title_new else R.string.hub_composer_title_edit)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(stringResource(R.string.hub_composer_members), style = MaterialTheme.typography.labelLarge)
                    state.members.forEach { member ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(member.summary.label, Modifier.weight(1f))
                            if (member.summary.ref != anchor) TextButton(onClick = {
                                state.remove(member)
                                searchJob?.cancel()
                                searchJob = scope.launch { runCatching { state.refresh() }.onFailure { state.error = it.message } }
                            }) { Text(stringResource(R.string.hub_remove)) }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.hub_composer_add_kind), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HubContextRuntime.adapters().forEach { adapter ->
                            FilterChip(
                                selected = state.selectedKind == key(adapter.moduleId, adapter.entityKind),
                                onClick = {
                                    searchJob?.cancel()
                                    state.chooseKind(adapter)
                                    searchJob = scope.launch { runCatching { state.refresh() }.onFailure { state.error = it.message } }
                                },
                                modifier = Modifier.testTag("hub-kind-${key(adapter.moduleId, adapter.entityKind)}"),
                                label = { Text(adapter.entityKind) },
                            )
                        }
                    }
                }
                if (state.selectedKind != null) item {
                    OutlinedTextField(state.query, { value ->
                        state.query = value
                        searchJob?.cancel()
                        searchJob = scope.launch {
                            delay(250)
                            runCatching { state.refresh() }.onFailure { state.error = it.message }
                        }
                    }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.hub_search)) })
                    state.results.filter { candidate -> state.members.none { it.summary.ref == candidate.ref } }.forEach { result ->
                        TextButton(onClick = {
                            state.add(result)
                            searchJob?.cancel()
                            searchJob = scope.launch { runCatching { state.refresh() }.onFailure { state.error = it.message } }
                        }, Modifier.fillMaxWidth().testTag("hub-result-${key(result.ref.moduleId, result.ref.entityKind)}/${result.ref.canonicalId}")) { Text(result.label) }
                    }
                    if (state.selectedResource()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                HubResourceKinds.NOTE to R.string.hub_resource_kind_note,
                                HubResourceKinds.WEB_URL to R.string.hub_resource_kind_web,
                                HubResourceKinds.ANDROID_URI to R.string.hub_resource_kind_deep_link,
                            ).forEach { (kind, label) -> FilterChip(state.resourceKind == kind, { state.resourceKind = kind; state.resourcePermission = false }, label = { Text(stringResource(label)) }) }
                            TextButton(onClick = { documentPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.hub_resource_pick_document)) }
                        }
                        OutlinedTextField(state.createDraft, { state.createDraft = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.hub_resource_title_optional)) })
                        OutlinedTextField(state.resourceValue, { state.resourceValue = it; state.resourcePermission = false }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.hub_resource_value)) })
                        Button(onClick = { scope.launch { state.createCanonical() } }, enabled = state.resourceValue.isNotBlank()) { Text(stringResource(R.string.hub_create)) }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(state.createDraft, { state.createDraft = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.hub_new_entity_name)) })
                        Button(onClick = { scope.launch { state.createCanonical() } }, enabled = state.createDraft.isNotBlank()) { Text(stringResource(R.string.hub_create)) }
                    }
                }
                item {
                    Text(stringResource(R.string.hub_template), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(state.typeId == null, { scope.launch { state.selectType(null) } }, label = { Text(stringResource(R.string.hub_template_none)) })
                        state.types.forEach { type -> FilterChip(state.typeId == type.id, { scope.launch { state.selectType(type.id) } }, label = { Text(type.name) }) }
                    }
                    TextButton(onClick = { templatesOpen = true }) { Text(stringResource(R.string.hub_manage_templates)) }
                }
                state.error?.let { message -> item { Text(if (message == "create") stringResource(R.string.hub_create_not_supported) else message, color = MaterialTheme.colorScheme.error) } }
                state.savedContextId?.let { contextId -> item {
                    OutlinedTextField(templateName, { templateName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.hub_save_as_template_name)) })
                    TextButton(onClick = { scope.launch { runCatching { HubContextRuntime.saveCombinationAsType(contextId, templateName) }.onSuccess { templateName = ""; state.initialize() }.onFailure { state.error = it.message } } }, enabled = templateName.isNotBlank()) {
                        Text(stringResource(R.string.hub_save_as_template))
                    }
                } }
            }
        },
        confirmButton = { Button(onClick = { scope.launch { runCatching { state.save() }.onSuccess { onSaved() }.onFailure { state.error = it.message } } }, Modifier.testTag("hub-composer-save")) { Text(stringResource(R.string.hub_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hub_cancel)) } },
    )
}

private data class TypeFieldDraft(val id: String, val label: String, val role: String, val acceptance: String, val min: String, val max: String)

@Composable
private fun HubContextTypeEditorDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var types by remember { mutableStateOf<List<HubContextType>>(emptyList()) }
    var id by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf("") }
    var fields by rememberSaveable { mutableStateOf<List<TypeFieldDraft>>(emptyList()) }
    var acceptance by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val adapters = HubContextRuntime.adapters()
    val choices = adapters.flatMap { adapter -> listOf("kind:${adapter.moduleId}/${adapter.entityKind}") + adapter.capabilities.map { "cap:$it" } }.distinct()
    LaunchedEffect(Unit) { types = HubContextRuntime.contextTypes().filterNot { it.locked } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_templates_title)) },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { type -> FilterChip(type.id == id, onClick = { scope.launch {
                        HubContextRuntime.contextType(type.id)?.let { loaded ->
                            id = loaded.first.id; name = loaded.first.name
                            fields = loaded.second.map { field -> TypeFieldDraft(field.fieldId, field.label, field.role, field.acceptedModuleId?.let { "kind:$it/${field.acceptedEntityKind}" } ?: "cap:${field.acceptedCapability}", field.minCardinality.toString(), field.maxCardinality?.toString().orEmpty()) }
                        }
                    } }, label = { Text(type.name) }) }
                    TextButton(onClick = { id = UUID.randomUUID().toString(); name = ""; fields = emptyList() }) { Text(stringResource(R.string.hub_new_template)) }
                }
            }
            item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.hub_template_name)) }) }
            items(fields, key = { it.id }) { field ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(field.acceptance.removePrefix("kind:").removePrefix("cap:"), style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(field.label, { value -> fields = fields.map { if (it.id == field.id) it.copy(label = value) else it } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.hub_field_label)) })
                    OutlinedTextField(field.role, { value -> fields = fields.map { if (it.id == field.id) it.copy(role = value) else it } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.hub_field_role)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(field.min, { value -> fields = fields.map { if (it.id == field.id) it.copy(min = value.filter(Char::isDigit)) else it } }, Modifier.weight(1f), label = { Text(stringResource(R.string.hub_min)) })
                        OutlinedTextField(field.max, { value -> fields = fields.map { if (it.id == field.id) it.copy(max = value.filter(Char::isDigit)) else it } }, Modifier.weight(1f), label = { Text(stringResource(R.string.hub_max)) })
                    }
                    Row {
                        TextButton(onClick = { val index = fields.indexOf(field); if (index > 0) fields = fields.toMutableList().also { java.util.Collections.swap(it, index, index - 1) } }) { Text("↑") }
                        TextButton(onClick = { val index = fields.indexOf(field); if (index in 0 until fields.lastIndex) fields = fields.toMutableList().also { java.util.Collections.swap(it, index, index + 1) } }) { Text("↓") }
                        TextButton(onClick = { fields = fields - field }) { Text(stringResource(R.string.hub_remove)) }
                    }
                }
            }
            item {
                Text(stringResource(R.string.hub_field_acceptance), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { choices.forEach { choice -> FilterChip(acceptance == choice, { acceptance = choice }, label = { Text(choice.removePrefix("kind:").removePrefix("cap:")) }) } }
                Button(onClick = { acceptance?.let { selected -> fields = fields + TypeFieldDraft(UUID.randomUUID().toString(), selected.substringAfterLast('/').substringAfter(':'), selected.substringAfterLast('/').substringAfter(':'), selected, "0", "") } }, enabled = acceptance != null) { Text(stringResource(R.string.hub_add_field)) }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        } },
        confirmButton = { Button(onClick = { scope.launch {
            val now = Instant.now().toString()
            val persisted = fields.mapIndexed { index, field ->
                val kind = field.acceptance.takeIf { it.startsWith("kind:") }?.removePrefix("kind:")?.split('/', limit = 2)
                HubContextTypeField(id, field.id, index, field.label, field.role, kind?.get(0), kind?.get(1), field.acceptance.takeIf { it.startsWith("cap:") }?.removePrefix("cap:"), field.min.toIntOrNull() ?: 0, field.max.toIntOrNull())
            }
            runCatching { HubContextRuntime.saveContextType(HubContextType(id, name, now, now), persisted) }.onSuccess { onDismiss() }.onFailure { error = it.message }
        } }, enabled = name.isNotBlank() && fields.isNotEmpty()) { Text(stringResource(R.string.hub_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hub_cancel)) } },
    )
}

private fun key(moduleId: String, entityKind: String) = "$moduleId/$entityKind"
