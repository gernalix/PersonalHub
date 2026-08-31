// v471
// v391
package com.example.multitimetracker.persistence

import android.content.Context
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.util.CapsuleWriteApi

/**
 * Auto-consistency engine (v391).
 *
 * Problem (simple):
 * - The app has TWO representations of history:
 *   1) Session-only tables in SQLite: sessions + session_tags  (authoritative log)
 *   2) Snapshot JSON fields (legacy): tags.totalMs + tagSessions (used for compatibility/export/older screens)
 *
 * If these drift apart (e.g. import edge cases, older migrations, partial restores),
 * users can see inconsistent totals or integrity reports full of mismatches.
 *
 * Solution:
 * - Detect drift.
 * - Recompute the legacy snapshot *derived fields* from the session tables:
 *   - Tag.totalMs
 *   - Tag.lastStartedAtMs (earliest running start per tag, if any)
 *   - tagSessions (rebuilt from sessions + session_tags)
 *
 * We deliberately DO NOT touch:
 * - sessions / session_tags (source of truth)
 * - tasks / closedSessions (state layer; can still exist for other features)
 */
internal object AutoConsistencyEngine {

    data class Result(
        val ran: Boolean,
        val changed: Boolean,
        val reason: String
    )

    fun runIfNeeded(
        context: Context,
        nowMs: Long,
        currentPatch: Long
    ): Result {
        fun currentDbStamp(): String {
            val file = SnapshotSqlite.internalDbFile(context)
            return if (file.exists()) {
                "${file.length()}:${file.lastModified()}"
            } else {
                "missing"
            }
        }

        val last = UiPrefsStore.getLong(context, UiPrefsStore.KEY_LAST_AUTOCONSIST_PATCH, 0L)
        if (last >= currentPatch) {
            return Result(ran = false, changed = false, reason = "already ran for patch=$last")
        }

        // If there is no snapshot, there is nothing to reconcile.
        val snap = SnapshotStore.load(context) ?: run {
            markRunBestEffort(context, currentPatch)
            return Result(ran = true, changed = false, reason = "no snapshot")
        }
        val initialSnapshotJson = runCatching { SnapshotSqlite.readSnapshot(context) }.getOrNull()
        val initialDbStamp = currentDbStamp()

        // Ensure session tables exist. If they don't, skip without mutating anything.
        runCatching { SnapshotSqlite.ensureSessionTables(context) }.onFailure {
            markRunBestEffort(context, currentPatch)
            return Result(ran = true, changed = false, reason = "ensureSessionTables failed: ${it.message}")
        }

        // If sessions table is empty, we have nothing to derive.
        val hasAnySessions = runCatching {
            val db = SnapshotSqlite.openReadableDb(context)
            db.use {
                it.rawQuery("SELECT 1 FROM ${SnapshotSqlite.SESSIONS_TABLE} WHERE deleted_at_ms IS NULL LIMIT 1", null)
                    .use { c -> c.moveToFirst() }
            }
        }.getOrDefault(false)

        if (!hasAnySessions) {
            markRunBestEffort(context, currentPatch)
            return Result(ran = true, changed = false, reason = "no sessions in table")
        }

        val (newTags, newClosedSessionRecords, newTaggedSessionRecords, changed) = recomputeDerivedFromSessionTables(
            context = context,
            nowMs = nowMs,
            tags = snap.tags,
            oldClosedSessionRecords = snap.closedSessions,
            oldTaggedSessionRecordsCount = snap.tagSessions.size
        )

        if (!changed) {
            markRunBestEffort(context, currentPatch)
            return Result(ran = true, changed = false, reason = "already consistent")
        }

        val snapshotStillMatches = runCatching {
            when (initialSnapshotJson) {
                null -> SnapshotStore.load(context) == snap
                else -> currentDbStamp() == initialDbStamp &&
                    SnapshotSqlite.readSnapshot(context) == initialSnapshotJson
            }
        }.getOrDefault(false)
        if (!snapshotStillMatches) {
            return Result(ran = false, changed = false, reason = "snapshot changed during auto-consistency")
        }

        // Persist updated derived fields back into snapshot.
        @OptIn(CapsuleWriteApi::class)
        runCatching {
            SnapshotStore.save(
                context = context,
                tasks = snap.tasks,
                tags = newTags,
                closedSessions = newClosedSessionRecords,
                tagSessions = newTaggedSessionRecords,
                timeFenceRules = snap.timeFenceRules,
                installAtMs = snap.installAtMs,
                appUsageMs = snap.appUsageMs,
                activeSessionStart = snap.activeSessionStart,
                activeTagStart = snap.activeTagStart,
                tagParents = snap.tagParents,
                chains = snap.chains,
                activeChainRun = snap.activeChainRun
            )
        }.onFailure {
            markRunBestEffort(context, currentPatch)
            return Result(ran = true, changed = false, reason = "SnapshotStore.save failed: ${it.message}")
        }

        markRunBestEffort(context, currentPatch)
        return Result(ran = true, changed = true, reason = "recomputed tag totals + tagSessions from session tables")
    }

