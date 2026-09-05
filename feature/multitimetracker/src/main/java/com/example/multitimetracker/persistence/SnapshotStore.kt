// v471
// v443
package com.example.multitimetracker.persistence

import android.content.Context
import com.example.multitimetracker.util.CapsuleAudit
import com.example.multitimetracker.util.CapsuleWriteApi
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
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
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TaskChain
import com.example.multitimetracker.model.TaskChainStep
import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimedTagNotificationType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the *authoritative* timing model (TimeCop style): timestamps + open sessions.
 *
 * This enables "auto-resume" after process death: when the app reopens, tasks that were running
 * remain running because their start timestamps are restored.
 */
@OptIn(com.example.multitimetracker.util.CapsuleWriteApi::class)
object SnapshotStore {

    // Legacy (pre-SQLite) storage keys.
    private const val PREFS = "multitimetracker_snapshot"
    private const val KEY_JSON = "snapshot_json"


    private const val SNAPSHOT_FILE = "snapshot.json"

    private fun corruptSnapshotFile(context: Context, nowMs: Long): java.io.File? {
        // Internal storage: always writable and private. Useful for post-mortem debugging.
        return runCatching {
            java.io.File(context.filesDir, "corrupt_snapshot_$nowMs.json")
        }.getOrNull()
    }

    private fun externalSnapshotFile(context: Context): java.io.File? {
        // App-specific external dir is readable by file managers and survives app updates.
        return context.getExternalFilesDir(null)?.let { dir -> java.io.File(dir, SNAPSHOT_FILE) }
    }

    data class Snapshot(
        val tasks: List<Task>,
        val tags: List<Tag>,
        val closedSessions: List<ClosedSessionRecord>,
        val tagSessions: List<TaggedSessionRecord>,
        val lifePeriods: List<LifePeriod> = emptyList(),
        val timeFenceRules: List<TimeFenceRule> = emptyList(),
        val installAtMs: Long,
        val appUsageMs: Long,
        val activeSessionStart: Map<Long, Long>,
        val activeTagStart: List<ActiveTag>,
        val tagParents: List<TagParentEdge>,
        val chains: List<TaskChain> = emptyList(),
        val activeChainRun: ActiveChainRun? = null,
        val chronologySessions: List<SessionUi> = emptyList(),
        val runningSessions: List<SessionUi> = emptyList(),
        val quickEventTemplates: List<QuickEventTemplate> = emptyList(),
        val quickEventEntries: List<QuickEventEntry> = emptyList(),
        val quickEventFieldDefinitions: List<QuickEventFieldDefinition> = emptyList(),
        val quickEventFieldValues: List<QuickEventFieldValue> = emptyList(),
        val quickEventMacros: List<QuickEventMacro> = emptyList(),
        val quickEventMacroActions: List<QuickEventMacroAction> = emptyList()
    )

    data class ActiveTag(
        val sessionId: Long,
        val tagId: Long,
        val startTs: Long
    )

    data class TagParentEdge(
        val childId: Long,
        val parentId: Long
    )

    private fun normalizeTagSessionsAgainstTags(
        tags: List<Tag>,
        tagSessions: List<TaggedSessionRecord>,
    ): List<TaggedSessionRecord> {
        if (tags.isEmpty() || tagSessions.isEmpty()) return tagSessions
        val canonicalNamesById = buildMap<Long, String> {
            tags.forEach { tag ->
                val trimmed = tag.name.trim()
                if (trimmed.isNotEmpty() && trimmed != "tag_${tag.id}") {
                    put(tag.id, trimmed)
                }
            }
        }
        if (canonicalNamesById.isEmpty()) return tagSessions
        return tagSessions.map { record ->
            val canonicalName = canonicalNamesById[record.tagId] ?: return@map record
            if (canonicalName == record.tagName) record else record.copy(tagName = canonicalName)
        }
    }

    private fun preserveNewerTimeFenceRuntimeState(
        incomingRules: List<TimeFenceRule>,
        existingRules: List<TimeFenceRule>,
    ): List<TimeFenceRule> {
        if (incomingRules.isEmpty() || existingRules.isEmpty()) return incomingRules

        val existingById = existingRules.associateBy { it.id }
        return incomingRules.map { incoming ->
            val existing = existingById[incoming.id] ?: return@map incoming
            val incomingLast = incoming.lastFiredAtMs ?: 0L
            val existingLast = existing.lastFiredAtMs ?: 0L
            if (existingLast <= incomingLast) {
                incoming
            } else {
                incoming.copy(
                    lastFiredAtMs = existing.lastFiredAtMs,
                    isEnabled = if (existing.scope == TimeFenceScope.ONE_TIME && !existing.isEnabled) {
                        false
                    } else {
                        incoming.isEnabled
                    }
                )
            }
        }
    }

