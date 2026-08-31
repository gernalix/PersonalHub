package com.gernalix.personalhub.core.migration

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.model.MigrationInventory
import com.gernalix.personalhub.core.model.SourceApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationMappingStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun cleanBefore() {
        context.deleteDatabase(MigrationMappingStore.DB_NAME)
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(MigrationMappingStore.DB_NAME)
    }

    @Test
    fun recordAllWritesMappingsAndCountsThemBySourceTable() {
        val store = MigrationMappingStore(context)
        val mappings = listOf(
            MigrationInventory.mappingForRow(SourceApp.SUPERCONTACTS, "contacts", "1"),
            MigrationInventory.mappingForRow(SourceApp.SUPERCONTACTS, "contacts", "2"),
            MigrationInventory.mappingForRow(SourceApp.LUOGHI, "places", "home"),
        )

        store.recordAll(mappings)

        assertEquals("ok", store.integrityCheck())
        assertEquals(
            mapOf(
                "luoghi.places" to 1,
                "supercontacts.contacts" to 2,
            ),
            store.mappingCountsBySourceTable(),
        )
        assertEquals(3, store.mappings().size)
    }

    @Test
    fun recordAllRollsBackTheWholeBatchOnDuplicateSourceKey() {
        val store = MigrationMappingStore(context)
        val first = MigrationInventory.mappingForRow(SourceApp.WORDPULSE, "sessions", "session-1")
        val duplicate = MigrationInventory.mappingForRow(SourceApp.WORDPULSE, "sessions", "session-1")

        assertThrows(SQLiteConstraintException::class.java) {
            store.recordAll(listOf(first, duplicate))
        }

        assertEquals(emptyMap<String, Int>(), store.mappingCountsBySourceTable())
    }

    @Test
    fun replaceAllMakesDeterministicRunsIdempotent() {
        val store = MigrationMappingStore(context)
        val firstRun = listOf(
            MigrationInventory.mappingForRow(SourceApp.WORDPULSE, "sessions", "session-1"),
        )
        val secondRun = listOf(
            MigrationInventory.mappingForRow(SourceApp.WORDPULSE, "sessions", "session-1"),
            MigrationInventory.mappingForRow(SourceApp.WORDPULSE, "word_entries", "word-1"),
        )

        store.replaceAll(firstRun)
        store.replaceAll(secondRun)

        assertEquals(
            mapOf(
                "wordpulse.sessions" to 1,
                "wordpulse.word_entries" to 1,
            ),
            store.mappingCountsBySourceTable(),
        )
        assertEquals(2, store.mappings().size)
    }
}
