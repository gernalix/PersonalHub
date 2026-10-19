package com.gernalix.personalhub

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.data.SostanzeRepository
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.SubstanceSaveOutcome
import com.gernalix.sostanze.data.SubstanceTypes
import com.gernalix.sostanze.hub.SubstancesWidgetsRuntime
import com.gernalix.sostanze.widget.SubstancesWidgetPrefs
import com.gernalix.sostanze.widget.SubstancesWidgetProvider
import com.gernalix.sostanze.R as SubstancesR
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SubstancesWidgetHostDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var host: AppWidgetHost
    private val ownedIds = mutableListOf<Int>()
    private val views = mutableMapOf<Int, AppWidgetHostView>()

    @Before fun setUp() {
        assertTrue("Widget recording tests require an isolated QA package",
            context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa(?:\\d+)?")))
        assertTrue("Widget recording tests require an emulator",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        host = AppWidgetHost(context, 762451)
        host.startListening()
    }

    @After fun tearDown() {
        if (::host.isInitialized) {
            ownedIds.forEach { host.deleteAppWidgetId(it) }
            SubstancesWidgetPrefs.delete(context, ownedIds.toIntArray())
            host.stopListening()
        }
    }

    @Test fun twoWidgetInstancesTargetAndRecordTwoSubstancesOnceEach() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, SubstancesWidgetProvider::class.java)
        assertNotNull("Substances provider is available to real widget hosts",
            manager.installedProviders.firstOrNull { it.provider == provider })
        val db = PersonalHubDatabase.get(context)
        val repository = SostanzeRepository(db)
        val suffix = UUID.randomUUID().toString().take(8)
        val firstId = save(repository, "Widget QA Alpha $suffix", 0.0)
        val secondId = save(repository, "Widget QA Beta $suffix", 4.0)
        val firstWidget = bindWidget(manager, provider)
        val secondWidget = bindWidget(manager, provider)
        launchHost(manager, listOf(firstWidget, secondWidget)).use { scenario ->
            SubstancesWidgetPrefs.save(context, firstWidget, firstId)
            SubstancesWidgetPrefs.save(context, secondWidget, secondId)
            SubstancesWidgetsRuntime.restoreActiveProfile(context)
            awaitLabel(firstWidget, "Widget QA Alpha $suffix")
            awaitLabel(secondWidget, "Widget QA Beta $suffix")
            capture("substances-widgets-bound.png")
            scenario.onActivity {
                assertTrue(views.getValue(firstWidget).findViewById<android.view.View>(SubstancesR.id.widgetSubstancesRoot).performClick())
            }
            awaitIntake(db, firstId)
            scenario.onActivity {
                assertTrue(views.getValue(secondWidget).findViewById<android.view.View>(SubstancesR.id.widgetSubstancesRoot).performClick())
            }
            awaitIntake(db, secondId)
            assertEquals(1, db.dao().recentIntakes().count { it.substanceId == firstId })
            assertEquals(1, db.dao().recentIntakes().count { it.substanceId == secondId })
            assertEquals(0.0, db.dao().substanceById(firstId)!!.stockCurrent, 0.0)
            assertEquals(2.0, db.dao().substanceById(secondId)!!.stockCurrent, 0.0)
        }
    }

    @Test fun blankBoundWidgetIsRehydratedWithoutRecordingAnIntake() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val db = PersonalHubDatabase.get(context)
        val title = "Widget QA Restore ${UUID.randomUUID().toString().take(8)}"
        val target = save(SostanzeRepository(db), title, 4.0)
        val id = bindWidget(manager, ComponentName(context, SubstancesWidgetProvider::class.java))
        SubstancesWidgetPrefs.save(context, id, target)
        launchHost(manager, listOf(id)).use {
            // Reproduce a host placeholder whose label and action have not been restored.
            manager.updateAppWidget(id, RemoteViews(context.packageName, SubstancesR.layout.widget_substances_action).apply {
                setTextViewText(SubstancesR.id.widgetSubstancesLabel, "")
            })
            awaitLabel(id, "")
            val before = db.dao().recentIntakes().count { it.substanceId == target }
            SubstancesWidgetsRuntime.restoreActiveProfile(context)
            awaitLabel(id, title)
            capture("substances-widget-rehydrated.png")
            assertNotNull(manager.getAppWidgetInfo(id))
            assertEquals(target, SubstancesWidgetPrefs.read(context, id))
            assertEquals(before, db.dao().recentIntakes().count { it.substanceId == target })
            instrumentation.runOnMainSync {
                assertTrue(views.getValue(id).findViewById<android.view.View>(SubstancesR.id.widgetSubstancesRoot).hasOnClickListeners())
            }
        }
    }

    private fun bindWidget(manager: AppWidgetManager, provider: ComponentName): Int {
        val id = host.allocateAppWidgetId()
        ownedIds += id
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        try {
            assertTrue("The QA widget is bound to its actual provider", manager.bindAppWidgetIdIfAllowed(id, provider))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        assertNotNull(manager.getAppWidgetInfo(id))
        return id
    }

    private fun launchHost(manager: AppWidgetManager, ids: List<Int>): ActivityScenario<ComponentActivity> {
        val scenario = ActivityScenario.launch<ComponentActivity>(Intent().setClassName(
            context, "com.gernalix.personalhub.WidgetHostQaActivity"))
        scenario.onActivity { activity ->
            val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            ids.forEach { id ->
                val view = host.createView(activity, id, requireNotNull(manager.getAppWidgetInfo(id)))
                views[id] = view
                root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 240))
            }
            activity.setContentView(root)
        }
        return scenario
    }

    private fun awaitLabel(id: Int, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var matches = false
            instrumentation.runOnMainSync {
                matches = views[id]?.findViewById<TextView>(SubstancesR.id.widgetSubstancesLabel)?.text?.toString() == expected
            }
            if (matches) return
            SystemClock.sleep(50)
        }
        fail("Actual bound widget did not display its expected label")
    }

    private suspend fun awaitIntake(db: PersonalHubDatabase, target: Long) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (db.dao().recentIntakes().any { it.substanceId == target }) return
            kotlinx.coroutines.delay(50)
        }
        fail("Actual widget PendingIntent did not record its synthetic intake")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(requireNotNull(context.getExternalFilesDir(null)), name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private suspend fun save(
        repository: SostanzeRepository,
        name: String,
        stock: Double
    ): Long =
        (repository.saveSubstance(
            SubstanceEntity(
                name = name,
                type = SubstanceTypes.FARMACO,
                stockCurrent = stock,
                stockUnit = "mg",
                dosePerIntake = 2.0,
                doseUnit = "mg",
                dailyFrequency = 10,
                startEpochDay = LocalDate.now().minusDays(1).toEpochDay(),
                forever = true,
                prn = true,
            )
        ) as SubstanceSaveOutcome.Saved).id
}
