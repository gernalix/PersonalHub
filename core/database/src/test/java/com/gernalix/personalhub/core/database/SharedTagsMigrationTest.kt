package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedTagsMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun versionEighteenConsolidatesModuleTagsAndSplitsEveryTimerReferenceShape() {
        val name = "shared-tags-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        createVersion18(file)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.execSQL("PRAGMA foreign_keys=ON")
            old.execSQL("INSERT INTO hub_generation(id,generation) VALUES(1,18)")
            old.execSQL("INSERT INTO contacts(id,public_id,created_at,updated_at) VALUES(1,'person-a',10,20)")
            old.execSQL("INSERT INTO tags(id,name,normalized_name,created_at) VALUES(1,'Friend','friend',10)")
            old.execSQL("INSERT INTO contact_tags(contact_id,tag_id,added_at) VALUES(1,1,21)")
            old.execSQL("INSERT INTO places(uuid,nickname,created_at,updated_at,archived) VALUES('place-p','P',10,20,0)")
            old.execSQL("INSERT INTO place_tags(id,name,normalized_name,created_at,updated_at) VALUES(1,'Quiet','quiet',10,20)")
            old.execSQL("INSERT INTO place_tag_cross_ref(place_uuid,tag_id) VALUES('place-p',1)")
            old.execSQL("INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES('a','A','EUR','0',0,1)")
            old.execSQL("INSERT INTO finance_transactions(id,accountId,uuid,amount,currency,placeId,fromReceipt,notes,occurredAt,createdAt,updatedAt,personId,category) VALUES(1,'a','tx-1','1','EUR','place-p',0,'',100,100,110,1,'Restaurant')")
            old.execSQL("INSERT INTO finance_recurrences(id,title,amount,currency,accountId,personId,chain,placeId,notes,lastBusinessDay,startDate,enabled,createdAt,updatedAt,kind,category) VALUES('rec-1','R','1','EUR','a',1,'','place-p','',0,'2026-01-01',1,100,120,'EXPENSE','Bills')")
            old.execSQL("INSERT INTO finance_attachments(id,transactionId,kind,uri,title,createdAt) VALUES('attachment-1',1,'photo','content://photo','Receipt',111)")
            old.execSQL("INSERT INTO finance_tags(id,name) VALUES(1,'Holiday')")
            old.execSQL("INSERT INTO finance_transaction_tags(transactionId,tagId) VALUES(1,1)")
            old.execSQL("INSERT INTO finance_recurrence_tags(recurrenceId,tagId) VALUES('rec-1',1)")
            old.execSQL("INSERT INTO sessions(id,title,start_ms,created_at_ms,updated_at_ms) VALUES(101,'S',1,1,1)")
            old.execSQL("INSERT INTO session_tags(session_id,tag_id) VALUES(101,1)")
            old.execSQL("INSERT INTO quick_event_templates(id,title,created_at_ms,updated_at_ms) VALUES(201,'E',1,1)")
            old.execSQL("INSERT INTO quick_event_template_tags(template_id,tag_id) VALUES(201,2)")
            old.execSQL("INSERT INTO snapshot(id,json,saved_at_ms) VALUES(1,?,100)", arrayOf(timerSnapshot().toString()))
            old.version = 18
        }

        val owner = PersonalHubDatabase.openTemporary(context, name)
        try {
            val db = owner.openHelper.writableDatabase
            assertEquals(20, db.version)
            assertEquals(1L, scalar(db, "SELECT count(*) FROM hub_tag_assignments a JOIN hub_entity_bindings b ON b.id=a.target_binding_id WHERE a.tag_id='people:1' AND b.canonical_id='person-a'"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM hub_tag_assignments a JOIN hub_entity_bindings b ON b.id=a.target_binding_id WHERE a.tag_id='places:1' AND b.canonical_id='place-p'"))
            assertEquals(2L, scalar(db, "SELECT count(*) FROM hub_tag_assignments WHERE tag_id='soldi:1'"))
            assertEquals("Restaurant", text(db, "SELECT t.name FROM hub_tags t JOIN hub_tag_assignments a ON a.tag_id=t.id JOIN hub_entity_bindings b ON b.id=a.target_binding_id WHERE t.namespace='soldi.category' AND t.kind='CATEGORY' AND b.canonical_id='tx-1'"))
            assertEquals("Bills", text(db, "SELECT t.name FROM hub_tags t JOIN hub_tag_assignments a ON a.tag_id=t.id JOIN hub_entity_bindings b ON b.id=a.target_binding_id WHERE t.namespace='soldi.category' AND t.kind='CATEGORY' AND b.canonical_id='rec-1'"))
            assertEquals(0L, scalar(db, "SELECT count(*) FROM pragma_table_info('finance_transactions') WHERE name='category'"))
            assertEquals(0L, scalar(db, "SELECT count(*) FROM pragma_table_info('finance_recurrences') WHERE name='category'"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM finance_attachments WHERE id='attachment-1' AND transactionId=1"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM hub_context_members m JOIN hub_entity_bindings b ON b.id=m.entity_id WHERE m.context_id='finance-context:tx-1' AND b.canonical_id='person-a' AND m.role='participant'"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM hub_context_members m JOIN hub_entity_bindings b ON b.id=m.entity_id WHERE m.context_id='finance-context:tx-1' AND b.canonical_id='place-p' AND m.role='merchant_place'"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM finance_transactions WHERE id=1 AND personId IS NULL AND placeId IS NULL"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM hub_context_members m JOIN hub_entity_bindings b ON b.id=m.entity_id WHERE m.context_id='finance-recurrence-context:rec-1' AND b.canonical_id='person-a' AND m.role='participant'"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM hub_context_members m JOIN hub_entity_bindings b ON b.id=m.entity_id WHERE m.context_id='finance-recurrence-context:rec-1' AND b.canonical_id='place-p' AND m.role='merchant_place'"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM finance_recurrences WHERE id='rec-1' AND personId IS NULL AND placeId IS NULL"))
            runBlocking { owner.financeDao().transactionViewsByUuid(listOf("tx-1")) }.single().let { view ->
                assertEquals("P", view.place)
                assertEquals(null, view.person)
            }
            assertNamespaces(db, 1, setOf("timer.now"))
            assertNamespaces(db, 2, setOf("timer.events"))
            assertNamespaces(db, 3, setOf("timer.since_when"))
            assertNamespaces(db, 4, setOf("timer.now", "timer.events"))
            assertNamespaces(db, 5, setOf("timer.now", "timer.events", "timer.since_when"))
            assertNamespaces(db, 6, setOf("timer.now"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM hub_tag_parents WHERE child_tag_id='timer.now:4' AND parent_tag_id='timer.now:1'"))
            assertEquals(1L, scalar(db, "SELECT count(*) FROM hub_tag_assignments a JOIN hub_entity_bindings b ON b.id=a.target_binding_id WHERE a.tag_id='timer.since_when:5' AND b.entity_kind='life_period' AND b.canonical_id='301'"))
            assertEquals("ok", text(db, "PRAGMA quick_check"))
            assertFalse(db.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        } finally {
            owner.close()
            context.deleteDatabase(name)
        }
    }

    private fun timerSnapshot() = JSONObject()
        .put("tags", JSONArray((1L..6L).map { id -> JSONObject().put("id", id).put("name", "Same $id").put("isArchived", false) }))
        .put("tasks", JSONArray().put(JSONObject().put("id", 1).put("tagIds", JSONArray(listOf(1, 4, 5)))))
        .put("quickEventTemplates", JSONArray().put(JSONObject().put("id", 201).put("tagIds", JSONArray(listOf(2, 4, 5)))))
        .put("lifePeriods", JSONArray().put(JSONObject().put("id", 301).put("tagIds", JSONArray(listOf(3, 5)))))
        .put("tagParents", JSONArray().put(JSONObject().put("childId", 4).put("parentId", 1)))

    private fun assertNamespaces(db: androidx.sqlite.db.SupportSQLiteDatabase, legacyId: Long, expected: Set<String>) {
        val actual = db.query("SELECT namespace FROM hub_tags WHERE id LIKE ? ORDER BY namespace", arrayOf("timer.%:$legacyId")).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(expected, actual)
    }

    private fun createVersion18(file: java.io.File) {
        val entities = JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/18.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (position in 0 until indices.length()) db.execSQL(indices.getJSONObject(position).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            db.version = 18
        }
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) = db.query(sql).use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun text(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) = db.query(sql).use { assertTrue(it.moveToFirst()); it.getString(0) }
}
