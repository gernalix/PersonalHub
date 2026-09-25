package com.gernalix.personalhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.core.hubcontext.*
import com.gernalix.personalhub.core.ui.HubTimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

internal data class TemporalEntry(
    val id: String,
    val sectionKey: String,
    val sectionTitle: String,
    val title: String,
    val subtitle: String?,
    val startMs: Long,
    val refs: List<HubEntityRef>,
    val selectable: Boolean = true,
)

private fun formatTemporalInput(epochMs: Long): String =
    HubTimeFormat.pattern(epochMs, "uuuu-MM-dd HH:mm")

internal data class SavedEpisodeEntry(
    val contextId: String,
    val title: String,
    val members: String,
)

private val moduleTitles = mapOf(
    "people" to "People",
    "places" to "Places",
    "soldi" to "Soldi",
    "substances" to "Sostanze",
    "timer" to "Timer",
    "wordpulse" to "WordPulse",
)

internal fun buildTemporalEntries(
    records: List<HubTemporalRecord>,
    boundedPeople: List<HubEntitySummary> = emptyList(),
    wordPulseAvailableLabel: (Int) -> String = { "Average fatigue signal: $it/100" },
    wordPulseUnavailableLabel: String = "Fatigue signal: unavailable",
    peopleTitle: String = "People",
): List<TemporalEntry> {
    val regular = records.filterNot { it.moduleId == "wordpulse" }.map { record ->
        val refs = listOfNotNull(record.entityRef)
        TemporalEntry(
            id = "record:${record.moduleId}/${record.source}/${record.stableId}",
            sectionKey = record.moduleId,
            sectionTitle = moduleTitles[record.moduleId] ?: record.moduleId.replaceFirstChar(Char::uppercase),
            title = record.title,
            subtitle = listOfNotNull(formatHubDateTime(record.startMs), record.subtitle).joinToString(" · "),
            startMs = record.startMs,
            refs = refs,
            selectable = refs.isNotEmpty(),
        )
    }
    val people = boundedPeople.distinctBy { it.ref }.map { person ->
        TemporalEntry(
            id = "person:${person.ref.canonicalId}",
            sectionKey = "people",
            sectionTitle = peopleTitle,
            title = person.label,
            subtitle = person.description,
            startMs = Long.MAX_VALUE,
            refs = listOf(person.ref),
        )
    }
    val wordPulse = records.filter { it.moduleId == "wordpulse" }
    val wordPulseEntry = if (wordPulse.isNotEmpty()) {
        val scores = wordPulse.mapNotNull { it.attributes["fatigueScore"]?.toIntOrNull() }.filter { it in 0..100 }
        val refs = wordPulse.mapNotNull { it.entityRef }.distinct()
        TemporalEntry(
            id = "wordpulse:aggregate",
            sectionKey = "wordpulse",
            sectionTitle = moduleTitles.getValue("wordpulse"),
            title = scores.takeIf { it.isNotEmpty() }?.let { wordPulseAvailableLabel(it.average().roundToInt()) } ?: wordPulseUnavailableLabel,
            subtitle = null,
            startMs = wordPulse.maxOf { it.startMs },
            refs = refs,
            selectable = refs.isNotEmpty(),
        )
    } else null
    return (people + regular + listOfNotNull(wordPulseEntry))
        .sortedWith(compareBy<TemporalEntry> { it.sectionTitle }.thenByDescending { it.startMs }.thenBy { it.title.lowercase() }.thenBy { it.id })
}

internal fun groupedTemporalEntries(entries: List<TemporalEntry>): List<Pair<String, List<TemporalEntry>>> =
    entries.groupBy { it.sectionTitle }.toSortedMap().map { it.key to it.value }

private fun encodeRef(ref: HubEntityRef) = "${ref.moduleId}\u001F${ref.entityKind}\u001F${ref.canonicalId}"

private fun decodeRef(value: String): HubEntityRef? {
    val parts = value.split('\u001F', limit = 3)
    return if (parts.size == 3) HubEntityRef(parts[0], parts[1], parts[2]) else null
}

