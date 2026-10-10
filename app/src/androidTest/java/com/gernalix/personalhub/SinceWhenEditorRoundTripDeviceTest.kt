package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

/** Production create/edit UI on the isolated QA app; no real counter or real-package writes. */
@RunWith(AndroidJUnit4::class)
class SinceWhenEditorRoundTripDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = PersonalHubDatabase.get(context)
    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun click(tag: String) { node(tag).performScrollTo().performClick() }
    private fun time(tag: String, hour: String, minute: String) {
        click(tag)
        node("sincewhen-hour").performScrollTo().performTextReplacement(hour)
        node("sincewhen-minute").performTextReplacement(minute)
        node("sincewhen-time-confirm").performClick()
    }
    private fun waitEditor() { compose.waitUntil(10000) { compose.onAllNodesWithTag("sincewhen-title").fetchSemanticsNodes().isNotEmpty() } }
    private fun row(title: String): SinceWhenCounterEntity = runBlocking { db.sinceWhenCounterDao().all().single { it.title == title } }
    private fun awaitSaved(title: String) { compose.waitUntil(10000) { runBlocking { db.sinceWhenCounterDao().all().any { it.title == title } } && compose.onAllNodesWithTag("sincewhen-save").fetchSemanticsNodes().isEmpty() } }

    @Test fun autonomousCreateSaveReopenEditReadbackPreservesAllFieldsAndLegacyProvenance() {
        check(context.packageName == "com.gernalix.personalhub.qa")
        val italian = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.ITALIAN) })
        assertEquals("Nome del contatore", italian.getString(com.gernalix.personalhub.core.ui.R.string.since_when_editor_counter_name))
        assertEquals("Unità di visualizzazione", italian.getString(com.gernalix.personalhub.core.ui.R.string.since_when_editor_units))
        db.openHelper.writableDatabase
        initializeHubContextRuntime(context)
        val suffix = System.currentTimeMillis().toString()
        val title = "B6 QA $suffix"
        val tags = HubContextRuntime.tags()
        val tag = runBlocking { tags.getOrCreate(HubTagNamespaces.SINCE_WHEN, "B6 Independent $suffix") }
        val timerTag = runBlocking { tags.getOrCreate(HubTagNamespaces.TIMER_NOW, "B6 Timer $suffix") }
        val scenario = ActivityScenario.launch<SinceWhenActivity>(Intent(context, SinceWhenActivity::class.java))
        try {
            node("sincewhen-create").performClick()
            waitEditor()
            node("sincewhen-title").performTextReplacement(title)
            node("sincewhen-description").performTextReplacement("Created through autonomous UI")
            time("sincewhen-start", "9", "17")
            click("sincewhen-color-4281032106")
            click("sincewhen-unit-HOURS")
            click("sincewhen-unit-DAYS")
            click("sincewhen-tag-${tag.id}")
            compose.onNodeWithText(timerTag.name).assertDoesNotExist()
            node("sincewhen-save").performClick()
            awaitSaved(title)
            val created = row(title)
            assertNull(created.endTimestamp)
            assertEquals(0xFF2B5DAAL, created.colorArgb)
            assertEquals("[\"HOURS\"]", created.displayUnitsJson)
            assertEquals(9, Instant.ofEpochMilli(created.initialTimestamp).atZone(ZoneId.systemDefault()).hour)
            assertEquals(17, Instant.ofEpochMilli(created.initialTimestamp).atZone(ZoneId.systemDefault()).minute)
            assertEquals(listOf(tag.id), runBlocking { tags.tags(HubEntityRef("since_when", "counter", created.id.toString())).map { it.id } })
            scenario.recreate()
            node("sincewhen-edit-${created.id}").performClick()
            waitEditor()
            node("sincewhen-description").assertTextContains("Created through autonomous UI")
            click("sincewhen-color-4281032106") // selected color stays selected
            node("sincewhen-color-4281032106").assertIsSelected()
            time("sincewhen-start", "13", "47")
            click("sincewhen-has-end")
            time("sincewhen-end", "23", "52")
            node("sincewhen-title").performScrollTo().performTextReplacement("$title edited")
            node("sincewhen-save").performClick()
            awaitSaved("$title edited")
            val edited = row("$title edited")
            assertEquals(listOf(tag.id), runBlocking { tags.tags(HubEntityRef("since_when", "counter", edited.id.toString())).map { it.id } })
            assertEquals(13, Instant.ofEpochMilli(edited.initialTimestamp).atZone(ZoneId.systemDefault()).hour)
            assertEquals(47, Instant.ofEpochMilli(edited.initialTimestamp).atZone(ZoneId.systemDefault()).minute)
            assertEquals(23, Instant.ofEpochMilli(edited.endTimestamp!!).atZone(ZoneId.systemDefault()).hour)
            assertEquals(52, Instant.ofEpochMilli(edited.endTimestamp!!).atZone(ZoneId.systemDefault()).minute)
            scenario.recreate()
            node("sincewhen-edit-${edited.id}").performClick()
            waitEditor()
            time("sincewhen-start", "13", "47") // unchanged picker must preserve milliseconds
            click("sincewhen-has-end")
            node("sincewhen-save").performClick()
            compose.waitUntil(10000) { runBlocking { db.sinceWhenCounterDao().get(edited.id)?.endTimestamp == null } }
            assertEquals(edited.copy(endTimestamp = null), runBlocking { db.sinceWhenCounterDao().get(edited.id) })
        } finally { scenario.close() }
        val legacy = SinceWhenCounterEntity(title = "B6 Legacy $suffix", description = "Legacy snapshot", initialTimestamp = Instant.parse("2026-02-10T13:47:23.456Z").toEpochMilli(), endTimestamp = Instant.parse("2026-02-11T18:29:45.678Z").toEpochMilli(), colorArgb = 0xFF9B3D5CL, displayUnitsJson = "[\"MINUTES\",\"SECONDS\"]", legacyTagIdsJson = "[42,73]", sourceEntityType = "timer/session", sourceEntityId = "b6-missing-source-$suffix", sourceTimestampField = "start", createdAt = 1790000000000)
        val id = runBlocking { db.sinceWhenCounterDao().insert(legacy) }
        val old = ActivityScenario.launch<SinceWhenActivity>(Intent(context, SinceWhenActivity::class.java).setData(Uri.parse("personalhub://sincewhen/v1/counter/$id")))
        try {
            waitEditor()
            node("sincewhen-title").performTextReplacement("${legacy.title} edited")
            node("sincewhen-save").performClick()
            awaitSaved("${legacy.title} edited")
            assertEquals(legacy.copy(id = id, title = "${legacy.title} edited"), runBlocking { db.sinceWhenCounterDao().get(id) })
            old.recreate()
            waitEditor()
            node("sincewhen-description").assertTextContains("Legacy snapshot")
            androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(context.cacheDir, "925611-editor.png"))
        } finally { old.close() }
    }
}
