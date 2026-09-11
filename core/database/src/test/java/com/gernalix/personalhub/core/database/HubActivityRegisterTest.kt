package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubActivityRegisterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun placeMutationCreatesReadableActivityAndUpdateUndoAppendsCompensation() = runBlocking {
        withDatabase { database ->
            val db = database.openHelper.writableDatabase
            val placeId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.execSQL(
                """
                INSERT INTO places(uuid,nickname,address,lat,lon,radius_m,notes,source_app,created_at,updated_at,archived,first_check_in_at_place)
                VALUES(?, 'Home', NULL, NULL, NULL, NULL, NULL, 'test', ?, ?, 0, NULL)
                """.trimIndent(),
                arrayOf(placeId, now, now),
            )
            db.execSQL("UPDATE places SET nickname='Casa', updated_at=? WHERE uuid=?", arrayOf(now + 1, placeId))

            val rows = database.activityDao().page("places", 1, null, null, 20)
            val update = rows.first { it.action == "place_updated" && it.entityId == placeId }
            assertEquals("Casa", update.entityLabel)
            assertTrue(update.reversible)
            assertNotNull(update.beforePayload)
            assertNotNull(update.afterPayload)

            val result = HubActivityUndoEngine.undo(database, update.id)
            assertTrue(result is HubActivityUndoResult.Success)
            assertEquals("Home", scalarText(db, "SELECT nickname FROM places WHERE uuid=?", arrayOf(placeId)))

            val original = database.activityDao().byId(update.id)!!
            assertEquals(HubActivityStatus.REVERTED, original.status)
            assertNotNull(original.revertedAt)
            val compensation = database.activityDao().page("places", 1, null, null, 20)
                .firstOrNull { it.revertsActivityId == update.id }
            assertNotNull(compensation)
            assertFalse(compensation!!.reversible)
        }
    }

    @Test
    fun peopleAuditBecomesReadableActivityAndCreateUndoSoftDeletesContact() = runBlocking {
        withDatabase { database ->
            val db = database.openHelper.writableDatabase
            val now = System.currentTimeMillis()
            db.execSQL("INSERT INTO contacts(public_id,created_at,updated_at,deleted_at,archived_at) VALUES('person-test',?,?,NULL,NULL)", arrayOf(now, now))
            val contactId = scalarLong(db, "SELECT id FROM contacts WHERE public_id='person-test'")
            db.execSQL(
                """
                INSERT INTO contact_fields(contact_id,field_type,value,normalized_value,sort_value,added_at,edited_at,position,is_primary,source,latitude,longitude,country_code,description)
                VALUES(?, 'name', 'Carlo', 'carlo', 'carlo', ?, NULL, 0, 0, 'manual', NULL, NULL, NULL, NULL)
                """.trimIndent(),
                arrayOf(contactId, now),
            )
            db.execSQL(
                """
                INSERT INTO contact_events(contact_id,entity_type,action_type,event_type,field_type,old_value,new_value,occurred_at,metadata_json)
                VALUES(?, 'Contact', 'Created', 'contact_add', NULL, NULL, NULL, ?, NULL)
                """.trimIndent(),
                arrayOf(contactId, now),
            )

            val created = database.activityDao().page("people", 1, null, null, 20).first()
            assertEquals("created", created.action)
            assertEquals("person-test", created.entityId)
            assertEquals("Carlo", created.entityLabel)
            assertTrue(created.reversible)

            val result = HubActivityUndoEngine.undo(database, created.id)
            assertTrue(result is HubActivityUndoResult.Success)
            assertNotNull(nullableLong(db, "SELECT deleted_at FROM contacts WHERE id=?", arrayOf(contactId)))
            assertEquals(HubActivityStatus.REVERTED, database.activityDao().byId(created.id)!!.status)
            val compensation = database.activityDao().page("people", 1, null, null, 20)
                .firstOrNull { it.revertsActivityId == created.id }
            assertNotNull(compensation)
            assertEquals("deleted", compensation!!.action)
            assertFalse(compensation.reversible)
        }
    }

    private suspend fun withDatabase(block: suspend (PersonalHubDatabase) -> Unit) {
        val name = "activity-register-${UUID.randomUUID()}.db"
        val database = PersonalHubDatabase.openTemporary(context, name)
        try {
            block(database)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun scalarLong(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?> = emptyArray(),
    ): Long = db.query(sql, args).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun scalarText(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?> = emptyArray(),
    ): String = db.query(sql, args).use { cursor -> cursor.moveToFirst(); cursor.getString(0) }

    private fun nullableLong(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?> = emptyArray(),
    ): Long? = db.query(sql, args).use { cursor ->
        cursor.moveToFirst()
        if (cursor.isNull(0)) null else cursor.getLong(0)
    }
}
