package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventDraft
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MutationEventStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun capturePersistsSemanticChangesAndGroupsOneDatabaseTransaction() = withDatabase { database ->
        val db = database.openHelper.writableDatabase
        assertFalse("mutation_events" in SyncJournal.tables(db))
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (id in listOf(first, second)) {
                db.execSQL(
                    """INSERT INTO places(uuid,nickname,address,lat,lon,radius_m,notes,source_app,created_at,updated_at,archived,first_check_in_at_place)
                        VALUES(?, 'Home', NULL, NULL, NULL, NULL, NULL, 'test', ?, ?, 0, NULL)""".trimIndent(),
                    arrayOf<Any?>(id, now, now),
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        db.execSQL("UPDATE places SET nickname='Casa', updated_at=? WHERE uuid=?", arrayOf<Any?>(now + 1, first))
        db.execSQL("UPDATE places SET updated_at=? WHERE uuid=?", arrayOf<Any?>(now + 2, first))

        val events = MutationEventStore.recent(db)
        assertEquals(3, events.size)
        val creates = events.filter { it.eventType == "places.place.created" }
        assertEquals(2, creates.size)
        assertEquals(creates[0].transactionId, creates[1].transactionId)
        assertEquals(setOf(1, 2), creates.map { it.sequence }.toSet())
        val update = events.single { it.eventType == "places.place.updated" }
        assertEquals(setOf("nickname"), JSONObject(update.beforeJson!!).keys().asSequence().toSet())
        assertEquals("Home", JSONObject(update.beforeJson).getString("nickname"))
        assertEquals("Casa", JSONObject(update.afterJson!!).getString("nickname"))
        assertEquals("Casa", JSONObject(update.contextJson).getString("name"))
        assertEquals("user", update.actorType)
        assertTrue(update.transactionId.isNotBlank())
        assertFalse(events.any { it.contextJson.contains("updated_at") })
    }

    @Test
    fun explicitSemanticAppendUsesSequenceAndPreservesDeletedName() = withDatabase { database ->
        val db = database.openHelper.writableDatabase
        val transaction = MutationEventStore.newTransactionId()
        val created = MutationEventStore.append(db, MutationEventDraft(
            transactionId = transaction,
            module = "tags", eventType = "tags.tag.created", entityType = "tag", entityId = "tag-1",
            beforeJson = null, afterJson = """{"name":"Work"}""",
        ))
        val deleted = MutationEventStore.append(db, MutationEventDraft(
            transactionId = transaction,
            module = "tags", eventType = "tags.tag.deleted", entityType = "tag", entityId = "tag-1",
            beforeJson = """{"name":"Work"}""", afterJson = null,
        ))
        assertEquals(1, created.sequence)
        assertEquals(2, deleted.sequence)
        assertNull(deleted.afterJson)
        assertEquals("Work", JSONObject(deleted.beforeJson!!).getString("name"))
    }

    @Test
    fun workflowyResourceCapturesOneLinkFactWithNameSnapshot() = withDatabase { database ->
        val db = database.openHelper.writableDatabase
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.execSQL(
            """INSERT INTO hub_resources(id,kind,title,value,persistedPermission,createdAt,updatedAt)
                VALUES(?, 'url', 'Workflowy · Study', 'https://workflowy.com/#/abc123', 0, ?, ?)""".trimIndent(),
            arrayOf<Any?>(id, now, now),
        )
        val event = MutationEventStore.recent(db).single { it.eventType == "workflowy.link.created" }
        assertEquals("Workflowy · Study", JSONObject(event.contextJson).getString("name"))
        db.execSQL("DELETE FROM hub_resources WHERE id=?", arrayOf(id))
        val deleted = MutationEventStore.recent(db).single { it.eventType == "workflowy.link.deleted" }
        assertEquals("Workflowy · Study", JSONObject(deleted.beforeJson!!).getString("title"))
        assertNull(deleted.afterJson)
    }

    private fun withDatabase(block: (PersonalHubDatabase) -> Unit) {
        val name = "mutation-events-${UUID.randomUUID()}.db"
        val database = PersonalHubDatabase.openTemporary(context, name)
        try { block(database) } finally { database.close(); context.deleteDatabase(name) }
    }
}
