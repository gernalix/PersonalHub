package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
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
class PersonalHubOpenManifestTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        context.getSharedPreferences("hub_open_manifest", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        context.getSharedPreferences("hub_open_manifest", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun nonGitManifestCachesInstalledTriggersAndRepairsAChangedSchema() {
        val first = PersonalHubDatabase.get(context).openHelper.writableDatabase
        val trigger = first.query(
            "SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'hub_dirty_%' ORDER BY name LIMIT 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        val prefs = context.getSharedPreferences("hub_open_manifest", Context.MODE_PRIVATE)
        val manifest = prefs.getString(PersonalHubDatabase.DB_NAME, null)
        assertNotNull(manifest)
        assertTrue(manifest!!.startsWith("v2:"))
        assertTrue(manifest.contains(":false:"))

        PersonalHubDatabase.resetForTests()
        PersonalHubDatabase.get(context).openHelper.writableDatabase
        assertEquals(manifest, prefs.getString(PersonalHubDatabase.DB_NAME, null))

        PersonalHubDatabase.resetForTests()
        val file = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER `$trigger`")
        }
        val repaired = PersonalHubDatabase.get(context).openHelper.writableDatabase
        repaired.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name=?", arrayOf(trigger)).use {
            assertTrue(it.moveToFirst())
        }
    }
}
