package com.gernalix.luoghi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.SharedTagEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceTagsLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun before() { PersonalHubDatabase.resetForTests(); context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME) }
    @After fun after() { PersonalHubDatabase.resetForTests(); context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME) }

    @Test fun namesRoundTripWithoutDuplicatingGlobalTagsAndCanBeCleared() = runBlocking {
        val db = PersonalHubDatabase.get(context)
        val repo = PlaceRepository(context, db)
        val tags = SharedTagEngine(db)
        val global = tags.getOrCreate(HubTagNamespaces.GLOBAL, "Shared")
        val personTag = tags.getOrCreate(HubTagNamespaces.PEOPLE, "Local")
        val id = repo.savePlace(null, "Cafe", null, null, null, 75.0, null, "test", tagNames = listOf("Local", " local "))
        val local = repo.tagsForPlace(id).single()
        assertNotEquals(personTag.id, local.id)
        assertEquals(HubTagNamespaces.PLACES, local.namespace)
        tags.assign(HubEntityRef("places", "place", id), listOf(global.id))
        repo.setPlaceTags(id, repo.tagsForPlace(id).map { it.name })
        assertEquals(setOf(local.id, global.id), repo.tagsForPlace(id).map { it.id }.toSet())
        assertNull(db.hubTagDao().tag(HubTagNamespaces.PLACES, SharedTagEngine.normalize("Shared")))
        repo.setPlaceTags(id, emptyList())
        assertTrue(repo.tagsForPlace(id).isEmpty())
        assertNotNull(db.hubTagDao().tag(global.id))
        assertNotNull(db.hubTagDao().tag(personTag.id))
    }

    @Test fun renameArchiveRestoreAndBulkEditsPreserveIdentities() = runBlocking {
        val db = PersonalHubDatabase.get(context)
        val repo = PlaceRepository(context, db)
        val tags = SharedTagEngine(db)
        val first = repo.savePlace(null, "First", null, null, null, 75.0, null, "test", tagNames = listOf("Old"))
        val second = repo.savePlace(null, "Second", null, null, null, 75.0, null, "test")
        val tag = repo.tagsForPlace(first).single()
        tags.rename(tag.id, "New")
        assertEquals("New", repo.tagsForPlace(first).single().name)
        repo.bulkAddPlaceTags(listOf(second, second), listOf(tag.id, tag.id))
        assertEquals(tag.id, repo.tagsForPlace(second).single().id)
        tags.archive(tag.id, true)
        repo.setPlaceTags(first, listOf("New"))
        assertTrue(repo.tagsForPlace(first).single().archived)
        repo.bulkRemovePlaceTags(listOf(first, second), listOf(tag.id))
        assertTrue(repo.tagsForPlace(first).isEmpty())
        assertTrue(repo.tagsForPlace(second).isEmpty())
        repo.setPlaceTags(second, listOf("New"))
        assertEquals(tag.id, repo.tagsForPlace(second).single().id)
        assertFalse(repo.tagsForPlace(second).single().archived)
    }

    @Test fun failedTagWriteRollsBackThePlaceMutation() = runBlocking {
        val db = PersonalHubDatabase.get(context)
        val repo = PlaceRepository(context, db)
        val id = repo.savePlace(null, "Original", null, null, null, 75.0, null, "test")
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_test_tag BEFORE INSERT ON hub_tags WHEN NEW.name='Reject' BEGIN SELECT RAISE(ABORT,'synthetic tag write failure'); END")
        val result = runCatching { repo.savePlace(id, "Changed", null, null, null, 75.0, null, "test", tagNames = listOf("Reject")) }
        assertTrue(result.isFailure)
        assertEquals("Original", db.placeDao().getPlace(id)?.nickname)
        assertTrue(repo.tagsForPlace(id).isEmpty())
    }
}
