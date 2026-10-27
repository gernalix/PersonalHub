package com.gernalix.luoghi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.luoghi.capsules.places.GeoSearch
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeoDatelineRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun before() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }

    @After fun after() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }

    @Test fun datelineCandidateSearchIncludesBothSidesOfMeridian() {
        val db = PersonalHubDatabase.get(context)
        val dao = db.placeDao()
        dao.upsertPlaceBlocking(PlaceEntity(uuid = "west", nickname = "West", lat = 0.0, lon = 179.9995))
        dao.upsertPlaceBlocking(PlaceEntity(uuid = "east", nickname = "East", lat = 0.0, lon = -179.9995))
        val box = GeoSearch.boundingBox(0.0, 179.9995, 500.0)
        assertTrue(box.minLon > box.maxLon)
        assertTrue(box.containsLongitude(-179.9995))
        val found = dao.placesInBoundingBoxBlocking(box.minLat, box.maxLat, box.minLon, box.maxLon, 100)
        assertEquals(setOf("west", "east"), found.map { it.uuid }.toSet())
        val page1 = dao.placesInBoundingBoxPageBlocking(box.minLat, box.maxLat, box.minLon, box.maxLon, 1, 0)
        val page2 = dao.placesInBoundingBoxPageBlocking(box.minLat, box.maxLat, box.minLon, box.maxLon, 1, 1)
        assertEquals(setOf("west", "east"), (page1 + page2).map { it.uuid }.toSet())
        assertEquals(2, dao.placesWithCoordinatesPageBlocking(1, 0).size +
            dao.placesWithCoordinatesPageBlocking(1, 1).size)
    }

    @Test fun largeRadiusNearPoleSearchesEveryLongitude() {
        val box = GeoSearch.boundingBox(89.999, 179.0, 500_000.0)
        assertEquals(-180.0, box.minLon, 0.0)
        assertEquals(180.0, box.maxLon, 0.0)
        assertTrue(box.containsLongitude(-179.0))
    }
}
