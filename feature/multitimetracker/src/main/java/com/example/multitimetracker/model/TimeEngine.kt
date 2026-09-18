// v471
// v355
package com.example.multitimetracker.model

// CAPSULE-AUDIT [CAP-001]: MODEL uses core contract DTOs (ClosedSessionRecord/TaggedSessionRecord).

import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * TimeEngine = "motore" puro che gestisce:
 * - start/stop dei task (ClosedSessionRecord = da PLAY a STOP)
 * - sessioni dei tag (TaggedSessionRecord) che iniziano/finiscono quando:
 *   - il task parte/si ferma
 *   - un tag viene aggiunto/rimosso mentre il task è running
 *
 * Modello timing: NOW - START_TIME (stile timecop)
 * Quindi il tempo "live" è calcolato, non incrementato in RAM.
 */
class TimeEngine {

    private var nextTaskId: Long = 1L
    private var nextTagId: Long = 1L

    private val activeSessionStart = mutableMapOf<Long, Long>() // sessionId -> startTs

    private data class TagKey(val sessionId: Long, val tagId: Long)
    private val activeTagStart = mutableMapOf<TagKey, Long>() // (sessionId, tagId) -> startTs

    private val closedSessions = mutableListOf<ClosedSessionRecord>()
    private val tagSessions = mutableListOf<TaggedSessionRecord>()

    data class RuntimeSnapshot(
        val activeSessionStart: Map<Long, Long>,
        val activeTagStart: List<Triple<Long, Long, Long>>
    )

    data class EngineResult(
        val tasks: List<Task>,
        val tags: List<Tag>
    )

    fun createTag(name: String): Tag = Tag(
        id = nextTagId++,
        name = name,
        isArchived = false,
        isDeleted = false,
        deletedAtMs = null,
        activeChildrenCount = 0,
        totalMs = 0L,
        lastStartedAtMs = null
    )

    fun createTask(name: String, tagIds: Set<Long>, link: String = ""): Task = Task(
        id = nextTaskId++,
        name = name,
        link = link,
        tagIds = tagIds,
        isDeleted = false,
        deletedAtMs = null,
        isRunning = false,
        totalMs = 0L,
        lastStartedAtMs = null
    )

