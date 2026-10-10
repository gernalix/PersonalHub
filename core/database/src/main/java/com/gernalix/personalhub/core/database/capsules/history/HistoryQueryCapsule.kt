package com.gernalix.personalhub.core.database.capsules.history

import androidx.sqlite.db.SupportSQLiteDatabase

/** Query-only facade over the canonical journals. No event copies or inferred history. */
object HistoryQueryCapsule {
    data class Filter(
        val modules: Set<String>? = null,
        val fromMs: Long? = null,
        val toMs: Long? = null,
        val query: String = "",
        val entityKind: String? = null,
        val entityId: String? = null,
        val eventId: String? = null,
    )
    data class Cursor(val occurredAt: Long, val key: String)
    data class Group(val source: String, val groupId: String, val module: String, val occurredAt: Long, val key: String,
        val entityKind: String? = null, val label: String? = null, val detail: String? = null)
    data class Page(val groups: List<Group>, val next: Cursor?)

    fun page(db: SupportSQLiteDatabase, filter: Filter, before: Cursor? = null, limit: Int = 50): Page {
        require(limit in 1..100)

        if (filter.modules?.isEmpty() == true) return Page(emptyList(), null)
        val args = mutableListOf<Any?>()
        val linkedGroup = filter.eventId?.let { eventId -> db.query(
            "SELECT transaction_id FROM mutation_events WHERE event_id=? UNION ALL SELECT group_id FROM hub_git_history_index WHERE id=? UNION ALL SELECT group_id FROM hub_activity_log WHERE id=? LIMIT 1",
            arrayOf(eventId,eventId,eventId),
        ).use { c -> if(c.moveToFirst() && !c.isNull(0)) c.getString(0) else null } }
        fun conditions(module: String, time: String, kind: String, entity: String, id: String, groupId: String, table: String, rowId: String): String {
            val clauses = mutableListOf<String>()
            if (filter.eventId != null) {
                clauses += "($id=? OR $groupId=?)"; args += filter.eventId; args += linkedGroup
            } else {
                filter.modules?.let { values ->
                    clauses += "$module IN (${values.joinToString(",") { "?" }})"; args.addAll(values.sorted())
                }
                filter.fromMs?.let { clauses += "$time>=?"; args += it }
                filter.toMs?.let { clauses += "$time<=?"; args += it }
                filter.entityKind?.let { clauses += "$kind=?"; args += it }
                filter.entityId?.let { clauses += "$entity=?"; args += it }
                if (filter.query.isNotBlank()) {
                    val match = HistorySearchIndex.matchQuery(filter.query)
                    if (match == null) clauses += "0" else {
                        val index = HistorySearchIndex.indexFor(table)
                        clauses += "$rowId IN (SELECT docid FROM `$index` WHERE `$index` MATCH ?)"
                        args += match
                    }
                }
            }
            return if (clauses.isEmpty()) "1" else clauses.joinToString(" AND ")
        }
        val semanticModule = "CASE WHEN module='money' THEN 'soldi' ELSE module END"
        val semantic = conditions(semanticModule, "occurred_at", "COALESCE(entity_kind,entity_type)",
            "COALESCE(canonical_id,entity_id)", "event_id", "transaction_id", "mutation_events", "rowid")
        val gitModule = moduleCase("g.table_name")
        val git = conditions(gitModule, "g.occurred_at", "e.entity_kind", "e.canonical_id", "g.id", "g.group_id",
            "hub_git_history_index", "g.rowid")
        val activity = conditions("a.module_id", "a.occurred_at", "a.entity_kind", "a.entity_id", "a.id", "a.group_id",
            "hub_activity_log", "a.rowid")
        val patch = conditions("'settings'", "p.applied_at", "NULL", "NULL", "p.id", "p.id", "hub_git_applied_patches", "p.rowid")
        var cursorClause = ""
        if (before != null && filter.eventId == null) {
            cursorClause = "WHERE occurred_at<? OR (occurred_at=? AND page_key<?)"
            args.addAll(listOf(before.occurredAt, before.occurredAt, before.key))
        }
        args += limit + 1
        // Match a group first, then hydrate the complete group separately. Pagination never splits it.
        val sql = """
            WITH candidates AS (
                SELECT 'semantic' source, transaction_id group_id, $semanticModule module,
                    MAX(occurred_at) occurred_at, 'semantic:'||transaction_id||':'||$semanticModule page_key
                FROM mutation_events WHERE $semantic
                GROUP BY transaction_id,$semanticModule
                UNION ALL
                SELECT 'git',COALESCE(NULLIF(g.group_id,''),g.id),$gitModule,MAX(g.occurred_at),
                    'git:'||COALESCE(NULLIF(g.group_id,''),g.id)||':'||$gitModule
                FROM hub_git_history_index g
                LEFT JOIN hub_entities e ON e.local_table=g.table_name AND
                    (g.row_key=hex(quote(e.local_key)) OR g.row_key=hex(e.local_key))
                WHERE $git AND (g.group_id IS NULL OR NOT EXISTS (
                    SELECT 1 FROM mutation_events m WHERE m.transaction_id=g.group_id
                        AND (CASE WHEN m.module='money' THEN 'soldi' ELSE m.module END)=$gitModule))
                    AND g.table_name NOT IN ('hub_context_members','hub_entity_bindings','hub_activity_log',
                        'hub_entities','hub_entity_aliases','hub_external_identities','mutation_events')
                GROUP BY COALESCE(NULLIF(g.group_id,''),g.id),$gitModule
                UNION ALL
                SELECT 'activity',COALESCE(NULLIF(a.group_id,''),a.id),a.module_id,MAX(a.occurred_at),
                    'activity:'||COALESCE(NULLIF(a.group_id,''),a.id)||':'||a.module_id
                FROM hub_activity_log a WHERE a.is_system=0 AND $activity
                    AND (a.group_id IS NULL OR NOT EXISTS (
                        SELECT 1 FROM mutation_events m WHERE m.transaction_id=a.group_id
                        AND (CASE WHEN m.module='money' THEN 'soldi' ELSE m.module END)=a.module_id))
                    AND (a.group_id IS NULL OR NOT EXISTS (
                        SELECT 1 FROM hub_git_history_index g WHERE g.group_id=a.group_id))
                GROUP BY COALESCE(NULLIF(a.group_id,''),a.id),a.module_id
                UNION ALL
                SELECT 'patch',p.id,'settings',p.applied_at,'patch:'||p.id
                FROM hub_git_applied_patches p WHERE $patch
            ) SELECT source,group_id,module,occurred_at,page_key FROM candidates
            $cursorClause ORDER BY occurred_at DESC,page_key DESC LIMIT ?
        """.trimIndent()
        val rows = db.query(sql,args.toTypedArray()).use { c -> buildList {
            while (c.moveToNext()) add(Group(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4)))
        } }
        val shown = rows.take(limit)
        return Page(shown, if (rows.size>limit) shown.last().let { Cursor(it.occurredAt,it.key) } else null)
    }

    /** Current temporal records are a separate view, never fabricated mutation events. */
    fun records(db: SupportSQLiteDatabase, filter: Filter, before: Cursor? = null, limit: Int = 50): Page {
        require(limit in 1..100)
        if (filter.modules?.isEmpty() == true) return Page(emptyList(),null)
        val args = mutableListOf<Any?>()
        val match = HistorySearchIndex.matchQuery(filter.query)
        fun search(table: String, rowid: String): String {
            if (filter.query.isBlank()) return "1"
            if (match == null) return "0"
            val index = HistorySearchIndex.indexFor(table)
            args += match
            return "$rowid IN (SELECT docid FROM `$index` WHERE `$index` MATCH ?)"
        }
        val sources = listOf(
            "SELECT 'intake_events' local_table,CAST(i.id AS TEXT) local_key,i.timestamp_utc occurred_at,s.name label,CAST(i.dose AS TEXT)||' '||i.dose_unit detail FROM intake_events i JOIN substances s ON s.id=i.substance_id WHERE ${search("substances","s.rowid")}",
            "SELECT 'sessions',CAST(s.id AS TEXT),s.start_ms,s.title,NULL FROM sessions s WHERE s.deleted_at_ms IS NULL AND ${search("sessions","s.rowid")}",
            "SELECT 'contacts',CAST(c.id AS TEXT),c.created_at,COALESCE((SELECT value FROM contact_fields f WHERE f.contact_id=c.id AND field_type='name' ORDER BY is_primary DESC,position,id LIMIT 1),NULL),NULL FROM contacts c WHERE c.deleted_at IS NULL AND " +
                if (filter.query.isBlank()) "1" else "EXISTS(SELECT 1 FROM contact_fields f WHERE f.contact_id=c.id AND ${search("contact_fields","f.rowid")})",
            "SELECT 'finance_transactions',CAST(t.id AS TEXT),t.occurredAt,COALESCE(NULLIF(n.name,''),NULLIF(t.notes,''),CAST(t.amount AS TEXT)||' '||t.currency),CAST(t.amount AS TEXT)||' '||t.currency FROM finance_transactions t LEFT JOIN finance_titles n ON n.id=t.titleId WHERE (${search("finance_titles","n.rowid")} OR ${search("finance_transactions","t.rowid")})",
            "SELECT 'places',p.uuid,p.created_at,p.nickname,p.address FROM places p WHERE ${search("places","p.rowid")}",
            "SELECT 'place_events',CAST(v.id AS TEXT),v.timestamp,p.nickname,v.event_type FROM place_events v JOIN places p ON p.uuid=v.place_id WHERE ${search("places","p.rowid")}",
            "SELECT 'since_when_counters',CAST(s.id AS TEXT),s.initial_timestamp,s.title,s.description FROM since_when_counters s WHERE ${search("since_when_counters","s.rowid")}",
            "SELECT 'wordpulse_sessions',CAST(w.id AS TEXT),w.started_at_utc_ms,NULL,NULL FROM wordpulse_sessions w WHERE " +
                if (filter.query.isBlank()) "1" else {
                    args += filter.query.lowercase(java.util.Locale.ROOT)
                    "instr(lower('Typing session WordPulse'),?)>0"
                },
        )
        val clauses = mutableListOf("e.lifecycle='ACTIVE'")
        filter.modules?.let { modules -> clauses += "e.owning_module IN (${modules.joinToString(",") { "?" }})"; args.addAll(modules.sorted()) }
        filter.fromMs?.let { clauses += "r.occurred_at>=?"; args += it }
        filter.toMs?.let { clauses += "r.occurred_at<=?"; args += it }
        filter.entityKind?.let { clauses += "e.entity_kind=?"; args += it }
        filter.entityId?.let { clauses += "e.canonical_id=?"; args += it }
        if (before != null) {
            clauses += "(r.occurred_at<? OR (r.occurred_at=? AND e.canonical_id<?))"
            args.addAll(listOf(before.occurredAt,before.occurredAt,before.key))
        }
        args += limit+1
        val sql = "WITH records AS (${sources.joinToString(" UNION ALL ")}) " +
            "SELECT e.canonical_id,e.owning_module,r.occurred_at,e.entity_kind,r.label,r.detail FROM records r " +
            "JOIN hub_entities e ON e.local_table=r.local_table AND e.local_key=r.local_key WHERE " +
            clauses.joinToString(" AND ") + " ORDER BY r.occurred_at DESC,e.canonical_id DESC LIMIT ?"
        val rows = db.query(sql,args.toTypedArray()).use { c -> buildList {
            while(c.moveToNext()) add(Group("record",c.getString(0),c.getString(1),c.getLong(2),c.getString(0),
                c.getString(3),c.getString(4),if(c.isNull(5)) null else c.getString(5)))
        } }
        val shown = rows.take(limit)
        return Page(shown,if(rows.size>limit) shown.last().let { Cursor(it.occurredAt,it.key) } else null)
    }

    private fun moduleCase(column: String): String {
        val tables = GIT_PEOPLE_TABLES + GIT_PLACES_TABLES + GIT_SUBSTANCES_TABLES + GIT_WORDPULSE_TABLES + GIT_TIMER_TABLES + GIT_TAG_TABLES
        return "CASE WHEN $column LIKE 'finance_%' THEN 'soldi' WHEN $column LIKE 'health_%' THEN 'salute' " +
            "WHEN $column='since_when_counters' THEN 'since_when' " +
            tables.joinToString(" ") { "WHEN $column='$it' THEN '${moduleForTable(it)}'" } + " ELSE 'hub' END"
    }

