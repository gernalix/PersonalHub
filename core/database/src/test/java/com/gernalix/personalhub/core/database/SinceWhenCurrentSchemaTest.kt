package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SinceWhenCurrentSchemaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @After fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @Test fun currentSchemaPersistsSourceSnapshotAcrossReopenWithoutSourceForeignKey() = runBlocking {
        val dao = PersonalHubDatabase.get(context).sinceWhenCounterDao()
        val id = dao.insert(
            SinceWhenCounterEntity(
                title = "Started journaling",
                initialTimestamp = 1_790_000_000_000,
                sourceEntityType = "timer/quick_event_entry",
                sourceEntityId = "event-42",
                sourceTimestampField = "event_date",
                createdAt = 1_790_000_100_000,
            ),
        )

        PersonalHubDatabase.closeInstance()
        val reopened = requireNotNull(PersonalHubDatabase.get(context).sinceWhenCounterDao().get(id))
        assertEquals("event-42", reopened.sourceEntityId)
        assertEquals("event_date", reopened.sourceTimestampField)
        assertNull(reopened.endTimestamp)
    }
    @Test fun completeCounterEditPersistsPreciseTimesLegacyTagsAndProvenance() = runBlocking {
        val original = SinceWhenCounterEntity(
            title = "Legacy counter", description = "Description",
            initialTimestamp = 1790000012345L, endTimestamp = 1790100098765L,
            colorArgb = 0xFF9B3D5CL, displayUnitsJson = "[\"HOURS\",\"SECONDS\"]",
            legacyTagIdsJson = "[42,73]", sourceEntityType = "timer/session",
            sourceEntityId = "gone-source", sourceTimestampField = "start", createdAt = 1790000000000L,
        )
        val id = PersonalHubDatabase.get(context).sinceWhenCounterDao().insert(original)
        PersonalHubDatabase.closeInstance()
        val dao = PersonalHubDatabase.get(context).sinceWhenCounterDao()
        assertEquals(original.copy(id = id), dao.get(id))
        val edited = requireNotNull(dao.get(id)).copy(title = "Edited", description = "Changed", initialTimestamp = 1790000067890L, endTimestamp = null, colorArgb = 0xFF2B5DAAL, displayUnitsJson = "[\"MINUTES\"]")
        dao.update(edited)
        PersonalHubDatabase.closeInstance()
        assertEquals(edited, PersonalHubDatabase.get(context).sinceWhenCounterDao().get(id))
    }
}
