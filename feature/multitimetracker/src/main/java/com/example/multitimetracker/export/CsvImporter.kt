// v471
// v355
package com.example.multitimetracker.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import org.json.JSONArray
import org.json.JSONObject
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.LifePeriodDisplayUnit
import com.example.multitimetracker.model.DEFAULT_LIFE_PERIOD_COLOR_ARGB
import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldType
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.model.TaskChain
import com.example.multitimetracker.model.TaskChainStep
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import com.example.multitimetracker.model.TimeEngine

/**
 * Importa i CSV prodotti da [CsvExporter] e ricostruisce lo snapshot in RAM.
 *
 * File attesi (nomi esatti come export):
 * - sessions.csv
 * - totals.csv (opzionale, solo validazione)
 * - tag_sessions.csv
 * - tag_totals.csv (opzionale, solo validazione)
 * - app_usage.csv (opzionale)
 * - dict.json (opzionale ma consigliato: anagrafica task/tag + associazioni)
 */
object CsvImporter {
    private fun pickByNameOrPrefix(byLowerName: Map<String, Uri>, exactName: String): Uri? {
        val key = exactName.lowercase()
        byLowerName[key]?.let { return it }
        // Tolleranza: file duplicati come "app_usage (1).csv" oppure nomi case-insensitive.
        return byLowerName.entries.firstOrNull { (k, _) -> k.startsWith(key.removeSuffix(".csv")) }?.value
    }

    private fun pickDocByNameOrPrefix(byLowerName: Map<String, DocumentFile>, exactName: String): DocumentFile? {
        val key = exactName.lowercase()
        byLowerName[key]?.let { return it }
        return byLowerName.entries.firstOrNull { (k, _) -> k.startsWith(key.removeSuffix(".csv")) }?.value
    }

    private const val LOG_TAG = "MT_IMPORT"

    private fun i(msg: String) = Log.i(LOG_TAG, msg)
    private fun w(msg: String) = Log.w(LOG_TAG, msg)
    private fun e(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(LOG_TAG, msg, tr) else Log.e(LOG_TAG, msg)
    }

    private data class DictPayload(
        val tasks: List<Task>,
        val tags: List<Tag>,
        val tagParentsByChild: Map<Long, Set<Long>>
    )

    private data class RuntimePayload(
        val timeFenceRules: List<TimeFenceRule>,
        val lifePeriods: List<LifePeriod>,
        val chains: List<TaskChain>,
        val activeChainRun: ActiveChainRun?
    )

    private data class QuickEventsPayload(
        val templates: List<QuickEventTemplate>,
        val entries: List<QuickEventEntry>,
        val fieldDefinitions: List<QuickEventFieldDefinition> = emptyList(),
        val fieldValues: List<QuickEventFieldValue> = emptyList(),
        val macros: List<QuickEventMacro> = emptyList(),
        val macroActions: List<QuickEventMacroAction> = emptyList()
    )

