package com.gernalix.personalhub.soldi

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.io.File

class BankingRenderTestActivity : ComponentActivity()

class BankingRenderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<BankingRenderTestActivity>()

    @Test fun rendersSyntheticAccountsComparableTotalsAndMovementState() {
        val revolut = BankingAccount("0123456789abcdef01234567", "Revolut", listOf("EUR"), listOf("ITAV", "OTHR"))
        val lunar = BankingAccount("abcdef0123456789abcdef01", "Lunar", listOf("DKK"), listOf("ITBD"))
        val snapshot = BankingSnapshot(
            accounts = listOf(revolut, lunar),
            balances = listOf(
                BankingBalance(revolut.id, revolut.bank, "ITAV", BigDecimal("100.00"), "EUR", null, null),
                BankingBalance(revolut.id, revolut.bank, "OTHR", BigDecimal("999.00"), "EUR", null, null),
                BankingBalance(lunar.id, lunar.bank, "ITBD", BigDecimal("750.00"), "DKK", LocalDate.of(2026, 10, 17), null),
            ),
            transactions = listOf(
                BankingTransaction("tx-book", revolut.id, revolut.bank, BigDecimal("-12.50"), "EUR", "DBIT", "BOOK", LocalDate.of(2026, 10, 17), "Synthetic shop"),
                BankingTransaction("tx-pending", lunar.id, lunar.bank, BigDecimal("25.00"), "DKK", "CRDT", "PDNG", LocalDate.of(2026, 10, 16), null),
            ),
            cache = BankingCacheInfo(Instant.parse("2026-10-17T10:00:00Z"), 60, false, true, null, Instant.parse("2026-10-17T16:00:00Z")),
        )
        val localAccounts = listOf(
            FinanceAccount("ph-revolut", "Revolut PH", "EUR", openingBalance = "100.00"),
            FinanceAccount("ph-lunar", "Lunar PH", "DKK", openingBalance = "750.00"),
        )
        val reconciliationState = BankingReconciliationState(accountLinks = mapOf(
            revolut.id to "ph-revolut",
            lunar.id to "ph-lunar",
        ))
        val reconciliation = reconcileBanking(
            snapshot, reconciliationState, localAccounts, emptyList(), Instant.parse("2026-10-17T10:01:00Z"),
        )
        compose.setContent {
            var filters by remember { mutableStateOf(BankingFilters(days = 90)) }
            SoldiTheme {
                BankingContent(
                    snapshot = snapshot,
                    filters = filters,
                    localAccounts = localAccounts,
                    reconciliation = reconciliation,
                    state = reconciliationState,
                    onFilters = { filters = it },
                    onMap = {},
                    onSuggestion = {},
                    onAlerts = {},
                )
            }
        }
        compose.onNodeWithText("Revolut 1").assertIsDisplayed()
        compose.onAllNodesWithText("100 EUR", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Lunar 1").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("750 DKK", substring = true).assertCountEquals(4)
        compose.onNodeWithText("Difference").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Synthetic shop").assertCountEquals(2)
        val screenshot = File(requireNotNull(compose.activity.getExternalFilesDir(null)), "banking-reconciliation.png")
        screenshot.outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue(screenshot.length() > 0)
    }
}