private val GIT_PEOPLE_TABLES = setOf(
    "contacts", "contact_fields", "contact_events", "contact_initiatives", "contact_messaging_links",
    "saved_searches", "saved_search_tags", "tags", "contact_tags", "people_photos",
)

private val GIT_PLACES_TABLES = setOf(
    "places", "place_aliases", "place_links", "place_events", "check_in_attempts",
    "check_in_attempt_candidates", "place_geofence_configs", "place_geofence_transition_log",
    "place_tags", "place_tag_cross_ref", "alert_rules", "alert_place_tag_targets",
    "history_audit_log", "history_actions", "global_stats_state", "route_distance_cache",
)

private val GIT_SUBSTANCES_TABLES = setOf(
    "substances", "intake_events", "stock_adjustments", "prescriptions", "interaction_rules",
    "interaction_targets", "settings", "macros", "macro_items", "notification_state",
)

private val GIT_WORDPULSE_TABLES = setOf(
    "app_state", "correction_events", "word_entries", "wordpulse_sessions", "pvt_results",
)

private val GIT_TIMER_TABLES = setOf(
    "sessions", "session_tags", "quick_event_entries", "quick_event_entry_field_values",
    "quick_event_entry_tags", "quick_event_macro_actions", "quick_event_macro_tags",
    "quick_event_macros", "quick_event_template_fields", "quick_event_template_tags",
    "quick_event_templates", "audit_events", "integrity_stats", "snapshot", "snapshot_history",
    "snapshot_payloads", "ui_prefs_mirror",
)

private val GIT_TAG_TABLES = setOf(
    "hub_tags", "hub_tag_aliases", "hub_tag_assignments", "hub_tag_parents", "hub_saved_tag_filters",
)

fun moduleForTable(table: String): String = when {
    table.startsWith("finance_") -> "soldi"
    table in GIT_PEOPLE_TABLES -> "people"
    table in GIT_PLACES_TABLES -> "places"
    table in GIT_SUBSTANCES_TABLES -> "substances"
    table in GIT_WORDPULSE_TABLES -> "wordpulse"
    table in GIT_TIMER_TABLES -> "timer"
    table in GIT_TAG_TABLES -> "tags"
    table == "since_when_counters" -> "since_when"
    table.startsWith("health_") -> "salute"
    else -> "hub"
}

}
