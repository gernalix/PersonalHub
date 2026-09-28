package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventDraft
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationUndoResolver
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationUndoTarget
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataTracking
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
        assertTrue("mutation_events" in SyncJournal.tables(db))
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
        assertEquals(events.minOf { it.occurredAt }, MutationEventStore.oldestOccurredAt(db))
        val creates = events.filter { it.eventType == "places.place.created" }
        assertEquals(2, creates.size)
        assertEquals(creates[0].transactionId, creates[1].transactionId)
        assertEquals(setOf(1, 2), creates.map { it.sequence }.toSet())
        val update = events.single { it.eventType == "places.place.updated" }
        val backingGroup = db.query(
            "SELECT group_id FROM hub_activity_log WHERE action='place_updated' AND entity_id=? LIMIT 1",
            arrayOf(first),
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
        assertEquals(update.transactionId, backingGroup)
        assertTrue(MutationUndoResolver.resolve(db, update.transactionId, gitEnabled = false) is MutationUndoTarget.Activity)
        assertNull(MutationUndoResolver.resolve(db, creates.first().transactionId, gitEnabled = false))
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
        assertEquals(deleted, MutationEventStore.byId(db, deleted.eventId))
        assertNull(deleted.afterJson)
        assertEquals("Work", JSONObject(deleted.beforeJson!!).getString("name"))
    }

    @Test
    fun timerTagRelationKeepsSessionAndTagNameSnapshots() = withDatabase { database ->
        val db = database.openHelper.writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL("INSERT INTO sessions(id,title,start_ms,end_ms,expected_end_ms,created_at_ms,updated_at_ms,deleted_at_ms) VALUES(11,'Walk',?,NULL,NULL,?,?,NULL)",
            arrayOf<Any?>(now, now, now))
        db.execSQL("INSERT INTO tags(id,name,normalized_name,created_at) VALUES(21,'Exercise','exercise',?)", arrayOf<Any?>(now))
        db.execSQL("INSERT INTO session_tags(session_id,tag_id) VALUES(11,21)")
        db.execSQL("UPDATE tags SET name='Fitness' WHERE id=21")
        db.execSQL("DELETE FROM session_tags WHERE session_id=11 AND tag_id=21")
        val events = MutationEventStore.recent(db).filter { it.eventType.startsWith("timer.session.tag_") }
        assertEquals(2, events.size)
        val added = events.single { it.eventType == "timer.session.tag_added" }
        val removed = events.single { it.eventType == "timer.session.tag_removed" }
        assertEquals("Walk", JSONObject(added.contextJson).getString("name"))
        assertEquals("Exercise", JSONObject(added.afterJson!!).getJSONObject("tag").getString("name"))
        assertEquals("Fitness", JSONObject(removed.beforeJson!!).getJSONObject("tag").getString("name"))
        assertNull(added.beforeJson)
        assertNull(removed.afterJson)
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
        val event = MutationEventStore.recent(db).single { it.eventType == "hub.resource.created" }
        assertEquals("Workflowy · Study", JSONObject(event.contextJson).getString("name"))
        val anchorBinding = UUID.randomUUID().toString()
        val resourceBinding = UUID.randomUUID().toString()
        val linkContext = UUID.randomUUID().toString()
        db.execSQL("INSERT INTO hub_entity_bindings(id,module_id,entity_kind,canonical_id,lifecycle,updated_at) VALUES(?, 'places', 'place', 'place-1', 'active', ?)",
            arrayOf<Any?>(anchorBinding, now))
        db.execSQL("INSERT INTO hub_entity_bindings(id,module_id,entity_kind,canonical_id,lifecycle,updated_at) VALUES(?, 'hub', 'resource', ?, 'active', ?)",
            arrayOf<Any?>(resourceBinding, id, now))
        db.execSQL("INSERT INTO hub_contexts(id,context_type_id,title,created_at,updated_at) VALUES(?,NULL,'Workflowy',?,?)",
            arrayOf<Any?>(linkContext, now, now))
        db.execSQL("INSERT INTO hub_context_members(context_id,entity_id,role,position) VALUES(?,?,'',0)",
            arrayOf<Any?>(linkContext, anchorBinding))
        db.execSQL("INSERT INTO hub_context_members(context_id,entity_id,role,position) VALUES(?,?,'',1)",
            arrayOf<Any?>(linkContext, resourceBinding))
        val association = MutationEventStore.recent(db).single { it.eventType == "workflowy.link.assigned" }
        assertEquals("places", association.module)
        assertEquals("Workflowy · Study", JSONObject(association.contextJson).getString("name"))
        db.execSQL("DELETE FROM hub_contexts WHERE id=?", arrayOf(linkContext))
        val unlinked = MutationEventStore.recent(db).single { it.eventType == "workflowy.link.unlinked" }
        assertEquals("places", unlinked.module)
        db.execSQL("DELETE FROM hub_resources WHERE id=?", arrayOf(id))
        val deleted = MutationEventStore.recent(db).single { it.eventType == "hub.resource.deleted" }
        assertEquals("Workflowy · Study", JSONObject(deleted.beforeJson!!).getString("title"))
        assertNull(deleted.afterJson)
    }

    @Test
    fun peopleDeletionKeepsNameAfterCanonicalContactIsDeleted() = withDatabase { database ->
        val db = database.openHelper.writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL("INSERT INTO contacts(public_id,created_at,updated_at,deleted_at,archived_at) VALUES('semantic-person',?,?,NULL,NULL)",
            arrayOf<Any?>(now, now))
        val contactId = db.query("SELECT id FROM contacts WHERE public_id='semantic-person'").use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        db.execSQL(
            """INSERT INTO contact_fields(contact_id,field_type,value,normalized_value,sort_value,added_at,edited_at,position,is_primary,source,latitude,longitude,country_code,description)
                VALUES(?,'name','Mario Rossi','mario rossi','mario rossi',?,NULL,0,0,'manual',NULL,NULL,NULL,NULL)""".trimIndent(),
            arrayOf<Any?>(contactId, now),
        )
        db.execSQL(
            """INSERT INTO contact_events(contact_id,entity_type,action_type,event_type,field_type,old_value,new_value,occurred_at,metadata_json)
                VALUES(?,'Contact','Deleted','contact_delete',NULL,NULL,NULL,?,NULL)""".trimIndent(),
            arrayOf<Any?>(contactId, now),
        )
        db.execSQL("UPDATE contacts SET deleted_at=? WHERE id=?", arrayOf<Any?>(now, contactId))
        val deleted = MutationEventStore.recent(db).single { it.eventType == "people.person.deleted" }
        assertEquals("Mario Rossi", JSONObject(deleted.beforeJson!!).getString("name"))
        assertNull(deleted.afterJson)
    }

    @Test
    fun explicitImportContextSurvivesStatementWrapperAndStaysOutOfHumanHistory() = withDatabase { database ->
        val db = database.openHelper.writableDatabase
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        GitDataTracking.setEditContext(db, author = "import", source = "import", groupId = "import-batch-1")
        try {
            db.execSQL(
                """INSERT INTO places(uuid,nickname,address,lat,lon,radius_m,notes,source_app,created_at,updated_at,archived,first_check_in_at_place)
                    VALUES(?, 'Imported', NULL, NULL, NULL, NULL, NULL, 'test', ?, ?, 0, NULL)""".trimIndent(),
                arrayOf<Any?>(id, now, now),
            )
        } finally { GitDataTracking.clearEditContext(db) }
        val event = MutationEventStore.recent(db).single { it.entityId == id }
        assertEquals("import-batch-1", event.transactionId)
        assertEquals("import", event.actorType)
        assertEquals("import", event.actorSource)
    }

    @Test
    fun reopeningDatabaseKeepsEventsAndDoesNotDuplicateCapture() {
        val name = "mutation-reopen-${UUID.randomUUID()}.db"
        val now = System.currentTimeMillis()
        fun insert(database: PersonalHubDatabase, nickname: String) {
            database.openHelper.writableDatabase.execSQL(
                """INSERT INTO places(uuid,nickname,address,lat,lon,radius_m,notes,source_app,created_at,updated_at,archived,first_check_in_at_place)
                    VALUES(?,?,NULL,NULL,NULL,NULL,NULL,'test',?,?,0,NULL)""".trimIndent(),
                arrayOf<Any?>(UUID.randomUUID().toString(), nickname, now, now),
            )
        }
        try {
            val first = PersonalHubDatabase.openTemporary(context, name)
            try { insert(first, "First") } finally { first.close() }
            val second = PersonalHubDatabase.openTemporary(context, name)
            try {
                insert(second, "Second")
                val events = MutationEventStore.recent(second.openHelper.readableDatabase)
                assertEquals(2, events.count { it.eventType == "places.place.created" })
                assertEquals(setOf("First", "Second"), events.map { JSONObject(it.contextJson).optString("name") }.toSet())
            } finally { second.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun withDatabase(block: (PersonalHubDatabase) -> Unit) {
        val name = "mutation-events-${UUID.randomUUID()}.db"
        val database = PersonalHubDatabase.openTemporary(context, name)
        try { block(database) } finally { database.close(); context.deleteDatabase(name) }
    }
}
