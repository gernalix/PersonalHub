package com.gernalix.luoghi

import com.gernalix.luoghi.capsules.places.GeoSearch
import com.gernalix.luoghi.capsules.places.PlaceSearch
import com.gernalix.luoghi.data.PlaceEntity
import kotlin.math.PI
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoSearchMutationFuzzTest {
    @Test fun haversinePropertiesThirtyThousandSeededInputs() {
        val r=Random(20261027)
        repeat(30_000) { i ->
            val la=r.nextDouble(-90.0,90.0); val lo=r.nextDouble(-180.0,180.0)
            val lb=r.nextDouble(-90.0,90.0); val ln=r.nextDouble(-180.0,180.0)
            val d=GeoSearch.distanceMeters(la,lo,lb,ln)
            assertTrue("nonfinite $i", d.isFinite())
            assertTrue("distance bound $i",d>=0.0 && d<=PI*6_371_008.8+0.1)
            assertEquals("symmetry $i",d,GeoSearch.distanceMeters(lb,ln,la,lo),1e-5)
            assertEquals("self $i",0.0,GeoSearch.distanceMeters(la,lo,la,lo),0.001)
        }
    }
    @Test fun antimeridianNeighborRemainsVisibleInsideBoundingBox() {
        val lat=0.0; val lon=179.9995; val otherLon=-179.9995; val radius=500.0
        assertTrue(GeoSearch.distanceMeters(lat,lon,lat,otherLon)<radius)
        val box=GeoSearch.boundingBox(lat,lon,radius)
        assertTrue("Antimeridian crossing near 180 degrees is lost before distance filter",
            box.containsLongitude(otherLon))
    }
    @Test fun unicodeSearchTenThousandSeededInputsNeverThrows() {
        val r=Random(20261027)
        val p=PlaceEntity(uuid="hidden-database-id",nickname="Café Central",address="Amager",notes="Courtyard")
        val letters="abCèßØüא☃%/[] \t\n"
        repeat(10_000) {
            val q=buildString { repeat(r.nextInt(70)) { append(letters[r.nextInt(letters.length)]) } }
            PlaceSearch.matches(p,listOf("tag","Δοκιμή"),q)
        }
        assertFalse(PlaceSearch.matches(p,emptyList(),"hidden-database-id"))
    }
}
