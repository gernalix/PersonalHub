package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.HubActivityUndoEngine
import com.gernalix.personalhub.core.database.HubActivityUndoResult
import com.gernalix.personalhub.core.database.HubActivityUndoConflict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealthPatchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun op(table: String, id: String, values: JSONObject): JSONObject =
        JSONObject().put("op","insert").put("table",table).put("key",JSONObject().put("id",id)).put("values",values)
    private fun patch(operations: JSONArray, id: String="health-import-synthetic") =
        JSONObject().put("format_version",1).put("schema_version",PersonalHubDatabase.SCHEMA_VERSION)
            .put("patch_id",id).put("author","chatgpt").put("reason","Synthetic health test")
            .put("operations",operations).toString().toByteArray()

    @Test fun syntheticSampleJournalAiAndEvidenceApplyAtomically() {
        PersonalHubDatabase.closeInstance(); context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
        try {
            val operations=JSONArray()
                .put(op("health_import_batches","batch",JSONObject().put("received_at_ms",1000).put("imported_at_ms",2000).put("source_system","MinSP").put("author","chatgpt")))
                .put(op("health_events","sample-event",JSONObject().put("import_batch_id","batch").put("event_kind","sample").put("occurred_at_ms",1000).put("time_precision","datetime").put("source_system","MinSP").put("created_at_ms",2000).put("updated_at_ms",2000)))
                .put(op("health_samples","sample",JSONObject().put("event_id","sample-event").put("sample_kind","blood")))
                .put(op("health_examinations","exam",JSONObject().put("canonical_name","exam").put("display_name_it","Esame").put("category_it","Sintetica")))
                .put(op("health_measurements","measurement",JSONObject().put("sample_id","sample").put("examination_id","exam").put("numeric_value",8.9).put("received_at_ms",3700000).put("availability_basis","chat_received_proxy")))
                .put(op("health_ai_snapshots","measurement-ai",JSONObject().put("subject_kind","measurement").put("subject_id","measurement").put("as_of_ms",1000).put("generated_at_ms",2000).put("generated_by","ChatGPT").put("assessment_version",1).put("comment_it","Synthetic trend")))
                .put(op("health_ai_snapshots","sample-ai",JSONObject().put("subject_kind","sample").put("subject_id","sample").put("as_of_ms",1000).put("generated_at_ms",2000).put("generated_by","ChatGPT").put("assessment_version",1).put("comment_it","Synthetic panel")))
                .put(op("health_events","journal-event",JSONObject().put("import_batch_id","batch").put("event_kind","journal").put("occurred_at_ms",1000).put("time_precision","datetime").put("source_system","MinSP").put("created_at_ms",2000).put("updated_at_ms",2000)))
                .put(op("health_journal_entries","journal",JSONObject().put("event_id","journal-event").put("text_it","Nota sintetica").put("original_text_da","Syntetisk note")))
                .put(op("health_ai_snapshots","journal-ai",JSONObject().put("subject_kind","journal").put("subject_id","journal").put("as_of_ms",1000).put("generated_at_ms",2000).put("generated_by","ChatGPT").put("assessment_version",1).put("stance","insufficient_evidence").put("comment_it","Synthetic review")))
            // Composite evidence key is a first-class patch operation.
            operations.put(JSONObject().put("op","insert").put("table","health_ai_evidence")
                .put("key",JSONObject().put("snapshot_id","journal-ai").put("evidence_kind","measurement").put("evidence_id","measurement"))
                .put("values",JSONObject().put("relevance_it","Synthetic context").put("position",0)))
            GitPatchEngine.apply(context,patch(operations))
            val db=PersonalHubDatabase.get(context).openHelper.writableDatabase
            assertEquals(1L,db.query("SELECT COUNT(*) FROM health_import_batches").use { it.moveToFirst(); it.getLong(0) })
            assertEquals(10L,db.query("SELECT COUNT(*) FROM hub_activity_log WHERE module_id='salute' AND group_id='health-import-synthetic'").use { it.moveToFirst(); it.getLong(0) })
            assertEquals(3L,db.query("SELECT COUNT(*) FROM health_ai_snapshots").use { it.moveToFirst(); it.getLong(0) })
            val protectedActivity=db.query("SELECT id FROM hub_activity_log WHERE source_table='health_measurements' AND entity_id='measurement' ORDER BY occurred_at DESC LIMIT 1").use { it.moveToFirst();it.getString(0) }
            val protectedUndo=runBlocking(Dispatchers.IO) { HubActivityUndoEngine.undo(PersonalHubDatabase.get(context),protectedActivity) }
            assertEquals(HubActivityUndoResult.Conflict(HubActivityUndoConflict.REFERENCED),protectedUndo)
            db.execSQL("INSERT INTO health_measurements(id,sample_id,examination_id,numeric_value,availability_basis) VALUES('standalone','sample','exam',1.0,'unknown')")
            val standaloneActivity=db.query("SELECT id FROM hub_activity_log WHERE source_table='health_measurements' AND entity_id='standalone' ORDER BY occurred_at DESC LIMIT 1").use { it.moveToFirst();it.getString(0) }
            assertTrue(runBlocking(Dispatchers.IO) { HubActivityUndoEngine.undo(PersonalHubDatabase.get(context),standaloneActivity) } is HubActivityUndoResult.Success)
            assertEquals(0L,db.query("SELECT COUNT(*) FROM health_measurements WHERE id='standalone'").use { it.moveToFirst();it.getLong(0) })

            assertEquals("Synthetic panel",db.query("SELECT ai_comment FROM v_health_samples WHERE sample_id='sample'").use { it.moveToFirst(); it.getString(0) })
            assertFalse(db.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
            val broken=JSONArray().put(op("health_measurements","bad",JSONObject().put("sample_id","missing").put("examination_id","exam").put("numeric_value",1).put("availability_basis","unknown")))
            assertThrows(Exception::class.java) { GitPatchEngine.apply(context,patch(broken,"health-import-broken")) }
            assertEquals(0L,db.query("SELECT COUNT(*) FROM health_measurements WHERE id='bad'").use { it.moveToFirst(); it.getLong(0) })
            val missingAi=JSONArray()
                .put(op("health_events","orphan-event",JSONObject().put("import_batch_id","batch").put("event_kind","sample").put("occurred_at_ms",1000).put("time_precision","datetime").put("source_system","MinSP").put("created_at_ms",2000).put("updated_at_ms",2000)))
                .put(op("health_samples","orphan-sample",JSONObject().put("event_id","orphan-event").put("sample_kind","blood")))
            assertThrows(IllegalArgumentException::class.java) { GitPatchEngine.apply(context,patch(missingAi,"health-import-missing-ai")) }
            assertEquals(0L,db.query("SELECT COUNT(*) FROM health_samples WHERE id='orphan-sample'").use { it.moveToFirst(); it.getLong(0) })
        } finally { PersonalHubDatabase.closeInstance(); context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME) }
    }
}
