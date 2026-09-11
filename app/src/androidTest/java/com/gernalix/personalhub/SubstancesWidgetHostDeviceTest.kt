package com.gernalix.personalhub

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.data.SostanzeRepository
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.SubstanceSaveOutcome
import com.gernalix.sostanze.data.SubstanceTypes
import com.gernalix.sostanze.widget.SubstancesWidgetPrefs
import com.gernalix.sostanze.widget.SubstancesWidgetProvider
import com.gernalix.sostanze.widget.SubstancesWidgetTapRunner
import com.gernalix.sostanze.widget.SubstancesWidgetTapResult
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubstancesWidgetHostDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var host: AppWidgetHost

    @Before fun setUp() {
        host = AppWidgetHost(context, 762451)
        host.startListening()
    }

    @After fun tearDown() {
        host.stopListening()
    }

    @Test fun twoWidgetInstancesTargetAndRecordTwoSubstancesOnceEach() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, SubstancesWidgetProvider::class.java)
        val info = manager.installedProviders.firstOrNull { it.provider == provider }
        assertNotNull("Substances widget provider is available to widget hosts", info)

        val db = PersonalHubDatabase.get(context)
        val repository = SostanzeRepository(db)
        val suffix = UUID.randomUUID().toString().take(8)
        val firstId = save(repository, "Widget QA Alpha $suffix", stock = 0.0)
        val secondId = save(repository, "Widget QA Beta $suffix", stock = 4.0)
        val firstWidgetId = host.allocateAppWidgetId()
        val secondWidgetId = host.allocateAppWidgetId()
        try {
            SubstancesWidgetPrefs.save(context, firstWidgetId, firstId)
            SubstancesWidgetPrefs.save(context, secondWidgetId, secondId)
            SubstancesWidgetProvider.updateWidgets(context, manager, intArrayOf(firstWidgetId, secondWidgetId))

            var exportRequests = 0
            val first = SubstancesWidgetTapRunner(repository) { exportRequests++ }.run(firstId)
            val second = SubstancesWidgetTapRunner(repository) { exportRequests++ }.run(secondId)

            assertTrue(first is SubstancesWidgetTapResult.Recorded)
            assertTrue(second is SubstancesWidgetTapResult.Recorded)
            assertEquals("Widget QA Alpha $suffix", (first as SubstancesWidgetTapResult.Recorded).title)
            assertEquals("Widget QA Beta $suffix", (second as SubstancesWidgetTapResult.Recorded).title)
            assertEquals(1, db.dao().recentIntakes().count { it.substanceId == firstId })
            assertEquals(1, db.dao().recentIntakes().count { it.substanceId == secondId })
            assertEquals(0.0, db.dao().substanceById(firstId)!!.stockCurrent, 0.0)
            assertEquals(2.0, db.dao().substanceById(secondId)!!.stockCurrent, 0.0)
            assertEquals(2, exportRequests)
        } finally {
            SubstancesWidgetPrefs.delete(context, intArrayOf(firstWidgetId, secondWidgetId))
        }
    }

    private suspend fun save(
        repository: SostanzeRepository,
        name: String,
        stock: Double
    ): Long =
        (repository.saveSubstance(
            SubstanceEntity(
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
        ) as SubstanceSaveOutcome.Saved).id
}
