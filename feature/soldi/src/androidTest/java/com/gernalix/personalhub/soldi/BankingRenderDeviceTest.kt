package com.gernalix.personalhub.soldi

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

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
                BankingBalance(lunar.id, lunar.bank, "ITBD", BigDecimal("750.00"), "DKK", null, null),
            ),
            transactions = listOf(
                BankingTransaction("tx-book", revolut.id, revolut.bank, BigDecimal("-12.50"), "EUR", "DBIT", "BOOK", LocalDate.of(2026, 10, 17), "Synthetic shop"),
                BankingTransaction("tx-pending", lunar.id, lunar.bank, BigDecimal("25.00"), "DKK", "CRDT", "PDNG", LocalDate.of(2026, 10, 16), null),
            ),
            cache = BankingCacheInfo(Instant.parse("2026-10-17T10:00:00Z"), 60, false, true, null, Instant.parse("2026-10-17T16:00:00Z")),
        )
        compose.setContent {
            var filters by remember { mutableStateOf(BankingFilters(days = 90)) }
            SoldiTheme { BankingContent(snapshot, filters) { filters = it } }
        }
        compose.onNodeWithText("Revolut 1").assertIsDisplayed()
        compose.onAllNodesWithText("100 EUR", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Lunar 1").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("750 DKK", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Synthetic shop").performScrollTo().assertIsDisplayed()
    }
}
