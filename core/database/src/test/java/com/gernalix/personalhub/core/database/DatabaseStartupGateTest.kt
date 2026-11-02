package com.gernalix.personalhub.core.database

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Robolectric
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseStartupGateTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        DatabaseVault.preferences(context).edit().clear().commit()
        DatabaseStartupGate.onApplicationStartupChecked(context, ready = false)
    }

    @After fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        DatabaseVault.preferences(context).edit().clear().commit()
        DatabaseStartupGate.onApplicationStartupChecked(context, ready = false)
    }

    @Test fun validatedDatabaseOpensModuleWithoutPreparingScreen() {
        val file = context.getDatabasePath(PersonalHubDatabase.DB_NAME).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = PersonalHubDatabase.SCHEMA_VERSION }
        DatabaseStartupGate.onApplicationStartupChecked(context, ready = true)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertFalse(DatabaseStartupGate.blockIfNotReady(activity))
    }

    @Test fun anUnexpectedSchemaChangeCannotReuseApplicationPreflight() {
        val file = context.getDatabasePath(PersonalHubDatabase.DB_NAME).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = 20 }
        DatabaseStartupGate.onApplicationStartupChecked(context, ready = true)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(DatabaseStartupGate.blockIfNotReady(activity))
    }

    @Test fun schema20IsBlockedBeforeRoomCanAttemptAnUpgrade() {
        val file = context.getDatabasePath(PersonalHubDatabase.DB_NAME).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = 20 }

        val status = DatabaseStartupGate.status(context)

        assertFalse(status.ready)
        assertEquals(20, status.currentVersion)
        assertEquals(PersonalHubDatabase.SCHEMA_VERSION, status.requiredVersion)
        assertTrue(status.reason.orEmpty().contains("20"))
        assertTrue(status.reason.orEmpty().contains(PersonalHubDatabase.SCHEMA_VERSION.toString()))
    }
}