    @CapsuleWriteApi
    fun save(
        context: Context,
        tasks: List<Task>,
        tags: List<Tag>,
        closedSessions: List<ClosedSessionRecord>,
        tagSessions: List<TaggedSessionRecord>,
        lifePeriods: List<LifePeriod>? = null,
        timeFenceRules: List<TimeFenceRule> = emptyList(),
        installAtMs: Long,
        appUsageMs: Long,
        activeSessionStart: Map<Long, Long>,
        activeTagStart: List<ActiveTag>,
        tagParents: List<TagParentEdge>,
        chains: List<TaskChain> = emptyList(),
        activeChainRun: ActiveChainRun? = null,
        chronologySessions: List<SessionUi> = emptyList(),
        runningSessions: List<SessionUi> = emptyList(),
        quickEventTemplates: List<QuickEventTemplate>? = null,
        quickEventEntries: List<QuickEventEntry>? = null,
        quickEventFieldDefinitions: List<QuickEventFieldDefinition>? = null,
        quickEventFieldValues: List<QuickEventFieldValue>? = null,
        quickEventMacros: List<QuickEventMacro>? = null,
        quickEventMacroActions: List<QuickEventMacroAction>? = null
    ) {
        CapsuleAudit.auditPersistenceWrite("SnapshotStore.save")

        val resolvedLifePeriods = lifePeriods ?: load(context)?.lifePeriods.orEmpty()
        val existingSnapshot = load(context)
        val resolvedQuickEventTemplates = quickEventTemplates ?: existingSnapshot?.quickEventTemplates.orEmpty()
        val resolvedQuickEventEntries = quickEventEntries ?: existingSnapshot?.quickEventEntries.orEmpty()
        val resolvedQuickEventFieldDefinitions = quickEventFieldDefinitions ?: existingSnapshot?.quickEventFieldDefinitions.orEmpty()
        val resolvedQuickEventFieldValues = quickEventFieldValues ?: existingSnapshot?.quickEventFieldValues.orEmpty()
        val resolvedQuickEventMacros = quickEventMacros ?: existingSnapshot?.quickEventMacros.orEmpty()
        val resolvedQuickEventMacroActions = quickEventMacroActions ?: existingSnapshot?.quickEventMacroActions.orEmpty()
        val normalizedTagSessions = normalizeTagSessionsAgainstTags(tags, tagSessions)
        val resolvedTimeFenceRules = preserveNewerTimeFenceRuntimeState(
            incomingRules = timeFenceRules,
            existingRules = existingSnapshot?.timeFenceRules.orEmpty(),
        )

        val root = JSONObject()

        root.put("installAtMs", installAtMs)

        root.put("appUsageMs", appUsageMs)

        root.put("tasks", JSONArray().apply {
            tasks.forEach { t ->
                put(
                    JSONObject()
                        .put("id", t.id)
                        .put("name", t.name)
.put("link", t.link)
.put("tagIds", JSONArray().apply { t.tagIds.forEach { put(it) } })
                        .put("isDeleted", t.isDeleted)
                        .put("deletedAtMs", t.deletedAtMs)
                        .put("isRunning", t.isRunning)
                        .put("totalMs", t.totalMs)
                        .put("lastStartedAtMs", t.lastStartedAtMs)
                )
            }
        })

        root.put("tags", JSONArray().apply {
            tags.forEach { tg ->
                put(
                    JSONObject()
                        .put("id", tg.id)
                        .put("name", tg.name)
                        .put("timedDurationMinutes", tg.timedDurationMinutes ?: JSONObject.NULL)
                        .put("notificationType", tg.notificationType.name)
                        .put("isArchived", tg.isArchived)
                        .put("showInTimeline", tg.showInTimeline)
                        .put("isDeleted", tg.isDeleted)
                        .put("deletedAtMs", tg.deletedAtMs)
                        .put("restoreSessionIds", JSONArray().apply { tg.restoreSessionIds.forEach { put(it) } })
                        .put("activeChildrenCount", tg.activeChildrenCount)
                        .put("totalMs", tg.totalMs)
                        .put("lastStartedAtMs", tg.lastStartedAtMs)
                )
            }
        })

        root.put("closedSessions", JSONArray().apply {
            closedSessions.forEach { s ->
                put(
                    JSONObject()
                        .put("sessionId", s.sessionId)
                        .put("sessionTitle", s.sessionTitle)
                        .put("startTs", s.startTs)
                        .put("endTs", s.endTs)
                )
            }
        })

        root.put("tagSessions", JSONArray().apply {
            normalizedTagSessions.forEach { s ->
                put(
                    JSONObject()
                        .put("tagId", s.tagId)
                        .put("tagName", s.tagName)
                        .put("sessionId", s.sessionId)
                        .put("sessionTitle", s.sessionTitle)
                        .put("startTs", s.startTs)
                        .put("endTs", s.endTs)
                )
            }
        })

        root.put("lifePeriods", JSONArray().apply {
            resolvedLifePeriods.forEach { period ->
                put(
                    JSONObject()
                        .put("id", period.id)
                        .put("title", period.title)
                        .put("description", period.description)
                        .put("startMs", period.startMs)
                        .put("endMs", period.endMs ?: JSONObject.NULL)
                        .put("colorArgb", period.colorArgb)
                        .put("tagIds", JSONArray().apply { period.tagIds.toList().sorted().forEach { put(it) } })
                        .put("displayUnits", JSONArray().apply {
                            LifePeriodDisplayUnit.displayOrder.filter { it in period.displayUnits }.forEach { put(it.name) }
                        })
                )
            }
        })
        // Time-fence rules (promemoria tag-driven su start/stop)
        root.put("timeFenceRules", JSONArray().apply {
            resolvedTimeFenceRules.forEach { r ->
                put(
                    JSONObject()
                        .put("id", r.id)
                        .put("message", r.message)
                        .put("trigger", r.trigger.name)
                        .put("delivery", r.delivery.name)
                        .put("scope", r.scope.name)
                        .put("matchMode", r.matchMode.name)
                        .put("isEnabled", r.isEnabled)
                        .put("isDeleted", r.isDeleted)
                        .put("deletedAtMs", r.deletedAtMs ?: JSONObject.NULL)
                        .put("timerMinutes", r.timerMinutes)
                        .put("cooldownMs", r.cooldownMs)
                        .put("lastFiredAtMs", r.lastFiredAtMs ?: JSONObject.NULL)
                        .put("tagIds", JSONArray().apply { r.tagIds.forEach { put(it) } })
                )
            }
        })

        root.put("activeSessionStart", JSONArray().apply {
            activeSessionStart.forEach { (sessionId, startTs) ->
                put(JSONObject().put("sessionId", sessionId).put("startTs", startTs))
            }
        })

        root.put("activeTagStart", JSONArray().apply {
            activeTagStart.forEach { a ->
                put(
                    JSONObject()
                        .put("sessionId", a.sessionId)
                        .put("tagId", a.tagId)
                        .put("startTs", a.startTs)
                )
            }
        })

        root.put("tagParents", JSONArray().apply {
            tagParents.forEach { e ->
                put(JSONObject().put("childId", e.childId).put("parentId", e.parentId))
            }
        })
                root.put("chains", JSONArray().apply {
                    chains.forEach { c ->
                        put(
                            JSONObject().apply {
                                put("id", c.id)
                                put("name", c.name)
                                put("isDeleted", c.isDeleted)
                                put("deletedAtMs", c.deletedAtMs)
                                put("steps", JSONArray().apply {
                                    c.steps.forEach { step ->
                                        put(
                                            JSONObject().apply {
                                                put("name", step.name)
                                                put("link", step.link)
                                                put("tagIds", JSONArray().apply { step.tagIds.forEach { put(it) } })
                                            }
                                        )
                                    }
                                })
                            }
                        )
                    }
                })

                if (activeChainRun != null) {
            root.put(
                "activeChainRun",
                JSONObject().apply {
                    put("chainId", activeChainRun.chainId)
                    put("stepIndex", activeChainRun.stepIndex)
                    put("currentSessionId", activeChainRun.currentSessionId)
                }
            )
        }

        root.put("chronologySessions", JSONArray().apply {
            chronologySessions.forEach { session ->
                put(session.toJson())
            }
        })
        root.put("runningSessions", JSONArray().apply {
            runningSessions.forEach { session ->
                put(session.toJson())
            }
        })
        root.put("quickEventTemplates", JSONArray().apply {
            resolvedQuickEventTemplates.forEach { template -> put(template.toJson()) }
        })
        root.put("quickEventEntries", JSONArray().apply {
            resolvedQuickEventEntries.forEach { entry -> put(entry.toJson()) }
        })
        root.put("quickEventFieldDefinitions", JSONArray().apply {
            resolvedQuickEventFieldDefinitions.forEach { field -> put(field.toJson()) }
        })
        root.put("quickEventFieldValues", JSONArray().apply {
            resolvedQuickEventFieldValues.forEach { value -> put(value.toJson()) }
        })
        root.put("quickEventMacros", JSONArray().apply {
            resolvedQuickEventMacros.forEach { macro -> put(macro.toJson()) }
        })
        root.put("quickEventMacroActions", JSONArray().apply {
            resolvedQuickEventMacroActions.forEach { action -> put(action.toJson()) }
        })

        val json = root.toString()
        val beforeCounts = CriticalDataGuard.fromSnapshotJson(SnapshotSqlite.readSnapshot(context))
        val afterCounts = CriticalDataGuard.fromSnapshotJson(json)
        CriticalDataGuard.requireNoCriticalDrop(
            context = context,
            component = "SnapshotStore",
            action = "save_runtime_snapshot",
            before = beforeCounts,
            after = afterCounts,
            sourceFile = SnapshotSqlite.DB_NAME,
            destFile = SnapshotSqlite.DB_NAME,
            includeLegacyTasks = false,
        )

        // 1) Primary: SQLite (transactional & resilient).
        // This must succeed; otherwise callers would keep running with unsaved in-memory state.
        SnapshotSqlite.writeSnapshot(context, json)
        CriticalDataGuard.rememberLastGood(context, afterCounts)

        // 2) Human-readable JSON: keep writing this as a debugging aid.
        runCatching {
            externalSnapshotFile(context)?.writeText(json, Charsets.UTF_8)
        }

        // 3) Legacy: keep prefs for backward compatibility across incremental installs.
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_JSON, json)
                .apply()
        }

        // Autoexport is triggered by SnapshotSqlite.writeSnapshot after the DB transaction commits.
    }

    fun load(context: Context): Snapshot? {
        return loadFromJson(
            context = context,
            json = loadSnapshotJson(context) ?: return null
        )
    }

    fun loadAsOf(context: Context, targetMs: Long): Snapshot? {
        return loadFromJson(
            context = context,
            json = SnapshotSqlite.readSnapshotAsOf(context, targetMs) ?: return null
        )
    }

    private fun loadSnapshotJson(context: Context): String? {
        val internalDb = SnapshotSqlite.internalDbFile(context)
        val hadInternalDbBeforeRestore = internalDb.exists() && internalDb.length() > 0L

        // 0) If internal DB is missing (fresh reinstall), attempt recovery from the user folder.
        SqliteVault.restoreFromUserFolderIfPossible(context)

        val hasInternalDbAfterRestore = internalDb.exists() && internalDb.length() > 0L
        val allowLegacyFallback = !hadInternalDbBeforeRestore && !hasInternalDbAfterRestore

        // 1) Primary: SQLite
        val jsonFromDb = SnapshotSqlite.readSnapshot(context)
        if (jsonFromDb == null && !allowLegacyFallback) {
            return null
        }

        // 2) Legacy fallbacks are migration-only. Once a vault/internal DB exists,
        // we must never hydrate state from global legacy stores because that would leak
        // tasks/tags/sessions/alerts/chains/settings across vault boundaries.
        val jsonFromPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)

        val jsonFromFile = runCatching {
            externalSnapshotFile(context)
                ?.takeIf { it.exists() }
                ?.readText(Charsets.UTF_8)
        }.getOrNull()

        return jsonFromDb ?: jsonFromPrefs ?: jsonFromFile
    }

    private fun loadFromJson(context: Context, json: String): Snapshot? {
        // If we loaded from legacy storage, migrate forward to SQLite.
        // A malformed persistent snapshot is a load failure, never an empty dataset.
        val root: JSONObject = runCatching { JSONObject(json) }.getOrElse { err ->
            runCatching {
                val nowMs = System.currentTimeMillis()
                corruptSnapshotFile(context, nowMs)?.writeText(json, Charsets.UTF_8)
            }
            ForensicLog.record(
                context = context,
                component = "SnapshotStore",
                action = "load_snapshot_parse_failed",
                sourceFile = SnapshotSqlite.DB_NAME,
                throwable = err,
            )
            runCatching {
                CapsuleAudit.warn(
                    code = "SNAPSHOT_CORRUPT",
                    originCapsule = "PERSISTENCE/SnapshotStore",
                    targetCapsule = "PERSISTENCE",
                    detail = "Blocked malformed persistent snapshot: ${err::class.java.simpleName}: ${err.message}".take(300)
                )
            }
            throw IllegalStateException("Persistent snapshot JSON parsing failed", err)
        }

        val tasks = root.optJSONArray("tasks")?.toTaskList() ?: emptyList()
        val tags = root.optJSONArray("tags")?.toTagList() ?: emptyList()
        val closedSessions = root.optJSONArray("closedSessions")?.toClosedSessionRecords() ?: emptyList()
        val tagSessions = root.optJSONArray("tagSessions")?.toTaggedSessionRecords() ?: emptyList()
        val lifePeriods = root.optJSONArray("lifePeriods")?.toLifePeriods() ?: emptyList()

        var timeFenceDeliveryMigrated = false
        val timeFenceRules = mutableListOf<TimeFenceRule>()
        root.optJSONArray("timeFenceRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tagIds = mutableSetOf<Long>()
                o.optJSONArray("tagIds")?.let { tarr ->
                    for (j in 0 until tarr.length()) {
                        val id = tarr.optLong(j, -1L)
                        if (id > 0L) tagIds.add(id)
                    }
                }
                val trigger = runCatching { TimeFenceTrigger.valueOf(o.optString("trigger")) }
                    .getOrElse { TimeFenceTrigger.ON_START }
                val scope = runCatching { TimeFenceScope.valueOf(o.optString("scope")) }
                    .getOrElse { TimeFenceScope.ALWAYS }
                val matchMode = runCatching { TimeFenceMatchMode.valueOf(o.optString("matchMode")) }
                    .getOrElse { TimeFenceMatchMode.AND }
                val rawDelivery = o.optString("delivery", "")
                val delivery = runCatching { TimeFenceDelivery.valueOf(rawDelivery) }
                    .getOrDefault(TimeFenceDelivery.NOTIFICATION)
                    .let { parsed ->
                        if (parsed == TimeFenceDelivery.PREFENCE) TimeFenceDelivery.NOTIFICATION else parsed
                    }
                if (rawDelivery != TimeFenceDelivery.NOTIFICATION.name || o.optString("delivery") != TimeFenceDelivery.NOTIFICATION.name) {
                    o.put("delivery", TimeFenceDelivery.NOTIFICATION.name)
                    timeFenceDeliveryMigrated = true
                }

                val timerMinutes = o.optInt("timerMinutes", 0).coerceAtLeast(0)

                timeFenceRules.add(
                    TimeFenceRule(
                        id = o.optLong("id"),
                        message = o.optString("message"),
                        trigger = trigger,
                        delivery = delivery,
                        scope = scope,
                        matchMode = matchMode,
                        tagIds = tagIds,
                        timerMinutes = timerMinutes,
                        isEnabled = o.optBoolean("isEnabled", true),
                        cooldownMs = o.optLong("cooldownMs", 0L),
                        lastFiredAtMs = o.optLong("lastFiredAtMs", -1L).takeIf { it > 0L }
                    ,
                        isDeleted = o.optBoolean("isDeleted", false),
                        deletedAtMs = o.optLong("deletedAtMs", -1L).takeIf { it > 0L }
                    )
                )
            }
        }
        if (timeFenceDeliveryMigrated) {
            SnapshotSqlite.writeSnapshot(context, root.toString())
        }

        val chains = root.optJSONArray("chains")?.toChains() ?: emptyList()
        val chronologySessions = root.optJSONArray("chronologySessions")?.toSessionUiList() ?: emptyList()
        val runningSessions = root.optJSONArray("runningSessions")?.toSessionUiList() ?: emptyList()
        val quickEventTemplates = root.optJSONArray("quickEventTemplates")?.toQuickEventTemplateList() ?: emptyList()
        val quickEventEntries = root.optJSONArray("quickEventEntries")?.toQuickEventEntryList()
            ?: root.optJSONArray("events")?.toLegacyQuickEventEntryList()
            ?: emptyList()
        val quickEventFieldDefinitions = root.optJSONArray("quickEventFieldDefinitions")?.toQuickEventFieldDefinitionList() ?: emptyList()
        val quickEventFieldValues = root.optJSONArray("quickEventFieldValues")?.toQuickEventFieldValueList() ?: emptyList()
        val quickEventMacros = root.optJSONArray("quickEventMacros")?.toQuickEventMacroList() ?: emptyList()
        val quickEventMacroActions = root.optJSONArray("quickEventMacroActions")?.toQuickEventMacroActionList() ?: emptyList()
        val activeChainRun = root.optJSONObject("activeChainRun")?.let { o ->
            ActiveChainRun(
                chainId = o.getLong("chainId"),
                stepIndex = o.getInt("stepIndex"),
                currentSessionId = o.getLong("currentSessionId")
            )
        }

        val installAtMs = run {
            val raw = root.optLong("installAtMs", -1L)
            if (raw > 0L) raw
            else {
                // Backward-compat: derive from earliest tracked session if present, otherwise 'now'.
                val earliestTask = closedSessions.minOfOrNull { it.startTs }
                val earliestTag = tagSessions.minOfOrNull { it.startTs }
                listOfNotNull(earliestTask, earliestTag).minOrNull() ?: System.currentTimeMillis()
            }
        }

        val appUsageMs = root.optLong("appUsageMs", 0L)

        val activeSessionStart = mutableMapOf<Long, Long>()
        root.optJSONArray("activeSessionStart")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                activeSessionStart[o.getLong("sessionId")] = o.getLong("startTs")
            }
        }

        val activeTagStart = mutableListOf<ActiveTag>()
        root.optJSONArray("activeTagStart")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                activeTagStart.add(
                    ActiveTag(
                        sessionId = o.getLong("sessionId"),
                        tagId = o.getLong("tagId"),
                        startTs = o.getLong("startTs")
                    )
                )
            }
        }

        val tagParents = mutableListOf<TagParentEdge>()
        root.optJSONArray("tagParents")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val childId = o.optLong("childId", -1L)
                val parentId = o.optLong("parentId", -1L)
                if (childId > 0L && parentId > 0L && childId != parentId) {
                    tagParents.add(TagParentEdge(childId = childId, parentId = parentId))
                }
            }
        }

        return Snapshot(
            tasks = tasks,
            tags = tags,
            closedSessions = closedSessions,
            tagSessions = tagSessions,
            lifePeriods = lifePeriods,
            timeFenceRules = timeFenceRules,
            installAtMs = installAtMs,
            appUsageMs = appUsageMs,
            activeSessionStart = activeSessionStart.toMap(),
            activeTagStart = activeTagStart.toList(),
            tagParents = tagParents.toList(),
            chains = chains,
            activeChainRun = activeChainRun,
            chronologySessions = chronologySessions,
            runningSessions = runningSessions,
            quickEventTemplates = quickEventTemplates,
            quickEventEntries = quickEventEntries,
            quickEventFieldDefinitions = quickEventFieldDefinitions,
            quickEventFieldValues = quickEventFieldValues,
            quickEventMacros = quickEventMacros,
            quickEventMacroActions = quickEventMacroActions
        )
    }

    private fun JSONArray.toTaskList(): List<Task> {
        val out = ArrayList<Task>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val tagIdsArr = o.getJSONArray("tagIds")
            val tagIds = buildSet<Long> {
                for (j in 0 until tagIdsArr.length()) add(tagIdsArr.getLong(j))
            }
            out.add(
                Task(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    link = if (o.has("link") && !o.isNull("link")) o.getString("link") else "",
                    tagIds = tagIds,
                    isDeleted = o.optBoolean("isDeleted", false),
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null,
                    isRunning = o.getBoolean("isRunning"),
                    totalMs = o.getLong("totalMs"),
                    lastStartedAtMs = if (o.isNull("lastStartedAtMs")) null else o.getLong("lastStartedAtMs")
                )
            )
        }
        return out
    }

    private fun JSONArray.toTagList(): List<Tag> {
        val out = ArrayList<Tag>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)

            val restoreArr = o.optJSONArray("restoreSessionIds")
            val restoreSessionIds = buildSet<Long> {
                if (restoreArr != null) {
                    for (j in 0 until restoreArr.length()) add(restoreArr.getLong(j))
                }
            }

            out.add(
                Tag(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    timedDurationMinutes = if (o.has("timedDurationMinutes") && !o.isNull("timedDurationMinutes")) o.getInt("timedDurationMinutes") else null,
                    notificationType = runCatching {
                        TimedTagNotificationType.valueOf(o.optString("notificationType", TimedTagNotificationType.NONE.name))
                    }.getOrDefault(TimedTagNotificationType.NONE),
                    isArchived = o.optBoolean("isArchived", false),
                    showInTimeline = o.optBoolean("showInTimeline", true),
                    isDeleted = o.optBoolean("isDeleted", false),
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null,
                    restoreSessionIds = restoreSessionIds,
                    activeChildrenCount = o.getInt("activeChildrenCount"),
                    totalMs = o.getLong("totalMs"),
                    lastStartedAtMs = if (o.isNull("lastStartedAtMs")) null else o.getLong("lastStartedAtMs")
                )
            )
        }
        return out
    }

    private fun JSONArray.toLifePeriods(): List<LifePeriod> {
        val out = ArrayList<LifePeriod>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val tagIdsArr = o.optJSONArray("tagIds")
            val tagIds = buildSet<Long> {
                if (tagIdsArr != null) {
                    for (j in 0 until tagIdsArr.length()) {
                        val tagId = tagIdsArr.optLong(j, -1L)
                        if (tagId > 0L) add(tagId)
                    }
                }
            }
            val displayUnitsArr = o.optJSONArray("displayUnits")
            val displayUnits = buildSet<LifePeriodDisplayUnit> {
                if (displayUnitsArr != null) {
                    for (j in 0 until displayUnitsArr.length()) {
                        runCatching { LifePeriodDisplayUnit.valueOf(displayUnitsArr.optString(j)) }
                            .getOrNull()
                            ?.let { add(it) }
                    }
                }
            }.ifEmpty { setOf(LifePeriodDisplayUnit.DAYS) }
            out.add(
                LifePeriod(
                    id = o.getLong("id"),
                    title = o.optString("title"),
                    description = o.optString("description"),
                    startMs = o.getLong("startMs"),
                    endMs = if (o.has("endMs") && !o.isNull("endMs")) o.getLong("endMs") else null,
                    colorArgb = o.optLong("colorArgb", DEFAULT_LIFE_PERIOD_COLOR_ARGB),
                    tagIds = tagIds,
                    displayUnits = displayUnits
                )
            )
        }
        return out
    }
    private fun JSONArray.toClosedSessionRecords(): List<ClosedSessionRecord> {
        val out = ArrayList<ClosedSessionRecord>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val sessionId = when {
                o.has("sessionId") -> o.optLong("sessionId", -1L)
                o.has("taskId") -> o.optLong("taskId", -1L)
                else -> -1L
            }
            val sessionTitle = when {
                o.has("sessionTitle") -> o.optString("sessionTitle")
                o.has("taskName") -> o.optString("taskName")
                o.has("taskTitle") -> o.optString("taskTitle")
                else -> ""
            }
            val startTs = o.optLong("startTs", Long.MIN_VALUE)
            val endTs = o.optLong("endTs", Long.MIN_VALUE)
            if (sessionId <= 0L || startTs == Long.MIN_VALUE || endTs == Long.MIN_VALUE) continue
            out.add(
                ClosedSessionRecord(
                    sessionId = sessionId,
                    sessionTitle = sessionTitle,
                    startTs = startTs,
                    endTs = endTs
                )
            )
        }
        return out
    }

    private fun JSONArray.toTaggedSessionRecords(): List<TaggedSessionRecord> {
        val out = ArrayList<TaggedSessionRecord>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val tagId = o.optLong("tagId", -1L)
            val sessionId = when {
                o.has("sessionId") -> o.optLong("sessionId", -1L)
                o.has("taskId") -> o.optLong("taskId", -1L)
                else -> -1L
            }
            val sessionTitle = when {
                o.has("sessionTitle") -> o.optString("sessionTitle")
                o.has("taskName") -> o.optString("taskName")
                o.has("taskTitle") -> o.optString("taskTitle")
                else -> ""
            }
            val startTs = o.optLong("startTs", Long.MIN_VALUE)
            val endTs = o.optLong("endTs", Long.MIN_VALUE)
            if (tagId <= 0L || sessionId <= 0L || startTs == Long.MIN_VALUE || endTs == Long.MIN_VALUE) continue
            out.add(
                TaggedSessionRecord(
                    tagId = tagId,
                    tagName = o.optString("tagName"),
                    sessionId = sessionId,
                    sessionTitle = sessionTitle,
                    startTs = startTs,
                    endTs = endTs
                )
            )
        }
        return out
    }


    private fun JSONArray.toChains(): List<TaskChain> {
        val out = ArrayList<TaskChain>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val stepsArr = o.optJSONArray("steps") ?: JSONArray()
            val steps = ArrayList<TaskChainStep>(stepsArr.length())
            for (j in 0 until stepsArr.length()) {
                val so = stepsArr.getJSONObject(j)
                val tagIdsArr = so.optJSONArray("tagIds") ?: JSONArray()
                val tagIds = buildSet<Long> {
                    for (k in 0 until tagIdsArr.length()) add(tagIdsArr.getLong(k))
                }
                steps.add(
                    TaskChainStep(
                        name = so.optString("name", ""),
                        link = so.optString("link", ""),
                        tagIds = tagIds
                    )
                )
            }
            out.add(
                TaskChain(
                    id = o.getLong("id"),
                    name = o.optString("name", ""),
                    steps = steps,
                    isDeleted = o.optBoolean("isDeleted", false),
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null
                )
            )
        }
        return out
    }

    private fun JSONArray.toSessionUiList(): List<SessionUi> {
        val out = ArrayList<SessionUi>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val tagIdsArr = o.optJSONArray("tagIds") ?: JSONArray()
            val tagIds = buildSet<Long> {
                for (j in 0 until tagIdsArr.length()) add(tagIdsArr.getLong(j))
            }
            out.add(
                SessionUi(
                    id = o.getLong("id"),
                    title = o.optString("title", ""),
                    startMs = o.getLong("startMs"),
                    endMs = if (o.has("endMs") && !o.isNull("endMs")) o.getLong("endMs") else null,
                    expectedEndMs = if (o.has("expectedEndMs") && !o.isNull("expectedEndMs")) o.getLong("expectedEndMs") else null,
                    tagIds = tagIds,
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null
                )
            )
        }
        return out
    }

    private fun SessionUi.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("startMs", startMs)
            .put("endMs", endMs ?: JSONObject.NULL)
            .put("expectedEndMs", expectedEndMs ?: JSONObject.NULL)
            .put("tagIds", JSONArray().apply { tagIds.toList().sorted().forEach { put(it) } })
            .put("deletedAtMs", deletedAtMs ?: JSONObject.NULL)
    }

    private fun JSONArray.toQuickEventTemplateList(): List<QuickEventTemplate> {
        val out = ArrayList<QuickEventTemplate>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val tagIdsArr = o.optJSONArray("tagIds") ?: JSONArray()
            val tagIds = buildSet<Long> {
                for (j in 0 until tagIdsArr.length()) add(tagIdsArr.getLong(j))
            }
            out.add(
                QuickEventTemplate(
                    id = o.getLong("id"),
                    title = o.optString("title", ""),
                    tagIds = tagIds,
                    sortOrder = o.optInt("sortOrder", 0),
                    isArchived = o.optBoolean("isArchived", false),
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null
                )
            )
        }
        return out
    }

    private fun JSONArray.toQuickEventEntryList(): List<QuickEventEntry> {
        val out = ArrayList<QuickEventEntry>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val tagIdsArr = o.optJSONArray("tagIds") ?: JSONArray()
            val tagIds = buildSet<Long> {
                for (j in 0 until tagIdsArr.length()) add(tagIdsArr.getLong(j))
            }
            val timestamp = o.optLong("timestampMs", Long.MIN_VALUE)
            if (timestamp == Long.MIN_VALUE) continue
            out.add(
                QuickEventEntry(
                    id = o.getLong("id"),
                    templateId = if (o.has("templateId") && !o.isNull("templateId")) o.getLong("templateId") else null,
                    macroId = if (o.has("macroId") && !o.isNull("macroId")) o.getLong("macroId") else null,
                    title = o.optString("title", ""),
                    timestampMs = timestamp,
                    tagIds = tagIds,
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null
                )
            )
        }
        return out
    }

    private fun JSONArray.toLegacyQuickEventEntryList(): List<QuickEventEntry> {
        val out = ArrayList<QuickEventEntry>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val tagIdsArr = o.optJSONArray("tagIds") ?: JSONArray()
            val tagIds = buildSet<Long> {
                for (j in 0 until tagIdsArr.length()) add(tagIdsArr.getLong(j))
            }
            val timestamp = o.optLong("timestampMs", Long.MIN_VALUE)
            if (timestamp == Long.MIN_VALUE) continue
            out.add(
                QuickEventEntry(
                    id = o.getLong("id"),
                    templateId = null,
                    macroId = null,
                    title = o.optString("title", ""),
                    timestampMs = timestamp,
                    tagIds = tagIds,
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null
                )
            )
        }
        return out
    }

    private fun JSONArray.toQuickEventFieldDefinitionList(): List<QuickEventFieldDefinition> {
        val out = ArrayList<QuickEventFieldDefinition>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            out.add(
                QuickEventFieldDefinition(
                    id = o.getLong("id"),
                    templateId = o.getLong("templateId"),
                    label = o.optString("label", ""),
                    type = runCatching { QuickEventFieldType.valueOf(o.optString("type", "TEXT")) }.getOrDefault(QuickEventFieldType.TEXT),
                    required = o.optBoolean("required", false),
                    defaultValue = o.optString("defaultValue", ""),
                    choiceOptions = o.optJSONArray("choiceOptions")?.toStringList() ?: emptyList(),
                    displayOrder = o.optInt("displayOrder", 0),
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null
                )
            )
        }
        return out
    }

    private fun JSONArray.toQuickEventFieldValueList(): List<QuickEventFieldValue> {
        val out = ArrayList<QuickEventFieldValue>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            out.add(
                QuickEventFieldValue(
                    id = o.getLong("id"),
                    entryId = o.getLong("entryId"),
                    fieldId = o.getLong("fieldId"),
                    label = o.optString("label", ""),
                    type = runCatching { QuickEventFieldType.valueOf(o.optString("type", "TEXT")) }.getOrDefault(QuickEventFieldType.TEXT),
                    value = o.optString("value", ""),
                    displayOrder = o.optInt("displayOrder", 0)
                )
            )
        }
        return out
    }

    private fun JSONArray.toQuickEventMacroList(): List<QuickEventMacro> {
        val out = ArrayList<QuickEventMacro>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val tagIdsArr = o.optJSONArray("tagIds") ?: JSONArray()
            val tagIds = buildSet<Long> {
                for (j in 0 until tagIdsArr.length()) add(tagIdsArr.getLong(j))
            }
            out.add(
                QuickEventMacro(
                    id = o.getLong("id"),
                    title = o.optString("title", ""),
                    tagIds = tagIds,
                    sortOrder = o.optInt("sortOrder", 0),
                    isArchived = o.optBoolean("isArchived", false),
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null
                )
            )
        }
        return out
    }

    private fun JSONArray.toQuickEventMacroActionList(): List<QuickEventMacroAction> {
        val out = ArrayList<QuickEventMacroAction>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            out.add(
                QuickEventMacroAction(
                    macroId = o.getLong("macroId"),
                    templateId = o.getLong("templateId"),
                    displayOrder = o.optInt("displayOrder", 0)
                )
            )
        }
        return out
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (i in 0 until length()) {
            optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun QuickEventTemplate.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("sortOrder", sortOrder)
            .put("isArchived", isArchived)
            .put("tagIds", JSONArray().apply { tagIds.toList().sorted().forEach { put(it) } })
            .put("deletedAtMs", deletedAtMs ?: JSONObject.NULL)
    }

    private fun QuickEventEntry.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("templateId", templateId ?: JSONObject.NULL)
            .put("macroId", macroId ?: JSONObject.NULL)
            .put("title", title)
            .put("timestampMs", timestampMs)
            .put("tagIds", JSONArray().apply { tagIds.toList().sorted().forEach { put(it) } })
            .put("deletedAtMs", deletedAtMs ?: JSONObject.NULL)
    }

    private fun QuickEventFieldDefinition.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("templateId", templateId)
            .put("label", label)
            .put("type", type.name)
            .put("required", required)
            .put("defaultValue", defaultValue)
            .put("choiceOptions", JSONArray().apply { choiceOptions.forEach { put(it) } })
            .put("displayOrder", displayOrder)
            .put("deletedAtMs", deletedAtMs ?: JSONObject.NULL)
    }

    private fun QuickEventFieldValue.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("entryId", entryId)
            .put("fieldId", fieldId)
            .put("label", label)
            .put("type", type.name)
            .put("value", value)
            .put("displayOrder", displayOrder)
    }

    private fun QuickEventMacro.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("sortOrder", sortOrder)
            .put("isArchived", isArchived)
            .put("tagIds", JSONArray().apply { tagIds.toList().sorted().forEach { put(it) } })
            .put("deletedAtMs", deletedAtMs ?: JSONObject.NULL)
    }

    private fun QuickEventMacroAction.toJson(): JSONObject {
        return JSONObject()
            .put("macroId", macroId)
            .put("templateId", templateId)
            .put("displayOrder", displayOrder)
    }
}
