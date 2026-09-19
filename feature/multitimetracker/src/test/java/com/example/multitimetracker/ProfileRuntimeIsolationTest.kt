package com.example.multitimetracker

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.multitimetracker.core.quickevent.QuickEventTarget
import com.example.multitimetracker.widget.QuickEventWidgetPrefs
import com.gernalix.personalhub.core.database.DatabaseProfiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileRuntimeIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetProfiles() {
        context.getSharedPreferences("personalhub_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("quick_event_widget_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        setActive("personal")
    }

    @Test
    fun widgetTargetCannotResolveAnIdenticalIdInAnotherProfile() {
        val target = QuickEventTarget.Template(42)
        QuickEventWidgetPrefs.save(context, 7, target)
        assertEquals(target, QuickEventWidgetPrefs.read(context, 7))

        setActive("work")
        assertNull(QuickEventWidgetPrefs.read(context, 7))
    }

    @Test
    fun timerAlarmAndNotificationIntentsAreAcceptedOnlyForTheirProfile() {
        val personal = Intent().putExtra(TimeFenceTimerReceiver.EXTRA_PROFILE_ID, "personal")
        val work = Intent().putExtra(TimeFenceTimerReceiver.EXTRA_PROFILE_ID, "work")
        assertTrue(TimeFenceTimerReceiver.isForActiveProfile(context, personal))
        assertFalse(TimeFenceTimerReceiver.isForActiveProfile(context, work))

        setActive("work")
        assertFalse(TimeFenceTimerReceiver.isForActiveProfile(context, personal))
        assertTrue(TimeFenceTimerReceiver.isForActiveProfile(context, work))
    }

    private fun setActive(profileId: String) {
        val profiles = JSONArray()
            .put(JSONObject().put("id", "personal").put("name", "Personal"))
            .put(JSONObject().put("id", "work").put("name", "Work"))
        context.getSharedPreferences("personalhub_profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles_json", profiles.toString())
            .putString("active_profile", profileId)
            .commit()
        assertEquals(profileId, DatabaseProfiles.activeProfileId(context))
    }
}
