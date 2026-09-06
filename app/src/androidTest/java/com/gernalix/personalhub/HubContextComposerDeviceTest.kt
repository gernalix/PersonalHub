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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubContextComposerDeviceTest {
    @Test fun personPrefillCreatesAndReopensSameContextToAddSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa"))
        check(android.os.Build.MODEL.contains("Pixel", ignoreCase = true))
        val people = PeopleHubAdapter(context)
        val places = PlacesHubAdapter(context)
        val timer = TimerSessionHubAdapter(context)
        HubContextRuntime.initialize(context, listOf(people, timer, places))
        val suffix = System.currentTimeMillis().toString()
        val person = runBlocking { requireNotNull(people.create(HubCreateRequest("UI Person $suffix"))) }
        val place = runBlocking { requireNotNull(places.create(HubCreateRequest("UI Place $suffix"))) }
        val sessionId = runBlocking { DefaultSessionCore(context).insertSession("UI Session $suffix", 10L, 20L, emptySet()) }
        val session = runBlocking { requireNotNull(timer.summaries(setOf(sessionId.toString()))[sessionId.toString()]) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(Intent().setClassName(context.packageName, "com.gernalix.personalhub.HubContextQaActivity")
            .putExtra("module", person.ref.moduleId).putExtra("kind", person.ref.entityKind).putExtra("id", person.ref.canonicalId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_link_action))
        click(device, "place")
        click(device, place.label)
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_save))
        val contextId = waitForContext(person.ref)

        click(device, place.label)
        click(device, "session")
        click(device, session.label)
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_save))

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && runBlocking { HubContextRuntime.context(contextId)?.members?.size } != 3) Thread.sleep(100)
        assertEquals(3, runBlocking { HubContextRuntime.context(contextId)?.members?.size })
        assertEquals(1, runBlocking { HubContextRuntime.contexts(person.ref).count { it.context.id == contextId } })
    }

    private fun click(device: UiDevice, text: String) {
        val objectRef = device.wait(Until.findObject(By.text(text)), 7_000)
            ?: error("Missing UI control: $text")
        objectRef.click()
        device.waitForIdle()
    }

    private fun waitForContext(ref: com.gernalix.personalhub.contracts.database.HubEntityRef): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            runBlocking { HubContextRuntime.contexts(ref).firstOrNull()?.context?.id }?.let { return it }
            Thread.sleep(100)
        }
        error("Context was not persisted")
    }
}
