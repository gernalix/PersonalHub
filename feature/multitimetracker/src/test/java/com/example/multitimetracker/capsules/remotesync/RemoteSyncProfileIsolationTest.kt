package com.example.multitimetracker.capsules.remotesync

import android.content.Context
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
class RemoteSyncProfileIsolationTest {
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
    fun restartedWorkCannotContinueWithPreviousProfileIdentity() {
        assertFalse(RemoteSyncWorker.matchesActiveProfile(context, "personal"))
        assertTrue(RemoteSyncWorker.matchesActiveProfile(context, "work"))
        assertFalse(RemoteSyncWorker.matchesActiveProfile(context, null))
    }
}
