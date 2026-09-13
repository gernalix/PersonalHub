package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubAutoExportStartupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        HubAutoExport.resetSchedulerForTests()
    }

    @Test
    fun failedStartupCanBeRetried() {
        val scheduler = FlakyScheduler()
        HubAutoExport.setSchedulerForTests(scheduler)

        val firstFailure = runCatching { HubAutoExport.start(context) }.exceptionOrNull()
        assertNotNull(firstFailure)

        HubAutoExport.start(context)

        assertEquals(2, scheduler.periodicAttempts)
    }

    private class FlakyScheduler : HubAutoExport.Scheduler {
        var periodicAttempts = 0

        override fun cancelLegacyWork(context: Context) = Unit

        override fun enqueuePeriodicRecovery(context: Context) {
            periodicAttempts += 1
            if (periodicAttempts == 1) error("simulated startup failure")
        }

        override fun enqueueAutoExport(context: Context, policy: ExistingWorkPolicy) = Unit
    }
}
