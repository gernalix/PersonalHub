package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.hub.TimerSessionHubAdapter
import com.gernalix.luoghi.hub.PlacesHubAdapter
import com.gernalix.personalhub.contracts.database.HubCreateRequest
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.supercontacts.app.hub.PeopleHubAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubContextExplorerDeviceTest {
    @Test fun recursiveScopeNarrowsAndBackRestoresOnPixel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa"))
        check(android.os.Build.MODEL.contains("Pixel", ignoreCase = true))
        val people = PeopleHubAdapter(context)
        val places = PlacesHubAdapter(context)
        val timer = TimerSessionHubAdapter(context)
        HubContextRuntime.initialize(context, listOf(people, timer, places))
        val suffix = System.currentTimeMillis().toString()
        val giovanni = runBlocking { requireNotNull(people.create(HubCreateRequest("Giovanni $suffix"))) }
        val piazza = runBlocking { requireNotNull(places.create(HubCreateRequest("Piazza Savona $suffix"))) }
        val oscar = runBlocking { requireNotNull(places.create(HubCreateRequest("Oscar Cafe $suffix"))) }
        val sessionA = session(context, timer, "12/02 $suffix", 10, 20)
        val sessionB = session(context, timer, "17/05 $suffix", 30, 40)
        val sessionX = session(context, timer, "Session X $suffix", 50, 60)
        runBlocking {
            HubContextRuntime.createContext(listOf(giovanni.ref to "", piazza.ref to "", sessionA.ref to ""))
            HubContextRuntime.createContext(listOf(giovanni.ref to "", piazza.ref to "", sessionB.ref to ""))
            HubContextRuntime.createContext(listOf(giovanni.ref to "", oscar.ref to "", sessionX.ref to ""))
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent().setClassName(context.packageName, "com.gernalix.personalhub.HubContextQaActivity")
            .putExtra("module", giovanni.ref.moduleId).putExtra("kind", giovanni.ref.entityKind).putExtra("id", giovanni.ref.canonicalId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_explore_action))
        click(device, "${piazza.label} — 2")
        assertNotNull(device.wait(Until.findObject(By.text("${sessionA.label} — 1")), 5_000))
        assertNotNull(device.wait(Until.findObject(By.text("${sessionB.label} — 1")), 5_000))
        assertFalse(device.hasObject(By.textContains(sessionX.label)))
        click(device, "${sessionA.label} — 1")
        device.pressBack()
        assertNotNull(device.wait(Until.findObject(By.text("${sessionB.label} — 1")), 5_000))
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_composer_from_scope))
        assertNotNull(device.wait(Until.findObject(By.text(giovanni.label)), 5_000))
        assertNotNull(device.wait(Until.findObject(By.text(piazza.label)), 5_000))
    }

    private fun session(context: Context, adapter: TimerSessionHubAdapter, title: String, start: Long, end: Long) = runBlocking {
        val id = DefaultSessionCore(context).insertSession(title, start, end, emptySet())
        requireNotNull(adapter.summaries(setOf(id.toString()))[id.toString()])
    }

    private fun click(device: UiDevice, text: String) {
        val target = device.wait(Until.findObject(By.text(text)), 7_000) ?: error("Missing UI control: $text")
        target.click()
        device.waitForIdle()
    }
}
