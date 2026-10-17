package com.gernalix.personalhub.soldi

import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransaction
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class BankingReconciliationTest {
    private val fetchedAt = Instant.parse("2026-10-17T12:00:00Z")
    private val bankAccount = BankingAccount("bank-1", "Revolut", listOf("EUR"), listOf("ITBD", "ITAV"))
    private val localAccount = FinanceAccount("ph-1", "Revolut PH", "EUR", openingBalance = "100.00")
    private val linked = BankingReconciliationState(accountLinks = mapOf(bankAccount.id to localAccount.id))

    @Test fun bookedBalanceUsesExactDecimalAndExplainedDifferenceOnly() {
        val snapshot = snapshot(
            bankBalance = "70.00",
            rows = listOf(
                bankTransaction("missing", "-20.00", "BOOK", "Synthetic shop"),
                bankTransaction("pending", "-5.00", "PDNG", "Pending fixture"),
            ),
        )
        val localRows = listOf(localTransaction("existing", "-10.00", "2026-10-16T08:00:00Z"))
        val result = reconcileBanking(snapshot, linked, listOf(localAccount), localRows, fetchedAt)
        val comparison = result.comparisons.single()

        assertEquals(BankingComparisonState.COMPARABLE, comparison.state)
        assertEquals(0, BigDecimal("90.00").compareTo(comparison.phBalance))
        assertEquals(0, BigDecimal("-20.00").compareTo(comparison.difference))
        assertEquals(BankingComparisonCause.MISSING_PH_TRANSACTIONS, comparison.cause)
        assertEquals(1, comparison.pendingCount)
        assertEquals("missing", result.suggestions.single().providerTransactionId)
        assertTrue(validateBankingSuggestion(
            result.suggestions.single(),
            result.suggestions.single().toEditor().draft.copy(amount = "-20.00"),
            snapshot,
            linked,
            listOf(localAccount),
            localRows,
            fetchedAt,
        ))
    }

    @Test fun stalePartialReferenceCurrencyAndBalanceTypeFailClosed() {
        val base = snapshot(bankBalance = "100.00")
        assertEquals(
            BankingComparisonState.STALE,
            reconcileBanking(base.copy(cache = base.cache.copy(stale = true)), linked, listOf(localAccount), emptyList(), fetchedAt)
                .comparisons.single().state,
        )
        assertEquals(
            BankingComparisonState.PARTIAL,
            reconcileBanking(base.copy(partialAccounts = setOf(bankAccount.id)), linked, listOf(localAccount), emptyList(), fetchedAt)
                .comparisons.single().state,
        )
        assertEquals(
            BankingComparisonState.REFERENCE_DATE_MISMATCH,
            reconcileBanking(base.copy(balances = base.balances.map { it.copy(referenceDate = LocalDate.of(2026, 10, 16)) }), linked,
                listOf(localAccount), emptyList(), fetchedAt).comparisons.single().state,
        )
        assertEquals(
            BankingComparisonState.CURRENCY_MISMATCH,
            reconcileBanking(base, linked, listOf(localAccount.copy(currency = "DKK")), emptyList(), fetchedAt).comparisons.single().state,
        )
        assertEquals(
            BankingComparisonState.NO_BOOKED_BALANCE,
            reconcileBanking(base.copy(balances = base.balances.map { it.copy(type = "ITAV") }), linked,
                listOf(localAccount), emptyList(), fetchedAt).comparisons.single().state,
        )
    }

    @Test fun existingAndAcceptedMovementsAreNeverSuggestedAgain() {
        val snapshot = snapshot(
            bankBalance = "90.00",
            rows = listOf(bankTransaction("same", "-10", "BOOK", "Fixture")),
        )
        val existing = listOf(localTransaction("local", "-10.0", "2026-10-17T08:00:00Z"))
        assertTrue(reconcileBanking(snapshot, linked, listOf(localAccount), existing, fetchedAt).suggestions.isEmpty())

        val accepted = linked.copy(acceptedSuggestions = mapOf("bank-1:same" to "local-uuid"))
        assertTrue(reconcileBanking(snapshot, accepted, listOf(localAccount), emptyList(), fetchedAt).suggestions.isEmpty())
    }

    @Test fun futureEntriesAndAmbiguousMatchesStaySeparate() {
        val snapshot = snapshot(
            bankBalance = "100.00",
            rows = listOf(bankTransaction("same", "-10", "BOOK", "Fixture")),
        )
        val rows = listOf(
            localTransaction("a", "-10", "2026-10-17T08:00:00Z"),
            localTransaction("b", "-10.00", "2026-10-16T08:00:00Z"),
            localTransaction("future", "25", "2026-10-18T08:00:00Z"),
        )
        val comparison = reconcileBanking(snapshot, linked, listOf(localAccount), rows, fetchedAt).comparisons.single()
        assertEquals(1, comparison.ambiguousCount)
        assertEquals(1, comparison.futurePhCount)
        assertEquals(BankingComparisonCause.FUTURE_PH_SEPARATE, comparison.cause)
    }

    @Test fun alertRequiresTwoEqualReadingsAndDoesNotRepeatUntilChange() {
        val comparison = reconcileBanking(snapshot(bankBalance = "80"), linked, listOf(localAccount), emptyList(), fetchedAt).comparisons
        val enabled = linked.copy(alertPreferences = BankingAlertPreferences(true, BigDecimal("10")))
        val first = advanceBankingAlerts(enabled, comparison)
        assertTrue(first.accountIdsToNotify.isEmpty())
        val second = advanceBankingAlerts(first.state, comparison)
        assertEquals(listOf(bankAccount.id), second.accountIdsToNotify)

        val fingerprint = requireNotNull(second.state.observedAlerts[bankAccount.id])
        val posted = second.state.copy(notifiedAlerts = mapOf(bankAccount.id to fingerprint))
        assertTrue(advanceBankingAlerts(posted, comparison).accountIdsToNotify.isEmpty())

        val changed = reconcileBanking(snapshot(bankBalance = "70"), linked, listOf(localAccount), emptyList(), fetchedAt).comparisons
        assertTrue(advanceBankingAlerts(posted, changed).accountIdsToNotify.isEmpty())
    }

    private fun snapshot(
        bankBalance: String,
        rows: List<BankingTransaction> = emptyList(),
    ) = BankingSnapshot(
        accounts = listOf(bankAccount),
        balances = listOf(BankingBalance(
            bankAccount.id, bankAccount.bank, "ITBD", BigDecimal(bankBalance), "EUR",
            LocalDate.of(2026, 10, 17), fetchedAt,
        )),
        transactions = rows,
        cache = BankingCacheInfo(fetchedAt, 60, false, true, null, fetchedAt.plusSeconds(21_600)),
    )

    private fun bankTransaction(id: String, amount: String, status: String, description: String) = BankingTransaction(
        id, bankAccount.id, bankAccount.bank, BigDecimal(amount), "EUR",
        if (amount.startsWith("-")) "DBIT" else "CRDT", status, LocalDate.of(2026, 10, 17), description,
    )

    private fun localTransaction(uuid: String, amount: String, occurredAt: String) = TransactionView(
        value = FinanceTransaction(
            accountId = localAccount.id,
            uuid = uuid,
            titleId = null,
            productId = null,
            amount = amount,
            currency = "EUR",
            chainId = null,
            placeId = null,
            notes = "",
            occurredAt = Instant.parse(occurredAt).toEpochMilli(),
            createdAt = 0,
            updatedAt = 0,
        ),
        title = "Fixture",
        chain = null,
        place = null,
    )
}
