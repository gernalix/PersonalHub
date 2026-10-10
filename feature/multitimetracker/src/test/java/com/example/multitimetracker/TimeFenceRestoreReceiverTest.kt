package com.example.multitimetracker

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.gernalix.personalhub.core.database.DatabaseGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeFenceRestoreReceiverTest {
    @Test
    fun bootAndUpdateReturnWithoutWaitingForDatabaseAndCoalesceRestore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Keep the restore worker queued: this test exercises broadcast dispatch, not alarms.
        WorkManager.initialize(context, Configuration.Builder().setExecutor { }.build())
        val work = WorkManager.getInstance(context)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            DatabaseGate.access {
                locked.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(locked.await(2, TimeUnit.SECONDS))
        try {
            val receiver = TimeFenceRestoreReceiver()
            val start = System.nanoTime()
            receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
            assertTrue("Broadcast waited for the canonical database", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            assertEquals(1, work.getWorkInfosForUniqueWork("timer-restore-active-profile").get(2, TimeUnit.SECONDS).size)
        } finally {
            release.countDown()
            holder.join(2_000)
            work.cancelUniqueWork("timer-restore-active-profile").result.get(2, TimeUnit.SECONDS)
        }
    }
}
