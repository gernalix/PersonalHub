package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    @Test fun allRegisteredModulesAndWorkflowyResourceUseOneExplorerAndComposerOnPixel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa"))
        check(android.os.Build.MODEL.contains("Pixel", ignoreCase = true))
        val db = PersonalHubDatabase.get(context)
        val people = PeopleHubAdapter(context)
        val places = PlacesHubAdapter(context)
        val soldi = SoldiTransactionHubAdapter(context)
        val substances = SubstanceHubAdapter(context)
        val words = WordSessionHubAdapter(context)
        val resources = ResourceHubAdapter(context)
        val adapters = listOf(people, places, soldi, substances, words, resources)
        HubContextRuntime.initialize(context, adapters)
        val suffix = System.currentTimeMillis().toString()
        val person = runBlocking { requireNotNull(people.create(HubCreateRequest("Giovanni $suffix"))) }
        val place = runBlocking { requireNotNull(places.create(HubCreateRequest("Piazza Savona $suffix"))) }
        val transactionUuid = UUID.randomUUID().toString()
        runBlocking {
            val finance = db.financeDao()
            val account = "qa-$suffix"
            finance.add(FinanceAccount(account, "QA", "EUR"))
            val title = finance.add(FinanceTitle(name = "Pranzo $suffix"))
            finance.add(FinanceTransaction(accountId = account, uuid = transactionUuid, titleId = title, productId = null, amount = "12.50", currency = "EUR", chainId = null, placeId = null, notes = "", occurredAt = "2026-02-12T12:00:00Z", createdAt = "2026-02-12T12:00:00Z", updatedAt = "2026-02-12T12:00:00Z"))
        }
        val transaction = runBlocking { requireNotNull(soldi.summaries(setOf(transactionUuid))[transactionUuid]) }
        val substanceId = runBlocking { db.dao().insertSubstance(SubstanceEntity(name = "Vitamina $suffix", canonicalName = "vitamina-$suffix", type = "integratore", stockCurrent = 10.0, stockUnit = "dose", dosePerIntake = 1.0, doseUnit = "dose", dailyFrequency = 1, startEpochDay = 20_000)) }
        val substance = runBlocking { requireNotNull(substances.summaries(setOf(substanceId.toString()))[substanceId.toString()]) }
        val wordId = "word-$suffix"
        runBlocking { db.wordPulseDao().insertSession(WordSession(wordId, System.currentTimeMillis(), null)) }
        val word = runBlocking { requireNotNull(words.summaries(setOf(wordId))[wordId]) }
        val workflowy = runBlocking { requireNotNull(resources.create(HubCreateRequest("Workflowy note $suffix", mapOf("kind" to HubResourceKinds.ANDROID_URI, "value" to "workflowy://note/$suffix")))) }
        val unsupported = runBlocking { requireNotNull(resources.create(HubCreateRequest("Unsupported link $suffix", mapOf("kind" to HubResourceKinds.ANDROID_URI, "value" to "no-handler-$suffix://item")))) }
        runBlocking { HubContextRuntime.createContext(listOf(transaction, person, place, substance, word, workflowy, unsupported).map { it.ref to "" }) }

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.pressHome()
        context.startActivity(Intent().setClassName(context.packageName, "com.gernalix.personalhub.HubContextQaActivity")
            .putExtra("module", transaction.ref.moduleId).putExtra("kind", transaction.ref.entityKind).putExtra("id", transaction.ref.canonicalId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 7_000)) { "QA host did not reach foreground" }
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_explore_action))
        listOf(person, place, substance, word, workflowy, unsupported).forEach { member ->
            assertNotNull(device.wait(Until.findObject(By.text("${member.label} — 1")), 7_000))
        }
        Intents.init()
        try {
            val workflowyIntent = allOf(hasAction(Intent.ACTION_VIEW), hasData("workflowy://note/$suffix"))
            intending(workflowyIntent).respondWith(ActivityResult(Activity.RESULT_OK, null))
            openFor(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_open_named, workflowy.label)).click()
            intended(workflowyIntent)
        } finally { Intents.release() }
        openFor(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_open_named, unsupported.label)).click()
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_resource_no_handler))), 7_000))
        click(device, "${person.label} — 1")
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_composer_from_scope))
        listOf(transaction, person).forEach { member -> assertNotNull(device.wait(Until.findObject(By.text(member.label)), 7_000)) }
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_manage_templates))
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_templates_title))), 7_000))
        val nameField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7_000)
        assertNotNull(nameField)
        nameField.text = "QA Type $suffix"
        click(device, "transaction")
        device.swipe(540, 1_800, 540, 650, 20)
        device.waitForIdle()
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_add_field))
        device.swipe(540, 1_800, 540, 650, 20)
        device.waitForIdle()
        click(device, context.getString(com.gernalix.personalhub.core.hubcontext.R.string.hub_save))
        assertTrue(runBlocking { HubContextRuntime.contextTypes().any { it.name == "QA Type $suffix" } })
    }

    private fun click(device: UiDevice, text: String) {
        val target = device.wait(Until.findObject(By.text(text)), 7_000) ?: error("Missing UI control: $text")
        target.click()
        device.waitForIdle()
    }

    private fun openFor(device: UiDevice, description: String) =
        device.wait(Until.findObject(By.desc(description)), 7_000) ?: error("Missing open action: $description")
}
