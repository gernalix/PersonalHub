package com.gernalix.personalhub.salute

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataTracking
import java.io.File
import java.security.MessageDigest

/** One-time, local import of the former read-only cache. It never downloads or serves data. */
internal object LegacyHealthImport {
    fun importCached(context: Context, database: PersonalHubDatabase) {
        val dir=File(context.noBackupFilesDir,"salute")
        val source=File(dir,"salute.db")
        if (!source.isFile) return
        val digest=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        val batch="legacy-salute-$digest"
        val target=database.openHelper.writableDatabase
        val imported=target.query("SELECT 1 FROM health_import_batches WHERE id=?",arrayOf(batch)).use { it.moveToFirst() }
        if (!imported) {
            val legacy=SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READONLY)
            try {
                require(legacy.rawQuery("PRAGMA quick_check",null).use { it.moveToFirst() && it.getString(0)=="ok" })
                require(!legacy.rawQuery("PRAGMA foreign_key_check",null).use { it.moveToFirst() })
                require(legacy.rawQuery("SELECT value FROM metadata WHERE key='consumer_contract_version'",null).use { it.moveToFirst() && it.getString(0)=="2" })
                target.beginTransaction()
                try {
                    GitDataTracking.setEditContext(target, "legacy_import", "import", "Legacy Salute cache migration", "health-import-$batch")
                    insert(target,"health_import_batches","id","received_at_ms","imported_at_ms","source_system","source_hash","author",values=arrayOf(batch,System.currentTimeMillis(),System.currentTimeMillis(),"legacy salute.db",digest,"legacy_import"))
                    importExaminations(legacy,target)
                    importSamples(legacy,target,batch)
                    importMeasurements(legacy,target,batch)
                    importJournal(legacy,target,batch)
                    preserveSourceMetadata(legacy,target,batch)
                    require(target.query("PRAGMA quick_check").use { it.moveToFirst() && it.getString(0)=="ok" })
                    require(!target.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
                    target.setTransactionSuccessful()
                } finally { GitDataTracking.clearEditContext(target); target.endTransaction() }
            } finally { legacy.close() }
        }
        // The former cache has no authority after a complete canonical transaction.
        source.delete()
        File(dir,"salute.db.bak").delete()
        File(dir,"salute.db.staging").delete()
    }