    data class ImportedSnapshot(
        val tasks: List<Task>,
        val tags: List<Tag>,
        val closedSessions: List<ClosedSessionRecord>,
        val tagSessions: List<TaggedSessionRecord>,
        val tagParentsByChild: Map<Long, Set<Long>>,
        val timeFenceRules: List<TimeFenceRule> = emptyList(),
        val lifePeriods: List<LifePeriod> = emptyList(),
        val chains: List<TaskChain> = emptyList(),
        val activeChainRun: ActiveChainRun? = null,
        val quickEventTemplates: List<QuickEventTemplate> = emptyList(),
        val quickEventEntries: List<QuickEventEntry> = emptyList(),
        val quickEventFieldDefinitions: List<QuickEventFieldDefinition> = emptyList(),
        val quickEventFieldValues: List<QuickEventFieldValue> = emptyList(),
        val quickEventMacros: List<QuickEventMacro> = emptyList(),
        val quickEventMacroActions: List<QuickEventMacroAction> = emptyList(),
        val appUsageMs: Long,
        val runtimeSnapshot: TimeEngine.RuntimeSnapshot?
    )

    
fun importFromUris(context: Context, uris: List<Uri>): ImportedSnapshot {
    if (uris.isEmpty()) throw IllegalArgumentException("Nessun file selezionato")

    i("importFromUris: START uris=${uris.size}")

    val byLowerName = uris.associateBy { uri -> (displayName(context, uri) ?: uri.lastPathSegment.orEmpty()).lowercase() }
    i("importFromUris: files=${byLowerName.keys.sorted().joinToString(",")}")

    // Manifest-driven import (preferred). Falls back to legacy behavior when manifest is missing.
    val manifestUri = pickByNameOrPrefix(byLowerName, BackupSchema.MANIFEST_FILE)
    val manifest = manifestUri?.let { uri ->
        runCatching { BackupSchema.parseManifestJson(readText(context, uri)) }
            .onFailure { ex -> e("importFromUris: manifest.json parse failed uri=$uri", ex) }
            .getOrNull()
    }
    val manifestFiles: List<BackupSchema.Entry> = manifest?.files?.takeIf { it.isNotEmpty() } ?: BackupSchema.entries

    var dict: DictPayload? = null
    var runtimePayload: RuntimePayload? = null
    var quickEventsPayload: QuickEventsPayload? = null
    var closedSessions: List<ClosedSessionRecord> = emptyList()
    var tagSessions: List<TaggedSessionRecord> = emptyList()
    var appUsageMs: Long = 0L

    for (entry in manifestFiles) {
        val uri = pickByNameOrPrefix(byLowerName, entry.name)
        if (uri == null) {
            if (entry.required) throw IllegalArgumentException("Manca ${entry.name}")
            continue
        }

        when (entry.handlerId) {
            "dict_v1" -> {
                dict = runCatching { parseDictJson(readText(context, uri)) }
                    .onFailure { ex -> e("importFromUris: dict.json parse failed uri=$uri", ex) }
                    .getOrNull()
            }
            "runtime_v1" -> {
                runtimePayload = runCatching { parseRuntimeJson(readText(context, uri)) }
                    .onFailure { ex -> e("importFromUris: runtime.json parse failed uri=$uri", ex) }
                    .getOrNull()
            }
            "sessions_v1" -> closedSessions = parseClosedSessionRecords(readLines(context, uri))
            "tag_sessions_v1" -> tagSessions = parseTaggedSessionRecords(readLines(context, uri))
            "app_usage_v1" -> appUsageMs = parseAppUsage(readLines(context, uri))
            "quick_events_v1", "quick_events_v2" -> {
                quickEventsPayload = runCatching { parseQuickEventsJson(readText(context, uri)) }
                    .onFailure { ex -> e("importFromUris: quick_events.json parse failed uri=$uri", ex) }
                    .getOrNull()
            }
            else -> {
                // ignore (e.g., totals.csv validation-only)
            }
        }
    }

    i(
        "importFromUris: manifest=${if (manifest == null) "NO" else "YES"} " +
            "tasks=${dict?.tasks?.size ?: 0} tags=${dict?.tags?.size ?: 0}"
    )
    i("importFromUris: sessions.csv rows=${closedSessions.size} tag_sessions.csv rows=${tagSessions.size}")

    // Extra-file diagnostics (only meaningful when manifest exists)
    if (manifest != null) {
        val known = manifestFiles.map { it.name.lowercase() }.toSet() + BackupSchema.MANIFEST_FILE.lowercase()
        val extras = byLowerName.keys.filter { it !in known }.sorted()
        if (extras.isNotEmpty()) w("importFromUris: extra files not in manifest: ${extras.joinToString(",")}")
    }

    val baseOut = buildSnapshot(
        baseTasks = dict?.tasks,
        baseTags = dict?.tags,
        baseTagParentsByChild = dict?.tagParentsByChild,
        closedSessions = closedSessions,
        tagSessions = tagSessions,
        appUsageMs = appUsageMs
    )

    val out = baseOut.copy(
        timeFenceRules = runtimePayload?.timeFenceRules ?: emptyList(),
        lifePeriods = runtimePayload?.lifePeriods ?: emptyList(),
        chains = runtimePayload?.chains ?: emptyList(),
        activeChainRun = runtimePayload?.activeChainRun,
        quickEventTemplates = quickEventsPayload?.templates ?: emptyList(),
        quickEventEntries = quickEventsPayload?.entries ?: emptyList(),
        quickEventFieldDefinitions = quickEventsPayload?.fieldDefinitions ?: emptyList(),
        quickEventFieldValues = quickEventsPayload?.fieldValues ?: emptyList(),
        quickEventMacros = quickEventsPayload?.macros ?: emptyList(),
        quickEventMacroActions = quickEventsPayload?.macroActions ?: emptyList()
    )

    i(
        "importFromUris: END tasks=${out.tasks.size} tags=${out.tags.size} " +
            "closedSessions=${out.closedSessions.size} tagSessions=${out.tagSessions.size} " +
            "runtimeSnapshot=${out.runtimeSnapshot != null}"
    )
    return out
}

/**
     * Import directly from the persistent backup folder (MultiTimer data) without any file picker.
     */
    
fun importFromBackupFolder(context: Context, dir: DocumentFile): ImportedSnapshot {
    i("importFromBackupFolder: START dir='${dir.name ?: "(null)"}' uri=${dir.uri}")

    val byLowerName: Map<String, DocumentFile> = dir.listFiles()
        .mapNotNull { f ->
            val n = f.name?.lowercase()
            if (n == null) null else (n to f)
        }
        .toMap()

    val manifestDoc = pickDocByNameOrPrefix(byLowerName, BackupSchema.MANIFEST_FILE)
    val manifest = manifestDoc
        ?.takeIf { it.canRead() }
        ?.let { doc -> runCatching { BackupSchema.parseManifestJson(readText(context, doc.uri)) }.getOrNull() }

    val manifestFiles: List<BackupSchema.Entry> = manifest?.files?.takeIf { it.isNotEmpty() } ?: BackupSchema.entries

    var dict: DictPayload? = null
    var runtimePayload: RuntimePayload? = null
    var quickEventsPayload: QuickEventsPayload? = null
    var closedSessions: List<ClosedSessionRecord> = emptyList()
    var tagSessions: List<TaggedSessionRecord> = emptyList()
    var appUsageMs: Long = 0L

    for (entry in manifestFiles) {
        val doc = pickDocByNameOrPrefix(byLowerName, entry.name)
        if (doc == null) {
            if (entry.required) throw IllegalArgumentException("Manca ${entry.name} in '${dir.name ?: "backup"}'")
            continue
        }
        if (!doc.canRead()) continue

        when (entry.handlerId) {
            "dict_v1" -> dict = runCatching { parseDictJson(readText(context, doc.uri)) }.getOrNull()
            "runtime_v1" -> runtimePayload = runCatching { parseRuntimeJson(readText(context, doc.uri)) }.getOrNull()
            "sessions_v1" -> closedSessions = parseClosedSessionRecords(readLines(context, doc.uri))
            "tag_sessions_v1" -> tagSessions = parseTaggedSessionRecords(readLines(context, doc.uri))
            "app_usage_v1" -> appUsageMs = parseAppUsage(readLines(context, doc.uri))
            "quick_events_v1", "quick_events_v2" -> quickEventsPayload = runCatching { parseQuickEventsJson(readText(context, doc.uri)) }.getOrNull()
            else -> {
                // ignore
            }
        }
    }

    i(
        "importFromBackupFolder: manifest=${if (manifest == null) "NO" else "YES"} " +
            "tasks=${dict?.tasks?.size ?: 0} tags=${dict?.tags?.size ?: 0}"
    )
    i("importFromBackupFolder: sessions.csv rows=${closedSessions.size} tag_sessions.csv rows=${tagSessions.size}")

    if (manifest != null) {
        val known = manifestFiles.map { it.name.lowercase() }.toSet() + BackupSchema.MANIFEST_FILE.lowercase()
        val extras = byLowerName.keys.filter { it !in known }.sorted()
        if (extras.isNotEmpty()) w("importFromBackupFolder: extra files not in manifest: ${extras.joinToString(",")}")
    }

    val baseOut = buildSnapshot(
        baseTasks = dict?.tasks,
        baseTags = dict?.tags,
        baseTagParentsByChild = dict?.tagParentsByChild,
        closedSessions = closedSessions,
        tagSessions = tagSessions,
        appUsageMs = appUsageMs
    )

    val out = baseOut.copy(
        timeFenceRules = runtimePayload?.timeFenceRules ?: emptyList(),
        lifePeriods = runtimePayload?.lifePeriods ?: emptyList(),
        chains = runtimePayload?.chains ?: emptyList(),
        activeChainRun = runtimePayload?.activeChainRun,
        quickEventTemplates = quickEventsPayload?.templates ?: emptyList(),
        quickEventEntries = quickEventsPayload?.entries ?: emptyList(),
        quickEventFieldDefinitions = quickEventsPayload?.fieldDefinitions ?: emptyList(),
        quickEventFieldValues = quickEventsPayload?.fieldValues ?: emptyList(),
        quickEventMacros = quickEventsPayload?.macros ?: emptyList(),
        quickEventMacroActions = quickEventsPayload?.macroActions ?: emptyList()
    )

    i(
        "importFromBackupFolder: END tasks=${out.tasks.size} tags=${out.tags.size} " +
            "closedSessions=${out.closedSessions.size} tagSessions=${out.tagSessions.size} " +
            "runtimeSnapshot=${out.runtimeSnapshot != null}"
    )
    return out
}

private fun buildSnapshot(
        baseTasks: List<Task>?,
        baseTags: List<Tag>?,
        baseTagParentsByChild: Map<Long, Set<Long>>?,
        closedSessions: List<ClosedSessionRecord>,
        tagSessions: List<TaggedSessionRecord>,
        appUsageMs: Long
    ): ImportedSnapshot {

        i(
            "buildSnapshot: START baseTasks=${baseTasks?.size ?: 0} baseTags=${baseTags?.size ?: 0} " +
                "closedSessions=${closedSessions.size} tagSessions=${tagSessions.size}"
        )

        // Start from dict.json if present (authoritative for structure).
        val tagsById: LinkedHashMap<Long, Tag> = linkedMapOf()
        val tasksById: LinkedHashMap<Long, Task> = linkedMapOf()

        baseTags?.forEach { t ->
            // Keep totals + lastStartedAtMs from dict.json; just reset UI-only fields.
            tagsById[t.id] = t.copy(activeChildrenCount = 0)
        }
        baseTasks?.forEach { t ->
            // Keep isRunning/totalMs/lastStartedAtMs from dict.json (authoritative).
            tasksById[t.id] = t
        }

        i("buildSnapshot: after dict.json -> tasks=${tasksById.size} tags=${tagsById.size}")

        // Fallback/merge from tag_sessions.csv (legacy support)
        val createdTasksFromTaggedSessionRecords = ArrayList<Long>(16)
        val createdTagsFromTaggedSessionRecords = ArrayList<Long>(16)
        tagSessions.forEach { s ->
            if (!tagsById.containsKey(s.tagId)) {
                tagsById[s.tagId] = Tag(
                    id = s.tagId,
                    name = s.tagName,
                    activeChildrenCount = 0,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
                createdTagsFromTaggedSessionRecords.add(s.tagId)
            }
            if (!tasksById.containsKey(s.sessionId)) {
                tasksById[s.sessionId] = Task(
                    id = s.sessionId,
                    name = s.sessionTitle,
                    tagIds = setOf(s.tagId),
                    link = "",
                    isRunning = false,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
                createdTasksFromTaggedSessionRecords.add(s.sessionId)
            } else {
                // Ensure the association is present (dict might be missing it).
                val cur = tasksById[s.sessionId] ?: return@forEach
                if (!cur.tagIds.contains(s.tagId)) {
                    tasksById[s.sessionId] = cur.copy(tagIds = cur.tagIds + s.tagId)
                }
            }
        }

        if (createdTagsFromTaggedSessionRecords.isNotEmpty() || createdTasksFromTaggedSessionRecords.isNotEmpty()) {
            w(
                "buildSnapshot: created from tag_sessions.csv -> " +
                    "tags=${createdTagsFromTaggedSessionRecords.size} tasks=${createdTasksFromTaggedSessionRecords.size} " +
                    "(firstTags=${createdTagsFromTaggedSessionRecords.take(12)}, firstTasks=${createdTasksFromTaggedSessionRecords.take(12)})"
            )
        }

        // Also merge task names from sessions.csv if needed.
        val createdTasksFromClosedSessionRecords = ArrayList<Long>(16)
        closedSessions.forEach { s ->
            val cur = tasksById[s.sessionId]
            if (cur == null) {
                tasksById[s.sessionId] = Task(
                    id = s.sessionId,
                    name = s.sessionTitle,
                    tagIds = emptySet(),
                    link = "",
                    isRunning = false,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
                createdTasksFromClosedSessionRecords.add(s.sessionId)
            } else if (cur.name.startsWith("task_")) {
                tasksById[s.sessionId] = cur.copy(name = s.sessionTitle)
            }
        }

        if (createdTasksFromClosedSessionRecords.isNotEmpty()) {
            w(
                "buildSnapshot: created from sessions.csv -> tasks=${createdTasksFromClosedSessionRecords.size} " +
                    "(firstTasks=${createdTasksFromClosedSessionRecords.take(12)})"
            )
        }

        i("buildSnapshot: after merges -> tasks=${tasksById.size} tags=${tagsById.size}")

        // Totals (authoritative from sessions content)
        val taskTotals = closedSessions
            .groupBy { it.sessionId }
            .mapValues { (_, v) -> v.sumOf { (it.endTs - it.startTs).coerceAtLeast(0L) } }

        val tagTotals = tagSessions
            .groupBy { it.tagId }
            .mapValues { (_, v) -> v.sumOf { (it.endTs - it.startTs).coerceAtLeast(0L) } }

        // Apply totals
        val tasks = tasksById.values
            .sortedBy { it.id }
            .map { t -> t.copy(totalMs = taskTotals[t.id] ?: t.totalMs) }

        val tags = tagsById.values
            .sortedBy { it.id }
            .map { t -> t.copy(totalMs = tagTotals[t.id] ?: t.totalMs, activeChildrenCount = 0) }

        val activeSessionStart = tasks
            .filter { it.isRunning && it.lastStartedAtMs != null }
            .associate { it.id to (it.lastStartedAtMs ?: 0L) }

        val activeTagStart = tasks
            .filter { it.isRunning && it.lastStartedAtMs != null }
            .flatMap { t ->
                val start = t.lastStartedAtMs ?: return@flatMap emptyList()
                t.tagIds.map { tagId -> Triple(t.id, tagId, start) }
            }

        val runtime = if (activeSessionStart.isEmpty() && activeTagStart.isEmpty()) {
            null
        } else {
            TimeEngine.RuntimeSnapshot(activeSessionStart = activeSessionStart, activeTagStart = activeTagStart)
        }

        i(
            "buildSnapshot: END tasks=${tasks.size} tags=${tags.size} " +
                "taskTotalsKeys=${taskTotals.size} tagTotalsKeys=${tagTotals.size} runtime=${runtime != null}"
        )

        return ImportedSnapshot(
            tasks = tasks,
            tags = tags,
            closedSessions = closedSessions,
            tagSessions = tagSessions,
            tagParentsByChild = baseTagParentsByChild ?: emptyMap(),
            appUsageMs = appUsageMs,
            lifePeriods = emptyList(),
            quickEventTemplates = emptyList(),
            quickEventEntries = emptyList(),
            runtimeSnapshot = runtime
        )
    }


    private fun displayName(context: Context, uri: Uri): String? {
        val cr = context.contentResolver
        val c = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return null
        c.use {
            if (!it.moveToFirst()) return null
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx < 0) return null
            return it.getString(idx)
        }
    }

    private fun readText(context: Context, uri: Uri): String {
        val cr = context.contentResolver
        cr.openInputStream(uri).use { input ->
            if (input == null) throw IllegalArgumentException("Impossibile leggere file: $uri")
            return input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun parseDictJson(text: String): DictPayload {
        val root = JSONObject(text)

        val tagsJson = root.optJSONArray("tags") ?: JSONArray()
        val tags = ArrayList<Tag>(tagsJson.length())
        for (i in 0 until tagsJson.length()) {
            val o = tagsJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            val name = o.optString("name", "").trim()
            if (id == Long.MIN_VALUE || name.isBlank()) continue
            val totalMs = o.optLong("totalMs", 0L)
            val lastStarted = if (o.isNull("lastStartedAtMs")) null else o.optLong("lastStartedAtMs")
            val isArchived = o.optBoolean("isArchived", false)
            val isDeleted = o.optBoolean("isDeleted", false)
            val deletedAtMs = if (o.isNull("deletedAtMs")) null else o.optLong("deletedAtMs")
            val restoreIdsJson = o.optJSONArray("restoreSessionIds")
            val restoreSessionIds = LinkedHashSet<Long>()
            if (restoreIdsJson != null) {
                for (j in 0 until restoreIdsJson.length()) {
                    val v = restoreIdsJson.optLong(j, Long.MIN_VALUE)
                    if (v != Long.MIN_VALUE) restoreSessionIds.add(v)
                }
            }
            tags.add(
                Tag(
                    id = id,
                    name = name,
                    isArchived = isArchived,
                    isDeleted = isDeleted,
                    deletedAtMs = deletedAtMs,
                    restoreSessionIds = restoreSessionIds,
                    activeChildrenCount = 0,
                    totalMs = totalMs,
                    lastStartedAtMs = lastStarted
                )
            )
        }

        val tasksJson = root.optJSONArray("tasks") ?: JSONArray()
        val tasks = ArrayList<Task>(tasksJson.length())
        for (i in 0 until tasksJson.length()) {
            val o = tasksJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            val name = o.optString("name", "").trim()
            val taskLink = o.optString("link", "").trim()
            // IMPORTANT: task titles may be empty ("Senza titolo").
            if (id == Long.MIN_VALUE) continue

            val tagIdsJson = o.optJSONArray("tagIds")
            val tagIds = LinkedHashSet<Long>()
            if (tagIdsJson != null) {
                for (j in 0 until tagIdsJson.length()) {
                    val v = tagIdsJson.optLong(j, Long.MIN_VALUE)
                    if (v != Long.MIN_VALUE) tagIds.add(v)
                }
            }

            val isRunning = o.optBoolean("isRunning", false)
            val totalMs = o.optLong("totalMs", 0L)
            val lastStarted = if (o.isNull("lastStartedAtMs")) null else o.optLong("lastStartedAtMs")
            val isDeleted = o.optBoolean("isDeleted", false)
            val deletedAtMs = if (o.isNull("deletedAtMs")) null else o.optLong("deletedAtMs")
            tasks.add(
                Task(
                    id = id,
                    name = name,
                    link = taskLink,
                    tagIds = tagIds,
                    isDeleted = isDeleted,
                    deletedAtMs = deletedAtMs,
                    isRunning = isRunning,
                    totalMs = totalMs,
                    lastStartedAtMs = lastStarted
                )
            )
        }

        val edgesJson = root.optJSONArray("tagParents") ?: JSONArray()
        val parentsByChild = LinkedHashMap<Long, MutableSet<Long>>()
        for (i in 0 until edgesJson.length()) {
            val o = edgesJson.optJSONObject(i) ?: continue
            val childId = o.optLong("childId", Long.MIN_VALUE)
            val parentId = o.optLong("parentId", Long.MIN_VALUE)
            if (childId == Long.MIN_VALUE || parentId == Long.MIN_VALUE) continue
            if (childId <= 0L || parentId <= 0L || childId == parentId) continue
            val set = parentsByChild.getOrPut(childId) { linkedSetOf() }
            set.add(parentId)
        }

        return DictPayload(tasks = tasks, tags = tags, tagParentsByChild = parentsByChild.mapValues { it.value.toSet() })
    }

    private fun parseRuntimeJson(text: String): RuntimePayload {
        val root = JSONObject(text)

        val rulesJson = root.optJSONArray("timeFenceRules") ?: JSONArray()
        val rules = ArrayList<TimeFenceRule>(rulesJson.length())
        for (i in 0 until rulesJson.length()) {
            val o = rulesJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            if (id == Long.MIN_VALUE) continue
            val message = o.optString("message", "")
            val trigger = runCatching { TimeFenceTrigger.valueOf(o.optString("trigger", TimeFenceTrigger.ON_START.name)) }
                .getOrDefault(TimeFenceTrigger.ON_START)
            val delivery = runCatching { TimeFenceDelivery.valueOf(o.optString("delivery", TimeFenceDelivery.PREFENCE.name)) }
                .getOrDefault(TimeFenceDelivery.PREFENCE)
            val scope = runCatching { TimeFenceScope.valueOf(o.optString("scope", TimeFenceScope.ALWAYS.name)) }
                .getOrDefault(TimeFenceScope.ALWAYS)
            val matchMode = runCatching { TimeFenceMatchMode.valueOf(o.optString("matchMode", TimeFenceMatchMode.AND.name)) }
                .getOrDefault(TimeFenceMatchMode.AND)
            val timerMinutes = o.optInt("timerMinutes", 0)
            val isEnabled = o.optBoolean("isEnabled", true)
            val cooldownMs = o.optLong("cooldownMs", 0L)
            val lastFiredAtMs = if (o.isNull("lastFiredAtMs")) null else o.optLong("lastFiredAtMs")
            val tagIdsJson = o.optJSONArray("tagIds")
            val tagIds = LinkedHashSet<Long>()
            if (tagIdsJson != null) {
                for (j in 0 until tagIdsJson.length()) {
                    val v = tagIdsJson.optLong(j, Long.MIN_VALUE)
                    if (v != Long.MIN_VALUE) tagIds.add(v)
                }
            }
            rules.add(
                TimeFenceRule(
                    id = id,
                    message = message,
                    trigger = trigger,
                    delivery = delivery,
                    scope = scope,
                    matchMode = matchMode,
                    tagIds = tagIds,
                    timerMinutes = timerMinutes,
                    isEnabled = isEnabled,
                    cooldownMs = cooldownMs,
                    lastFiredAtMs = lastFiredAtMs
                )
            )
        }

        val periodsJson = root.optJSONArray("lifePeriods") ?: JSONArray()
        val lifePeriods = ArrayList<LifePeriod>(periodsJson.length())
        for (i in 0 until periodsJson.length()) {
            val o = periodsJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            if (id == Long.MIN_VALUE) continue
            val tagIdsJson = o.optJSONArray("tagIds")
            val tagIds = LinkedHashSet<Long>()
            if (tagIdsJson != null) {
                for (j in 0 until tagIdsJson.length()) {
                    val v = tagIdsJson.optLong(j, Long.MIN_VALUE)
                    if (v != Long.MIN_VALUE && v > 0L) tagIds.add(v)
                }
            }
            val displayUnitsJson = o.optJSONArray("displayUnits")
            val displayUnits = LinkedHashSet<LifePeriodDisplayUnit>()
            if (displayUnitsJson != null) {
                for (j in 0 until displayUnitsJson.length()) {
                    runCatching { LifePeriodDisplayUnit.valueOf(displayUnitsJson.optString(j)) }.getOrNull()?.let { displayUnits.add(it) }
                }
            }
            if (displayUnits.isEmpty()) displayUnits.add(LifePeriodDisplayUnit.DAYS)
            lifePeriods.add(
                LifePeriod(
                    id = id,
                    title = o.optString("title", ""),
                    description = o.optString("description", ""),
                    startMs = o.optLong("startMs"),
                    endMs = if (o.isNull("endMs")) null else o.optLong("endMs"),
                    colorArgb = o.optLong("colorArgb", DEFAULT_LIFE_PERIOD_COLOR_ARGB),
                    tagIds = tagIds,
                    displayUnits = displayUnits
                )
            )
        }

        val chainsJson = root.optJSONArray("chains") ?: JSONArray()
        val chains = ArrayList<TaskChain>(chainsJson.length())
        for (i in 0 until chainsJson.length()) {
            val o = chainsJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            if (id == Long.MIN_VALUE) continue
            val name = o.optString("name", "")
            val isDeleted = o.optBoolean("isDeleted", false)
            val deletedAtMs = if (o.isNull("deletedAtMs")) null else o.optLong("deletedAtMs")
            val stepsJson = o.optJSONArray("steps") ?: JSONArray()
            val steps = ArrayList<TaskChainStep>(stepsJson.length())
            for (j in 0 until stepsJson.length()) {
                val so = stepsJson.optJSONObject(j) ?: continue
                val stepName = so.optString("name", "")
                val stepLink = so.optString("link", "")
                val stTagsJson = so.optJSONArray("tagIds")
                val stTags = LinkedHashSet<Long>()
                if (stTagsJson != null) {
                    for (k in 0 until stTagsJson.length()) {
                        val v = stTagsJson.optLong(k, Long.MIN_VALUE)
                        if (v != Long.MIN_VALUE) stTags.add(v)
                    }
                }
                steps.add(TaskChainStep(name = stepName, link = stepLink, tagIds = stTags))
            }
            chains.add(TaskChain(id = id, name = name, steps = steps, isDeleted = isDeleted, deletedAtMs = deletedAtMs))
        }

        val acrObj = if (root.isNull("activeChainRun")) null else root.optJSONObject("activeChainRun")
        val activeChainRun = if (acrObj == null) {
            null
        } else {
            val chainId = acrObj.optLong("chainId", Long.MIN_VALUE)
            val stepIndex = acrObj.optInt("stepIndex", -1)
            val currentSessionId = acrObj.optLong("currentSessionId", Long.MIN_VALUE)
            if (chainId == Long.MIN_VALUE || currentSessionId == Long.MIN_VALUE || stepIndex < 0) {
                null
            } else {
                ActiveChainRun(chainId = chainId, stepIndex = stepIndex, currentSessionId = currentSessionId)
            }
        }

        return RuntimePayload(timeFenceRules = rules, lifePeriods = lifePeriods, chains = chains, activeChainRun = activeChainRun)
    }

    private fun parseQuickEventsJson(text: String): QuickEventsPayload {
        val root = JSONObject(text)
        val templatesJson = root.optJSONArray("templates") ?: JSONArray()
        val templates = ArrayList<QuickEventTemplate>(templatesJson.length())
        for (i in 0 until templatesJson.length()) {
            val o = templatesJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            val title = o.optString("title", "").trim()
            if (id == Long.MIN_VALUE || title.isBlank()) continue
            templates.add(
                QuickEventTemplate(
                    id = id,
                    title = title,
                    tagIds = o.optLongSet("tagIds"),
                    sortOrder = o.optInt("sortOrder", 0),
                    isArchived = o.optBoolean("isArchived", false),
                    deletedAtMs = if (o.isNull("deletedAtMs")) null else o.optLong("deletedAtMs")
                )
            )
        }

        val entriesJson = root.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<QuickEventEntry>(entriesJson.length())
        for (i in 0 until entriesJson.length()) {
            val o = entriesJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            val title = o.optString("title", "").trim()
            val timestampMs = o.optLong("timestampMs", Long.MIN_VALUE)
            if (id == Long.MIN_VALUE || timestampMs == Long.MIN_VALUE || title.isBlank()) continue
            entries.add(
                QuickEventEntry(
                    id = id,
                    templateId = if (o.isNull("templateId")) null else o.optLong("templateId"),
                    macroId = if (o.isNull("macroId")) null else o.optLong("macroId"),
                    title = title,
                    timestampMs = timestampMs,
                    tagIds = o.optLongSet("tagIds"),
                    deletedAtMs = if (o.isNull("deletedAtMs")) null else o.optLong("deletedAtMs")
                )
            )
        }
        val fieldsJson = root.optJSONArray("fields") ?: root.optJSONArray("fieldDefinitions") ?: JSONArray()
        val fields = ArrayList<QuickEventFieldDefinition>(fieldsJson.length())
        for (i in 0 until fieldsJson.length()) {
            val o = fieldsJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            val templateId = o.optLong("templateId", Long.MIN_VALUE)
            val label = o.optString("label", "").trim()
            if (id == Long.MIN_VALUE || templateId == Long.MIN_VALUE || label.isBlank()) continue
            fields.add(
                QuickEventFieldDefinition(
                    id = id,
                    templateId = templateId,
                    label = label,
                    type = runCatching { QuickEventFieldType.valueOf(o.optString("type", "TEXT")) }.getOrDefault(QuickEventFieldType.TEXT),
                    required = o.optBoolean("required", false),
                    defaultValue = o.optString("defaultValue", ""),
                    choiceOptions = o.optStringList("choiceOptions"),
                    displayOrder = o.optInt("displayOrder", 0),
                    deletedAtMs = if (o.isNull("deletedAtMs")) null else o.optLong("deletedAtMs")
                )
            )
        }

        val fieldValuesJson = root.optJSONArray("fieldValues") ?: JSONArray()
        val fieldValues = ArrayList<QuickEventFieldValue>(fieldValuesJson.length())
        for (i in 0 until fieldValuesJson.length()) {
            val o = fieldValuesJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            val entryId = o.optLong("entryId", Long.MIN_VALUE)
            val fieldId = o.optLong("fieldId", Long.MIN_VALUE)
            if (id == Long.MIN_VALUE || entryId == Long.MIN_VALUE || fieldId == Long.MIN_VALUE) continue
            fieldValues.add(
                QuickEventFieldValue(
                    id = id,
                    entryId = entryId,
                    fieldId = fieldId,
                    label = o.optString("label", ""),
                    type = runCatching { QuickEventFieldType.valueOf(o.optString("type", "TEXT")) }.getOrDefault(QuickEventFieldType.TEXT),
                    value = o.optString("value", ""),
                    displayOrder = o.optInt("displayOrder", 0)
                )
            )
        }

        val macrosJson = root.optJSONArray("macros") ?: JSONArray()
        val macros = ArrayList<QuickEventMacro>(macrosJson.length())
        for (i in 0 until macrosJson.length()) {
            val o = macrosJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            val title = o.optString("title", "").trim()
            if (id == Long.MIN_VALUE || title.isBlank()) continue
            macros.add(
                QuickEventMacro(
                    id = id,
                    title = title,
                    tagIds = o.optLongSet("tagIds"),
                    sortOrder = o.optInt("sortOrder", 0),
                    isArchived = o.optBoolean("isArchived", false),
                    deletedAtMs = if (o.isNull("deletedAtMs")) null else o.optLong("deletedAtMs")
                )
            )
        }

        val macroActionsJson = root.optJSONArray("macroActions") ?: JSONArray()
        val macroActions = ArrayList<QuickEventMacroAction>(macroActionsJson.length())
        for (i in 0 until macroActionsJson.length()) {
            val o = macroActionsJson.optJSONObject(i) ?: continue
            val macroId = o.optLong("macroId", Long.MIN_VALUE)
            val templateId = o.optLong("templateId", Long.MIN_VALUE)
            if (macroId == Long.MIN_VALUE || templateId == Long.MIN_VALUE) continue
            macroActions.add(
                QuickEventMacroAction(
                    macroId = macroId,
                    templateId = templateId,
                    displayOrder = o.optInt("displayOrder", 0)
                )
            )
        }

        return QuickEventsPayload(
            templates = templates,
            entries = entries,
            fieldDefinitions = fields,
            fieldValues = fieldValues,
            macros = macros,
            macroActions = macroActions
        )
    }

    private fun JSONObject.optStringList(name: String): List<String> {
        val arr = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optLongSet(name: String): Set<Long> {
        val arr = optJSONArray(name) ?: return emptySet()
        val out = LinkedHashSet<Long>()
        for (i in 0 until arr.length()) {
            val v = arr.optLong(i, Long.MIN_VALUE)
            if (v != Long.MIN_VALUE) out.add(v)
        }
        return out
    }

    private fun readLines(context: Context, uri: Uri): List<String> {
        val cr = context.contentResolver
        cr.openInputStream(uri).use { input ->
            if (input == null) throw IllegalArgumentException("Impossibile leggere file: $uri")
            return input.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        }
    }

    private fun parseClosedSessionRecords(lines: List<String>): List<ClosedSessionRecord> {
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().trim()
        if (header != "task_id,task_name,start_ts,end_ts") {
            throw IllegalArgumentException("Header sessions.csv non valido")
        }
        return lines.drop(1).mapNotNull { line ->
            val row = parseCsvLine(line)
            if (row.size < 4) return@mapNotNull null
            ClosedSessionRecord(
                sessionId = row[0].toLong(),
                sessionTitle = row[1],
                startTs = row[2].toLong(),
                endTs = row[3].toLong()
            )
        }
    }

    private fun parseTaggedSessionRecords(lines: List<String>): List<TaggedSessionRecord> {
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().trim()
        if (header != "tag_id,tag_name,task_id,task_name,start_ts,end_ts") {
            throw IllegalArgumentException("Header tag_sessions.csv non valido")
        }
        return lines.drop(1).mapNotNull { line ->
            val row = parseCsvLine(line)
            if (row.size < 6) return@mapNotNull null
            TaggedSessionRecord(
                tagId = row[0].toLong(),
                tagName = row[1],
                sessionId = row[2].toLong(),
                sessionTitle = row[3],
                startTs = row[4].toLong(),
                endTs = row[5].toLong()
            )
        }
    }

    private fun parseAppUsage(lines: List<String>): Long {
        if (lines.isEmpty()) return 0L
        val header = lines.first().trim()
        if (header != "total_ms") {
            throw IllegalArgumentException("Header app_usage.csv non valido")
        }
        val valueLine = lines.drop(1).firstOrNull()?.trim().orEmpty()
        if (valueLine.isBlank()) return 0L
        return runCatching { valueLine.toLong() }.getOrDefault(0L)
    }

    /**
     * CSV parser minimale compatibile con [CsvExporter.csvEscape]:
     * - campi separati da virgola
     * - stringhe tra virgolette con "" come escape
     */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    // escaped quote?
                    val next = if (i + 1 < line.length) line[i + 1] else null
                    if (next == '"') {
                        sb.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    i++
                    continue
                } else {
                    sb.append(c)
                    i++
                    continue
                }
            } else {
                when (c) {
                    ',' -> {
                        out.add(sb.toString())
                        sb.setLength(0)
                        i++
                    }
                    '"' -> {
                        inQuotes = true
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        out.add(sb.toString())
        return out
    }
}