private fun saveTemporalRecords(records: List<HubTemporalRecord>): List<Any?> = records.map { record ->
    listOf(
        record.moduleId, record.source, record.stableId, record.kind.name, record.startMs, record.endMs,
        record.title, record.subtitle, record.entityRef?.let(::encodeRef), record.attributes,
    )
}

private fun restoreTemporalRecords(saved: List<Any?>): List<HubTemporalRecord> {
    @Suppress("UNCHECKED_CAST")
    return (saved as List<List<Any?>>).map { row ->
        HubTemporalRecord(
            moduleId = row[0] as String,
            source = row[1] as String,
            stableId = row[2] as String,
            kind = HubTemporalKind.valueOf(row[3] as String),
            startMs = row[4] as Long,
            endMs = row[5] as Long?,
            title = row[6] as String,
            subtitle = row[7] as String?,
            entityRef = (row[8] as String?)?.let(::decodeRef),
            attributes = row[9] as Map<String, String>,
        )
    }
}

private val TemporalRecordStateSaver = Saver<MutableState<List<HubTemporalRecord>>, List<Any?>>(
    save = { state -> saveTemporalRecords(state.value) },
    restore = { saved -> mutableStateOf(restoreTemporalRecords(saved)) },
)

private fun saveHubSummaries(people: List<HubEntitySummary>): List<Any?> = people.map { summary ->
    listOf(encodeRef(summary.ref), summary.label, summary.description, summary.lifecycle, summary.attributes)
}

private fun restoreHubSummaries(saved: List<Any?>): List<HubEntitySummary> {
    @Suppress("UNCHECKED_CAST")
    return (saved as List<List<Any?>>).mapNotNull { row ->
        (row[0] as String).let(::decodeRef)?.let { ref ->
            HubEntitySummary(ref, row[1] as String, row[2] as String?, row[3] as String, row[4] as Map<String, String>)
        }
    }
}

private val HubSummaryStateSaver = Saver<MutableState<List<HubEntitySummary>>, List<Any?>>(
    save = { state -> saveHubSummaries(state.value) },
    restore = { saved -> mutableStateOf(restoreHubSummaries(saved)) },
)