    /**
     * "Ticker" per aggiornare l'orologio della UI senza toccare lo stato ogni frame.
     */
    fun uiTickerFlow(periodMs: Long = 250L): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }

    /**
     * Mostra un tempo "live" se l'elemento è in esecuzione.
     */
    fun displayMs(totalMs: Long, lastStartedAtMs: Long?, nowMs: Long): Long {
        return if (lastStartedAtMs == null) totalMs else totalMs + (nowMs - lastStartedAtMs).coerceAtLeast(0L)
    }

    fun toggleTask(
        tasks: List<Task>,
        tags: List<Tag>,
        sessionId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        val idx = tasks.indexOfFirst { it.id == sessionId }
        if (idx < 0) return EngineResult(tasks, tags)

        val t = tasks[idx]
        if (t.isDeleted) return EngineResult(tasks, tags)
        return if (t.isRunning) {
            stopTask(tasks, tags, t, nowMs)
        } else {
            startTask(tasks, tags, t, nowMs)
        }
    }

    /**
     * Undo a previous start (PLAY) without creating any ClosedSessionRecord/TaggedSessionRecord.
     *
     * This is used by legacy rollback handling to revert a START event.
     */
    fun cancelTaskStart(
        tasks: List<Task>,
        tags: List<Tag>,
        sessionId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        val idx = tasks.indexOfFirst { it.id == sessionId }
        if (idx < 0) return EngineResult(tasks, tags)

        val t = tasks[idx]
        if (!t.isRunning) return EngineResult(tasks, tags)

        // 1) Stop the task state (without creating any session)
        val newTasks = tasks.toMutableList()
        newTasks[idx] = t.copy(isRunning = false, lastStartedAtMs = null)

        // 2) Remove active start markers for this task + its tag links
        activeSessionStart.remove(sessionId)

        val newTags = tags.toMutableList()
        val tagIdxById = tags.mapIndexed { i, tag -> tag.id to i }.toMap()

        // Remove TagKey entries for this task and update tag running counts.
        val removedTagIds = mutableListOf<Long>()
        for (tagId in t.tagIds) {
            val key = TagKey(sessionId = sessionId, tagId = tagId)
            if (activeTagStart.remove(key) != null) {
                removedTagIds.add(tagId)
            }
        }

        // Recompute activeChildrenCount/lastStartedAtMs only for affected tags.
        if (removedTagIds.isNotEmpty()) {
            // Current min-start per tag across remaining activeTagStart.
            val minStartByTagId: Map<Long, Long> = activeTagStart
                .entries
                .groupBy({ it.key.tagId }, { it.value })
                .mapValues { (_, starts) -> starts.minOrNull() ?: nowMs }

            for (tagId in removedTagIds.distinct()) {
                val tIdx = tagIdxById[tagId] ?: continue
                val tag = newTags[tIdx]
                val newCount = (tag.activeChildrenCount - 1).coerceAtLeast(0)
                val newLast = minStartByTagId[tagId]
                newTags[tIdx] = tag.copy(activeChildrenCount = newCount, lastStartedAtMs = newLast)
            }
        }

        return EngineResult(newTasks, newTags)
    }

    /**
     * Aggiorna i tag di un task.
     *
     * Regole (coerenti):
     * - se il task NON è running: aggiorna solo la relazione task<tag>
     * - se il task È running:
     *   - per ogni tag rimosso: chiude la TaggedSessionRecord (end=now) e decrementa activeChildrenCount del tag
     *   - per ogni tag aggiunto: apre una nuova TaggedSessionRecord (start=now) e incrementa activeChildrenCount del tag
     *   - NON chiude la ClosedSessionRecord (la sessione del task resta da PLAY a STOP)
     */
    fun reassignTaskTags(
        tasks: List<Task>,
        tags: List<Tag>,
        sessionId: Long,
        newTagIds: Set<Long>,
        newName: String? = null,
        newLink: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        val idx = tasks.indexOfFirst { it.id == sessionId }
        if (idx < 0) return EngineResult(tasks, tags)

        val task = tasks[idx]

        val updatedTask = task.copy(
            // IMPORTANT: allow empty titles.
            // If the user clears the title in Edit Task, the title must disappear
            // and the UI will show tags (or "Senza titolo"), exactly like when
            // a task is created without a title.
            name = (newName ?: task.name).trim(),
            tagIds = newTagIds,
            link = newLink ?: task.link
        )
        val newTasks = tasks.toMutableList().also { it[idx] = updatedTask }.toList()

        // Retroactive tags: rebuild historical tag sessions for this task every time its tag set changes.
        // This ensures tag totals update immediately when tags are added/removed on tasks that already have time.
        rebuildTaggedSessionRecordsForTask(task = updatedTask, tasks = newTasks, tags = tags, nowMs = nowMs)

        // After rebuilding tagSessions, recompute tag totals so UI/export match the regenerated history.
        val newTags = recomputeAllTagTotals(tags = tags, tasks = newTasks, nowMs = nowMs)

        return EngineResult(newTasks, newTags)
    }

    fun getClosedSessionRecords(): List<ClosedSessionRecord> = closedSessions.toList()

    fun getTaggedSessionRecords(): List<TaggedSessionRecord> = tagSessions.toList()

    fun getActiveTaskStart(sessionId: Long): Long? = activeSessionStart[sessionId]


/**
 * Edit a completed ClosedSessionRecord by its global index in the closedSessions list.
 * Also updates:
 * - Task.totalMs
 * - TaggedSessionRecords that match the old (sessionId,start,end) triple
 * - Tag.totalMs for impacted tags
 *
 * NOTE: This is intended for editing *inactive* tasks' sessions only.
 */
