package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceProduct
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.TimeZone

/** Append-only synthetic fixtures; restricted to an isolated QA package on an emulator. */
@RunWith(AndroidJUnit4::class)
class HistorySearchInputQaDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun afternoonDeepLinkKeepsItsBoundsAndFindsProductTransactions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        check(Build.MODEL.contains("sdk", ignoreCase = true) && Build.HARDWARE in setOf("goldfish", "ranchu"))
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Copenhagen"))
        try {
            val suffix = System.nanoTime().toString()
            val label = "Caffè QA $suffix"
            val at = Instant.parse("2026-11-04T17:30:00Z").toEpochMilli()
            val dao = PersonalHubDatabase.get(context).financeDao()
            runBlocking {
                val account = FinanceAccount(name = "History QA $suffix", currency = "DKK")
                dao.add(account)
                val productId = dao.add(FinanceProduct(name = label))
                dao.add(FinanceTransaction(accountId = account.id, titleId = null, productId = productId,
                    amount = "-3", currency = "DKK", chainId = null, placeId = null, notes = "",
                    occurredAt = at, createdAt = at, updatedAt = at))
            }
            val uri = HubDeepLinkContract.searchUri(
                fromIso = "2026-11-04T17:00:00Z", toIso = "2026-11-04T17:59:00Z",
                modules = listOf("soldi"), scopeModuleId = "soldi", query = "caf $suffix",
            )
            ActivityScenario.launch<HubDeepLinkActivity>(Intent(context, HubDeepLinkActivity::class.java).setData(uri)).use {
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("history-from").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("history-from").assertTextContains("2026-11-04 18:00")
                compose.onNodeWithTag("history-to").assertTextContains("2026-11-04 18:59")
                compose.onNodeWithTag("history-mode-records").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodesWithText(label, substring = true).onFirst().assertIsDisplayed()
            }
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }
}
