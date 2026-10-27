package com.gernalix.luoghi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceAliasLinkCreatedAtTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun before() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }

    @After fun after() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }

    @Test fun editingAliasAndLinkRetainsOriginalCreationDate() = runBlocking(Dispatchers.IO) {
        val dao = PersonalHubDatabase.get(context).placeDao()
        dao.upsertPlaceBlocking(PlaceEntity(uuid = "p", nickname = "Test"))
        val repo = PlaceRepository(context)
        val alias = repo.createAlias("p", "First", null)
        val link = repo.createLink("p", "owner", "kind", "key")
        val aliasCreated = assertNotNull(dao.getAliasBlocking(alias)).let { dao.getAliasBlocking(alias)!!.createdAt }
        val linkCreated = assertNotNull(dao.getLinkBlocking(link)).let { dao.getLinkBlocking(link)!!.createdAt }
        repo.updateAlias(alias, "p", "Updated", null)
        repo.updateLink(link, "p", "owner", "kind", "updated")
        assertEquals(aliasCreated, dao.getAliasBlocking(alias)!!.createdAt)
        assertEquals(linkCreated, dao.getLinkBlocking(link)!!.createdAt)
    }
}
