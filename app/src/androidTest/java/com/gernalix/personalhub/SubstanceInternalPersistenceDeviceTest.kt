package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.data.SettingEntity
import com.gernalix.sostanze.data.SostanzeRepository
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.SubstanceSaveOutcome
import com.gernalix.sostanze.data.SubstanceTypes
import com.gernalix.sostanze.data.PrescriptionEntity
import com.gernalix.sostanze.data.InteractionRuleEntity
import com.gernalix.sostanze.data.InteractionTargetKinds
import com.gernalix.sostanze.data.MacroEntity
import com.gernalix.sostanze.domain.NotificationPlan
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Internal Substances writers with no direct entry editor. */
@RunWith(AndroidJUnit4::class)
class SubstanceInternalPersistenceDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @TableProbe("notification_state", "settings")
    @Test fun notificationPlanAndSettingPersistThroughProductionWriters() = runBlocking {
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val repository = SostanzeRepository(owner)
        val dao = owner.dao()
        val marker = "QA914263Internal${System.currentTimeMillis()}"
        val scheduledFor = System.currentTimeMillis() + 60_000
        try {
            repository.saveNotifications(listOf(NotificationPlan(marker, -914263L, scheduledFor)))
            val db = owner.openHelper.readableDatabase
            db.query(
                "SELECT scheduled_for_ms, scheduled_for_utc FROM notification_state WHERE kind=? AND entity_id=?",
                arrayOf(marker, -914263L),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(scheduledFor, cursor.getLong(0))
                assertEquals(scheduledFor, cursor.getLong(1))
            }

            dao.upsertSetting(SettingEntity(marker, "one"))
            assertEquals("one", dao.settingValue(marker))
            dao.upsertSetting(SettingEntity(marker, "two"))
            assertEquals("two", dao.settingValue(marker))
            owner.openHelper.readableDatabase.query(
                "SELECT value FROM settings WHERE `key`=?", arrayOf(marker),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("two", cursor.getString(0))
            }
        } finally {
            owner.openHelper.writableDatabase.execSQL("DELETE FROM notification_state WHERE kind=? AND entity_id=?", arrayOf(marker, -914263L))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM settings WHERE `key`=?", arrayOf(marker))
        }
    }

    @TableProbe("prescriptions", "interaction_rules", "interaction_targets", "macros", "macro_items")
    @Test fun relatedSubstanceRowsPersistThroughRepository() = runBlocking {
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val repository = SostanzeRepository(owner)
        val marker = "QA914263Relations${System.currentTimeMillis()}"
        val day = LocalDate.now().toEpochDay()
        val saved = repository.saveSubstance(SubstanceEntity(
            name = marker, type = SubstanceTypes.FARMACO, stockCurrent = 10.0,
            stockUnit = "mg", dosePerIntake = 1.0, doseUnit = "mg",
            dailyFrequency = 1, startEpochDay = day,
        )) as SubstanceSaveOutcome.Saved
        val substanceId = saved.id
        var prescriptionId = 0L
        var ruleId = 0L
        var macroId = 0L
        fun count(table: String, where: String, value: Long): Long =
            owner.openHelper.readableDatabase.query("SELECT count(*) FROM $table WHERE $where=?", arrayOf(value)).use {
                it.moveToFirst(); it.getLong(0)
            }
        try {
            prescriptionId = repository.savePrescription(PrescriptionEntity(
                substanceId = substanceId, prescriptionEpochDay = day,
                quantityPrescribed = 10.0, refillEveryMonths = 1,
                orderEpochDay = day, packageDoseCount = 10, remainingDoses = 10,
                doseMg = 1.0,
            ))
            assertEquals(1L, count("prescriptions", "id", prescriptionId))
            repository.savePrescription(requireNotNull(owner.dao().prescriptionById(prescriptionId)).copy(remainingDoses = 8))
            assertEquals(8, owner.dao().prescriptionById(prescriptionId)?.remainingDoses)

            repository.saveInteractionRule(
                InteractionRuleEntity(sourceSubstanceId = substanceId, avoidBeforeHours = 1.0, avoidAfterHours = 2.0),
                InteractionTargetKinds.ALL_PRESENT_AND_FUTURE, null,
            )
            ruleId = owner.dao().allInteractionRules().single { it.sourceSubstanceId == substanceId }.id
            assertEquals(1L, count("interaction_targets", "rule_id", ruleId))
            repository.saveInteractionRule(
                InteractionRuleEntity(id = ruleId, sourceSubstanceId = substanceId, avoidBeforeHours = 3.0, avoidAfterHours = 4.0),
                InteractionTargetKinds.ALL_PRESENT_AND_FUTURE, null,
            )
            assertEquals(3.0, owner.dao().allInteractionRules().single { it.id == ruleId }.avoidBeforeHours, 0.0)
            assertEquals(1L, count("interaction_targets", "rule_id", ruleId))

            macroId = repository.saveMacro(MacroEntity(name = marker), listOf(substanceId))
            assertEquals(1L, count("macro_items", "macro_id", macroId))
            repository.saveMacro(MacroEntity(id = macroId, name = "${marker}Edited"), listOf(substanceId))
            assertEquals(1L, count("macros", "id", macroId))
            assertEquals(1L, count("macro_items", "macro_id", macroId))
        } finally {
            if (macroId != 0L) repository.deleteMacro(macroId)
            if (ruleId != 0L) repository.deleteInteractionRule(ruleId)
            if (prescriptionId != 0L) repository.deletePrescription(prescriptionId)
            owner.openHelper.writableDatabase.execSQL("DELETE FROM substances WHERE id=?", arrayOf(substanceId))
            assertEquals(0L, count("substances", "id", substanceId))
        }
    }
}
