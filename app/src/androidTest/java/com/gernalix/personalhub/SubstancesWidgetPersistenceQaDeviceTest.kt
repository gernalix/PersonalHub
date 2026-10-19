package com.gernalix.personalhub

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.TextView
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.data.SostanzeRepository
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.SubstanceSaveOutcome
import com.gernalix.sostanze.data.SubstanceTypes
import com.gernalix.sostanze.widget.SubstancesWidgetPrefs
import com.gernalix.sostanze.widget.SubstancesWidgetProvider
import com.gernalix.sostanze.R as SubstancesR
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Two phases separated by a real process restart controlled outside instrumentation. */
class SubstancesWidgetPersistenceQaDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var host: AppWidgetHost
    private val fixture by lazy { context.getSharedPreferences("widget-recovery-qa-fixture", Context.MODE_PRIVATE) }

    @Before fun setUp() {
        assertTrue(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa(?:\\d+)?")))
        assertTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        host = AppWidgetHost(context, 762452)
        host.startListening()
    }

    @After fun stopListening() {
        if (::host.isInitialized) host.stopListening()
    }

    @Test fun prepareOwnedWidgetBeforeProcessRestart() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        if (fixture.contains("widget")) {
            val previous = fixture.getInt("widget", AppWidgetManager.INVALID_APPWIDGET_ID)
            assertNull("A live QA fixture must be resumed, not replaced", manager.getAppWidgetInfo(previous))
            // A previous runner can die after deleting its owned binding but before
            // an asynchronous preference cleanup reaches disk.
            check(fixture.edit().clear().commit())
        }
        val db = PersonalHubDatabase.get(context)
        val title = "Widget QA Persistent ${UUID.randomUUID().toString().take(8)}"
        val target = (SostanzeRepository(db).saveSubstance(SubstanceEntity(
            name = title, type = SubstanceTypes.FARMACO, stockCurrent = 4.0,
            stockUnit = "mg", dosePerIntake = 2.0, doseUnit = "mg",
            dailyFrequency = 10, startEpochDay = LocalDate.now().minusDays(1).toEpochDay(),
            forever = true, prn = true,
        )) as SubstanceSaveOutcome.Saved).id
        val id = host.allocateAppWidgetId()
        var handedOff = false
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
            try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, SubstancesWidgetProvider::class.java))) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            SubstancesWidgetPrefs.save(context, id, target)
            SubstancesWidgetProvider.updateWidgets(context, manager, intArrayOf(id))
            val view = createView(manager, id)
            awaitLabel(view, title)
            // Retain a real blank RemoteViews across the process boundary. The
            // verification must recover it through ordinary foreground startup.
            manager.updateAppWidget(id, RemoteViews(context.packageName, SubstancesR.layout.widget_substances_action).apply {
                setTextViewText(SubstancesR.id.widgetSubstancesLabel, "")
            })
            awaitLabel(view, "")
            assertEquals(0, db.dao().recentIntakes().count { it.substanceId == target })
            check(fixture.edit().putInt("widget", id).putLong("target", target).putString("title", title).commit())
            handedOff = true
        } finally {
            if (!handedOff) {
                host.deleteAppWidgetId(id)
                SubstancesWidgetPrefs.delete(context, intArrayOf(id))
            }
        }
    }

    @Test fun existingWidgetRecoversOnNormalForegroundWithoutRecording() = runBlocking {
        val id = fixture.getInt("widget", AppWidgetManager.INVALID_APPWIDGET_ID)
        assertTrue("The same owned fixture survives process restart", id != AppWidgetManager.INVALID_APPWIDGET_ID)
        val target = fixture.getLong("target", -1)
        val title = requireNotNull(fixture.getString("title", null))
        val manager = AppWidgetManager.getInstance(context)
        assertNotNull("Widget binding survives", manager.getAppWidgetInfo(id))
        val view = createView(manager, id)
        awaitLabel(view, "")
        // Launch the normal product entry point, never its widget refresh helper.
        ActivityScenario.launch<ComponentActivity>(Intent().setClassName(
            context, "com.gernalix.personalhub.MainActivity")).use {
            awaitLabel(view, title)
            assertEquals(target, SubstancesWidgetPrefs.read(context, id))
            assertEquals(0, PersonalHubDatabase.get(context).dao().recentIntakes().count { it.substanceId == target })
        }
        host.deleteAppWidgetId(id)
        SubstancesWidgetPrefs.delete(context, intArrayOf(id))
        check(fixture.edit().clear().commit())
    }

    private fun createView(manager: AppWidgetManager, id: Int): AppWidgetHostView {
        lateinit var result: AppWidgetHostView
        instrumentation.runOnMainSync {
            result = host.createView(context, id, requireNotNull(manager.getAppWidgetInfo(id)))
        }
        return result
    }

    private fun awaitLabel(view: AppWidgetHostView, title: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var matches = false
            instrumentation.runOnMainSync {
                matches = view.findViewById<TextView>(SubstancesR.id.widgetSubstancesLabel)?.text?.toString() == title
            }
            if (matches) return
            SystemClock.sleep(50)
        }
        fail("Normal foreground did not restore the existing widget label")
    }
}
