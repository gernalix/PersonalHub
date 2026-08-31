package com.gernalix.personalhub.core.migration

import android.database.sqlite.SQLiteDatabase
import com.gernalix.personalhub.core.model.SourceApp
import com.gernalix.personalhub.core.model.SourceTablePlan
import com.gernalix.personalhub.core.model.UnifiedEntityType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationSourceDatabaseScannerTest {
    private val database = SQLiteDatabase.create(null)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun scanReadsStableIdsFromConfiguredSourceColumn() {
        database.execSQL("CREATE TABLE contacts(id INTEGER PRIMARY KEY, public_id TEXT)")
        database.execSQL("INSERT INTO contacts(id, public_id) VALUES (2, 'b'), (1, 'a')")

        val rows = MigrationSourceDatabaseScanner(database).scan(
            SourceTablePlan(
                sourceApp = SourceApp.SUPERCONTACTS,
                sourceTable = "contacts",
                entityType = UnifiedEntityType.PERSON,
                sourceIdColumn = "id",
            ),
        )

        assertEquals(SourceApp.SUPERCONTACTS, rows.sourceApp)
        assertEquals("contacts", rows.sourceTable)
        assertEquals(listOf("1", "2"), rows.sourceIds)
    }

    @Test
    fun scanQuotesAttachedDatabaseStyleTableNames() {
        database.execSQL("ATTACH DATABASE ':memory:' AS mtt_remote_sync")
        database.execSQL("CREATE TABLE mtt_remote_sync.sync_queue(id TEXT PRIMARY KEY)")
        database.execSQL("INSERT INTO mtt_remote_sync.sync_queue(id) VALUES ('q2'), ('q1')")

        val rows = MigrationSourceDatabaseScanner(database).scan(
            SourceTablePlan(
                sourceApp = SourceApp.MULTITIMETRACKER,
                sourceTable = "mtt_remote_sync.sync_queue",
                entityType = UnifiedEntityType.FEATURE_LOCAL_RECORD,
                sourceIdColumn = "id",
            ),
        )

        assertEquals(listOf("q1", "q2"), rows.sourceIds)
    }

    @Test
    fun scanBuildsDeterministicIdsForCompositeKeys() {
        database.execSQL("CREATE TABLE contact_tags(contact_id INTEGER NOT NULL, tag_id INTEGER NOT NULL)")
        database.execSQL("INSERT INTO contact_tags(contact_id, tag_id) VALUES (2, 1), (1, 3)")

        val rows = MigrationSourceDatabaseScanner(database).scan(
            SourceTablePlan(
                sourceApp = SourceApp.SUPERCONTACTS,
                sourceTable = "contact_tags",
                entityType = UnifiedEntityType.TAG,
                sourceIdColumns = listOf("contact_id", "tag_id"),
            ),
        )

        assertEquals(listOf("contact_id=1|tag_id=3", "contact_id=2|tag_id=1"), rows.sourceIds)
    }

    @Test
    fun quoteIdentifierPathEscapesSegments() {
        assertEquals(
            "\"main\".\"table\"\"name\"",
            MigrationSourceDatabaseScanner.quoteIdentifierPath("main.table\"name"),
        )
    }
}
