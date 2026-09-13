package com.gernalix.personalhub.soldi

import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceCapsule
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceRecurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SoldiV2LogicTest {
    @Test
    fun revolutScreenshotAutofillParsesAmountsRateFeeAndAccounts() {
        val eur = FinanceAccount(id = "eur", name = "Revolut EUR", currency = "EUR")
        val dkk = FinanceAccount(id = "dkk", name = "Revolut DKK", currency = "DKK")
        val text = """
            €1 = kr. 7.4496
            EUR
            -€200
            balance €200
            DKK
            +kr.1,482.47
            balance kr. 1,735.84
            after kr. 7.45 fee
        """.trimIndent()

        val parsed = RevolutScreenshotParser.parse(text, listOf(eur, dkk))
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals("200", parsed.sourceAmount)
        assertEquals("EUR", parsed.sourceCurrency)
        assertEquals("1482.47", parsed.targetAmount)
        assertEquals("DKK", parsed.targetCurrency)
        assertEquals("7.4496", parsed.quotedRate)
        assertEquals("7.45", parsed.feeAmount)
        assertEquals("DKK", parsed.feeCurrency)
        assertEquals("eur", parsed.sourceAccountId)
        assertEquals("dkk", parsed.targetAccountId)
    }

    @Test
    fun effectiveRateUsesNetReceivedAmount() {
        assertEquals("7.41235", FinanceCapsule.effectiveRate("200", "1482.47").toPlainString())
    }

    @Test
    fun lastBusinessDaySkipsWeekend() {
        val rule = recurrence(lastBusinessDay = true, dayOfMonth = null)
        assertEquals(LocalDate.of(2026, 5, 29), FinanceCapsule.occurrenceDate(rule, YearMonth.of(2026, 5)))
    }

    @Test
    fun monthlyDayClampsToEndOfShortMonth() {
        val rule = recurrence(lastBusinessDay = false, dayOfMonth = 31)
        assertEquals(LocalDate.of(2027, 2, 28), FinanceCapsule.occurrenceDate(rule, YearMonth.of(2027, 2)))
    }

    private fun recurrence(lastBusinessDay: Boolean, dayOfMonth: Int?) = FinanceRecurrence(
        id = "rule",
        title = "Test",
        amount = "-10",
        currency = "DKK",
        accountId = "account",
        dayOfMonth = dayOfMonth,
        lastBusinessDay = lastBusinessDay,
        startDate = "2026-01-01",
    )
}
