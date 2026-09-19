package com.gernalix.personalhub.salute

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LegacyHealthImportTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    @Test fun existingCacheMovesToCanonicalDbOnceWithSourceProvenance() {
        val file=File(context.noBackupFilesDir,"salute/salute.db")
        file.parentFile!!.mkdirs()
        file.delete()
        val name="health-import-${UUID.randomUUID()}.db"
        try {
            SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
                val schema=javaClass.classLoader!!.getResourceAsStream("legacy_health_schema.sql")!!.bufferedReader().use { it.readText() }
                schema.split(';').map(String::trim).filter(String::isNotEmpty).forEach(old::execSQL)
                old.execSQL("INSERT INTO metadata(key,value) VALUES('consumer_contract_version','2')")
                old.execSQL("INSERT INTO categories(id,name) VALUES(1,'Categoria')")
                old.execSQL("INSERT INTO examinations(id,category_id,name) VALUES(1,1,'Esame')")
                old.execSQL("INSERT INTO analyses(id,examination_id,name) VALUES(1,1,'Misura')")
                old.execSQL("INSERT INTO samples(id,collection_epoch_ms,utc_offset_min,time_precision,source) VALUES(1,1000,0,'datetime','synthetic')")
                old.execSQL("INSERT INTO test_events(id,event_epoch_ms,utc_offset_min,event_time_precision,examination_id,sample_id,status) VALUES(1,1000,0,'datetime',1,1,'done')")
                old.execSQL("INSERT INTO measurements(id,test_event_id,analysis_id,numeric_value,received_epoch_ms,received_source) VALUES(1,1,1,8.9,3700000,'ChatGPT message')")
                old.execSQL("INSERT INTO sample_comments(id,sample_id,comment_type,author_label,comment_it,source,recorded_epoch_ms) VALUES(1,1,'clinician_summary','GP','Synthetic source note','synthetic',2000)")
            }
            val db=PersonalHubDatabase.openTemporary(context,name)
            try {
                LegacyHealthImport.importCached(context,db)
                val sqlite=db.openHelper.writableDatabase
                assertEquals(1L,sqlite.query("SELECT COUNT(*) FROM health_samples").use { it.moveToFirst();it.getLong(0) })
                assertEquals(1L,sqlite.query("SELECT COUNT(*) FROM health_measurements").use { it.moveToFirst();it.getLong(0) })
                assertEquals("chat_received_proxy",sqlite.query("SELECT availability_basis FROM health_measurements").use { it.moveToFirst();it.getString(0) })
                assertEquals("Synthetic source note",sqlite.query("SELECT value FROM health_source_metadata WHERE key='legacy.comment.1.comment_it'").use { it.moveToFirst();it.getString(0) })
                assertTrue(sqlite.query("SELECT COUNT(*) FROM hub_activity_log WHERE module_id='salute' AND group_id LIKE 'health-import-legacy-salute-%'").use { it.moveToFirst();it.getLong(0)>0 })
                assertEquals("ok",sqlite.query("PRAGMA quick_check").use { it.moveToFirst();it.getString(0) })
                assertFalse(sqlite.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
                assertFalse(file.exists())
                LegacyHealthImport.importCached(context,db)
                assertEquals(1L,sqlite.query("SELECT COUNT(*) FROM health_import_batches").use { it.moveToFirst();it.getLong(0) })
            } finally { db.close() }
        } finally { file.delete();context.deleteDatabase(name) }
    }
}
