package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.example.multitimetracker.TimeFenceRestoreReceiver
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.core.database.DatabaseGate
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only QA upgrade gate. CI seeds >700 records and >1000 mutations with
 * HistoryAndPatchReviewDeviceTest, then reinstalls the same .qa APK to deliver
 * ACTION_MY_PACKAGE_REPLACED. This never touches the production package/data.
 */
@RunWith(AndroidJUnit4::class)
class HistoryUpgradeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun requireQa() {
        assertEquals("com.gernalix.personalhub.qa", context.packageName)
    }

    private fun countRows(table: String): Int =
        PersonalHubDatabase.get(context).openHelper.readableDatabase
            .query("SELECT count(*) FROM $table").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }

    private fun waitLoaded(count: Int) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("history-loaded-count")
                .fetchSemanticsNodes().any { node ->
                    node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) {
                        emptyList()
                    }.any { it.text.contains(count.toString()) }
                }
        }
    }

    @Test fun upgradeBroadcastNeverWaitsForAnOccupiedDatabase() {
        requireQa()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            DatabaseGate.access {
                locked.countDown()
                release.await(4, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue("Could not acquire synthetic database lock", locked.await(3, TimeUnit.SECONDS))
        try {
            var elapsedMs = Long.MAX_VALUE
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val start = SystemClock.elapsedRealtime()
                TimeFenceRestoreReceiver().onReceive(
                    context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED),
                )
                elapsedMs = SystemClock.elapsedRealtime() - start
            }
            Log.i("PHHistoryUpgradeQa", "restore_broadcast_ms=$elapsedMs")
            assertTrue("Update receiver blocked main thread: ${elapsedMs}ms", elapsedMs < 1_500)
        } finally {
            release.countDown()
            holder.join(5_000)
        }
        assertTrue(
            "Package-replacement restore was not queued",
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("timer-restore-active-profile")
                .get(5, TimeUnit.SECONDS).isNotEmpty(),
        )
    }

    @Test fun restoredCloneRetainsRealisticHistoryAndPagesResponsively() {
        requireQa()
        assertTrue("CI fixture missing: intakes", countRows("intake_events") >= 719)
        assertTrue("CI fixture missing: mutations", countRows("mutation_events") >= 1100)
        initializeHubContextRuntime(context)
        val uri = HubDeepLinkContract.searchUri(
            modules = listOf("substances"),
            scopeModuleId = "substances",
        )
        val scenario = ActivityScenario.launch<HubDeepLinkActivity>(
            Intent(context, HubDeepLinkActivity::class.java).setData(uri),
        )
        try {
            compose.onNodeWithTag("history-mode-records").performClick()
            waitLoaded(50)
            compose.onNodeWithTag("history-results")
                .performScrollToNode(hasTestTag("history-more"))
            val started = SystemClock.elapsedRealtime()
            compose.onNodeWithTag("history-more").performClick()
            waitLoaded(100)
            val elapsedMs = SystemClock.elapsedRealtime() - started
            Log.i("PHHistoryUpgradeQa", "after_upgrade_load_more_ms=$elapsedMs")
            assertTrue("Upgrade made paging too slow: ${elapsedMs}ms", elapsedMs < 12_000)
        } finally {
            scenario.close()
        }
    }
}
