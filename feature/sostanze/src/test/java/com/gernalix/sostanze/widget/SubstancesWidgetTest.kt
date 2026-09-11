package com.gernalix.sostanze.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.data.IntakeOutcome
import com.gernalix.sostanze.data.SostanzeRepository
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.SubstanceSaveOutcome
import com.gernalix.sostanze.data.SubstanceTypes
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubstancesWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun selectedSubstanceSurvivesWidgetPreferenceReloadAndRename() = database { db, repository ->
        val id = save(repository, "Widget Vitamin")
        SubstancesWidgetPrefs.save(context, 81_001, id)

        assertEquals(id, SubstancesWidgetPrefs.read(context, 81_001))

        val original = db.dao().substanceById(id)!!
        db.dao().updateSubstance(original.copy(name = "Widget Vitamin Renamed"))
        val choices = SubstancesWidgetPicker.choices(db.dao().allSubstances())
        assertEquals("Widget Vitamin Renamed", choices.single { it.substanceId == id }.title)
    }

    @Test fun widgetTapCreatesExactlyOneCanonicalIntakeAndPreservesZeroStockSemantics() = database { db, repository ->
        val id = save(repository, "Widget Zero", stock = 0.0)
        var exportRequests = 0

        val result = SubstancesWidgetTapRunner(repository) { exportRequests++ }.run(id)

        assertTrue(result is SubstancesWidgetTapResult.Recorded)
        assertEquals("Widget Zero", (result as SubstancesWidgetTapResult.Recorded).title)
        assertEquals(1, db.dao().recentIntakes().count { it.substanceId == id })
        assertEquals(0.0, db.dao().substanceById(id)!!.stockCurrent, 0.0)
        assertEquals(0.0, db.dao().intakeById(result.intakeId)!!.appliedStockDelta, 0.0)
        assertEquals(1, exportRequests)
    }

    @Test fun unavailableTargetDoesNotRecordOrReportSuccess() = database { db, repository ->
        val id = save(repository, "Widget Archived")
        repository.archiveSubstance(id)
        var exportRequests = 0

        val result = SubstancesWidgetTapRunner(repository) { exportRequests++ }.run(id)

        assertEquals(SubstancesWidgetTapResult.Unavailable, result)
        assertEquals(0, db.dao().recentIntakes().count { it.substanceId == id })
        assertEquals(0, exportRequests)
    }

    private fun database(block: suspend (PersonalHubDatabase, SostanzeRepository) -> Unit) = runBlocking {
        val name = "substances-widget-${UUID.randomUUID()}.db"
        val db = PersonalHubDatabase.openTemporary(context, name)
        try { block(db, SostanzeRepository(db)) } finally { db.close(); context.deleteDatabase(name) }
    }

    private suspend fun save(
        repository: SostanzeRepository,
        name: String,
        stock: Double = 10.0
    ): Long =
        (repository.saveSubstance(substance(name, stock)) as SubstanceSaveOutcome.Saved).id

    private fun substance(name: String, stock: Double) = SubstanceEntity(
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
}