@Composable
fun HubTemporalSearchScreen(
    onBack: () -> Unit,
    initialFromMs: Long? = null,
    initialToMs: Long? = null,
    initialModules: Set<String> = emptySet(),
    autoSearch: Boolean = false,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val providers = remember { HubContextRuntime.temporalProviders() }
    val allModules = remember(providers) { providers.map { it.moduleId }.toSet() }
    var selected by rememberSaveable(initialModules, allModules) {
        val requested = if (initialModules.isEmpty()) allModules else allModules.intersect(initialModules)
        mutableStateOf(requested.ifEmpty { allModules })
    }
    var fromText by rememberSaveable(initialFromMs) {
        mutableStateOf(formatTemporalInput(initialFromMs ?: (System.currentTimeMillis() - 24 * 60 * 60 * 1000L)))
    }
    var toText by rememberSaveable(initialToMs) {
        mutableStateOf(formatTemporalInput(initialToMs ?: (System.currentTimeMillis() + 60_000L)))
    }
    var records by rememberSaveable(saver = TemporalRecordStateSaver) { mutableStateOf(emptyList<HubTemporalRecord>()) }
    var boundedPeople by rememberSaveable(saver = HubSummaryStateSaver) { mutableStateOf(emptyList<HubEntitySummary>()) }
    var cursors by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var episodeTitle by rememberSaveable { mutableStateOf("") }
    var selectedRefs by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var collapsedSections by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var savedEpisodes by remember { mutableStateOf<List<SavedEpisodeEntry>>(emptyList()) }
    var editingEpisodeContextId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val entries = remember(records, boundedPeople, resources) {
        buildTemporalEntries(
            records,
            boundedPeople,
            wordPulseAvailableLabel = { resources.getString(R.string.temporal_wordpulse_fatigue_available, it) },
            wordPulseUnavailableLabel = resources.getString(R.string.temporal_wordpulse_fatigue_unavailable),
            peopleTitle = resources.getString(R.string.temporal_people),
        ).sortedWith(
            compareBy<TemporalEntry> { it.sectionTitle }
                .thenByDescending { it.startMs }
                .thenBy { it.title.lowercase() }
                .thenBy { it.id },
        )
    }

    suspend fun loadSavedEpisodes() {
        savedEpisodes = HubContextRuntime.titledContexts().mapNotNull { view ->
            val title = view.context.title?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            SavedEpisodeEntry(
                contextId = view.context.id,
                title = title,
                members = view.members.joinToString(" · ") { it.label },
            )
        }
        error = null
    }

    if (editingEpisodeContextId != null) {
        HubContextComposerScreen(
            onBack = { editingEpisodeContextId = null },
            onSaved = {
                editingEpisodeContextId = null
                scope.launch { runCatching { loadSavedEpisodes() }.onFailure { error = it.message } }
            },
            editingContextId = editingEpisodeContextId,
        )
        return
    }

    LaunchedEffect(Unit) {
        runCatching { loadSavedEpisodes() }.onFailure { error = it.message }
    }

    fun invalidateTemporalResults() {
        records = emptyList()
        boundedPeople = emptyList()
        cursors = emptyMap()
        selectedRefs = emptyList()
        saving = false
    }

    suspend fun search(append: Boolean) {
        val from = parseHubDateTime(fromText)
        val to = parseHubDateTime(toText)
        require(from < to)
        val requestedProviders = providers.filter { provider ->
            provider.moduleId in selected && (!append || cursors[provider.moduleId] != null)
        }
        if (requestedProviders.isEmpty()) {
            if (!append) {
                records = emptyList()
                boundedPeople = emptyList()
                cursors = emptyMap()
                selectedRefs = emptyList()
            }
            return
        }
        val pages = coroutineScope {
            requestedProviders.map { provider ->
                async(Dispatchers.IO) {
                    provider.moduleId to provider.queryTemporal(
                        HubTemporalQuery(from, to, 50, if (append) cursors[provider.moduleId] else null),
                    )
                }
            }.awaitAll()
        }
        cursors = if (append) {
            cursors.toMutableMap().apply { pages.forEach { (moduleId, page) -> put(moduleId, page.nextCursor) } }
        } else {
            pages.associate { (moduleId, page) -> moduleId to page.nextCursor }
        }
        val incoming = mergeTemporalSlices(pages.map { it.second.records }, from, to, selected)
        val nextRecords = if (append) {
            mergeTemporalSlices(listOf(records, incoming), from, to, selected)
                .distinctBy { Triple(it.moduleId, it.source, it.stableId) }
        } else incoming
        records = nextRecords
        val recordsNeedingPeopleLookup = if (append) incoming else nextRecords
        val linkedPeople = coroutineScope {
            recordsNeedingPeopleLookup.mapNotNull { record -> record.entityRef }.distinct().map { ref ->
                async(Dispatchers.IO) {
                    HubContextRuntime.linked(ref).filter { it.ref.moduleId == "people" && it.ref.entityKind == "person" }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.ref }
        boundedPeople = if (append) {
            (boundedPeople + linkedPeople).distinctBy { it.ref }
        } else {
            linkedPeople
        }
        if (!append) {
            saving = false
            selectedRefs = emptyList()
        }
        error = null
    }

    LaunchedEffect(initialFromMs, initialToMs, initialModules, autoSearch) {
        if (autoSearch) runCatching { search(false) }.onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.temporal_back)) }
            Text(stringResource(R.string.temporal_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(64.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(fromText, { fromText = it; invalidateTemporalResults() }, Modifier.weight(1f).testTag("temporal-from"), label = { Text(stringResource(R.string.temporal_from)) })
            OutlinedTextField(toText, { toText = it; invalidateTemporalResults() }, Modifier.weight(1f).testTag("temporal-to"), label = { Text(stringResource(R.string.temporal_to)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { runCatching { search(false) }.onFailure { error = it.message } } }, modifier = Modifier.testTag("temporal-search")) { Text(stringResource(R.string.temporal_search)) }
            OutlinedButton(onClick = { saving = !saving }, enabled = entries.any { it.selectable }) {
                Text(stringResource(if (saving) R.string.temporal_cancel_selection else R.string.temporal_save_episode))
            }
            OutlinedButton(onClick = {
                runCatching {
                    val from = parseHubDateTime(fromText)
                    val to = parseHubDateTime(toText)
                    require(from < to)
                    val zone = ZoneId.systemDefault()
                    val uri = HubDeepLinkContract.searchUri(
                        fromIso = Instant.ofEpochMilli(from).atZone(zone).toOffsetDateTime().toString(),
                        toIso = Instant.ofEpochMilli(to).atZone(zone).toOffsetDateTime().toString(),
                        modules = selected,
                    )
                    copyTemporalLink(context, uri.toString())
                }.onFailure { error = it.message }
            }) { Text(stringResource(R.string.deep_link_copy)) }
        }
        if (saving) {
            OutlinedTextField(episodeTitle, { episodeTitle = it }, Modifier.fillMaxWidth().testTag("temporal-episode-title"), label = { Text(stringResource(R.string.temporal_episode_title)) })
            Button(
                onClick = {
                    scope.launch {
                        val refs = selectedRefs.mapNotNull(::decodeRef).distinct()
                        runCatching { HubContextRuntime.createContext(refs.map { it to "" }, title = episodeTitle) }
                            .onSuccess {
                                saving = false
                                selectedRefs = emptyList()
                                episodeTitle = ""
                                loadSavedEpisodes()
                            }
                            .onFailure { error = it.message }
                    }
                },
                enabled = episodeTitle.isNotBlank() && selectedRefs.distinct().size >= 2,
                modifier = Modifier.testTag("temporal-save-selection"),
            ) { Text(stringResource(R.string.temporal_save_selection)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("saved-episodes-title") {
                Text(stringResource(R.string.temporal_saved_episodes), style = MaterialTheme.typography.titleMedium)
            }
            if (savedEpisodes.isEmpty()) {
                item("saved-episodes-empty") {
                    Text(stringResource(R.string.temporal_saved_episodes_empty), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                items(savedEpisodes, key = { "episode:${it.contextId}" }) { episode ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingEpisodeContextId = episode.contextId }
                            .testTag("temporal-saved-episode-${episode.contextId}"),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(episode.title, style = MaterialTheme.typography.titleMedium)
                            if (episode.members.isNotBlank()) Text(episode.members, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (entries.isEmpty()) item { Text(stringResource(R.string.temporal_empty), modifier = Modifier.testTag("temporal-empty")) }
            groupedTemporalEntries(entries).forEach { (section, sectionEntries) ->
                item(section) {
                    val collapsed = section in collapsedSections
                    TextButton(
                        onClick = {
                            collapsedSections = if (collapsed) collapsedSections - section else collapsedSections + section
                        },
                        modifier = Modifier.fillMaxWidth().testTag("temporal-section-$section"),
                    ) { Text("${if (collapsed) "+" else "-"} $section") }
                }
                if (section !in collapsedSections) items(sectionEntries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (saving && entry.selectable) {
                                val encoded = entry.refs.map(::encodeRef)
                                val checked = encoded.any { it in selectedRefs }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { enabled ->
                                        selectedRefs = if (enabled) (selectedRefs + encoded).distinct() else selectedRefs - encoded.toSet()
                                    },
                                    modifier = Modifier.testTag("temporal-check-${entry.id}"),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                entry.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
            if (cursors.any { (moduleId, cursor) -> moduleId in selected && cursor != null }) item {
                TextButton(onClick = { scope.launch { runCatching { search(true) }.onFailure { error = it.message } } }) { Text(stringResource(R.string.temporal_more)) }
            }
        }
    }
}

private fun copyTemporalLink(context: Context, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.temporal_title), value))
    Toast.makeText(context, R.string.deep_link_copied, Toast.LENGTH_SHORT).show()
}
