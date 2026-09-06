package com.gernalix.sostanze

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.data.*
import com.gernalix.sostanze.domain.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SostanzeCampaignTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun database(block: suspend (PersonalHubDatabase, SostanzeRepository) -> Unit) = runBlocking {
        val name = "substances-${UUID.randomUUID()}.db"
        val db = PersonalHubDatabase.openTemporary(context, name)
        try { block(db, SostanzeRepository(db)) } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun substance(name: String = "Medicine", stock: Double = 10.0) = SubstanceEntity(
        name = name, type = SubstanceTypes.FARMACO, stockCurrent = stock, stockUnit = "mg",
        dosePerIntake = 2.0, doseUnit = "mg", dailyFrequency = 2,
        startEpochDay = LocalDate.now().minusDays(1).toEpochDay(), forever = true,
    )

    @Test fun canonicalIdentityTrueUpdateArchiveAndNoSeed() = database { db, repository ->
        repository.initialize()
        assertEquals(0, db.dao().substanceCount())
        val id = (repository.saveSubstance(substance("  Vitamin D  ")) as SubstanceSaveOutcome.Saved).id
        assertTrue(repository.saveSubstance(substance("vitamin d")) is SubstanceSaveOutcome.Duplicate)
        val event = (repository.recordIntake(id, idempotencyKey = "first") as IntakeOutcome.Recorded).id
        val original = db.dao().substanceById(id)!!
        assertTrue(repository.saveSubstance(original.copy(name = "Vitamin D3")) is SubstanceSaveOutcome.Saved)
        assertEquals(event, db.dao().intakeById(event)!!.id)
        repository.archiveSubstance(id)
        assertEquals(IntakeOutcome.Archived, repository.recordIntake(id, idempotencyKey = "archived"))
        assertTrue(repository.saveSubstance(substance(" vitamin d3 ")) is SubstanceSaveOutcome.RestoreRequired)
        repository.restoreSubstance(id)
        assertFalse(db.dao().substanceById(id)!!.archived)
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun stockLedgerIdempotencyHistoryEditDeleteAndPrescriptionUndoAreExact() = database { db, repository ->
        val id = (repository.saveSubstance(substance()) as SubstanceSaveOutcome.Saved).id
        val prescriptionId = repository.savePrescription(
            PrescriptionEntity(substanceId = id, prescriptionEpochDay = LocalDate.now().toEpochDay(),
                quantityPrescribed = 3.0, refillEveryMonths = 1, orderEpochDay = LocalDate.now().toEpochDay(),
                packageDoseCount = 3, remainingDoses = 3, doseMg = 2.0)
        )
        val intakeId = (repository.recordIntake(id, idempotencyKey = "tap") as IntakeOutcome.Recorded).id
        assertEquals(IntakeOutcome.Duplicate, repository.recordIntake(id, idempotencyKey = "tap"))
        assertEquals(8.0, db.dao().substanceById(id)!!.stockCurrent, 0.0)
        assertEquals(2, db.dao().prescriptionById(prescriptionId)!!.remainingDoses)
        assertEquals(IntakeEditOutcome.Updated, repository.editIntake(intakeId, System.currentTimeMillis() - 1000, 2.0))
        assertEquals(6.0, db.dao().substanceById(id)!!.stockCurrent, 0.0)
        assertTrue(repository.deleteIntake(intakeId))
        assertEquals(10.0, db.dao().substanceById(id)!!.stockCurrent, 0.0)
        assertEquals(3, db.dao().prescriptionById(prescriptionId)!!.remainingDoses)
        assertTrue(repository.historyPage(10).isEmpty())
        assertEquals(StockOutcome.Insufficient(10.0), repository.adjustStock(id, -11.0, null))
        assertEquals(StockOutcome.NoChange, repository.adjustStock(id, 0.0, null))
        assertTrue(repository.setStock(id, 4.5) is StockOutcome.Applied)
        assertEquals(4.5, db.dao().substanceById(id)!!.stockCurrent, 0.0)
    }

    @Test fun sameNamePrescriptionsReuseOneCanonicalSubstanceAndRemainIndependent() = database { db, repository ->
        val today = LocalDate.now().toEpochDay()
        val first = repository.createPrescription(PrescriptionDraft(" Aspirin ", 20, doseMg = 100.0, frequencyPeriod = "DAY", frequencyCount = 1, orderEpochDay = today - 1))
        val second = repository.createPrescription(PrescriptionDraft("aspirin", 30, doseMg = 100.0, frequencyPeriod = "WEEK", frequencyCount = 2, orderEpochDay = today))
        assertEquals(1, db.dao().substanceCount())
        assertNotEquals(first, second)
        repository.savePrescription(db.dao().prescriptionById(first)!!.copy(remainingDoses = 9))
        assertEquals(30, db.dao().prescriptionById(second)!!.remainingDoses)
        repository.deletePrescription(first)
        assertNotNull(db.dao().prescriptionById(second))
        assertTrue(repository.recentMatchingCosts("aspirin").size <= 5)
    }

    @Test fun editingSameInteractionTwicePersistsNewestValues() = database { db, repository ->
        val source = (repository.saveSubstance(substance("Source")) as SubstanceSaveOutcome.Saved).id
        repository.saveInteractionRule(InteractionRuleEntity(sourceSubstanceId = source, avoidBeforeHours = 1.0, avoidAfterHours = 2.0), InteractionTargetKinds.ALL_PRESENT_AND_FUTURE, null)
        val id = db.dao().allInteractionRules().single().id
        repository.saveInteractionRule(InteractionRuleEntity(id, source, 3.0, 4.0), InteractionTargetKinds.ALL_PRESENT_AND_FUTURE, null)
        repository.saveInteractionRule(InteractionRuleEntity(id, source, 5.0, 6.0), InteractionTargetKinds.ALL_PRESENT_AND_FUTURE, null)
        assertEquals(5.0, db.dao().allInteractionRules().single().avoidBeforeHours, 0.0)
        assertEquals(6.0, db.dao().allInteractionRules().single().avoidAfterHours, 0.0)
        assertEquals(1, db.dao().allInteractionTargets().size)
    }

    @Test fun scheduleInteractionAndDepletionBoundariesAreDeterministic() {
        val zone = ZoneId.of("Europe/Copenhagen")
        val date = LocalDate.of(2026, 9, 7) // Monday
        val plan = substance().copy(id = 1, doseTimesCsv = "08:00,20:00", daysMask = 1).toPlan()
        assertTrue(SostanzeEngine.isRegimenActive(plan, date))
        assertFalse(SostanzeEngine.isRegimenActive(plan, date.plusDays(1)))
        assertEquals(2, SostanzeEngine.scheduledTimesMs(plan, date, zone).size)
        assertEquals(date.plusDays(5), SostanzeEngine.depletionDate(10, 2, "DAY", date))
        assertEquals(date.plusDays(14), SostanzeEngine.depletionDate(4, 2, "WEEK", date))
        val now = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val source = plan.copy(id = 2, name = "Source")
        val rule = InteractionRulePlan(9, 2, 0.0, 2.0, InteractionEnforcementMode.BLOCK, false, setOf(1))
        val state = SostanzeEngine.doseState(plan, listOf(IntakeRecord(1, 2, now - 60_000, 1.0)), listOf(rule), listOf(plan, source), now, zone)
        assertEquals(DoseSection.BLOCKED, state.section)
        assertTrue(state.canRecord) // command returns Blocked after the tap
        assertEquals(1, SostanzeEngine.interactionEndNotifications(listOf(state, state)).size)
        assertTrue(SostanzeEngine.missedDoseNotifications(listOf(state), now).isEmpty())
    }

    @Test fun versionFiveMigrationPreservesHistoricalDuplicateRowsAndChildren() {
        val name = "substances-v5-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(
            context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/5.json")
                .bufferedReader().use { it.readText() }
        ).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO hub_generation VALUES(1,10)")
            old.execSQL("INSERT INTO substances(id,name,type,stock_current,stock_unit,dose_per_intake,dose_unit,daily_frequency,start_epoch_day,end_epoch_day,forever,archived,prn) VALUES(1,'Same','farmaco',10,'mg',1,'mg',1,1,NULL,1,0,0)")
            old.execSQL("INSERT INTO substances(id,name,type,stock_current,stock_unit,dose_per_intake,dose_unit,daily_frequency,start_epoch_day,end_epoch_day,forever,archived,prn) VALUES(2,' same ','farmaco',10,'mg',1,'mg',1,1,NULL,1,0,0)")
            old.execSQL("INSERT INTO intake_events(id,substance_id,timestamp_ms,timestamp_utc,dose,dose_unit,tap_group_id) VALUES(1,2,1000,'1970-01-01T00:00:01Z',1,'mg','legacy')")
            old.version = 5
        }
        val db = PersonalHubDatabase.openTemporary(context, name)
        try {
            runBlocking {
                assertEquals(2, db.dao().substanceCount())
                assertNotNull(db.dao().intakeById(1))
                assertEquals(2, db.dao().substancesByCanonicalName("same").size)
            }
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
