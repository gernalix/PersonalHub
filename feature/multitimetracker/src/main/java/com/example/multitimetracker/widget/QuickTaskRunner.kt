// v471
// v349
package com.example.multitimetracker.widget

import android.content.Context
import com.example.multitimetracker.R
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.persistence.AuditLogSqlite
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.util.CapsuleWriteApi
import org.json.JSONObject
import kotlin.random.Random

/**
 * Shared logic used by the home-screen widget to create + start a new running *session*.
 *
 * IMPORTANT: session-only behavior (no task creation).
 */
@OptIn(CapsuleWriteApi::class)
object QuickSessionRunner {
    sealed class Result {
        data class Success(val sessionId: Long, val title: String) : Result()
        data class Failure(val error: Throwable) : Result()
    }

    interface SessionStarter {
        fun ensureRunningSessionRow(title: String, startMs: Long, tagIds: Set<Long>, nowMs: Long): Long
    }

    private fun randomTitleSuffix(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I
        val suffix = buildString {
            repeat(6) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
        return suffix
    }

    private fun ensureTempTag(engine: TimeEngine, tags: List<Tag>): Pair<List<Tag>, Long> {
        val existing = tags.firstOrNull { it.name == "#temp" }
        return if (existing != null) {
            tags to existing.id
        } else {
            val created = engine.createTag("#temp")
            (tags + created) to created.id
        }
    }

    private fun notifySnapshotChanged(context: Context) {
        context.sendBroadcast(
            android.content.Intent(QuickSessionWidgetProvider.ACTION_SNAPSHOT_CHANGED)
                .setPackage(context.packageName)
        )
    }

    fun run(
        context: Context,
        sessionStarter: SessionStarter = object : SessionStarter {
            override fun ensureRunningSessionRow(title: String, startMs: Long, tagIds: Set<Long>, nowMs: Long): Long =
                com.example.multitimetracker.core.session.DefaultSessionCore(context)
                    .ensureRunningSessionRow(title = title, startMs = startMs, tagIds = tagIds, nowMs = nowMs)
        },
        auditInsert: (String, Long, Long) -> Unit = { title, tempTagId, now ->
            AuditLogSqlite.insert(
                context = context,
                isSystem = false,
                action = "SESSION_START_NEW",
                entityType = "SESSION",
                entityId = null,
                summary = context.getString(R.string.audit_session_start_new_widget, title),
                payload = JSONObject()
                    .put("startTs", now)
                    .put("title", title)
                    .put("tagIds", org.json.JSONArray().apply { put(tempTagId) })
                    .put("fromWidget", true)
                    .put("undoable", true)
            )
        },
        notifyChanged: (Context) -> Unit = ::notifySnapshotChanged,
    ): Result {
        return runCatching { runOrThrow(context, sessionStarter, auditInsert, notifyChanged) }
            .fold(onSuccess = { it }, onFailure = { Result.Failure(it) })
    }

    private fun runOrThrow(
        context: Context,
        sessionStarter: SessionStarter,
        auditInsert: (String, Long, Long) -> Unit,
        notifyChanged: (Context) -> Unit,
    ): Result.Success {
        val snapshot = SnapshotStore.load(context)

        val appUsageMs = snapshot?.appUsageMs ?: 0L
        val installAtMs = snapshot?.installAtMs ?: System.currentTimeMillis()

        val engine = TimeEngine()
        val tasks = snapshot?.tasks ?: emptyList()
        val tags = snapshot?.tags ?: emptyList()
        val closedSessions = snapshot?.closedSessions ?: emptyList()
        val tagSessions = snapshot?.tagSessions ?: emptyList()

        val runtime = snapshot?.let {
            TimeEngine.RuntimeSnapshot(
                activeSessionStart = it.activeSessionStart,
                activeTagStart = it.activeTagStart.map { a -> Triple(a.sessionId, a.tagId, a.startTs) }
            )
        } ?: TimeEngine.RuntimeSnapshot(emptyMap(), emptyList())

        engine.importRuntimeSnapshot(
            tasks = tasks,
            tags = tags,
            closedSessionsSnapshot = closedSessions,
            tagSessionsSnapshot = tagSessions,
            snapshot = runtime
        )

        val (tags2, tempTagId) = ensureTempTag(engine, tags)
        val now = System.currentTimeMillis()

        // If we created #temp, persist it in the snapshot JSON so tag IDs remain consistent.
        // NOTE: we intentionally DO NOT touch tasks/sessions/runtime here (session-only source of truth is sessions table).
        if (tags2.size != tags.size) {
            SnapshotStore.save(
                context = context,
                tasks = tasks,
                tags = tags2,
                closedSessions = closedSessions,
                tagSessions = tagSessions,
                timeFenceRules = snapshot?.timeFenceRules ?: emptyList(),
                installAtMs = installAtMs,
                appUsageMs = appUsageMs,
                activeSessionStart = snapshot?.activeSessionStart ?: emptyMap(),
                activeTagStart = snapshot?.activeTagStart ?: emptyList(),
                tagParents = snapshot?.tagParents ?: emptyList(),
                // IMPORTANT: do not wipe chains when the widget writes the snapshot.
                chains = snapshot?.chains ?: emptyList(),
                activeChainRun = snapshot?.activeChainRun
            )
        }

        // v110 ARCH: write directly to session tables (source of truth), avoid replace-all mirroring.
        val title = context.getString(R.string.quick_session_title_prefix, randomTitleSuffix())
        val sessionId = sessionStarter.ensureRunningSessionRow(
            title = title.trim(),
            startMs = now,
            tagIds = setOf(tempTagId),
            nowMs = now
        )

        // Audit log: session started from widget.
        auditInsert(title, tempTagId, now)
        notifyChanged(context)
        return Result.Success(sessionId, title)
    }
}

