package com.example.multitimetracker.hub

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import com.gernalix.personalhub.contracts.database.SinceWhenSourceDescriptor
import com.gernalix.personalhub.contracts.database.SinceWhenTimestampSource
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TimerQuickEventEntryHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "timer"
    override val entityKind = "quick_event_entry"
    override val capabilities = setOf("event", "time_point")
    private val database get() = PersonalHubDatabase.get(context.applicationContext)

    override suspend fun exists(canonicalId: String): Boolean = read(canonicalId) != null

    override suspend fun lifecycle(canonicalId: String): String = read(canonicalId)?.let {
        if (it.deletedAt == null) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    } ?: HubEntityLifecycle.DELETED

    override suspend fun summaries(canonicalIds: Set<String>): Map<String, HubEntitySummary> = withContext(Dispatchers.IO) {
        canonicalIds.mapNotNull { id -> read(id)?.takeIf { it.deletedAt == null }?.let { id to it.summary() } }.toMap()
    }

    override suspend fun search(query: String, limit: Int): List<HubEntitySummary> = withContext(Dispatchers.IO) {
        val text = query.trim()
        val sql = if (text.isBlank()) {
            "SELECT id,title,timestamp_ms,created_at_ms,deleted_at_ms FROM quick_event_entries WHERE deleted_at_ms IS NULL ORDER BY timestamp_ms DESC,id DESC LIMIT ?"
        } else {
            "SELECT id,title,timestamp_ms,created_at_ms,deleted_at_ms FROM quick_event_entries WHERE deleted_at_ms IS NULL AND title LIKE ? ORDER BY timestamp_ms DESC,id DESC LIMIT ?"
        }
        val args = if (text.isBlank()) arrayOf(limit.coerceIn(1, 100)) else arrayOf("%$text%", limit.coerceIn(1, 100))
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    Entry(cursor.getLong(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3), null).summary(),
                )
            }
        }
    }

    override suspend fun openTarget(canonicalId: String): HubOpenTarget = HubOpenTarget(
        HubDeepLinkContract.moduleUri("timer", "quickEventEntryId" to canonicalId).toString(),
        "com.example.multitimetracker.MainActivity",
    )

    override suspend fun sinceWhenSource(canonicalId: String): SinceWhenSourceDescriptor? {
        val entry = read(canonicalId)?.takeIf { it.deletedAt == null } ?: return null
        return SinceWhenSourceDescriptor(
            entityType = "$moduleId/$entityKind",
            entityId = canonicalId,
            defaultCounterTitle = entry.title,
            timestampSources = listOf(SinceWhenTimestampSource(
                "event_date",
                context.getString(com.example.multitimetracker.R.string.since_when_event_date),
                entry.timestamp,
                true,
            )),
        )
    }

    private data class Entry(val id: Long, val title: String, val timestamp: Long, val createdAt: Long, val deletedAt: Long?) {
        fun summary() = HubEntitySummary(
            HubEntityRef("timer", "quick_event_entry", id.toString()),
            title,
            attributes = mapOf("time_ms" to timestamp.toString(), "added_at_ms" to createdAt.toString()),
        )
    }

    private fun read(canonicalId: String): Entry? {
        val id = canonicalId.toLongOrNull() ?: return null
        return database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery(
                "SELECT id,title,timestamp_ms,created_at_ms,deleted_at_ms FROM quick_event_entries WHERE id=?",
                arrayOf(id),
            ),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else Entry(
                cursor.getLong(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3),
                if (cursor.isNull(4)) null else cursor.getLong(4),
            )
        }
    }
}
