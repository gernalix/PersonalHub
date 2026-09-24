package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
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
    }

    @After fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        DatabaseVault.preferences(context).edit().clear().commit()
    }

    @Test fun schema20IsBlockedBeforeRoomCanAttemptAnUpgrade() {
        val file = context.getDatabasePath(PersonalHubDatabase.DB_NAME).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = 20 }

        val status = DatabaseStartupGate.status(context)

        assertFalse(status.ready)
        assertEquals(20, status.currentVersion)
        assertEquals(21, status.requiredVersion)
        assertTrue(status.reason.orEmpty().contains("20"))
        assertTrue(status.reason.orEmpty().contains("21"))
    }
}
