package com.gernalix.sostanze.widget

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.gernalix.sostanze.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubstancesWidgetRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun prepareProfiles() {
        context.getSharedPreferences("substances_widget_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        setProfile("personal", includeWork = false)
    }

    @Test fun initialFrameworkViewIsNeverAnEmptyRectangle() {
        val view = LayoutInflater.from(context).inflate(R.layout.widget_substances_action, null)
        assertTrue(view.findViewById<TextView>(R.id.widgetSubstancesLabel).text.isNotBlank())
    }

    @Test fun legacyTargetIsAdoptedWithoutRecreatingItsWidget() {
        val prefs = context.getSharedPreferences("substances_widget_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("substance_701", 42L).commit()
        assertEquals(42L, SubstancesWidgetPrefs.read(context, 701))
        setProfile("work")
        assertNull(SubstancesWidgetPrefs.read(context, 701))
        setProfile("personal")
        assertEquals(42L, SubstancesWidgetPrefs.read(context, 701))
    }

    @Test fun legacyTargetKeepsItsActiveDatabaseBehaviorWhenOtherProfilesExist() {
        val prefs = context.getSharedPreferences("substances_widget_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("substance_701", 42L).commit()
        setProfile("work")
        assertEquals(42L, SubstancesWidgetPrefs.read(context, 701))
        setProfile("personal")
        assertNull(SubstancesWidgetPrefs.read(context, 701))
        assertEquals(42L, prefs.getLong("substance_701", -1))
    }

    @Test fun restoredOverlappingIdsKeepBothTargetsAndTheirOriginalProfiles() {
        SubstancesWidgetPrefs.save(context, 1, 11L)
        setProfile("work")
        SubstancesWidgetPrefs.save(context, 2, 22L)
        SubstancesWidgetPrefs.restore(context, intArrayOf(1, 2), intArrayOf(2, 3))
        assertNull(SubstancesWidgetPrefs.read(context, 1))
        assertNull(SubstancesWidgetPrefs.read(context, 2))
        assertEquals(22L, SubstancesWidgetPrefs.read(context, 3))
        setProfile("personal")
        assertEquals(11L, SubstancesWidgetPrefs.read(context, 2))
        assertNull(SubstancesWidgetPrefs.read(context, 3))
    }

    private fun setProfile(id: String, includeWork: Boolean = true) {
        val profiles = JSONArray().put(JSONObject().put("id", "personal").put("name", "Personal"))
        if (includeWork) profiles.put(JSONObject().put("id", "work").put("name", "Work"))
        context.getSharedPreferences("personalhub_profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles_json", profiles.toString()).putString("active_profile", id).commit()
    }
}
