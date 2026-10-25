package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceGeoMetadataMigrationTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        DatabaseVault.preferences(context).edit().clear().commit()
    }

    @After fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        DatabaseVault.preferences(context).edit().clear().commit()
    }

    @Test fun automaticUpgrade25To26PreservesExistingPlaces() = runBlocking {
        val target = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        target.parentFile!!.mkdirs()
        val schema = JSONObject(
            context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/25.json")
                .bufferedReader().use { it.readText() },
        ).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(target, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indexes.length()) {
                    val index = indexes.getJSONObject(j)
                    db.execSQL(index.getString("createSql")
                        .replace("$" + "{TABLE_NAME}", table))
                }
            }
            val views = schema.optJSONArray("views")
            if (views != null) for (i in 0 until views.length()) {
                db.execSQL(views.getJSONObject(i).getString("createSql"))
            }
            db.execSQL("""
                INSERT INTO places(uuid, nickname, address, lat, lon, created_at, updated_at, archived)
                VALUES('existing-place', 'Home', 'Amager', 55.6, 12.6, 1000, 1000, 0)
            """)
            db.version = 25
        }

        val migrated = DatabaseVault.ensureStartupReady(context)
        assertTrue("Schema upgrade: ${DatabaseVault.error(context)}", migrated)
        val db = PersonalHubDatabase.get(context)
        assertNotNull(db.placeDao().getPlace("existing-place"))
        assertEquals("Amager", db.placeDao().getPlace("existing-place")?.address)
        assertTrue(db.placeDao().placesNeedingGeoMetadata(5).any { it.uuid == "existing-place" })
        assertEquals(26, SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version })
    }
}
