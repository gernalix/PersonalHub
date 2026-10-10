package com.gernalix.personalhub.core.database.capsules.identity

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.contracts.database.CanonicalEntityRef
import com.gernalix.personalhub.contracts.database.HubEntityRef
import org.json.JSONArray
import org.json.JSONObject

/** One identity authority; adapters may translate local keys only at this capsule boundary. */
class CanonicalIdentityCapsule(context: Context) {
    private val appContext = context.applicationContext
    private val db get() = PersonalHubDatabase.get(appContext).openHelper.readableDatabase

    fun canonicalId(table: String, localKey: Any): String = db.query(
        "SELECT canonical_id FROM hub_entities WHERE local_table=? AND local_key=?",
        arrayOf(table, localKey.toString()),
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Unregistered entity: $table" }
        cursor.getString(0)
    }

    fun localKey(kind: String, canonicalId: String): String? {
        val resolved = resolve(kind, canonicalId) ?: return null
        return db.query("SELECT local_key FROM hub_entities WHERE canonical_id=? AND lifecycle='ACTIVE'", arrayOf(resolved.canonicalId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun resolve(kind: String, id: String): CanonicalEntityRef? {
        var current = id
        val seen = mutableSetOf<String>()
        while (true) {
            check(seen.add(current)) { "Canonical alias cycle" }
            val next = db.query("SELECT canonical_id FROM hub_entity_aliases WHERE alias_canonical_id=?", arrayOf(current)).use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: break
            current = next
        }
        return db.query("SELECT entity_kind FROM hub_entities WHERE canonical_id=?", arrayOf(current)).use {
            if (!it.moveToFirst() || it.getString(0) != kind) null else CanonicalEntityRef(kind, current)
        }
    }

    fun resolveExternal(kind: String, system: String, scope: String, externalId: String): CanonicalEntityRef? {
        val id = db.query("SELECT canonical_id FROM hub_external_identities WHERE entity_kind=? AND system=? AND source_scope=? AND external_id=? AND lifecycle='ACTIVE'",arrayOf(kind,system,scope,externalId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        return resolve(kind,id)
    }

    /** Explicit provider tuple only. A conflicting claim is a hard integrity error. */
    fun linkExternal(identity: com.gernalix.personalhub.contracts.database.HubExternalIdentity): String {
        db.beginTransaction()
        try {
            val existing = db.query("SELECT id,canonical_id,entity_kind FROM hub_external_identities WHERE system=? AND source_scope=? AND external_id=?",arrayOf(identity.system,identity.sourceScope,identity.externalId)).use {
                if (!it.moveToFirst()) null else Triple(it.getString(0),it.getString(1),it.getString(2))
            }
            if (existing != null) {
                require(existing.second == identity.canonicalId && existing.third == identity.entityKind) { "External identity collision" }
                db.setTransactionSuccessful()
                return existing.first
            }
            db.execSQL("INSERT INTO hub_external_identities(id,canonical_id,entity_kind,system,source_scope,external_id,external_id_normalized,link_method,first_seen_at,last_seen_at,verified_at,lifecycle,metadata_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf(identity.id,identity.canonicalId,identity.entityKind,identity.system,identity.sourceScope,identity.externalId,identity.externalIdNormalized,identity.linkMethod,identity.firstSeenAt,identity.lastSeenAt,identity.verifiedAt,identity.lifecycle,identity.metadataJson,identity.createdAt,identity.updatedAt))
            db.setTransactionSuccessful()
            return identity.id
        } finally { db.endTransaction() }
    }

    companion object {
        /** Legacy in-process requests are translated once; all persisted/shared bindings are canonical. */
        fun normalize(db: SupportSQLiteDatabase, ref: HubEntityRef): HubEntityRef {
            val kind = if (ref.entityKind == "alert" && ref.moduleId != "timer") "alerts/alert" else "${ref.moduleId}/${ref.entityKind}"
            val matches = db.query(
                "SELECT canonical_id FROM hub_entities WHERE entity_kind=? AND (canonical_id=? OR local_key=?)",
                arrayOf(kind,ref.canonicalId,ref.canonicalId),
            ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            require(matches.size == 1) { "Unknown or ambiguous canonical entity: $kind" }
            var current = matches.single()
            val seen = mutableSetOf<String>()
            while (true) {
                check(seen.add(current)) { "Canonical alias cycle" }
                current = db.query("SELECT canonical_id FROM hub_entity_aliases WHERE alias_canonical_id=?",arrayOf(current)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: break
            }
            return ref.copy(canonicalId = current)
        }

        fun eventRef(db: SupportSQLiteDatabase, table: String, rowKey: String): CanonicalEntityRef? {
            val key = com.gernalix.personalhub.core.database.capsules.sync.SyncJournal.keyValues(rowKey)
            if (key.size != 1) return null
            return db.query("SELECT entity_kind,canonical_id FROM hub_entities WHERE local_table=? AND local_key=?",
                arrayOf(table,key.single().toString())).use {
                if (it.moveToFirst()) CanonicalEntityRef(it.getString(0),it.getString(1)) else null
            }
        }

        /** Snapshot storage remains internal; its externally addressable Timer rules have permanent registry IDs. */
        fun synchronizeTimerAlertIdentities(db: SupportSQLiteDatabase, json: String) {
            val rules = JSONObject(json).optJSONArray("timeFenceRules") ?: JSONArray()
            val keys = mutableSetOf<String>()
            val now = System.currentTimeMillis()
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                val key = rule.getLong("id").toString()
                require(keys.add(key)) { "Duplicate Timer alert ID" }
                val existing = db.query("SELECT canonical_id FROM hub_entities WHERE local_table='snapshot.timeFenceRules' AND local_key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else null }
                val lifecycle = if(rule.optBoolean("isDeleted")) "TOMBSTONED" else "ACTIVE"
                if (existing == null) db.execSQL("INSERT INTO hub_entities VALUES(?,'timer/alert','timer','snapshot.timeFenceRules',?,?,?,?)",arrayOf(java.util.UUID.randomUUID().toString(),key,lifecycle,now,now))
                else db.execSQL("UPDATE hub_entities SET lifecycle=?,updated_at=? WHERE canonical_id=? AND lifecycle<>'MERGED'",arrayOf(lifecycle,now,existing))
            }
            val removed = db.query("SELECT canonical_id,local_key FROM hub_entities WHERE local_table='snapshot.timeFenceRules' AND lifecycle='ACTIVE'").use { c -> buildList { while(c.moveToNext()) if(c.getString(1) !in keys) add(c.getString(0)) } }
            removed.forEach { db.execSQL("UPDATE hub_entities SET lifecycle='TOMBSTONED',updated_at=? WHERE canonical_id=?",arrayOf(now,it)) }
        }

        const val MUTATION_TRIGGER = "canonical_mutation_resolution"
        val mutationTriggerSql = "CREATE TRIGGER `$MUTATION_TRIGGER` AFTER INSERT ON hub_entities BEGIN " +
            "UPDATE mutation_events SET canonical_id=NEW.canonical_id WHERE entity_kind=NEW.entity_kind AND canonical_id IS NULL AND entity_id=NEW.local_key; END"

        fun install(context: Context, db: SupportSQLiteDatabase) {
            com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore.install(db)
            db.execSQL("DROP TRIGGER IF EXISTS `$MUTATION_TRIGGER`")
            db.execSQL(mutationTriggerSql)
            // Shared SQL is also exercised by the isolated external migration tests.
            val sql = context.assets.open("canonical-identity-triggers.json").bufferedReader().use { JSONArray(it.readText()) }
            for (i in 0 until sql.length()) db.execSQL(sql.getString(i))
            validate(context, db)
        }

        fun validate(context: Context, db: android.database.sqlite.SQLiteDatabase) = validateQueries(context) { sql, args ->
            db.rawQuery(sql, args).use { it.moveToFirst() }
        }

        fun validate(context: Context, db: SupportSQLiteDatabase) = validateQueries(context) { sql, args ->
            db.query(sql, args).use { it.moveToFirst() }
        }

        private fun validateQueries(context: Context, hasRow: (String, Array<String>) -> Boolean) {
            val queries = context.assets.open("canonical-identity-validation.json").bufferedReader().use { JSONArray(it.readText()) }
            for (i in 0 until queries.length()) {
                val item = queries.getJSONObject(i)
                val args = item.getJSONArray("args")
                check(!hasRow(item.getString("sql"), Array(args.length()) { args.getString(it) })) { item.getString("error") }
            }
        }
    }
}
