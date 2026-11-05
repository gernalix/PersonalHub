package com.example.multitimetracker.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.multitimetracker.util.CapsuleWriteApi
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(CapsuleWriteApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SnapshotStoragePrivacyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val external get() = requireNotNull(context.getExternalFilesDir(null)).resolve("snapshot.json")

    @Before fun resetSyntheticStorage() {
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase("personalhub.db")
        context.getSharedPreferences("multitimetracker_snapshot", Context.MODE_PRIVATE).edit().clear().commit()
        external.delete()
    }

    @After fun closeSyntheticDatabase() {
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase("personalhub.db")
        external.delete()
    }

    private fun save(usage: Long) = SnapshotStore.save(
        context = context, tasks = emptyList(), tags = emptyList(),
        closedSessions = emptyList(), tagSessions = emptyList(),
        installAtMs = 1L, appUsageMs = usage, activeSessionStart = emptyMap(),
        activeTagStart = emptyList(), tagParents = emptyList(),
    )

    @Test fun normalSaveKeepsPrivatePersistenceWithoutCreatingAnExternalMirror() {
        save(17L)
        val stored = requireNotNull(SnapshotSqlite.readSnapshot(context))
        assertEquals(17L, JSONObject(stored).getLong("appUsageMs"))
        assertEquals(stored, context.getSharedPreferences("multitimetracker_snapshot", Context.MODE_PRIVATE)
            .getString("snapshot_json", null))
        assertNotNull(SnapshotStore.load(context))
        assertFalse(external.exists())
    }

    @Test fun laterSavesPreserveAnExistingExternalFileWithoutUpdatingIt() {
        save(17L)
        val original = requireNotNull(SnapshotSqlite.readSnapshot(context))
        external.writeText(original)
        save(18L)
        assertEquals(original, external.readText())
        assertEquals(18L, JSONObject(requireNotNull(SnapshotSqlite.readSnapshot(context))).getLong("appUsageMs"))
    }

    @Test fun syntheticExternalLegacyRecoveryStillWorks() {
        save(17L)
        val original = requireNotNull(SnapshotSqlite.readSnapshot(context))
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase("personalhub.db")
        context.getSharedPreferences("multitimetracker_snapshot", Context.MODE_PRIVATE).edit().clear().commit()
        external.writeText(original)
        val recovered = requireNotNull(SnapshotStore.load(context))
        assertEquals(17L, recovered.appUsageMs)
        assertEquals(original, external.readText())
    }
}