    private fun markRunBestEffort(context: Context, currentPatch: Long) {
        runCatching { UiPrefsStore.putLong(context, UiPrefsStore.KEY_LAST_AUTOCONSIST_PATCH, currentPatch) }
        runCatching { UiPrefsStore.mirrorAllToSqlite(context) }
    }

    private data class Derived(
        val tags: List<Tag>,
        val tagSessions: List<TaggedSessionRecord>,
        val changed: Boolean
    )

    private fun recomputeDerivedFromSessionTables(
        context: Context,
        nowMs: Long,
        tags: List<Tag>,
        oldClosedSessionRecords: List<ClosedSessionRecord>,
        oldTaggedSessionRecordsCount: Int
    ): Quadruple<List<Tag>, List<ClosedSessionRecord>, List<TaggedSessionRecord>, Boolean> {
        val tagNameById = tags.associateBy({ it.id }, { it.name })

        // 1) Totals (including running up to nowMs), derived from sessions + session_tags.
        val totalsRows = runCatching { SessionMirrorSqlite.computeSessionOnlyTagTotals(context = context, tags = tags, nowMs = nowMs) }
            .getOrElse { emptyList<SessionMirrorSqlite.SessionOnlyTagTotalsRow>() }

        val totalMsByTagId = totalsRows.associateBy({ it.tagId }, { it.totalMs })

        // 2) Earliest running start per tag (for Tag.lastStartedAtMs)
        val runningMinStartByTagId = HashMap<Long, Long>()
        runCatching {
            val db = SnapshotSqlite.openReadableDb(context)
            db.use { sqlDb ->
                val q = """
                    SELECT st.tag_id, MIN(s.start_ms) AS min_start
                    FROM ${SnapshotSqlite.SESSIONS_TABLE} s
                    JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st ON st.session_id = s.id
                    WHERE s.deleted_at_ms IS NULL AND s.end_ms IS NULL
                    GROUP BY st.tag_id
                """.trimIndent()

                sqlDb.rawQuery(q, null).use { c ->
                    while (c.moveToNext()) {
                        val tagId = c.getLong(0)
                        val minStart = c.getLong(1)
                        if (minStart > 0L) runningMinStartByTagId[tagId] = minStart
                    }
                }
            }
        }

        // 3) Rebuild closedSessions + tagSessions from session tables.
        val rebuiltClosedSessionRecords = ArrayList<ClosedSessionRecord>(1024)
        val rebuiltTaggedSessionRecords = ArrayList<TaggedSessionRecord>(2048)
        runCatching {
            val db = SnapshotSqlite.openReadableDb(context)
            db.use { sqlDb ->
                val q = """
                    SELECT s.id, s.title, s.start_ms, s.end_ms, st.tag_id
                    FROM ${SnapshotSqlite.SESSIONS_TABLE} s
                    JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st ON st.session_id = s.id
                    WHERE s.deleted_at_ms IS NULL
                """.trimIndent()

                val seenClosedSessionIds = LinkedHashSet<Long>()
                sqlDb.rawQuery(q, null).use { c ->
                    while (c.moveToNext()) {
                        val sessionId = c.getLong(0)
                        val title = c.getString(1) ?: ""
                        val start = c.getLong(2)
                        val end = if (c.isNull(3)) null else c.getLong(3)
                        val tagId = c.getLong(4)
                        if (end != null && end > start && seenClosedSessionIds.add(sessionId)) {
                            rebuiltClosedSessionRecords.add(
                                ClosedSessionRecord(
                                    sessionId = sessionId,
                                    sessionTitle = title,
                                    startTs = start,
                                    endTs = end
                                )
                            )
                        }
                        val effectiveEnd = end ?: nowMs
                        if (effectiveEnd <= start) continue
                        val tagName = tagNameById[tagId] ?: continue
                        // Legacy contract names: sessionId/sessionTitle, but semantics are "session".
                        rebuiltTaggedSessionRecords.add(
                            TaggedSessionRecord(
                                tagId = tagId,
                                tagName = tagName,
                                sessionId = sessionId,
                                sessionTitle = title,
                                startTs = start,
                                endTs = effectiveEnd
                            )
                        )
                    }
                }
            }
        }

        // 4) Apply derived totals into Tag objects while preserving flags.
        var changed = false
        val updatedTags = tags.map { t ->
            if (t.isDeleted) return@map t
            val newTotal = totalMsByTagId[t.id] ?: 0L
            val newRunningStart = runningMinStartByTagId[t.id]
            if (t.totalMs != newTotal || t.lastStartedAtMs != newRunningStart) {
                changed = true
                t.copy(totalMs = newTotal, lastStartedAtMs = newRunningStart)
            } else {
                t
            }
        }

        if (rebuiltClosedSessionRecords != oldClosedSessionRecords) changed = true

        // Cheap drift signal: if tagSessions count differs, we treat that as inconsistency.
        if (rebuiltTaggedSessionRecords.size != oldTaggedSessionRecordsCount) changed = true

        return Quadruple(updatedTags, rebuiltClosedSessionRecords, rebuiltTaggedSessionRecords, changed)
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}




