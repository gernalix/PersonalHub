package com.gernalix.luoghi.capsules.geofence

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceGeofenceProfileIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun selectWorkProfile() {
        val profiles = JSONArray()
            .put(JSONObject().put("id", "personal").put("name", "Personal"))
            .put(JSONObject().put("id", "work").put("name", "Work"))
        context.getSharedPreferences("personalhub_profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles_json", profiles.toString())
            .putString("active_profile", "work")
            .commit()
    }

    @Test
    fun platformTransitionCannotCrossProfile() {
        val personal = Intent().putExtra(PlaceGeofenceRegistrar.PROFILE_ID, "personal")
        val work = Intent().putExtra(PlaceGeofenceRegistrar.PROFILE_ID, "work")
        assertFalse(PlaceGeofenceReceiver.isForActiveProfile(context, personal))
        assertTrue(PlaceGeofenceReceiver.isForActiveProfile(context, work))
    }
}