    private fun insert(db: SupportSQLiteDatabase, table: String, vararg columns: String, values: Array<Any?>) {
        val names=columns.joinToString(",") { "`$it`" }
        db.execSQL("INSERT INTO `$table` ($names) VALUES (${columns.joinToString(",") { "?" }})",values)
    }
    private fun Cursor.str(name: String): String? = getColumnIndex(name).takeIf { it>=0 && !isNull(it) }?.let(::getString)
    private fun Cursor.long(name: String): Long? = getColumnIndex(name).takeIf { it>=0 && !isNull(it) }?.let(::getLong)
    private fun Cursor.double(name: String): Double? = getColumnIndex(name).takeIf { it>=0 && !isNull(it) }?.let(::getDouble)
    private fun id(kind: String, value: Long) = "legacy-$kind-$value"
    private fun metadata(db: SupportSQLiteDatabase, kind: String, owner: String, key: String, value: Any?) {
        if (value!=null) insert(db,"health_source_metadata","owner_kind","owner_id","key","value",values=arrayOf(kind,owner,key,value.toString()))
    }
    private fun importExaminations(old: SQLiteDatabase, db: SupportSQLiteDatabase) {
        old.rawQuery("SELECT a.id,a.name,a.default_unit,c.name AS category_name FROM analyses a JOIN examinations e ON e.id=a.examination_id JOIN categories c ON c.id=e.category_id",null).use { c ->
            while(c.moveToNext()) {
                val key=id("analysis",c.getLong(0))
                insert(db,"health_examinations","id","canonical_name","display_name_it","category_it","default_unit",values=arrayOf(key,key,c.getString(1),c.getString(3),if(c.isNull(2)) null else c.getString(2)))
            }
        }
    }
    private fun importSamples(old: SQLiteDatabase, db: SupportSQLiteDatabase, batch: String) {
        old.rawQuery("SELECT s.*,m.name AS material_name FROM samples s LEFT JOIN materials m ON m.id=s.material_id",null).use { c ->
            while(c.moveToNext()) {
                val oldId=c.long("id")!!; val sid=id("sample",oldId); val eid=id("event-sample",oldId)
                val material=c.str("material_name")
                insert(db,"health_events","id","import_batch_id","event_kind","occurred_at_ms","utc_offset_min","time_precision","title_it","source_system","created_at_ms","updated_at_ms",values=arrayOf(eid,batch,"sample",c.long("collection_epoch_ms"),c.long("utc_offset_min"),c.str("time_precision")?:"unknown","Campione",c.str("source")?:"legacy salute.db",System.currentTimeMillis(),System.currentTimeMillis()))
                insert(db,"health_samples","id","event_id","sample_kind","material_it",values=arrayOf(sid,eid,when { material?.contains("blod",true)==true || material?.contains("sang",true)==true -> "blood"; material?.contains("urin",true)==true -> "urine"; else -> "other" },material))
                metadata(db,"sample",sid,"legacy.notes",c.str("notes"))
                metadata(db,"sample",sid,"legacy.material_id",c.long("material_id"))
            }
        }
        // Results without an explicit specimen stay separate; date-only grouping is forbidden.
        old.rawQuery("SELECT t.* FROM test_events t WHERE t.sample_id IS NULL",null).use { c ->
            while(c.moveToNext()) {
                val oldId=c.long("id")!!; val sid=id("sample-test",oldId); val eid=id("event-test",oldId)
                insert(db,"health_events","id","import_batch_id","event_kind","occurred_at_ms","utc_offset_min","time_precision","title_it","source_system","created_at_ms","updated_at_ms",values=arrayOf(eid,batch,"sample",c.long("event_epoch_ms"),c.long("utc_offset_min"),c.str("event_time_precision")?:"unknown","Campione",c.str("source")?:"legacy salute.db",System.currentTimeMillis(),System.currentTimeMillis()))
                insert(db,"health_samples","id","event_id","sample_kind",values=arrayOf(sid,eid,"other"))
            }
        }
    }
    private fun importMeasurements(old: SQLiteDatabase, db: SupportSQLiteDatabase, batch: String) {
        old.rawQuery("SELECT m.*,t.sample_id,t.id AS legacy_test_id,t.location,t.overall_result,t.status AS test_status,t.notes AS test_notes,t.source AS test_source FROM measurements m JOIN test_events t ON t.id=m.test_event_id",null).use { c ->
            while(c.moveToNext()) {
                val mid=id("measurement",c.long("id")!!)
                val sid=c.long("sample_id")?.let { id("sample",it) } ?: id("sample-test",c.long("legacy_test_id")!!)
                val basis=when { c.long("received_epoch_ms")==null -> "unknown"; c.str("received_source")?.contains("ChatGPT",true)==true -> "chat_received_proxy"; else -> "minsp_notification" }
                val text=c.str("text_value") ?: if(c.double("numeric_value")==null && c.str("interpretation")==null) c.str("status") else null
                insert(db,"health_measurements","id","sample_id","examination_id","numeric_value","text_value","unit","interpretation_it","flag","received_at_ms","availability_basis",values=arrayOf(mid,sid,id("analysis",c.long("analysis_id")!!),c.double("numeric_value"),text,c.str("unit"),c.str("interpretation"),c.str("flag"),c.long("received_epoch_ms"),basis))
                for (key in arrayOf("outcome_id","status","received_utc_offset_min","received_source","legacy_test_id","location","overall_result","test_status","test_notes","test_source")) metadata(db,"measurement",mid,"legacy.$key",c.str(key))
            }
        }
        old.rawQuery("SELECT * FROM sample_comments",null).use { c ->
            while(c.moveToNext()) {
                val sid=id("sample",c.long("sample_id")!!)
                val prefix="legacy.comment.${c.long("id")}."
                for (key in arrayOf("comment_type","author_label","comment_it","source","recorded_epoch_ms")) metadata(db,"sample",sid,prefix+key,c.str(key))
            }
        }
    }
    private fun importJournal(old: SQLiteDatabase, db: SupportSQLiteDatabase, batch: String) {
        old.rawQuery("SELECT * FROM medical_journal_entries",null).use { c ->
            while(c.moveToNext()) {
                val key=c.long("id")!!; val jid=id("journal",key); val eid=id("event-journal",key)
                insert(db,"health_events","id","import_batch_id","event_kind","occurred_at_ms","utc_offset_min","time_precision","title_it","source_system","source_ref","created_at_ms","updated_at_ms",values=arrayOf(eid,batch,"journal",c.long("encounter_epoch_ms"),c.long("encounter_utc_offset_min"),c.str("encounter_time_precision")?:"unknown",c.str("title_it"),c.str("source_system")?:"legacy salute.db",c.str("source_ref"),System.currentTimeMillis(),System.currentTimeMillis()))
                insert(db,"health_journal_entries","id","event_id","authored_at_ms","authored_utc_offset_min","authored_time_precision","encounter_type_it","department_it","clinician_role_it","note_type_it","title_it","text_it","original_text_da","source_ref",values=arrayOf(jid,eid,c.long("authored_epoch_ms"),c.long("authored_utc_offset_min"),c.str("authored_time_precision"),c.str("encounter_type_it"),c.str("department_it"),c.str("clinician_role_it"),c.str("note_type_it"),c.str("title_it"),c.str("text_it")?:"",c.str("original_text_da")?:"",c.str("source_ref")))
                for (field in arrayOf("facility_it","clinician_name","notes")) metadata(db,"journal",jid,"legacy.$field",c.str(field))
            }
        }
        old.rawQuery("SELECT * FROM medical_journal_snapshots",null).use { c ->
            while(c.moveToNext()) insert(db,"health_ai_snapshots","id","subject_kind","subject_id","as_of_ms","generated_at_ms","generated_by","model","assessment_version","comment_it","uncertainty_it",values=arrayOf(id("journal-ai",c.long("id")!!),"journal",id("journal",c.long("entry_id")!!),c.long("as_of_epoch_ms")!!,c.long("generated_epoch_ms")!!,c.str("generated_by")?:"ChatGPT",c.str("model"),c.long("assessment_version")?:1L,c.str("snapshot_it")?:"",c.str("uncertainties_it")))
        }
        for((table,kind,column) in listOf(Triple("medical_journal_snapshot_entries","journal","related_entry_id"),Triple("medical_journal_snapshot_measurements","measurement","measurement_id"))) {
            old.rawQuery("SELECT * FROM $table",null).use { c -> while(c.moveToNext()) insert(db,"health_ai_evidence","snapshot_id","evidence_kind","evidence_id","relevance_it","position",values=arrayOf(id("journal-ai",c.long("snapshot_id")!!),kind,id(kind,c.long(column)!!),c.str("relevance_it")?:"",0)) }
        }
        old.rawQuery("SELECT * FROM medical_journal_metadata",null).use { c -> while(c.moveToNext()) metadata(db,"journal",id("journal",c.long("entry_id")!!),c.str("key")?:"legacy.unknown",c.str("value")) }
    }
    private fun preserveSourceMetadata(old: SQLiteDatabase, db: SupportSQLiteDatabase, batch: String) {
        // Legacy dictionaries and test-event rows have no one-to-one first-class target.
        // Keep their original scalar fields as provenance attached to the canonical import.
        for ((table,key) in listOf("categories" to "id", "examinations" to "id",
            "materials" to "id", "result_outcomes" to "id", "test_events" to "id",
            "metadata" to "key")) {
            old.rawQuery("SELECT * FROM $table",null).use { c ->
                while(c.moveToNext()) {
                    val owner=c.str(key) ?: continue
                    for(index in 0 until c.columnCount) {
                        if(c.isNull(index)) continue
                        val name=c.getColumnName(index)
                        metadata(db,"import",batch,"legacy.$table.$owner.$name",c.getString(index))
                    }
                }
            }
        }
    }

}
