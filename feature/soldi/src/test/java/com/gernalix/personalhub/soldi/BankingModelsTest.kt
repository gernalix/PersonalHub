package com.gernalix.personalhub.soldi

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BankingModelsTest {
    private val cache = """{"fetched_at":"2026-10-17T10:00:00Z","age_seconds":60,"stale":false,"complete":true,"window_days":90,"last_error":null,"next_refresh_eligible_at":"2026-10-17T16:00:00Z"}"""

    @Test fun parsesAccountsBalancesAndTransactionsWithoutInventingFields() {
        val (accounts, metadata) = BankingJson.accounts("""{"accounts":[{"account_id":"0123456789abcdef01234567","bank":"Revolut","currencies":["EUR"],"balance_types":["ITAV","OTHR"]}],"cache":$cache}""")
        assertFalse(metadata.stale)
        val account = accounts.single()
        val (balances, _) = BankingJson.balances(account, """{"account_id":"${account.id}","bank":"Revolut","balances":[{"type":"ITAV","amount":"100.00","currency":"EUR","reference_date":"2026-10-17","last_changed_at":null},{"type":"OTHR","amount":"9.00","currency":"EUR","reference_date":null,"last_changed_at":null}],"cache":$cache}""")
        val (transactions, next, _) = BankingJson.transactions(account, """{"account_id":"${account.id}","bank":"Revolut","transactions":[{"transaction_id":"tx-1","amount":"-10.00","currency":"EUR","direction":"DBIT","status":"PDNG","booking_date":"2026-10-16","transaction_date":null,"value_date":null,"description":null}],"pagination":{"page":1,"page_size":1,"total":2,"next_page":2,"complete":true},"filters":{"days":30,"status":null,"direction":null},"cache":$cache}""")
        assertEquals(2, next)
        assertNull(transactions.single().description)
        assertEquals(BigDecimal("100.00"), balances.first().amount)
    }

    @Test fun totalsNeverMixCurrencyOrOtherBalanceTypes() {
        val rows = listOf(
            balance("ITAV", "EUR", "10"), balance("ITAV", "EUR", "2"),
            balance("ITAV", "DKK", "50"), balance("OTHR", "EUR", "999"),
        )
        val totals = comparableBankingTotals(rows)
        assertEquals(2, totals.size)
        assertEquals(BigDecimal("50"), totals.single { it.currency == "DKK" }.amount)
        assertEquals(BigDecimal("12"), totals.single { it.currency == "EUR" }.amount)
    }

    @Test fun postedTransitionReplacesPendingDuplicateAndFiltersApply() {
        val pending = transaction("same", "PDNG", "Revolut", "EUR", LocalDate.of(2026, 10, 16))
        val posted = pending.copy(status = "BOOK")
        val otherAccount = posted.copy(accountId = "other-account")
        val other = transaction("other", "BOOK", "Lunar", "DKK", LocalDate.of(2026, 8, 1))
        val result = filterBankingTransactions(listOf(pending, posted, otherAccount, other), BankingFilters(bank = "Revolut", status = "BOOK", days = 30), LocalDate.of(2026, 10, 17))
        assertEquals(setOf(posted, otherAccount), result.toSet())
    }

    @Test fun protectedCacheRoundTripsAndUrlRequiresHttpsV1() {
        val account = BankingAccount("0123456789abcdef01234567", "Lunar", listOf("DKK"), listOf("ITBD"))
        val snapshot = BankingSnapshot(listOf(account), listOf(balance("ITBD", "DKK", "42")), listOf(transaction("tx", "BOOK", "Lunar", "DKK", LocalDate.of(2026, 10, 17))), BankingJson.accounts("""{"accounts":[],"cache":$cache}""").second)
        assertEquals(snapshot, BankingJson.decode(BankingJson.encode(snapshot)))
        assertEquals(BankingSecureStore.DEFAULT_BASE_URL, BankingSecureStore.normalizeBaseUrl(BankingSecureStore.DEFAULT_BASE_URL + "/"))
        assertThrows(IllegalArgumentException::class.java) { BankingSecureStore.normalizeBaseUrl("http://example.invalid/api/v1") }
        assertThrows(IllegalArgumentException::class.java) { BankingSecureStore.normalizeBaseUrl("https://example.invalid/api/v1") }
        assertEquals(BankingFailure.AUTHORIZATION, bankingFailureForHttpStatus(401))
        assertEquals(BankingFailure.RATE_LIMIT, bankingFailureForHttpStatus(429))
        assertEquals(BankingFailure.SERVER, bankingFailureForHttpStatus(503))
        assertTrue(bankingCacheIsStale(snapshot.cache, Instant.parse("2026-10-17T16:00:00Z")))
    }

    private fun balance(type: String, currency: String, amount: String) = BankingBalance("account", "bank", type, BigDecimal(amount), currency, null, null)
    private fun transaction(id: String, status: String, bank: String, currency: String, date: LocalDate) = BankingTransaction(id, "account", bank, BigDecimal.ONE, currency, "CRDT", status, date, "fixture")
}
