package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.multitimetracker.hub.TimerSessionHubAdapter
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PersonalHubApplication::class)
class HubContextAllModulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        com.supercontacts.app.data.repository.AppContainer.resetForTests()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @After fun tearDown() {
        com.supercontacts.app.data.repository.AppContainer.resetForTests()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @Test fun oneGenericRegistrationCoversAllModulesResourcesReverseTraversalAndPortableSnapshot() = runBlocking {
        val db = PersonalHubDatabase.get(context)
        val people = PeopleHubAdapter(context)
        val places = PlacesHubAdapter(context)
        val timer = TimerSessionHubAdapter(context)
        val soldi = SoldiTransactionHubAdapter(context)
        val substances = SubstanceHubAdapter(context)
        val words = WordSessionHubAdapter(context)
        val resources = ResourceHubAdapter(context)
        HubContextRuntime.initialize(context, listOf(people, timer, places, soldi, substances, words, resources))
        assertEquals(
            setOf("people/person", "timer/session", "places/place", "soldi/transaction", "substances/substance", "wordpulse/word_session", "hub/resource", "tags/tag"),
            HubContextRuntime.adapters().map { "${it.moduleId}/${it.entityKind}" }.toSet(),
        )

        val person = requireNotNull(people.create(HubCreateRequest("Giovanni")))
        val place = requireNotNull(places.create(HubCreateRequest("Piazza Savona")))
        val finance = db.financeDao()
        finance.add(FinanceAccount(id = "cash", name = "Cash", currency = "EUR"))
        val titleId = finance.add(FinanceTitle(name = "Pranzo"))
        val transactionUuid = UUID.randomUUID().toString()
        val financeAt = Instant.parse("2026-02-12T12:00:00Z").toEpochMilli()
        finance.add(FinanceTransaction(accountId = "cash", uuid = transactionUuid, titleId = titleId, productId = null, amount = "12.50", currency = "EUR", chainId = null, placeId = null, notes = "", occurredAt = financeAt, createdAt = financeAt, updatedAt = financeAt))
        val transaction = requireNotNull(soldi.summaries(setOf(transactionUuid))[transactionUuid])
        val transactionSinceWhen = requireNotNull(soldi.sinceWhenSource(transactionUuid))
        assertEquals(listOf("transaction_date", "added_at"), transactionSinceWhen.timestampSources.map { it.id })
        assertEquals("transaction_date", transactionSinceWhen.timestampSources.single { it.isDefault }.id)
        val placeSinceWhen = requireNotNull(places.sinceWhenSource(place.ref.canonicalId))
        assertEquals(listOf("added_at"), placeSinceWhen.timestampSources.map { it.id })

        val substanceId = db.dao().insertSubstance(substance("Vitamina D", "vitamina d"))
        val secondSubstanceId = db.dao().insertSubstance(substance("Magnesio", "magnesio"))
        val substance = requireNotNull(substances.summaries(setOf(substanceId.toString()))[substanceId.toString()])
        val secondSubstance = requireNotNull(substances.summaries(setOf(secondSubstanceId.toString()))[secondSubstanceId.toString()])
        val wordSessionId = "word-${UUID.randomUUID()}"
        db.wordPulseDao().insertSession(WordSession(wordSessionId, 1_770_897_600_000L, 1_770_897_660_000L))
        val wordSession = requireNotNull(words.summaries(setOf(wordSessionId))[wordSessionId])

        val workflowy = requireNotNull(resources.create(HubCreateRequest("Workflowy note", mapOf("kind" to HubResourceKinds.ANDROID_URI, "value" to "workflowy://note/abc"))))
        val document = requireNotNull(resources.create(HubCreateRequest("Documento", mapOf("kind" to HubResourceKinds.SAF_DOCUMENT, "value" to "content://documents/document/primary:test.pdf", "persisted_permission" to "true"))))
        assertEquals("workflowy://note/abc", resources.openTarget(workflowy.ref.canonicalId)?.uri)
        assertEquals("true", document.attributes["persisted_permission"])

        val members = listOf(person, place, transaction, substance, secondSubstance, wordSession, workflowy)
        val tag = requireNotNull(HubContextRuntime.tags().create(HubTagNamespaces.GLOBAL, "First used test").tag)
        val unusedTagSource = requireNotNull(HubContextRuntime.adapter("tags", "tag").sinceWhenSource(tag.id))
        assertEquals(listOf("tag_created"), unusedTagSource.timestampSources.map { it.id })
        HubContextRuntime.tags().assign(person.ref, listOf(tag.id))
        val usedTagSource = requireNotNull(HubContextRuntime.adapter("tags", "tag").sinceWhenSource(tag.id))
        assertEquals(listOf("first_used", "tag_created"), usedTagSource.timestampSources.map { it.id })
        assertEquals("first_used", usedTagSource.timestampSources.single { it.isDefault }.id)
        val contextId = HubContextRuntime.createContext(members.map { it.ref to "" }, title = "12/02/2026")
        HubContextRuntime.createContext(listOf(place.ref to "", document.ref to ""))
        assertEquals(7, requireNotNull(HubContextRuntime.context(contextId)).members.size)
        assertEquals(2, requireNotNull(HubContextRuntime.context(contextId)).members.count { it.ref.entityKind == "substance" })
        listOf(transaction.ref, substance.ref, wordSession.ref).forEach { start ->
            assertTrue(HubContextRuntime.linked(start).any { it.ref == person.ref })
        }
        assertTrue(HubContextRuntime.explore(listOf(person.ref, place.ref)).facets.flatMap { it.candidates }.any { it.summary.ref == workflowy.ref })

        val now = System.currentTimeMillis()
        HubContextRuntime.saveContextType(
            HubContextType("finance-template", "Finance activity", now, now),
            listOf(HubContextTypeField("finance-template", "transaction", 0, "Transaction", acceptedModuleId = "soldi", acceptedEntityKind = "transaction", minCardinality = 1)),
        )
        assertEquals("transaction", HubContextRuntime.contextType("finance-template")!!.second.single().acceptedEntityKind)

        assertTrue(db.openHelper.readableDatabase.query("SELECT count(*) FROM hub_sync_pending WHERE table_name IN ('hub_contexts','hub_context_members','hub_resources')").use { it.moveToFirst(); it.getInt(0) > 0 })
        PersonalHubDatabase.closeInstance()
        HubContextRuntime.initialize(context, listOf(people, timer, places, soldi, substances, words, resources))
        assertEquals(7, requireNotNull(HubContextRuntime.context(contextId)).members.size)
        PersonalHubDatabase.get(context).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        PersonalHubDatabase.closeInstance()
        val snapshot = File(context.cacheDir, "hub-context-portable-${UUID.randomUUID()}.db")
        context.getDatabasePath(PersonalHubDatabase.DATABASE_NAME).copyTo(snapshot)
        try {
            val reopened = PersonalHubDatabase.openTemporary(context, snapshot.absolutePath)
            try {
                assertEquals(7, reopened.hubContextDao().memberCount(contextId))
                assertNotNull(reopened.hubResourceDao().resource(workflowy.ref.canonicalId))
                assertTrue(reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
            } finally { reopened.close() }
        } finally { snapshot.delete() }
    }

    private fun substance(name: String, canonical: String) = SubstanceEntity(
        name = name, canonicalName = canonical, type = "integratore", stockCurrent = 30.0, stockUnit = "dose",
        dosePerIntake = 1.0, doseUnit = "dose", dailyFrequency = 1, startEpochDay = 20_000,
    )
}
