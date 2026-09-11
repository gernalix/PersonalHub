package com.gernalix.personalhub

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.multitimetracker.core.quickevent.DefaultQuickEventCore
import com.example.multitimetracker.core.quickevent.QuickEventTarget
import com.example.multitimetracker.widget.QuickEventWidgetClickActivity
import com.example.multitimetracker.widget.QuickEventWidgetPrefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickEventWidgetClickDeviceTest {
    @Test
    fun twoWidgetEventTapsCreateOneEntryEach() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = DefaultQuickEventCore(context)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()

        val suffix = System.currentTimeMillis().toString()
        val coffeeTitle = "Widget QA Coffee $suffix"
        val walkTitle = "Widget QA Walk $suffix"
        val coffeeTemplate = core.insertTemplate(coffeeTitle, emptySet(), 90_001, isArchived = false, fields = emptyList())
        val walkTemplate = core.insertTemplate(walkTitle, emptySet(), 90_002, isArchived = false, fields = emptyList())
        val coffeeWidgetId = 90_001
        val walkWidgetId = 90_002
        QuickEventWidgetPrefs.save(context, coffeeWidgetId, QuickEventTarget.Template(coffeeTemplate))
        QuickEventWidgetPrefs.save(context, walkWidgetId, QuickEventTarget.Template(walkTemplate))

        launchWidgetClick(context, coffeeWidgetId)
        device.waitForIdle()

        launchWidgetClick(context, walkWidgetId)
        device.waitForIdle()

        val entries = core.readSnapshot().entries.filter { it.templateId in setOf(coffeeTemplate, walkTemplate) && it.deletedAtMs == null }
        assertEquals(listOf(coffeeTitle), entries.filter { it.templateId == coffeeTemplate }.map { it.title })
        assertEquals(listOf(walkTitle), entries.filter { it.templateId == walkTemplate }.map { it.title })
    }

    private fun launchWidgetClick(context: Context, appWidgetId: Int) {
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, QuickEventWidgetClickActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