fun editClosedSessionRecordByIndex(
    tasks: List<Task>,
    tags: List<Tag>,
    sessionIndex: Int,
    newStartTs: Long,
    newEndTs: Long
): EngineResult {
    if (sessionIndex !in closedSessions.indices) return EngineResult(tasks, tags)

    val old = closedSessions[sessionIndex]
    val oldDur = (old.endTs - old.startTs).coerceAtLeast(0L)
    val newDur = (newEndTs - newStartTs).coerceAtLeast(0L)

    // Replace the session
    closedSessions[sessionIndex] = old.copy(startTs = newStartTs, endTs = newEndTs)

    // Update task total
    val deltaTask = newDur - oldDur
    val newTasks = tasks.map { t ->
        if (t.id != old.sessionId) t else t.copy(totalMs = (t.totalMs + deltaTask).coerceAtLeast(0L))
    }

    // Update matching tag sessions + tag totals
    val impactedTagIds = mutableSetOf<Long>()
    val tagDeltaById = mutableMapOf<Long, Long>()

    for (i in tagSessions.indices) {
        val ts = tagSessions[i]
        if (ts.sessionId == old.sessionId && ts.startTs == old.startTs && ts.endTs == old.endTs) {
            val tagId = ts.tagId
            impactedTagIds.add(tagId)
            tagSessions[i] = ts.copy(startTs = newStartTs, endTs = newEndTs)

            val oldTagDur = (ts.endTs - ts.startTs).coerceAtLeast(0L)
            val newTagDur = (newEndTs - newStartTs).coerceAtLeast(0L)
            tagDeltaById[tagId] = (tagDeltaById[tagId] ?: 0L) + (newTagDur - oldTagDur)
        }
    }

    val newTags = tags.map { tag ->
        val d = tagDeltaById[tag.id] ?: 0L
        if (d == 0L) tag else tag.copy(totalMs = (tag.totalMs + d).coerceAtLeast(0L))
    }

    return EngineResult(newTasks, newTags)
}

/**
 * Delete a completed ClosedSessionRecord by its global index in the closedSessions list.
 * Also updates:
 * - Task.totalMs
 * - removes matching TaggedSessionRecords (same sessionId,start,end)
 * - recomputes Tag.totalMs (safe full recompute)
 */
fun deleteClosedSessionRecordByIndex(
    tasks: List<Task>,
    tags: List<Tag>,
    sessionIndex: Int,
    nowMs: Long
): EngineResult {
    if (sessionIndex !in closedSessions.indices) return EngineResult(tasks, tags)

    val old = closedSessions.removeAt(sessionIndex)
    val oldDur = (old.endTs - old.startTs).coerceAtLeast(0L)

    // Update task total
    val newTasks = tasks.map { t ->
        if (t.id != old.sessionId) t else t.copy(totalMs = (t.totalMs - oldDur).coerceAtLeast(0L))
    }

    // Remove matching tag sessions (same triple)
    val it = tagSessions.listIterator()
    while (it.hasNext()) {
        val ts = it.next()
        if (ts.sessionId == old.sessionId && ts.startTs == old.startTs && ts.endTs == old.endTs) {
            it.remove()
        }
    }

    // Safe: recompute tag totals from scratch, so UI/export always matches history
    val newTags = recomputeAllTagTotals(tags = tags, tasks = newTasks, nowMs = nowMs)
    return EngineResult(newTasks, newTags)
}



    /**
     * Undo a stop by deleting a completed ClosedSessionRecord (by global index) and restoring the task
     * into a running state starting from the original startTs.
     *
     * NOTE: This assumes the user is undoing a *recent* stop (e.g. via snackbar).
     */
    fun undoStopBySessionIndex(
        tasks: List<Task>,
        tags: List<Tag>,
        sessionIndex: Int,
        nowMs: Long
    ): EngineResult {
        if (sessionIndex !in closedSessions.indices) return EngineResult(tasks, tags)

        val s = closedSessions[sessionIndex]
        val sessionId = s.sessionId
        val startTs = s.startTs

        // 1) Remove the completed session (and matching tag sessions + totals).
        val afterDelete = deleteClosedSessionRecordByIndex(
            tasks = tasks,
            tags = tags,
            sessionIndex = sessionIndex,
            nowMs = nowMs
        )

        val taskAfter = afterDelete.tasks.firstOrNull { it.id == sessionId } ?: return afterDelete
        if (taskAfter.isDeleted) return afterDelete

        // 2) Mark task as running again from the original start.
        activeSessionStart[sessionId] = startTs
        val newTasks = afterDelete.tasks.map { t0 ->
            if (t0.id != sessionId) t0
            else t0.copy(isRunning = true, lastStartedAtMs = startTs)
        }

        // 3) Restore tag runtime state for the task's CURRENT tagIds.
        var newTags = afterDelete.tags
        val taskTags = taskAfter.tagIds
        taskTags.forEach { tagId ->
            val key = TagKey(sessionId = sessionId, tagId = tagId)
            if (!activeTagStart.containsKey(key)) {
                activeTagStart[key] = startTs
            }
        }

        // Update tag active counts + runningSince timestamps (UI only; totals already fixed by delete).
        newTags = newTags.map { tag ->
            if (tag.id !in taskTags) tag
            else {
                val countForTag = activeTagStart.keys.count { it.tagId == tag.id }
                val earliest = activeTagStart
                    .filter { (k, _) -> k.tagId == tag.id }
                    .minOfOrNull { it.value }
                tag.copy(
                    activeChildrenCount = countForTag,
                    lastStartedAtMs = earliest
                )
            }
        }

        return EngineResult(newTasks, newTags)
    }

    /**
     * Undo the most recent START of a task, without creating any sessions.
     * This is only valid if the task is currently running and the activeStart matches expectedStartTs.
     */
    fun undoStartTask(
        tasks: List<Task>,
        tags: List<Tag>,
        sessionId: Long,
        expectedStartTs: Long
    ): EngineResult {
        val task = tasks.firstOrNull { it.id == sessionId } ?: return EngineResult(tasks, tags)
        if (task.isDeleted || !task.isRunning) return EngineResult(tasks, tags)

        val active = activeSessionStart[sessionId] ?: return EngineResult(tasks, tags)
        if (active != expectedStartTs) return EngineResult(tasks, tags)

        // 1) Remove runtime tracking
        activeSessionStart.remove(sessionId)
        task.tagIds.forEach { tagId ->
            activeTagStart.remove(TagKey(sessionId = sessionId, tagId = tagId))
        }

        // 2) Task becomes inactive again (no totals change)
        val newTasks = tasks.map { t ->
            if (t.id != sessionId) t else t.copy(isRunning = false, lastStartedAtMs = null)
        }

        // 3) Recompute affected tags' running state (UI-only fields)
        val affectedTagIds = task.tagIds
        val newTags = tags.map { tag ->
            if (tag.id !in affectedTagIds) return@map tag
            val countForTag = activeTagStart.keys.count { it.tagId == tag.id }
            val earliest = activeTagStart
                .filter { (k, _) -> k.tagId == tag.id }
                .minOfOrNull { it.value }
            tag.copy(
                activeChildrenCount = countForTag,
                lastStartedAtMs = earliest
            )
        }

        return EngineResult(newTasks, newTags)
    }

    /**
     * Edit the start timestamp of a RUNNING task.
 * This changes the live duration and will affect the final duration when the task is stopped.
 */
