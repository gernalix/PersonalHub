package com.example.multitimetracker.api

import android.content.Context
import com.example.multitimetracker.persistence.SnapshotSqlite
import com.example.multitimetracker.persistence.SnapshotStore

internal data class MttPublicSessionRow(
    val id: String,
    val startMs: Long,
    val endMs: Long?,
    val tags: List<String>,
)

internal class MttPublicSessionsRepository(private val context: Context) {
    fun latestSessionsByTag(tag: String, limit: Int): List<MttPublicSessionRow> {
        val normalizedTag = tag.trim()
        if (normalizedTag.isBlank()) return emptyList()

        val tags = SnapshotStore.load(context)?.tags.orEmpty()
            .filterNot { it.isDeleted }
        val tagIds = tags
            .filter { it.name.equals(normalizedTag, ignoreCase = true) }
            .map { it.id }
            .toSet()
        if (tagIds.isEmpty()) return emptyList()

        val tagNamesById = tags.associateBy({ it.id }, { it.name })
        SnapshotSqlite.ensureSessionTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val placeholders = tagIds.joinToString(",") { "?" }
            val boundedLimit = limit.coerceIn(1, 100)
            val sessions = ArrayList<Triple<Long, Long, Long?>>()
            val args = tagIds.map { it.toString() }.toMutableList().also {
                it.add(boundedLimit.toString())
            }.toTypedArray()
            val sql = """
                SELECT DISTINCT s.id, s.start_ms, s.end_ms
                FROM ${SnapshotSqlite.SESSIONS_TABLE} s
                JOIN ${SnapshotSqlite.SESSION_TAGS_TABLE} st ON st.session_id = s.id
                WHERE s.deleted_at_ms IS NULL
                  AND s.end_ms IS NOT NULL
                  AND st.tag_id IN ($placeholders)
                ORDER BY s.start_ms DESC, s.id DESC
                LIMIT ?
            """.trimIndent()
            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    sessions.add(
                        Triple(
                            cursor.getLong(0),
                            cursor.getLong(1),
                            if (cursor.isNull(2)) null else cursor.getLong(2),
                        )
                    )
                }
            }
            if (sessions.isEmpty()) return emptyList()

            val sessionIds = sessions.map { it.first }
            val sessionPlaceholders = sessionIds.joinToString(",") { "?" }
            val tagsBySessionId = LinkedHashMap<Long, MutableList<String>>()
            db.rawQuery(
                """
                SELECT session_id, tag_id
                FROM ${SnapshotSqlite.SESSION_TAGS_TABLE}
                WHERE session_id IN ($sessionPlaceholders)
                ORDER BY session_id, tag_id
                """.trimIndent(),
                sessionIds.map { it.toString() }.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val sessionId = cursor.getLong(0)
                    val tagId = cursor.getLong(1)
                    val name = tagNamesById[tagId] ?: continue
                    tagsBySessionId.getOrPut(sessionId) { mutableListOf() }.add(name)
                }
            }

            return sessions.map { (id, start, end) ->
                MttPublicSessionRow(
                    id = id.toString(),
                    startMs = start,
                    endMs = end,
                    tags = tagsBySessionId[id].orEmpty(),
                )
            }
        } finally {
            db.close()
        }
    }
}

