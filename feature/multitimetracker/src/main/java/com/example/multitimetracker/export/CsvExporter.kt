// v471
// v355
package com.example.multitimetracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.LifePeriodDisplayUnit
import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TaskChain
import com.example.multitimetracker.model.TaskChainStep
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import com.example.multitimetracker.persistence.SafeFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter

private fun csvEscape(s: String): String {
    // RFC4180-ish: if the field contains a quote, escape it by doubling.
    // Always wrap in quotes (simple + safe for commas/newlines).
    val out = StringBuilder(s.length + 8)
    for (c in s) {
        if (c == '"') out.append("\"\"") else out.append(c)
    }
    return "\"$out\""
}

private fun shareExportDir(context: Context): File {
    val dir = File(context.cacheDir, "share_exports")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

private fun shareExportFile(context: Context, fileName: String): File {
    return File(shareExportDir(context), fileName)
}


object CsvExporter {

    private fun buildDictJson(
        tasks: List<Task>,
        tags: List<Tag>,
        tagParentsByChild: Map<Long, Set<Long>>
    ): String {
        val root = JSONObject()
        root.put("schema_version", 4)
        root.put("exported_at", System.currentTimeMillis())

        val tagsArr = JSONArray()
        tags.sortedBy { it.id }.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id)
            o.put("name", t.name)
            o.put("isArchived", t.isArchived)
            o.put("isDeleted", t.isDeleted)
            o.put("deletedAtMs", t.deletedAtMs ?: JSONObject.NULL)
            val restoreSessionIdsArr = JSONArray()
            t.restoreSessionIds.toList().sorted().forEach { restoreSessionIdsArr.put(it) }
            o.put("restoreSessionIds", restoreSessionIdsArr)
            o.put("totalMs", t.totalMs)
            o.put("lastStartedAtMs", t.lastStartedAtMs ?: JSONObject.NULL)
            tagsArr.put(o)
        }
        root.put("tags", tagsArr)

        val edgesArr = JSONArray()
        tagParentsByChild.toSortedMap().forEach { (childId, parents) ->
            parents.toList().sorted().forEach { parentId ->
                edgesArr.put(JSONObject().put("childId", childId).put("parentId", parentId))
            }
        }
        root.put("tagParents", edgesArr)

        val tasksArr = JSONArray()
        tasks.sortedBy { it.id }.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id)
            o.put("name", t.name)
            o.put("link", t.link)
            o.put("isDeleted", t.isDeleted)
            o.put("deletedAtMs", t.deletedAtMs ?: JSONObject.NULL)
            o.put("isRunning", t.isRunning)
            o.put("totalMs", t.totalMs)
            o.put("lastStartedAtMs", t.lastStartedAtMs ?: JSONObject.NULL)
            val tagIdsArr = JSONArray()
            t.tagIds.sorted().forEach { tagIdsArr.put(it) }
            o.put("tagIds", tagIdsArr)
            tasksArr.put(o)
        }
        root.put("tasks", tasksArr)

        return root.toString(2) + "\n"
    }

    private fun buildRuntimeJson(
        lifePeriods: List<LifePeriod>,
        timeFenceRules: List<TimeFenceRule>,
        chains: List<TaskChain>,
        activeChainRun: ActiveChainRun?
    ): String {
        val root = JSONObject()
        root.put("schema_version", 1)
        root.put("exported_at", System.currentTimeMillis())

        val rulesArr = JSONArray()
        timeFenceRules.sortedBy { it.id }.forEach { r ->
            val o = JSONObject()
            o.put("id", r.id)
            o.put("message", r.message)
            o.put("trigger", r.trigger.name)
            o.put("delivery", r.delivery.name)
            o.put("scope", r.scope.name)
            o.put("matchMode", r.matchMode.name)
            o.put("timerMinutes", r.timerMinutes)
            o.put("isEnabled", r.isEnabled)
            o.put("cooldownMs", r.cooldownMs)
            o.put("lastFiredAtMs", r.lastFiredAtMs ?: JSONObject.NULL)
            val tagIdsArr = JSONArray()
            r.tagIds.toList().sorted().forEach { tagIdsArr.put(it) }
            o.put("tagIds", tagIdsArr)
            rulesArr.put(o)
        }
        root.put("timeFenceRules", rulesArr)

        val periodsArr = JSONArray()
        lifePeriods.sortedBy { it.id }.forEach { p ->
            periodsArr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("title", p.title)
                    .put("description", p.description)
                    .put("startMs", p.startMs)
                    .put("endMs", p.endMs ?: JSONObject.NULL)
                    .put("colorArgb", p.colorArgb)
                    .put("tagIds", JSONArray().apply { p.tagIds.toList().sorted().forEach { put(it) } })
                    .put("displayUnits", JSONArray().apply {
                        LifePeriodDisplayUnit.displayOrder.filter { it in p.displayUnits }.forEach { put(it.name) }
                    })
            )
        }
        root.put("lifePeriods", periodsArr)

        val chainsArr = JSONArray()
        chains.sortedBy { it.id }.forEach { c ->
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("isDeleted", c.isDeleted)
            o.put("deletedAtMs", c.deletedAtMs ?: JSONObject.NULL)
            val stepsArr = JSONArray()
            c.steps.forEach { s: TaskChainStep ->
                val so = JSONObject()
                so.put("name", s.name)
                so.put("link", s.link)
                val stTags = JSONArray()
                s.tagIds.toList().sorted().forEach { stTags.put(it) }
                so.put("tagIds", stTags)
                stepsArr.put(so)
            }
            o.put("steps", stepsArr)
            chainsArr.put(o)
        }
        root.put("chains", chainsArr)

        root.put(
            "activeChainRun",
            activeChainRun?.let {
                JSONObject()
                    .put("chainId", it.chainId)
                    .put("stepIndex", it.stepIndex)
                    .put("currentSessionId", it.currentSessionId)
            } ?: JSONObject.NULL
        )

        return root.toString(2) + "\n"
    }

    private data class TagTotalRow(
        val tagId: Long,
        val tagName: String,
        val totalMs: Long,
    )

    private fun buildQuickEventsJson(
        templates: List<QuickEventTemplate>,
        entries: List<QuickEventEntry>,
        fieldDefinitions: List<QuickEventFieldDefinition>,
        fieldValues: List<QuickEventFieldValue>,
        macros: List<QuickEventMacro>,
        macroActions: List<QuickEventMacroAction>
    ): String {
        val root = JSONObject()
        root.put("schema_version", 2)
        root.put("exported_at", System.currentTimeMillis())
        root.put("templates", JSONArray().apply {
            templates.sortedBy { it.id }.forEach { t ->
                put(
                    JSONObject()
                        .put("id", t.id)
                        .put("title", t.title)
                        .put("tagIds", JSONArray().apply { t.tagIds.toList().sorted().forEach { put(it) } })
                        .put("sortOrder", t.sortOrder)
                        .put("isArchived", t.isArchived)
                        .put("deletedAtMs", t.deletedAtMs ?: JSONObject.NULL)
                )
            }
        })
        root.put("entries", JSONArray().apply {
            entries.sortedBy { it.id }.forEach { e ->
                put(
                    JSONObject()
                        .put("id", e.id)
                        .put("templateId", e.templateId ?: JSONObject.NULL)
                        .put("macroId", e.macroId ?: JSONObject.NULL)
                        .put("title", e.title)
                        .put("timestampMs", e.timestampMs)
                        .put("tagIds", JSONArray().apply { e.tagIds.toList().sorted().forEach { put(it) } })
                        .put("deletedAtMs", e.deletedAtMs ?: JSONObject.NULL)
                )
            }
        })
        root.put("fields", JSONArray().apply {
            fieldDefinitions.sortedBy { it.id }.forEach { f ->
                put(
                    JSONObject()
                        .put("id", f.id)
                        .put("templateId", f.templateId)
                        .put("label", f.label)
                        .put("type", f.type.name)
                        .put("required", f.required)
                        .put("defaultValue", f.defaultValue)
                        .put("choiceOptions", JSONArray().apply { f.choiceOptions.forEach { put(it) } })
                        .put("displayOrder", f.displayOrder)
                        .put("deletedAtMs", f.deletedAtMs ?: JSONObject.NULL)
                )
            }
        })
        root.put("fieldValues", JSONArray().apply {
            fieldValues.sortedBy { it.id }.forEach { v ->
                put(
                    JSONObject()
                        .put("id", v.id)
                        .put("entryId", v.entryId)
                        .put("fieldId", v.fieldId)
                        .put("label", v.label)
                        .put("type", v.type.name)
                        .put("value", v.value)
                        .put("displayOrder", v.displayOrder)
                )
            }
        })
        root.put("macros", JSONArray().apply {
            macros.sortedBy { it.id }.forEach { m ->
                put(
                    JSONObject()
                        .put("id", m.id)
                        .put("title", m.title)
                        .put("tagIds", JSONArray().apply { m.tagIds.toList().sorted().forEach { put(it) } })
                        .put("sortOrder", m.sortOrder)
                        .put("isArchived", m.isArchived)
                        .put("deletedAtMs", m.deletedAtMs ?: JSONObject.NULL)
                )
            }
        })
        root.put("macroActions", JSONArray().apply {
            macroActions.sortedWith(compareBy({ it.macroId }, { it.displayOrder }, { it.templateId })).forEach { a ->
                put(
                    JSONObject()
                        .put("macroId", a.macroId)
                        .put("templateId", a.templateId)
                        .put("displayOrder", a.displayOrder)
                )
            }
        })
        return root.toString(2) + "\n"
    }

    /**
     * Writes CSV content to a file inside a SAF directory (DocumentFile).
     * Uses a truncate write ("wt") so the result is always coherent.
     */
    private fun writeCsvToDir(context: Context, dir: DocumentFile, fileName: String, writeBody: (Appendable) -> Unit) {
        val cr = context.contentResolver
        val existing = dir.findFile(fileName)
        val target = existing ?: dir.createFile("text/csv", fileName)
        requireNotNull(target) { "Impossibile creare file: $fileName" }

        cr.openOutputStream(target.uri, "wt")?.use { out: java.io.OutputStream ->
            requireNotNull(out) { "Impossibile aprire output stream per: $fileName" }
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                writeBody(w)
                w.flush()
            }
        } ?: throw IllegalStateException("Impossibile aprire output stream per: $fileName")
    }


    /**
     * Writes text content to a file inside a SAF directory (DocumentFile).
     * Uses a truncate write ("wt") so the result is always coherent.
     */
    private fun writeTextToDir(context: Context, dir: DocumentFile, mime: String, fileName: String, writeBody: (Appendable) -> Unit) {
        val cr = context.contentResolver
        val existing = dir.findFile(fileName)
        val target = existing ?: dir.createFile(mime, fileName)
        requireNotNull(target) { "Impossibile creare file: $fileName" }

        cr.openOutputStream(target.uri, "wt")?.use { out: java.io.OutputStream ->
            requireNotNull(out) { "Impossibile aprire output stream per: $fileName" }
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                writeBody(w)
                w.flush()
            }
        } ?: throw IllegalStateException("Impossibile aprire output stream per: $fileName")
    }

    /**
     * Full export into a directory called from automatic backup/import flows.
     */
    fun exportAllToDirectory(
        context: Context,
        dir: DocumentFile,
        tasks: List<Task>,
        tags: List<Tag>,
        closedSessions: List<ClosedSessionRecord>,
        tagSessions: List<TaggedSessionRecord>,
        tagParentsByChild: Map<Long, Set<Long>>,
        lifePeriods: List<LifePeriod>,
        timeFenceRules: List<TimeFenceRule>,
        chains: List<TaskChain>,
        activeChainRun: ActiveChainRun?,
        appUsageMs: Long,
        quickEventTemplates: List<QuickEventTemplate> = emptyList(),
        quickEventEntries: List<QuickEventEntry> = emptyList(),
        quickEventFieldDefinitions: List<QuickEventFieldDefinition> = emptyList(),
        quickEventFieldValues: List<QuickEventFieldValue> = emptyList(),
        quickEventMacros: List<QuickEventMacro> = emptyList(),
        quickEventMacroActions: List<QuickEventMacroAction> = emptyList()
    ) {

        // 5° file: dict.json (anagrafica task/tag + associazioni task↔tag), leggibile da umani.
        writeTextToDir(context, dir, "application/json", "dict.json") { w ->
            w.append(buildDictJson(tasks = tasks, tags = tags, tagParentsByChild = tagParentsByChild))
        }

        writeCsvToDir(context, dir, "sessions.csv") { w ->
            w.appendLine("task_id,task_name,start_ts,end_ts")
            closedSessions.forEach { s ->
                w.appendLine("${s.sessionId},${csvEscape(s.sessionTitle)},${s.startTs},${s.endTs}")
            }
        }

        writeCsvToDir(context, dir, "totals.csv") { w ->
            val totals = closedSessions
                .groupBy { it.sessionId to it.sessionTitle }
                .mapValues { (_, v) -> v.sumOf { it.endTs - it.startTs } }
            w.appendLine("task_id,task_name,total_ms")
            totals.forEach { (k, total) ->
                w.appendLine("${k.first},${csvEscape(k.second)},$total")
            }
        }

        writeCsvToDir(context, dir, "tag_sessions.csv") { w ->
            w.appendLine("tag_id,tag_name,task_id,task_name,start_ts,end_ts")
            tagSessions.forEach { s ->
                w.appendLine(
                    "${s.tagId},${csvEscape(s.tagName)}," +
                        "${s.sessionId},${csvEscape(s.sessionTitle)}," +
                        "${s.startTs},${s.endTs}"
                )
            }
        }

        writeCsvToDir(context, dir, "tag_totals.csv") { w ->
            writeTagTotalsCsv(
                writer = w,
                rows = computeTagTotalRows(
                    tags = tags,
                    tagSessions = tagSessions,
                    runningTasks = tasks,
                    nowMs = System.currentTimeMillis(),
                )
            )
        }

        writeCsvToDir(context, dir, "app_usage.csv") { w ->
            w.appendLine("total_ms")
            w.appendLine(appUsageMs.toString())
        }

        // runtime.json: alerts (time-fence rules) + chains (including trash)
        writeTextToDir(context, dir, "application/json", "runtime.json") { w ->
            w.append(
                buildRuntimeJson(
                    lifePeriods = lifePeriods,
                    timeFenceRules = timeFenceRules,
                    chains = chains,
                    activeChainRun = activeChainRun
                )
            )
        }

        writeTextToDir(context, dir, "application/json", "quick_events.json") { w ->
            w.append(
                buildQuickEventsJson(
                    quickEventTemplates,
                    quickEventEntries,
                    quickEventFieldDefinitions,
                    quickEventFieldValues,
                    quickEventMacros,
                    quickEventMacroActions
                )
            )
        }

        writeTextToDir(context, dir, "application/json", BackupSchema.MANIFEST_FILE) { w ->
            w.append(BackupSchema.buildManifestJson())
        }
    }

    fun exportManualShareFiles(
        context: Context,
        tasks: List<Task>,
        tags: List<Tag>,
        closedSessions: List<ClosedSessionRecord>,
        tagSessions: List<TaggedSessionRecord>,
        tagParentsByChild: Map<Long, Set<Long>>,
        lifePeriods: List<LifePeriod>,
        timeFenceRules: List<TimeFenceRule>,
        chains: List<TaskChain>,
        activeChainRun: ActiveChainRun?,
        appUsageMs: Long,
        nowMs: Long,
        quickEventTemplates: List<QuickEventTemplate> = emptyList(),
        quickEventEntries: List<QuickEventEntry> = emptyList(),
        quickEventFieldDefinitions: List<QuickEventFieldDefinition> = emptyList(),
        quickEventFieldValues: List<QuickEventFieldValue> = emptyList(),
        quickEventMacros: List<QuickEventMacro> = emptyList(),
        quickEventMacroActions: List<QuickEventMacroAction> = emptyList()
    ): List<File> {
        return listOf(
            exportClosedSessionRecords(context, closedSessions),
            exportTaskTotals(context, closedSessions),
            exportTaggedSessionRecords(context, tagSessions),
            exportTagTotals(
                context = context,
                tags = tags,
                tagSessions = tagSessions,
                runningTasks = tasks,
                nowMs = nowMs,
            ),
            shareExportFile(context, "dict.json").also {
                SafeFiles.writeAtomicUtf8(it, buildDictJson(tasks = tasks, tags = tags, tagParentsByChild = tagParentsByChild))
            },
            shareExportFile(context, "runtime.json").also {
                SafeFiles.writeAtomicUtf8(
                    it,
                    buildRuntimeJson(
                        lifePeriods = lifePeriods,
                        timeFenceRules = timeFenceRules,
                        chains = chains,
                        activeChainRun = activeChainRun
                    )
                )
            },
            shareExportFile(context, "quick_events.json").also {
                SafeFiles.writeAtomicUtf8(
                    it,
                    buildQuickEventsJson(
                        quickEventTemplates,
                        quickEventEntries,
                        quickEventFieldDefinitions,
                        quickEventFieldValues,
                        quickEventMacros,
                        quickEventMacroActions
                    )
                )
            },
            shareExportFile(context, BackupSchema.MANIFEST_FILE).also {
                SafeFiles.writeAtomicUtf8(it, BackupSchema.buildManifestJson())
            },
            shareExportFile(context, "app_usage.csv").also {
                SafeFiles.writeAtomicUtf8(it, "total_ms\n$appUsageMs\n")
            }
        )
    }

    fun exportClosedSessionRecords(context: Context, sessions: List<ClosedSessionRecord>): File {
        val file = shareExportFile(context, "sessions.csv")
        val text = buildString {
            appendLine("task_id,task_name,start_ts,end_ts")
            sessions.forEach { s ->
                appendLine("${s.sessionId},${csvEscape(s.sessionTitle)},${s.startTs},${s.endTs}")
            }
        }
        SafeFiles.writeAtomicUtf8(file, text)
        return file
    }


    fun exportTaskTotals(context: Context, sessions: List<ClosedSessionRecord>): File {
        val totals = sessions
            .groupBy { it.sessionId to it.sessionTitle }
            .mapValues { (_, v) -> v.sumOf { it.endTs - it.startTs } }

        val file = shareExportFile(context, "totals.csv")
        val text = buildString {
            appendLine("task_id,task_name,total_ms")
            totals.forEach { (k, total) ->
                appendLine("${k.first},${csvEscape(k.second)},$total")
            }
        }
        SafeFiles.writeAtomicUtf8(file, text)
        return file
    }


    fun exportTaggedSessionRecords(context: Context, tagSessions: List<TaggedSessionRecord>): File {
        val file = shareExportFile(context, "tag_sessions.csv")
        val text = buildString {
            appendLine("tag_id,tag_name,task_id,task_name,start_ts,end_ts")
            tagSessions.forEach { s ->
                appendLine(
                    "${s.tagId},${csvEscape(s.tagName)}," +
                        "${s.sessionId},${csvEscape(s.sessionTitle)}," +
                        "${s.startTs},${s.endTs}"
                )
            }
        }
        SafeFiles.writeAtomicUtf8(file, text)
        return file
    }


    fun exportTagTotals(
        context: Context,
        tags: List<Tag>,
        tagSessions: List<TaggedSessionRecord>,
        runningTasks: List<Task>,
        nowMs: Long
    ): File {
        val out = File(context.cacheDir, "tag_totals.csv")
        FileWriter(out).use { writer ->
            writeTagTotalsCsv(
                writer = writer,
                rows = computeTagTotalRows(
                    tags = tags,
                    tagSessions = tagSessions,
                    runningTasks = runningTasks,
                    nowMs = nowMs,
                )
            )
        }
        return out
    }

    private fun writeTagTotalsCsv(
        writer: Appendable,
        rows: List<TagTotalRow>,
    ) {
        writer.appendLine("tag_id,tag_name,total_ms")
        rows.forEach { row ->
            writer.appendLine("${row.tagId},${csvEscape(row.tagName)},${row.totalMs}")
        }
    }

    private fun computeTagTotalRows(
        tags: List<Tag>,
        tagSessions: List<TaggedSessionRecord>,
        runningTasks: List<Task>,
        nowMs: Long,
    ): List<TagTotalRow> {
        val tagNamesById = tags.associate { it.id to it.name }.toMutableMap()
        val intervalsByTag = linkedMapOf<Long, MutableList<Pair<Long, Long>>>()

        fun addInterval(tagId: Long, start: Long, end: Long, fallbackName: String? = null) {
            if (end <= start) return
            if (fallbackName != null) {
                tagNamesById.putIfAbsent(tagId, fallbackName)
            }
            intervalsByTag.getOrPut(tagId) { mutableListOf() }.add(start to end)
        }

        tagSessions.forEach { session ->
            addInterval(
                tagId = session.tagId,
                start = session.startTs,
                end = session.endTs,
                fallbackName = session.tagName,
            )
        }

        runningTasks
            .asSequence()
            .filter { it.isRunning && it.lastStartedAtMs != null }
            .forEach { task ->
                val start = task.lastStartedAtMs ?: return@forEach
                task.tagIds.forEach { tagId ->
                    addInterval(tagId = tagId, start = start, end = nowMs)
                }
            }

        return intervalsByTag
            .toSortedMap()
            .map { (tagId, intervals) ->
                TagTotalRow(
                    tagId = tagId,
                    tagName = tagNamesById[tagId] ?: "Tag $tagId",
                    totalMs = unionTotalMs(intervals),
                )
            }
    }

    private fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
        if (intervals.isEmpty()) return 0L
        val sorted = intervals.sortedBy { it.first }
        var total = 0L
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (start, end) = sorted[i]
            if (end <= start) continue
            if (start <= curEnd) {
                if (end > curEnd) curEnd = end
            } else {
                total += (curEnd - curStart)
                curStart = start
                curEnd = end
            }
        }
        total += (curEnd - curStart)
        return total.coerceAtLeast(0L)
    }
}

