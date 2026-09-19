package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.DatabasePreferences
import com.gernalix.personalhub.core.database.capsules.sync.SyncJournal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production UI writes in an isolated package. Safe to run on devices with a real PH install. */
@RunWith(AndroidJUnit4::class)
class PersistenceUiRegressionDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val db get() = PersonalHubDatabase.get(context).openHelper.writableDatabase

    private fun marker(kind: String) = "QA914263${kind}${System.currentTimeMillis()}"

    private fun requireIsolatedPackage() {
        check(context.packageName == "com.gernalix.personalhub.qa") {
            "Persistence UI QA requires the isolated .qa package"
        }
    }

    private fun openActivity(activity: Class<out Activity>) {
        requireIsolatedPackage()
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        device.waitForIdle()
        repeat(3) {
            if (device.currentPackageName == "com.google.android.permissioncontroller") {
                device.pressBack()
                device.waitForIdle()
            }
        }
        device.wait(Until.findObject(By.text("Not now")), 1_500)?.let(::tap)
    }

    private fun text(value: String): UiObject2 =
        requireNotNull(device.wait(Until.findObject(By.text(value)), 8_000)) {
            val visible = device.findObjects(By.clazz("android.widget.TextView"))
                .mapNotNull { it.text }.take(25)
            "Missing UI text: $value; package=${device.currentPackageName}; visible=$visible"
        }

    private fun description(value: String): UiObject2 =
        requireNotNull(device.wait(Until.findObject(By.desc(value)), 8_000)) {
            val visible = device.findObjects(By.clazz("android.widget.TextView"))
                .mapNotNull { it.text }.take(25)
            "Missing UI action: $value; package=${device.currentPackageName}; visible=$visible"
        }

    private fun tap(target: UiObject2) {
        var node: UiObject2? = target
        while (node != null && !node.isClickable) node = node.parent
        (node ?: target).click()
    }

    private fun tapText(value: String) = tap(text(value))
    private fun tapDescription(value: String) = tap(description(value))

    private fun firstEditor(): UiObject2 =
        requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)) {
            "Missing editable field"
        }

    private fun setFirstEditor(value: String) {
        repeat(3) { attempt ->
            try {
                firstEditor().text = value
                return
            } catch (stale: StaleObjectException) {
                if (attempt == 2) throw stale
                device.waitForIdle()
            }
        }
    }

    private fun oneId(table: String, column: String, value: String): Long? =
        db.query("SELECT id FROM $table WHERE $column=?", arrayOf(value)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun scalar(sql: String, vararg args: Any): Long =
        db.query(sql, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun cleanActivityRows(marker: String) {
        val pattern = "%$marker%"
        db.execSQL(
            "DELETE FROM hub_activity_log WHERE entity_label LIKE ? OR before_payload LIKE ? OR after_payload LIKE ?",
            arrayOf(pattern, pattern, pattern),
        )
    }

    @TableProbe("quick_event_entries", "hub_generation", "hub_sync_pending")
    @Test fun timerEventCreateEditReopenReadbackAndCleanup() {
        val title = marker("Timer")
        var id: Long? = null
        val generationBefore = scalar("SELECT generation FROM hub_generation WHERE id=1")
        try {
            openActivity(com.example.multitimetracker.MainActivity::class.java)
            tapText("Events")
            tapText("Register event")
            setFirstEditor(title)
            tapText("Save")
            text(title)
            id = oneId("quick_event_entries", "title", title)
            assertNotNull("Timer event did not persist", id)
            assertTrue(scalar("SELECT generation FROM hub_generation WHERE id=1") > generationBefore)
            val pendingKeys = db.query(
                "SELECT row_key FROM hub_sync_pending WHERE table_name='quick_event_entries'",
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue("Sync journal missed Timer entry", pendingKeys.any {
                SyncJournal.keyValues(it).single() == id
            })

            tapText(title)
            setFirstEditor("${title}Edited")
            tapText("Save")
            assertEquals("${title}Edited", db.query(
                "SELECT title FROM quick_event_entries WHERE id=? AND deleted_at_ms IS NULL",
                arrayOf(id!!),
            ).use { it.moveToFirst(); it.getString(0) })
            tapText("Now")
            tapText("Events")
            text("${title}Edited")
        } finally {
            id?.let { db.execSQL("DELETE FROM quick_event_entries WHERE id=?", arrayOf(it)) }
            cleanActivityRows(title)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM quick_event_entries WHERE title LIKE ?", "%$title%"))
        }
    }

    @TableProbe("contacts", "contact_fields", "contact_events", "hub_activity_log")
    @Test fun peopleCreateEditReopenReadbackAndCleanup() {
        val name = marker("Person")
        var id: Long? = null
        try {
            openActivity(com.supercontacts.app.MainActivity::class.java)
            tapDescription("New contact")
            setFirstEditor(name)
            tapText("Save")
            for (attempt in 0 until 40) {
                id = db.query(
                    "SELECT contact_id FROM contact_fields WHERE field_type='name' AND value=?",
                    arrayOf(name),
                ).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (id != null) break
                Thread.sleep(200)
            }
            assertNotNull("People create did not persist; package=${device.currentPackageName}; " +
                "visible=${device.findObjects(By.clazz("android.widget.TextView")).mapNotNull { it.text }.take(12)}", id)
            text(name)
            assertTrue(scalar("SELECT COUNT(*) FROM contact_events WHERE contact_id=?", id!!) > 0)
            assertTrue(scalar("SELECT COUNT(*) FROM hub_activity_log WHERE module_id='people' AND entity_label=?", name) > 0)

            tapDescription("Edit")
            setFirstEditor("${name}Edited")
            tapText("Save")
            assertEquals(1L, scalar(
                "SELECT COUNT(*) FROM contact_fields WHERE contact_id=? AND field_type='name' AND value=?",
                id!!, "${name}Edited",
            ))
            device.pressBack()
            tapText("${name}Edited")
            text("${name}Edited")
            tapDescription("Delete")
            tapText("Delete")
            assertEquals(0L, scalar("SELECT COUNT(*) FROM contacts WHERE id=? AND deleted_at IS NULL", id!!))
        } finally {
            id?.let { db.execSQL("DELETE FROM contacts WHERE id=?", arrayOf(it)) }
            cleanActivityRows(name)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM contact_fields WHERE value LIKE ?", "%$name%"))
        }
    }

    @TableProbe("substances", "intake_events", "stock_adjustments")
    @Test fun substanceStockRefillThenDoseButtonPersistsIntakeWithoutCrash() {
        val name = marker("Dose")
        var id: Long? = null
        try {
            openActivity(com.gernalix.sostanze.MainActivity::class.java)
            tapText("+")
            setFirstEditor(name)
            tapText("Save")
            id = oneId("substances", "name", name)
            assertNotNull("Substance create did not persist", id)
            assertEquals(0L, scalar("SELECT CAST(stock_current AS INTEGER) FROM substances WHERE id=?", id!!))

            tapText("Stock")
            tapText(name)
            tapText("+ refill")
            setFirstEditor("10")
            tapText("Save")
            assertEquals(10L, scalar("SELECT CAST(stock_current AS INTEGER) FROM substances WHERE id=?", id!!))
            tapText("Home")
            tapText(name)
            assertEquals(1L, scalar("SELECT COUNT(*) FROM intake_events WHERE substance_id=?", id!!))
            text(name) // The completed dose must remain reachable for edit and cleanup.
            assertEquals(9L, scalar("SELECT CAST(stock_current AS INTEGER) FROM substances WHERE id=?", id!!))
            assertEquals(2L, scalar("SELECT COUNT(*) FROM stock_adjustments WHERE substance_id=?", id!!))
            assertTrue("App process crashed after dose tap", device.hasObject(By.text("PersonalHub")))
        } finally {
            id?.let { db.execSQL("DELETE FROM substances WHERE id=?", arrayOf(it)) }
            cleanActivityRows(name)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM substances WHERE name LIKE ?", "%$name%"))
        }
    }

    /** Domain writer for database-backed settings; these namespaces have no separate editor UI. */
    @TableProbe("hub_preferences", "ui_prefs_mirror")
    @Test fun databasePreferencesWriteEditReadbackAndCleanup() {
        requireIsolatedPackage()
        val namespace = marker("Prefs")
        val ordinary = DatabasePreferences(context, namespace)
        val timer = DatabasePreferences(context, "ui_prefs")
        val timerKey = "qa914263"
        val previousTimerValue = timer.getString(timerKey, null)
        try {
            assertTrue(ordinary.edit().putString("value", "one").commit())
            assertEquals("one", JSONObject(db.query(
                "SELECT json FROM hub_preferences WHERE namespace=?", arrayOf(namespace),
            ).use { it.moveToFirst(); it.getString(0) }).getString("value"))
            assertTrue(ordinary.edit().putString("value", "two").commit())
            assertEquals("two", ordinary.getString("value", null))

            assertTrue(timer.edit().putString(timerKey, "one").commit())
            assertEquals("one", JSONObject(db.query(
                "SELECT json FROM ui_prefs_mirror WHERE id=1",
            ).use { it.moveToFirst(); it.getString(0) }).getString(timerKey))
            assertTrue(timer.edit().putString(timerKey, "two").commit())
            assertEquals("two", timer.getString(timerKey, null))
        } finally {
            if (previousTimerValue == null) timer.edit().remove(timerKey).commit()
            else timer.edit().putString(timerKey, previousTimerValue).commit()
            db.execSQL("DELETE FROM hub_preferences WHERE namespace=?", arrayOf(namespace))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM hub_preferences WHERE namespace=?", namespace))
        }
    }
}
