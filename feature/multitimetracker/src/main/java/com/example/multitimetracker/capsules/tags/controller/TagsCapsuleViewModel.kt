// v384
package com.example.multitimetracker.capsules.tags.controller

import android.util.Log
import com.example.multitimetracker.applyClosedSessionTitleUpdates
import com.example.multitimetracker.applySessionTitleUpdates
import com.example.multitimetracker.buildAutoDerivedSessionTitleUpdates
import com.example.multitimetracker.buildSnapshotDerivedSessionTitleUpdates
import com.example.multitimetracker.capsules.sessions.public.SessionsRuntimePublicApi
import com.example.multitimetracker.capsules.sessions.public.SessionsRuntimeState
import com.example.multitimetracker.capsules.system.CapsuleRuntimeChange
import com.example.multitimetracker.capsules.system.CapsuleRuntimeParticipant
import com.example.multitimetracker.capsules.tags.public.TagsSnapshotProjection
import com.example.multitimetracker.capsules.system.TagsCapsuleAccess
import com.example.multitimetracker.canonicalizeTaggedSessionRecords
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.capsules.tags.state.TagsHostState
import com.example.multitimetracker.capsules.tags.state.TagsUiState
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.TimedTagNotificationType
import com.example.multitimetracker.ui.util.TagHierarchy
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.util.CapsuleWriteApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Feature Capsule: Tags
 *
 * Ownership:
 * - Tag CRUD policy lives here.
 * - MainViewModel supplies only state/persistence/core primitives through TagsCapsuleAccess.
 * - Cross-capsule writes are explicit: session title projection uses SessionCore through the access API.
 */
