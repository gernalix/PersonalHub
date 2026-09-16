package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import android.app.Instrumentation.ActivityResult
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gernalix.luoghi.hub.PlacesHubAdapter
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.hub.TimerSessionHubAdapter
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceTitle
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransaction
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.ResourceHubAdapter
import com.gernalix.personalhub.soldi.hub.SoldiTransactionHubAdapter
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.hub.SubstanceHubAdapter
import com.supercontacts.app.hub.PeopleHubAdapter
import com.wordpulse.app.data.WordSession
import com.wordpulse.app.hub.WordSessionHubAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.hamcrest.Matchers.allOf

@RunWith(AndroidJUnit4::class)
class HubContextAllModulesDeviceTest {
    @Test fun allRegisteredModulesAndWorkflowyResourceUseOneExplorerAndComposerOnPhysicalQaDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa"))
        check(!android.os.Build.FINGERPRINT.startsWith("generic") && !android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val db = PersonalHubDatabase.get(context)
        val people = PeopleHubAdapter(context)
        val places = PlacesHubAdapter(context)
        val timer = TimerSessionHubAdapter(context)
        val soldi = SoldiTransactionHubAdapter(context)
        val substances = SubstanceHubAdapter(context)
        val words = WordSessionHubAdapter(context)
        val resources = ResourceHubAdapter(context)
        val adapters = listOf(people, timer, places, soldi, substances, words, resources)
        HubContextRuntime.initialize(context, adapters)
        val suffix = System.currentTimeMillis().toString()
        val person = runBlocking { requireNotNull(people.create(HubCreateRequest("Giovanni $suffix"))) }
        val place = runBlocking { requireNotNull(places.create(HubCreateRequest("Piazza Savona $suffix"))) }
        val timerId = DefaultSessionCore(context).insertSession("Timer $suffix", System.currentTimeMillis() - 60_000L, System.currentTimeMillis(), emptySet())
        val timerSession = runBlocking { requireNotNull(timer.summaries(setOf(timerId.toString()))[timerId.toString()]) }
        val transactionUuid = UUID.randomUUID().toString()
        val linkedTransactionUuid = UUID.randomUUID().toString()
        runBlocking {
            val finance = db.financeDao()
            val account = "qa-$suffix"
            finance.add(FinanceAccount(account, "QA", "EUR"))
            val title = finance.add(FinanceTitle(name = "Pranzo $suffix"))
            val linkedTitle = finance.add(FinanceTitle(name = "Cena $suffix"))
            finance.add(FinanceTransaction(accountId = account, uuid = transactionUuid, titleId = title, productId = null, amount = "12.50", currency = "EUR", chainId = null, placeId = null, notes = "", occurredAt = "2026-02-12T12:00:00Z", createdAt = "2026-02-12T12:00:00Z", updatedAt = "2026-02-12T12:00:00Z"))
            finance.add(FinanceTransaction(accountId = account, uuid = linkedTransactionUuid, titleId = linkedTitle, productId = null, amount = "18.50", currency = "EUR", chainId = null, placeId = null, notes = "", occurredAt = "2026-02-12T13:00:00Z", createdAt = "2026-02-12T13:00:00Z", updatedAt = "2026-02-12T13:00:00Z"))
        }
        val transaction = runBlocking { requireNotNull(soldi.summaries(setOf(transactionUuid))[transactionUuid]) }
        val linkedTransaction = runBlocking { requireNotNull(soldi.summaries(setOf(linkedTransactionUuid))[linkedTransactionUuid]) }
        val substanceId = runBlocking { db.dao().insertSubstance(SubstanceEntity(name = "Vitamina $suffix", canonicalName = "vitamina-$suffix", type = "integratore", stockCurrent = 10.0, stockUnit = "dose", dosePerIntake = 1.0, doseUnit = "dose", dailyFrequency = 1, startEpochDay = 20_000)) }
        val substance = runBlocking { requireNotNull(substances.summaries(setOf(substanceId.toString()))[substanceId.toString()]) }
        val wordId = "word-$suffix"
        runBlocking { db.wordPulseDao().insertSession(WordSession(wordId, System.currentTimeMillis(), null)) }
        val word = runBlocking { requireNotNull(words.summaries(setOf(wordId))[wordId]) }
        val workflowyUri = "https://workflowy.com/#/$suffix"
        val workflowy = runBlocking { requireNotNull(resources.create(HubCreateRequest("Workflowy note $suffix", mapOf("kind" to HubResourceKinds.ANDROID_URI, "value" to workflowyUri)))) }
        val unsupportedUri = "no-handler-$suffix://item"
        val unsupported = runBlocking { requireNotNull(resources.create(HubCreateRequest("Unsupported link $suffix", mapOf("kind" to HubResourceKinds.ANDROID_URI, "value" to unsupportedUri)))) }
        runBlocking { HubContextRuntime.createContext(listOf(transaction, linkedTransaction, person, timerSession, place, substance, word, workflowy, unsupported).map { it.ref to "" }) }

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        InstrumentationRegistry.getInstrumentation().startActivitySync(Intent().setClassName(context.packageName, "com.gernalix.personalhub.HubContextQaActivity")
            .putExtra("module", transaction.ref.moduleId).putExtra("kind", transaction.ref.entityKind).putExtra("id", transaction.ref.canonicalId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 7_000)) { "QA host did not reach foreground" }
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_explore_action))
        listOf(person, timerSession, place, linkedTransaction, substance, word).forEach { member ->
            openFor(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_open_named, member.label)).click()
            val exactDetail = "hub-detail-${member.ref.moduleId}/${member.ref.entityKind}/${member.ref.canonicalId}"
            val destination = if (member.ref.moduleId in setOf("people", "places")) {
                device.wait(Until.findObject(By.text(member.label)), 7_000)
            } else if (member.ref.moduleId == "wordpulse") {
                findTextContains(device, "Session ${member.ref.canonicalId.take(8)}")
            } else {
                device.wait(Until.findObject(By.desc(exactDetail)), 7_000)
            }
            assertNotNull("Exact detail missing for ${member.ref}", destination)
            returnToExplorer(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_explorer_title))
        }
        Intents.init()
        try {
            val workflowyIntent = allOf(hasAction(Intent.ACTION_VIEW), hasData(workflowyUri))
            intending(workflowyIntent).respondWith(ActivityResult(Activity.RESULT_OK, null))
            openFor(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_open_named, workflowy.label)).click()
            intended(workflowyIntent)
        } finally { Intents.release() }
        assertTrue(context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(unsupportedUri)), 0).isEmpty())
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_close))
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_explore_action))
        findText(device, "${person.label} — 1")?.click() ?: error("Missing scope candidate: ${person.label}")
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_composer_from_scope))
        listOf(transaction, person).forEach { member -> assertNotNull(device.wait(Until.findObject(By.text(member.label)), 7_000)) }
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_manage_templates))
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_templates_title))), 7_000))
        val nameField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7_000)
        assertNotNull(nameField)
        nameField.text = "QA Type $suffix"
        click(device, "transaction")
        findTextBelow(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_add_field))?.click()
            ?: error("Missing Add field control")
        findTextBelow(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_save))?.click()
            ?: error("Missing Save control")
        assertTrue(waitForContextType("QA Type $suffix"))
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(workflowyUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.gone(By.pkg(context.packageName).depth(0)), 7_000))
    }

    private fun click(device: UiDevice, text: String) {
        val target = device.wait(Until.findObject(By.text(text)), 7_000) ?: error("Missing UI control: $text")
        target.click()
        device.waitForIdle()
    }

    private fun openFor(device: UiDevice, description: String): androidx.test.uiautomator.UiObject2 {
        val x = device.displayWidth / 2
        repeat(20) {
            device.findObject(By.desc(description))?.let { return it }
            device.swipe(x, device.displayHeight * 4 / 5, x, device.displayHeight / 3, 12)
            device.waitForIdle()
        }
        error("Missing open action: $description")
    }

    private fun returnToExplorer(device: UiDevice, title: String) {
        repeat(12) {
            device.pressBack()
            device.waitForIdle()
            if (qaHostIsResumed() && device.wait(Until.findObject(By.text(title)), 2_000) != null) return
        }
        error("Explorer did not return after leaving exact detail")
    }

    private fun qaHostIsResumed(): Boolean {
        var resumed = false
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .any { it is HubContextQaActivity }
        }
        return resumed
    }

    private fun findTextContains(device: UiDevice, text: String): androidx.test.uiautomator.UiObject2? {
        val x = device.displayWidth / 2
        repeat(12) {
            device.findObject(By.textContains(text))?.let { return it }
            device.swipe(x, device.displayHeight * 4 / 5, x, device.displayHeight / 3, 12)
            device.waitForIdle()
        }
        return null
    }

    private fun findText(device: UiDevice, text: String): androidx.test.uiautomator.UiObject2? {
        val x = device.displayWidth / 2
        repeat(20) {
            device.findObject(By.text(text))?.let { return it }
            device.swipe(x, device.displayHeight / 3, x, device.displayHeight * 4 / 5, 12)
            device.waitForIdle()
        }
        return null
    }

    private fun findTextBelow(device: UiDevice, text: String): androidx.test.uiautomator.UiObject2? {
        val x = device.displayWidth / 2
        repeat(20) {
            device.findObject(By.text(text))?.let { return it }
            device.swipe(x, device.displayHeight * 2 / 3, x, device.displayHeight / 3, 12)
            device.waitForIdle()
        }
        return null
    }

    private fun waitForContextType(name: String): Boolean {
        repeat(50) {
            if (runBlocking { HubContextRuntime.contextTypes().any { it.name == name }) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }
}