fun editRunningTaskStart(
    tasks: List<Task>,
    tags: List<Tag>,
    sessionId: Long,
    newStartTs: Long
): EngineResult {
    if (!activeSessionStart.containsKey(sessionId)) return EngineResult(tasks, tags)
    activeSessionStart[sessionId] = newStartTs

    val newTasks = tasks.map { t ->
        if (t.id != sessionId) t else t.copy(lastStartedAtMs = newStartTs, isRunning = true)
    }
    return EngineResult(newTasks, tags)
}

fun exportRuntimeSnapshot(): RuntimeSnapshot {
        val activeTags = activeTagStart.entries.map { (k, startTs) ->
            Triple(k.sessionId, k.tagId, startTs)
        }
        return RuntimeSnapshot(
            activeSessionStart = activeSessionStart.toMap(),
            activeTagStart = activeTags
        )
    }

    /**
     * Restores all volatile runtime structures needed to correctly STOP running items after process death.
     *
     * IMPORTANT: callers must also restore tasks/tags lists in the ViewModel.
     */
    fun importRuntimeSnapshot(
        tasks: List<Task>,
        tags: List<Tag>,
        closedSessionsSnapshot: List<ClosedSessionRecord>,
        tagSessionsSnapshot: List<TaggedSessionRecord>,
        snapshot: RuntimeSnapshot
    ) {
        activeSessionStart.clear()
        activeSessionStart.putAll(snapshot.activeSessionStart)

        activeTagStart.clear()
        snapshot.activeTagStart.forEach { (sessionId, tagId, startTs) ->
            activeTagStart[TagKey(sessionId = sessionId, tagId = tagId)] = startTs
        }

        closedSessions.clear()
        closedSessions.addAll(closedSessionsSnapshot)
        tagSessions.clear()
        tagSessions.addAll(tagSessionsSnapshot)

        nextTaskId = (tasks.maxOfOrNull { it.id } ?: 0L) + 1L
        nextTagId = (tags.maxOfOrNull { it.id } ?: 0L) + 1L
    }

    /**
     * Carica uno snapshot importato (tipicamente da CSV) sostituendo tutto lo stato volatile.
     *
     * Nota: l'app al momento non persiste su DB, quindi questo è il modo più semplice per
     * ripristinare tasks/tags/sessioni dopo reinstallazione.
     */
    fun loadImportedSnapshot(
        tasks: List<Task>,
        tags: List<Tag>,
        importedClosedSessionRecords: List<ClosedSessionRecord>,
        importedTaggedSessionRecords: List<TaggedSessionRecord>,
        runtimeSnapshot: RuntimeSnapshot? = null
    ) {
        // reset runtime + sessions
        activeSessionStart.clear()
        activeTagStart.clear()
        closedSessions.clear()
        closedSessions.addAll(importedClosedSessionRecords)
        tagSessions.clear()
        tagSessions.addAll(importedTaggedSessionRecords)

        // update id generators so that new entities won't collide
        nextTaskId = (tasks.maxOfOrNull { it.id } ?: 0L) + 1L
        nextTagId = (tags.maxOfOrNull { it.id } ?: 0L) + 1L

        // restore runtime snapshot if present
        if (runtimeSnapshot != null) {
            importRuntimeSnapshot(
                tasks = tasks,
                tags = tags,
                closedSessionsSnapshot = importedClosedSessionRecords,
                tagSessionsSnapshot = importedTaggedSessionRecords,
                snapshot = runtimeSnapshot
            )
        } else {
            // best-effort: rebuild runtime from imported tasks (so STOP works correctly)
            tasks.filter { it.isRunning && it.lastStartedAtMs != null }.forEach { t ->
                val start = t.lastStartedAtMs ?: return@forEach
                activeSessionStart[t.id] = start
                t.tagIds.forEach { tagId ->
                    activeTagStart[TagKey(sessionId = t.id, tagId = tagId)] = start
                }
            }
        }
    }

    fun clearSessions() {
        closedSessions.clear()
        tagSessions.clear()
        activeSessionStart.clear()
        activeTagStart.clear()
    }

    fun deleteTask(
        tasks: List<Task>,
        tags: List<Tag>,
        sessionId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        val idx = tasks.indexOfFirst { it.id == sessionId }
        if (idx < 0) return EngineResult(tasks, tags)

        val t = tasks[idx]
        val stopped = if (t.isRunning) stopTask(tasks, tags, t, nowMs) else EngineResult(tasks, tags)

        // Soft delete: keep item in list so IDs are never reused and we can offer a "Trash".
        val newTasks = stopped.tasks.map { task ->
            if (task.id == sessionId) {
                task.copy(isDeleted = true, deletedAtMs = nowMs, isRunning = false, lastStartedAtMs = null)
            } else task
        }

        // IMPORTANT: tag totals must update when a task is deleted (active or not).
        // We keep the historical tagSessions/closedSessions (so a future "restore" can bring them back),
        // but tag totals are computed only from sessions belonging to NON-deleted tasks.
        val newTags = recomputeAllTagTotals(tags = stopped.tags, tasks = newTasks, nowMs = nowMs)

        return EngineResult(newTasks, newTags)
    }

    /**
     * Rebuild all TaggedSessionRecords (historical) for a single task from ClosedSessionRecords, applying the CURRENT task.tagIds
     * to the entire history.
     *
     * If the task is running, its active tag runtime is rebuilt to start from the task's start.
     */
    private fun rebuildTaggedSessionRecordsForTask(
        task: Task,
        tasks: List<Task>,
        tags: List<Tag>,
        nowMs: Long
    ) {
        val tagNameById = tags.associate { it.id to it.name }

        // 1) Remove historical tag sessions for this task.
        tagSessions.removeAll { it.sessionId == task.id }

        // 2) Recreate TaggedSessionRecords from the task's ClosedSessionRecords, for each current tag.
        val relevantClosedSessionRecords = closedSessions.filter { it.sessionId == task.id }
        task.tagIds.forEach { tagId ->
            relevantClosedSessionRecords.forEach { ts ->
                tagSessions.add(
                    TaggedSessionRecord(
                        tagId = tagId,
                        tagName = tagNameById[tagId] ?: "",
                        sessionId = ts.sessionId,
                        sessionTitle = ts.sessionTitle,
                        startTs = ts.startTs,
                        endTs = ts.endTs
                    )
                )
            }
        }

        // 3) Rebuild active tag runtime for this task if it is currently running.
        // Clear any activeTagStart entries for this task first.
        activeTagStart.keys.filter { it.sessionId == task.id }.forEach { activeTagStart.remove(it) }

        if (task.isRunning) {
            val start = activeSessionStart[task.id] ?: task.lastStartedAtMs
            if (start != null) {
                task.tagIds.forEach { tagId ->
                    activeTagStart[TagKey(sessionId = task.id, tagId = tagId)] = start
                }
            }
        }

        // Safety: if task was deleted, ensure no active runtime is kept.
        val isDeleted = tasks.firstOrNull { it.id == task.id }?.isDeleted == true
        if (isDeleted) {
            activeSessionStart.remove(task.id)
            activeTagStart.keys.filter { it.sessionId == task.id }.forEach { activeTagStart.remove(it) }
        }
    }

    /**
     * Recompute *all* tag totals from tagSessions + active runtime, excluding sessions belonging to deleted tasks.
     * This is called after retroactive regeneration and after task delete/restore/purge operations.
     */
    private fun recomputeAllTagTotals(
        tags: List<Tag>,
        tasks: List<Task>,
        nowMs: Long
    ): List<Tag> {
        fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
            if (intervals.isEmpty()) return 0L
            val sorted = intervals.sortedBy { it.first }
            var total = 0L
            var curStart = sorted[0].first
            var curEnd = sorted[0].second
            for (i in 1 until sorted.size) {
                val (s, e) = sorted[i]
                if (e <= s) continue
                if (s <= curEnd) {
                    if (e > curEnd) curEnd = e
                } else {
                    total += (curEnd - curStart)
                    curStart = s
                    curEnd = e
                }
            }
            total += (curEnd - curStart)
            return total.coerceAtLeast(0L)
        }

        val taskById = tasks.associateBy { it.id }
        val isTaskActiveAndNotDeleted: (Long) -> Boolean = { sessionId ->
            val t = taskById[sessionId]
            t != null && !t.isDeleted
        }

        // Base totals from closed sessions.
        // IMPORTANT: totals are computed as UNION of intervals (overlaps counted once).
        val intervalsByTag = mutableMapOf<Long, MutableList<Pair<Long, Long>>>()
        tagSessions.forEach { s ->
            if (!isTaskActiveAndNotDeleted(s.sessionId)) return@forEach
            val start = s.startTs
            val end = s.endTs
            if (end <= start) return@forEach
            intervalsByTag.getOrPut(s.tagId) { mutableListOf() }.add(start to end)
        }

        val totalsByTag: Map<Long, Long> = intervalsByTag.mapValues { (_, intervals) ->
            unionTotalMs(intervals)
        }

        // Active runtime info from activeTagStart (do NOT add to totalsByTag here).
        // We keep activeChildrenCount + earliest start for UI, while UI computes the live delta using nowMs.
        val activeStartsByTag = mutableMapOf<Long, MutableList<Long>>()
        activeTagStart.forEach { (k, start) ->
            if (!isTaskActiveAndNotDeleted(k.sessionId)) return@forEach
            activeStartsByTag.getOrPut(k.tagId) { mutableListOf() }.add(start)
        }

        return tags.map { tag ->
            val activeStarts = activeStartsByTag[tag.id]
            val activeCount = activeStarts?.size ?: 0
            val earliest = activeStarts?.minOrNull()
            tag.copy(
                totalMs = totalsByTag[tag.id] ?: 0L,
                activeChildrenCount = activeCount,
                lastStartedAtMs = earliest
            )
        }
    }

    fun deleteTag(
        tasks: List<Task>,
        tags: List<Tag>,
        tagId: Long,
        deleteAssociatedTasks: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        var curTasks = tasks
        var curTags = tags

        val tasksWithTag = curTasks.filter { !it.isDeleted && it.tagIds.contains(tagId) }

        // For the "Solo tag" option, remember which tasks were linked to this tag.
        // This lets us re-associate automatically if the tag is restored from the trash.
        val restoreSessionIdsSnapshot = tasksWithTag.map { it.id }.toSet()

        if (deleteAssociatedTasks) {
            // Soft delete all tasks that currently have the tag.
            tasksWithTag.forEach { task ->
                val res = deleteTask(
                    tasks = curTasks,
                    tags = curTags,
                    sessionId = task.id,
                    nowMs = nowMs
                )
                curTasks = res.tasks
                curTags = res.tags
            }
        } else {
            // Remove the tag from each task; if a task is running, close TaggedSessionRecords coherently.
            tasksWithTag.forEach { task ->
                val res = reassignTaskTags(
                    tasks = curTasks,
                    tags = curTags,
                    sessionId = task.id,
                    newTagIds = task.tagIds - tagId,
                    nowMs = nowMs
                )
                curTasks = res.tasks
                curTags = res.tags
            }
        }

        // Soft delete the tag itself.
        curTags = curTags.map { tag ->
            if (tag.id == tagId) {
                if (deleteAssociatedTasks) {
                    tag.copy(isDeleted = true, deletedAtMs = nowMs, restoreSessionIds = emptySet())
                } else {
                    tag.copy(isDeleted = true, deletedAtMs = nowMs, restoreSessionIds = restoreSessionIdsSnapshot)
                }
            } else tag
        }
        return EngineResult(curTasks, curTags)
    }

    fun restoreTask(
        tasks: List<Task>,
        tags: List<Tag>,
        sessionId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        val newTasks = tasks.map {
            if (it.id == sessionId) it.copy(isDeleted = false, deletedAtMs = null) else it
        }

        // Recompute tag totals immediately: restored tasks must contribute their historical sessions again.
        val newTags = recomputeAllTagTotals(tags = tags, tasks = newTasks, nowMs = nowMs)
        return EngineResult(newTasks, newTags)
    }

    fun restoreTag(
        tasks: List<Task>,
        tags: List<Tag>,
        tagId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        var curTasks = tasks
        var curTags = tags

        val tag = curTags.firstOrNull { it.id == tagId } ?: return EngineResult(curTasks, curTags)
        val sessionIdsToRestore = tag.restoreSessionIds

        // First restore the tag itself.
        curTags = curTags.map {
            if (it.id == tagId) it.copy(isDeleted = false, deletedAtMs = null, restoreSessionIds = emptySet()) else it
        }

        // Then re-associate it to tasks that had it before deletion ("Solo tag").
        // Use reassignTaskTags so running task/tag sessions remain coherent.
        sessionIdsToRestore.forEach { sessionId ->
            val t = curTasks.firstOrNull { it.id == sessionId }
            if (t != null && !t.isDeleted) {
                val res = reassignTaskTags(
                    tasks = curTasks,
                    tags = curTags,
                    sessionId = sessionId,
                    newTagIds = t.tagIds + tagId,
                    nowMs = nowMs
                )
                curTasks = res.tasks
                curTags = res.tags
            }
        }

        return EngineResult(curTasks, curTags)
    }

    fun purgeTask(
        tasks: List<Task>,
        tags: List<Tag>,
        sessionId: Long
    ): EngineResult {
        // Remove runtime tracking
        activeSessionStart.remove(sessionId)
        activeTagStart.keys.filter { it.sessionId == sessionId }.forEach { activeTagStart.remove(it) }

        // Remove sessions
        closedSessions.removeAll { it.sessionId == sessionId }
        tagSessions.removeAll { it.sessionId == sessionId }

        val newTasks = tasks.filterNot { it.id == sessionId }
        return EngineResult(newTasks, tags)
    }

    fun purgeTag(
        tasks: List<Task>,
        tags: List<Tag>,
        tagId: Long
    ): EngineResult {
        // Remove runtime tracking for that tag
        activeTagStart.keys.filter { it.tagId == tagId }.forEach { activeTagStart.remove(it) }

        // Remove sessions
        tagSessions.removeAll { it.tagId == tagId }

        // Remove the tag from all tasks (including deleted ones) to avoid dangling references
        val updatedTasks = tasks.map { t ->
            if (t.tagIds.contains(tagId)) t.copy(tagIds = t.tagIds - tagId) else t
        }
        val newTags = tags.filterNot { it.id == tagId }
        return EngineResult(updatedTasks, newTags)
    }



    fun renameTag(
        tags: List<Tag>,
        tagId: Long,
        newName: String
    ): List<Tag> {
        val n = newName.trim()
        if (n.isEmpty()) return tags
        for (index in tagSessions.indices) {
            val session = tagSessions[index]
            if (session.tagId == tagId && session.tagName != n) {
                tagSessions[index] = session.copy(tagName = n)
            }
        }
        return tags.map { tag ->
            if (tag.id == tagId) tag.copy(name = n) else tag
        }
    }
    private fun startTask(tasks: List<Task>, tags: List<Tag>, task: Task, nowMs: Long): EngineResult {
        activeSessionStart[task.id] = nowMs

        val newTask = task.copy(
            isRunning = true,
            lastStartedAtMs = nowMs
        )

        // Avvia tag-session per tutti i tag associati al task in questo momento
        var newTags = tags
        newTask.tagIds.forEach { tagId ->
            newTags = startTagForTask(newTask, newTags, tagId, nowMs)
        }

        val newTasks = tasks.map { if (it.id == task.id) newTask else it }
        return EngineResult(newTasks, newTags)
    }

    private fun stopTask(tasks: List<Task>, tags: List<Tag>, task: Task, nowMs: Long): EngineResult {
        val start = activeSessionStart.remove(task.id) ?: task.lastStartedAtMs
        val delta = if (start != null && nowMs > start) (nowMs - start) else 0L

        val newTask = task.copy(
            isRunning = false,
            totalMs = task.totalMs + delta,
            lastStartedAtMs = null
        )

        // log task-session per export
        if (start != null && nowMs > start) {
            closedSessions.add(
                ClosedSessionRecord(
                    sessionId = task.id,
                    sessionTitle = task.name,
                    startTs = start,
                    endTs = nowMs
                )
            )
        }

        // chiudi tutte le tag-session attive per questo task
        var newTags = tags
        task.tagIds.forEach { tagId ->
            newTags = stopTagForTask(task, newTags, tagId, nowMs)
        }

        val newTasks = tasks.map { if (it.id == task.id) newTask else it }
        return EngineResult(newTasks, newTags)
    }

    private fun startTagForTask(task: Task, tags: List<Tag>, tagId: Long, nowMs: Long): List<Tag> {
        val key = TagKey(sessionId = task.id, tagId = tagId)
        if (activeTagStart.containsKey(key)) return tags // già attivo

        // When adding a tag to a running task, the expected semantics are that the tag applies
        // to the whole running session (i.e., it should start at the same time as the task).
        // Using nowMs here makes tag totals appear smaller than the task duration.
        val start = task.lastStartedAtMs ?: nowMs
        activeTagStart[key] = start

        // Keep a tag-level "running since" for UI: earliest start among active sessions for this tag.
        val earliest = activeTagStart
            .filter { (k, _) -> k.tagId == tagId }
            .minOfOrNull { it.value }

        return tags.map { tag ->
            if (tag.id != tagId) return@map tag
            val prevStarted = tag.lastStartedAtMs
            val newStarted = when {
                earliest != null -> earliest
                prevStarted != null -> minOf(prevStarted, start)
                else -> start
            }
            tag.copy(
                activeChildrenCount = tag.activeChildrenCount + 1,
                lastStartedAtMs = newStarted
            )
        }
    }

    private fun stopTagForTask(task: Task, tags: List<Tag>, tagId: Long, nowMs: Long): List<Tag> {
        val key = TagKey(sessionId = task.id, tagId = tagId)
        val start = activeTagStart.remove(key) ?: return tags

        val delta = if (nowMs > start) (nowMs - start) else 0L

        if (delta > 0L) {
            val tagNameById = tags.associate { it.id to it.name }
            val tagName = tagNameById[tagId] ?: ""
            tagSessions.add(
                TaggedSessionRecord(
                    tagId = tagId,
                    tagName = tagName,
                    sessionId = task.id,
                    sessionTitle = task.name,
                    startTs = start,
                    endTs = nowMs
                )
            )
        }

        // Recompute earliest active start for this tag (if still running for other tasks).
        val earliest = activeTagStart
            .filter { (k, _) -> k.tagId == tagId }
            .minOfOrNull { it.value }

        return tags.map { tag ->
            if (tag.id != tagId) return@map tag

            val newCount = (tag.activeChildrenCount - 1).coerceAtLeast(0)
            val newStartedAt = earliest // null if no longer active

            tag.copy(
                activeChildrenCount = newCount,
                totalMs = tag.totalMs + delta,
                lastStartedAtMs = newStartedAt
            )
        }
    }
}