class TagsCapsuleViewModel(
    private val access: TagsCapsuleAccess,
    private val sessionsRuntime: SessionsRuntimePublicApi,
) : CapsuleRuntimeParticipant {
    override val capsuleId: String = "tags"
    private val liveTags = MutableStateFlow<List<Tag>>(emptyList())
    private val liveTagParentsByChild = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    private var runtimeCanonicalTags: List<Tag> = emptyList()
    val uiState: StateFlow<TagsUiState> = combine(
        access.hostStateFlow(),
        sessionsRuntime.runtimeState,
        liveTags,
        liveTagParentsByChild,
    ) { host, runtime, tags, parents ->
        buildUiState(
            host = host,
            runtime = runtime,
            tags = tags,
            tagParentsByChild = parents,
        )
    }.stateIn(
        access.runtimeScope(),
        SharingStarted.Eagerly,
        buildUiState(access.hostStateFlow().value, sessionsRuntime.runtimeStateValue(), emptyList(), emptyMap()),
    )

    override fun onCapsuleRuntimeChanged(context: android.content.Context?, change: CapsuleRuntimeChange) = Unit

    fun tags(): List<Tag> = liveTags.value
    fun runningSessions(): List<SessionUi> = sessionsRuntime.runtimeStateValue().runningSessions
    fun chronologySessions(): List<SessionUi> = sessionsRuntime.runtimeStateValue().chronologySessions
    fun tasks(): List<Task> = sessionsRuntime.runtimeStateValue().tasks
    fun tagParentsByChild(): Map<Long, Set<Long>> = liveTagParentsByChild.value

    fun replaceTags(tags: List<Tag>) {
        liveTags.value = tags
    }

    fun replaceTagParentsByChild(parentsByChild: Map<Long, Set<Long>>) {
        liveTagParentsByChild.value = parentsByChild
    }

    fun prepareSnapshotTags(snapshot: SnapshotStore.Snapshot): TagsSnapshotProjection {
        val projection = projectSnapshotTags(snapshot)
        return TagsSnapshotProjection(
            tags = projection.tags,
            activeTagStartByTagId = projection.activeTagStartByTagId,
        )
    }

    fun mergeSnapshotTags(canonicalTags: List<Tag>, runtimeTags: List<Tag>): List<Tag> {
        if (canonicalTags.isEmpty()) return runtimeTags
        val runtimeById = runtimeTags.associateBy { it.id }
        val canonicalIds = canonicalTags.mapTo(linkedSetOf()) { it.id }
        val merged = canonicalTags.map { canonical ->
            val runtime = runtimeById[canonical.id] ?: return@map canonical
            val canonicalName = canonical.name.trim()
            val runtimeName = runtime.name.trim()
            val resolvedName = when {
                canonicalName.isNotEmpty() && canonicalName != "tag_${canonical.id}" -> canonical.name
                runtimeName.isNotEmpty() && runtimeName != "tag_${runtime.id}" -> runtime.name
                else -> canonical.name
            }
            canonical.copy(
                name = resolvedName,
                totalMs = runtime.totalMs,
                activeChildrenCount = runtime.activeChildrenCount,
                lastStartedAtMs = runtime.lastStartedAtMs,
            )
        }.toMutableList()
        runtimeTags.filter { it.id !in canonicalIds }.forEach { merged.add(it) }
        return merged
    }

    fun selectCanonicalTags(currentTags: List<Tag>, persistedCanonicalTags: List<Tag>): List<Tag> {
        if (hasResolvedTagName(currentTags)) return currentTags
        if (hasResolvedTagName(runtimeCanonicalTags)) return runtimeCanonicalTags
        if (hasResolvedTagName(persistedCanonicalTags)) return persistedCanonicalTags
        return when {
            currentTags.isNotEmpty() -> currentTags
            runtimeCanonicalTags.isNotEmpty() -> runtimeCanonicalTags
            else -> persistedCanonicalTags
        }
    }

    fun selectTagsForAuthoritativeRead(
        context: android.content.Context,
        currentTags: List<Tag>,
        persistedCanonicalTags: List<Tag>,
    ): List<Tag> {
        if (persistedCanonicalTags.isEmpty()) return currentTags
        val currentTagIds = currentTags.mapTo(linkedSetOf()) { it.id }
        val persistedTagIds = persistedCanonicalTags.mapTo(linkedSetOf()) { it.id }
        val requiredTagIds = runCatching {
            val session = access.sessionCore(context)
            buildSet {
                session.readAllSessions().forEach { addAll(it.tagIds) }
            }
        }.getOrDefault(emptySet())
        if (requiredTagIds.isEmpty()) return currentTags
        return if (
            !currentTagIds.containsAll(requiredTagIds) &&
            persistedTagIds.containsAll(requiredTagIds)
        ) {
            persistedCanonicalTags
        } else {
            currentTags
        }
    }

    fun rememberRuntimeCanonicalTags(tags: List<Tag>) {
        if (tags.isEmpty()) return
        runtimeCanonicalTags = mergeSnapshotTags(
            canonicalTags = tags,
            runtimeTags = runtimeCanonicalTags,
        )
    }

    fun replaceRuntimeCanonicalTags(tags: List<Tag>) {
        if (tags.isEmpty()) return
        runtimeCanonicalTags = tags
    }

    fun withResolvedTagDefinitions(
        snapshot: SnapshotStore.Snapshot,
        canonicalTags: List<Tag>,
    ): SnapshotStore.Snapshot {
        val resolved = normalizeTagsAgainstCanonicalDefinitions(snapshot.tags, canonicalTags)
        return if (resolved == snapshot.tags) snapshot else snapshot.copy(tags = resolved)
    }

    fun withResolvedTagSessionNames(snapshot: SnapshotStore.Snapshot): SnapshotStore.Snapshot {
        val resolved = canonicalizeTaggedSessionRecords(
            tagSessions = snapshot.tagSessions,
            tags = snapshot.tags,
        )
        return if (resolved == snapshot.tagSessions) snapshot else snapshot.copy(tagSessions = resolved)
    }

    @OptIn(CapsuleWriteApi::class)
    fun repairPersistedTagNames(context: android.content.Context, canonicalTags: List<Tag>) {
        if (canonicalTags.isEmpty()) return
        val current = SnapshotStore.load(context) ?: return
        val repairedTags = mergeSnapshotTags(
            canonicalTags = canonicalTags,
            runtimeTags = current.tags,
        )
        val repairedTagSessions = canonicalizeTaggedSessionRecords(
            tagSessions = current.tagSessions,
            tags = repairedTags,
        )
        val repaired = current.copy(
            tags = repairedTags,
            tagSessions = repairedTagSessions,
        )
        if (repaired == current) return
        SnapshotStore.save(
            context = context,
            tasks = repaired.tasks,
            tags = repaired.tags,
            closedSessions = repaired.closedSessions,
            tagSessions = repaired.tagSessions,
            lifePeriods = repaired.lifePeriods,
            timeFenceRules = repaired.timeFenceRules,
            installAtMs = repaired.installAtMs,
            appUsageMs = repaired.appUsageMs,
            activeSessionStart = repaired.activeSessionStart,
            activeTagStart = repaired.activeTagStart,
            tagParents = repaired.tagParents,
            chains = repaired.chains,
            activeChainRun = repaired.activeChainRun,
            chronologySessions = repaired.chronologySessions,
            runningSessions = repaired.runningSessions,
            quickEventTemplates = repaired.quickEventTemplates,
            quickEventEntries = repaired.quickEventEntries,
            quickEventFieldDefinitions = repaired.quickEventFieldDefinitions,
            quickEventFieldValues = repaired.quickEventFieldValues,
            quickEventMacros = repaired.quickEventMacros,
            quickEventMacroActions = repaired.quickEventMacroActions,
        )
    }

    fun repairAutoDerivedSessionTitlesFromPersistedSnapshot(
        context: android.content.Context,
        currentTags: List<Tag>,
    ) {
        val persistedSnapshot = SnapshotStore.load(context) ?: return
        if (persistedSnapshot.tagSessions.isEmpty()) return

        val session = access.sessionCore(context)
        val authoritativeSessions = session.readAllSessions()
            .filter { it.deletedAtMs == null }
            .distinctBy { it.id }
        val sessionTitlesById = buildSnapshotDerivedSessionTitleUpdates(
            snapshotTagSessions = persistedSnapshot.tagSessions,
            sessions = authoritativeSessions,
            currentTags = currentTags,
        )
        if (sessionTitlesById.isEmpty()) return

        sessionTitlesById.forEach { (sessionId, updatedTitle) ->
            val currentSession = authoritativeSessions.firstOrNull { it.id == sessionId }
                ?: session.readSessionById(sessionId)
                ?: return@forEach
            if (currentSession.title.trim() == updatedTitle.trim()) return@forEach
            session.updateSessionMeta(
                sessionId = sessionId,
                title = updatedTitle,
                tagIds = currentSession.tagIds,
            )
        }
    }

    fun addTag(
        name: String,
        timedDurationMinutes: Int? = null,
        notificationType: TimedTagNotificationType = TimedTagNotificationType.NONE
    ) {
        if (access.blockWriteIfNeeded()) return
        val n = name.trim()
        if (n.isBlank()) return

        val already = tags().any { !it.isDeleted && it.name.equals(n, ignoreCase = true) }
        if (already) {
            access.appContextOrNull()?.let { access.showTagAlreadyExists(it) }
            return
        }

        val normalizedTimedDuration = timedDurationMinutes?.takeIf { it > 0 }
        val created = access.createTagRecord(n).copy(
            timedDurationMinutes = normalizedTimedDuration,
            notificationType = if (normalizedTimedDuration == null) TimedTagNotificationType.NONE else notificationType
        )
        liveTags.update { current -> current + created }

        access.logUserEvent(
            action = "TAG_CREATE",
            entityType = "TAG",
            entityId = created.id,
            summary = "Create tag: ${created.name.trim().ifBlank { "#${created.id}" }}",
            payload = JSONObject().put("tagId", created.id).put("name", created.name.trim()),
            undoable = true
        )

        persistTagMutation()
    }

    fun renameTag(
        tagId: Long,
        newName: String,
        timedDurationMinutes: Int? = null,
        notificationType: TimedTagNotificationType = TimedTagNotificationType.NONE
    ) {
        if (access.blockWriteIfNeeded()) return
        if (newName.isBlank()) return
        val beforeTags = tags()
        val beforeRuntime = sessionsRuntime.runtimeStateValue()
        val before = beforeTags.firstOrNull { it.id == tagId } ?: return
        val normalizedTimedDuration = timedDurationMinutes?.takeIf { it > 0 }
        val renamedTags = access.renameTagRecords(beforeTags, tagId, newName).map { tag ->
            if (tag.id == tagId) {
                tag.copy(
                    timedDurationMinutes = normalizedTimedDuration,
                    notificationType = if (normalizedTimedDuration == null) TimedTagNotificationType.NONE else notificationType
                )
            } else {
                tag
            }
        }
        val oldName = before.name.trim()
        val ctx = access.appContextOrNull()

        fun commitRename(sessionTitlesById: Map<Long, String>) {
            val now = System.currentTimeMillis()
            liveTags.value = renamedTags
            sessionsRuntime.updateRuntimeState { current ->
                current.copy(
                    closedSessions = applyClosedSessionTitleUpdates(current.closedSessions, sessionTitlesById),
                    tagSessions = canonicalizeTaggedSessionRecords(current.tagSessions, renamedTags, sessionTitlesById),
                    chronologySessions = applySessionTitleUpdates(current.chronologySessions, sessionTitlesById),
                    runningSessions = applySessionTitleUpdates(current.runningSessions, sessionTitlesById),
                )
            }
            access.logUserEvent(
                action = "TAG_RENAME",
                entityType = "TAG",
                entityId = tagId,
                summary = "Rename tag: ${oldName.ifBlank { "#$tagId" }} -> ${newName.trim()}",
                payload = JSONObject().put("tagId", tagId).put("oldName", oldName).put("newName", newName.trim()),
                undoable = oldName.isNotBlank()
            )
            access.persist()
            access.scheduleAutoBackup()
        }

        if (ctx == null) {
            val fallbackTitles = buildAutoDerivedSessionTitleUpdates(
                sessions = (beforeRuntime.chronologySessions + beforeRuntime.runningSessions).distinctBy { it.id },
                beforeTags = beforeTags,
                afterTags = renamedTags,
            )
            commitRename(fallbackTitles)
            return
        }

        access.launchIo("renameTag tagId=$tagId") {
            runCatching {
                val session = access.sessionCore(ctx)
                val authoritativeSessions = (session.readAllSessions() + session.readRunningSessions())
                    .filter { it.deletedAtMs == null }
                    .distinctBy { it.id }
                val sessionTitlesById = buildAutoDerivedSessionTitleUpdates(
                    sessions = authoritativeSessions,
                    beforeTags = beforeTags,
                    afterTags = renamedTags,
                )

                sessionTitlesById.forEach { (sessionId, updatedTitle) ->
                    val currentSession = session.readSessionById(sessionId) ?: return@forEach
                    if (currentSession.title.trim() == updatedTitle.trim()) return@forEach
                    session.updateSessionMeta(
                        sessionId = sessionId,
                        title = updatedTitle,
                        tagIds = currentSession.tagIds
                    )
                }

                commitRename(sessionTitlesById)
            }.onFailure { err ->
                Log.e("TagsCapsule", "renameTag failed (tagId=$tagId)", err)
                withContext(Dispatchers.Main) {
                    access.showSessionWriteFailed(ctx)
                }
            }
        }
    }

    fun deleteTag(tagId: Long, deleteTasks: Boolean) {
        if (access.blockWriteIfNeeded()) return
        val before = tags().firstOrNull { it.id == tagId }
        val currentRuntime = sessionsRuntime.runtimeStateValue()
        val currentTags = tags()
        run {
            val now = System.currentTimeMillis()
            val res = access.deleteTagRecords(
                tasks = currentRuntime.tasks,
                tags = currentTags,
                tagId = tagId,
                deleteAssociatedTasks = deleteTasks,
                nowMs = now
            )
            liveTags.value = res.tags
            sessionsRuntime.updateRuntimeState { current ->
                current.copy(
                    tasks = res.tasks,
                    closedSessions = access.closedSessionRecords(),
                    tagSessions = access.taggedSessionRecords(),
                    runningMinStartByTagId = access.activeTagStartByTagId(),
                )
            }
        }
        persistTagMutation()
        access.logUserEvent(
            action = "TAG_DELETE",
            entityType = "TAG",
            entityId = tagId,
            summary = "Delete tag: ${before?.name ?: "#$tagId"}",
            payload = JSONObject().put("tagId", tagId).put("deleteAssociatedTasks", deleteTasks),
            undoable = !deleteTasks
        )
    }

    fun restoreTag(tagId: Long) {
        if (access.blockWriteIfNeeded()) return
        val before = tags().firstOrNull { it.id == tagId }
        val currentRuntime = sessionsRuntime.runtimeStateValue()
        val currentTags = tags()
        run {
            val now = System.currentTimeMillis()
            val res = access.restoreTagRecords(
                tasks = currentRuntime.tasks,
                tags = currentTags,
                tagId = tagId,
                nowMs = now
            )
            liveTags.value = res.tags
            sessionsRuntime.updateRuntimeState { current ->
                current.copy(
                    tasks = res.tasks,
                    closedSessions = access.closedSessionRecords(),
                    tagSessions = access.taggedSessionRecords(),
                )
            }
        }
        persistTagMutation()
        access.logUserEvent(
            action = "TAG_RESTORE",
            entityType = "TAG",
            entityId = tagId,
            summary = "Restore tag: ${before?.name ?: "#$tagId"}",
            payload = JSONObject().put("tagId", tagId),
            undoable = true
        )
    }

    fun purgeTag(tagId: Long) {
        if (access.blockWriteIfNeeded()) return
        val currentRuntime = sessionsRuntime.runtimeStateValue()
        val res = access.purgeTagRecords(currentRuntime.tasks, tags(), tagId)
        liveTags.value = res.tags
        sessionsRuntime.updateRuntimeState { current ->
            current.copy(
                tasks = res.tasks,
                closedSessions = access.closedSessionRecords(),
                tagSessions = access.taggedSessionRecords(),
            )
        }
        persistTagMutation()
    }

    fun setTagArchived(tagId: Long, archived: Boolean) {
        if (access.blockWriteIfNeeded()) return
        val before = tags().firstOrNull { it.id == tagId } ?: return
        if (before.isDeleted) return
        if (before.isArchived == archived) return

        liveTags.update { cur -> cur.map { t -> if (t.id == tagId) t.copy(isArchived = archived) else t } }

        val verb = if (archived) "Archive" else "Unarchive"
        access.logUserEvent(
            action = if (archived) "TAG_ARCHIVE" else "TAG_UNARCHIVE",
            entityType = "TAG",
            entityId = tagId,
            summary = "$verb tag: ${before.name.trim().ifBlank { "#$tagId" }}",
            payload = JSONObject().put("tagId", tagId).put("archived", archived),
            undoable = true
        )

        persistTagMutation()
    }

    fun setTagShowInTimeline(tagId: Long, show: Boolean) {
        if (access.blockWriteIfNeeded()) return
        val before = tags().firstOrNull { it.id == tagId } ?: return
        if (before.isDeleted) return
        if (before.showInTimeline == show) return

        liveTags.update { cur -> cur.map { t -> if (t.id == tagId) t.copy(showInTimeline = show) else t } }

        access.logUserEvent(
            action = if (show) "TAG_TIMELINE_SHOW" else "TAG_TIMELINE_HIDE",
            entityType = "TAG",
            entityId = tagId,
            summary = (if (show) "Show" else "Hide") + " tag in Timeline: " + before.name.trim().ifBlank { "#$tagId" },
            payload = JSONObject().put("tagId", tagId).put("showInTimeline", show),
            undoable = true
        )

        persistTagMutation()
    }

    fun setTagParents(tagId: Long, parentIds: Set<Long>) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        val curTags = tags()

        val validTagIds = curTags.asSequence().filter { !it.isDeleted }.map { it.id }.toSet()
        val cleaned = parentIds
            .asSequence()
            .filter { it > 0L }
            .filter { it != tagId }
            .filter { it in validTagIds }
            .toSet()

        val filtered = cleaned.filterNot { parentId ->
            TagHierarchy.wouldCreateCycle(
                child = tagId,
                parent = parentId,
                parentsByChild = tagParentsByChild()
            )
        }.toSet()

        if (filtered.size != cleaned.size) {
            access.showTagHierarchyCycleNotAllowed(ctx)
        }

        val oldParents = tagParentsByChild()[tagId] ?: emptySet()
        if (oldParents == filtered) return

        val childName = curTags.firstOrNull { it.id == tagId }?.name?.trim().orEmpty()
        fun parentNames(ids: Set<Long>): List<String> = ids.map { id ->
            curTags.firstOrNull { it.id == id }?.name?.trim().takeIf { !it.isNullOrBlank() } ?: "#$id"
        }.sorted()
        val oldNames = parentNames(oldParents)
        val newNames = parentNames(filtered)

        liveTagParentsByChild.update { current ->
            val m = current.toMutableMap()
            if (filtered.isEmpty()) m.remove(tagId) else m[tagId] = filtered
            m.toMap()
        }

        val payload = JSONObject()
            .put("tagId", tagId)
            .put("childName", childName)
            .put("oldParentIds", JSONArray(oldParents.toList()))
            .put("newParentIds", JSONArray(filtered.toList()))
            .put("oldParentNames", JSONArray(oldNames))
            .put("newParentNames", JSONArray(newNames))

        access.logUserEvent(
            action = "TAG_PARENTS_CHANGE",
            entityType = "TAG",
            entityId = tagId,
            summary = "Tag parents: ${childName.ifBlank { "#$tagId" }}  ${oldNames.joinToString(", ")} -> ${newNames.joinToString(", ")}",
            payload = payload,
            undoable = true
        )
        access.persist()
        access.scheduleAutoBackup()
    }

    private fun buildUiState(
        host: TagsHostState,
        runtime: SessionsRuntimeState,
        tags: List<Tag>,
        tagParentsByChild: Map<Long, Set<Long>>,
    ): TagsUiState {
        return TagsUiState(
            tags = tags,
            tasks = runtime.tasks,
            closedSessions = runtime.closedSessions,
            tagSessions = runtime.tagSessions,
            chronologySessions = runtime.chronologySessions,
            runningSessions = runtime.runningSessions,
            activeTagStart = runtime.runningMinStartByTagId.filterValues { it > 0L },
            activeTagTotalsMsByTagId = runtime.activeTagTotalsMsByTagId,
            runningMinStartByTagId = runtime.runningMinStartByTagId,
            tagLastUsedMsByTagId = runtime.tagLastUsedMsByTagId,
            tagParentsByChild = tagParentsByChild,
            tagTotalsMsByTagId = runtime.tagTotalsMsByTagId,
            nowMs = host.nowMs,
            effectiveNowMs = host.effectiveNowMs,
            isReadOnly = host.isReadOnly,
            eventTags = host.eventTags,
            sinceWhenTags = host.sinceWhenTags,
        )
    }

    private fun projectSnapshotTags(snapshot: SnapshotStore.Snapshot): TagsSnapshotProjection {
        val (projectedTags, activeTagStartByTagId) = reconcileTagsFromSnapshot(
            tags = snapshot.tags,
            tagSessions = snapshot.tagSessions,
            activeSessionStart = snapshot.activeSessionStart,
            activeTagStart = snapshot.activeTagStart,
        )
        return TagsSnapshotProjection(
            tags = projectedTags,
            activeTagStartByTagId = activeTagStartByTagId,
        )
    }

    private fun normalizeTagsAgainstCanonicalDefinitions(
        tags: List<Tag>,
        canonicalTags: List<Tag>,
    ): List<Tag> {
        if (tags.isEmpty() || canonicalTags.isEmpty()) return tags
        val canonicalNamesById = LinkedHashMap<Long, String>()
        canonicalTags.forEach { tag ->
            val trimmed = tag.name.trim()
            if (trimmed.isNotEmpty() && trimmed != "tag_${tag.id}") {
                canonicalNamesById[tag.id] = trimmed
            }
        }
        if (canonicalNamesById.isEmpty()) return tags
        return tags.map { tag ->
            val canonicalName = canonicalNamesById[tag.id] ?: return@map tag
            if (canonicalName == tag.name) tag else tag.copy(name = canonicalName)
        }
    }

    private fun hasResolvedTagName(tags: List<Tag>): Boolean {
        if (tags.isEmpty()) return false
        return tags.any { tag ->
            val trimmed = tag.name.trim()
            trimmed.isNotEmpty() && trimmed != "tag_${tag.id}"
        }
    }

    private fun reconcileTagsFromSnapshot(
        tags: List<Tag>,
        tagSessions: List<TaggedSessionRecord>,
        activeSessionStart: Map<Long, Long>,
        activeTagStart: List<SnapshotStore.ActiveTag>,
    ): Pair<List<Tag>, Map<Long, Long>> {
        fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
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
                    total += curEnd - curStart
                    curStart = start
                    curEnd = end
                }
            }
            total += curEnd - curStart
            return total.coerceAtLeast(0L)
        }

        val totalsByTagId: Map<Long, Long> = tagSessions
            .groupBy { it.tagId }
            .mapValues { (_, records) ->
                unionTotalMs(records.map { it.startTs to it.endTs })
            }

        val runningStartsByTagId: Map<Long, List<Long>> =
            if (activeSessionStart.isNotEmpty()) {
                activeTagStart
                    .groupBy { it.tagId }
                    .mapValues { (_, rows) -> rows.map { it.startTs } }
            } else {
                emptyMap()
            }

        val earliestStartByTagId = runningStartsByTagId
            .mapValues { (_, starts) -> starts.minOrNull() ?: 0L }
            .filterValues { it > 0L }
        val trustRebuiltTotals = tagSessions.isNotEmpty()

        val projectedTags = tags.map { tag ->
            val starts = runningStartsByTagId[tag.id]
            tag.copy(
                totalMs = if (trustRebuiltTotals) totalsByTagId[tag.id] ?: 0L else tag.totalMs,
                activeChildrenCount = starts?.size ?: 0,
                lastStartedAtMs = starts?.minOrNull(),
            )
        }

        return projectedTags to earliestStartByTagId
    }

    private fun persistTagMutation() {
        access.rememberCurrentStateAsPersisted()
        access.clearPersistenceFailureReport()
        access.persist()
        access.scheduleAutoBackup()
    }
}
