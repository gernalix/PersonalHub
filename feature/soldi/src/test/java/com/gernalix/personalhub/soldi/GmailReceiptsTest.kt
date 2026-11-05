package com.gernalix.personalhub.soldi

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GmailReceiptsTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private fun message(body: String, mime: String = "text/plain", subject: String = "Order receipt") = JSONObject().apply {
        put("id", "abc123"); put("internalDate", now.toEpochMilli().toString())
        put("payload", JSONObject().apply {
            put("mimeType", mime); put("headers", JSONArray().put(JSONObject().put("name", "Subject").put("value", subject)))
            put("body", JSONObject().put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())))
        })
    }
    private fun receipt() = requireNotNull(GmailReceiptParser.parse(message("Merchant: Example Shop\nDate: 2026-11-04\nTotal: 12.99 EUR\nOrder: order-1")))
    private fun snapshot() = BankingSnapshot(emptyList(), emptyList(), listOf(
        BankingTransaction("one", "account", "Fixture", BigDecimal("-12.99"), "EUR", "DBIT", "BOOK", LocalDate.parse("2026-11-05"), "EXAMPLE SHOP")
    ), BankingCacheInfo(now, 0, false, true, null, null))
    @Test fun explicitFieldsAndStableMessageIdentity() {
        val receipt = receipt()
        assertEquals("abc123", receipt.messageId); assertEquals("Example Shop", receipt.merchant)
        assertEquals("order-1", receipt.orderId); assertEquals(BigDecimal("12.99"), receipt.amount)
        assertEquals(LocalDate.parse("2026-11-04"), receipt.date); assertTrue(receipt.complete)
    }
    @Test fun missingOrContradictoryFieldsNeverBecomeFacts() {
        val receipt = requireNotNull(GmailReceiptParser.parse(message("Total: 12.99 EUR\nTotal: 14.99 EUR")))
        assertNull(receipt.amount); assertNull(receipt.date); assertNull(receipt.merchant); assertFalse(receipt.complete)
        assertNull(GmailReceiptParser.parse(message("Meet tomorrow", subject = "Hello")))
        assertNull(GmailReceiptParser.parse(message("Total: 12.99 $"))?.amount)
    }
    @Test fun parsesStructuredOrderWithoutUsingMailTimestampAsPurchaseDate() {
        val receipt = requireNotNull(GmailReceiptParser.parse(message("""<script type="application/ld+json">{"@type":"Order","merchant":{"name":"Example"},"price":"9.99","priceCurrency":"DKK","orderNumber":"A"}</script>""", "text/html")))
        assertEquals(BigDecimal("9.99"), receipt.amount); assertEquals("Example", receipt.merchant); assertNull(receipt.date)
    }
    @Test fun aiFieldsStayProposalsAndCannotChangeIdentityOrIntroduceInvalidMoney() {
        val receipt = receipt()
        val reviewed = GmailReceiptReview.apply(receipt, """{"messageId":"abc123","merchant":"QA","orderId":null,"date":"2026-11-01","amount":"12.99","currency":"EUR"}""")
        assertEquals("revisione confermata", reviewed.parser)
        assertEquals("QA", reviewed.merchant)
        assertTrue(runCatching { GmailReceiptReview.apply(receipt, """{"messageId":"other","merchant":null,"orderId":null,"date":null,"amount":null,"currency":null}""") }.isFailure)
        assertTrue(runCatching { GmailReceiptReview.fields(receipt, "QA", null, "2026-02-30", "12", "EUR") }.isFailure)
        assertTrue(runCatching { GmailReceiptReview.fields(receipt, "QA", null, null, "-12", "EUR") }.isFailure)
    }
    @Test fun icsUsesExplicitTimezoneStableUidAndCancellation() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:fixture-event\r\nSUMMARY:Dentist\r\nDTSTART;TZID=Europe/Copenhagen:20261105T100000\r\nLOCATION:Clinic\r\nSEQUENCE:2\r\nEND:VEVENT\r\nEND:VCALENDAR"
        val event = GmailCalendarParser.parseIcs(ics, "message").single()
        assertEquals(Instant.parse("2026-11-05T09:00:00Z"), event.startsAt)
        assertEquals("fixture-event", event.uid); assertEquals("Clinic", event.location)
        assertEquals(2, event.sequence); assertFalse(event.cancelled)
        assertTrue(GmailCalendarParser.parseIcs(ics.replace("SEQUENCE:2", "SEQUENCE:3\r\nSTATUS:CANCELLED"), "message-2").single().cancelled)
        assertTrue(GmailCalendarParser.parseIcs(ics.replace("DTSTART;TZID=Europe/Copenhagen", "DTSTART"), "message").isEmpty())
    }

    @Test fun shippingTrackingAndInformalAppointmentsStaySeparateFromAccounting() {
        val parcel = requireNotNull(GmailReceiptParser.parse(message("Tracking number: QA123456789", subject = "Shipment confirmation")))
        assertEquals("shipment", parcel.kind); assertEquals("QA123456789", parcel.tracking); assertNull(parcel.amount)
        val invitation = requireNotNull(GmailReceiptParser.parse(message("See you next week", subject = "Appointment confirmation")))
        assertEquals("appointment", invitation.kind); assertNull(invitation.date)
        val verified = GmailAppointmentReview.fields(invitation, "Clinic", "2026-11-05", "10:00", "Europe/Copenhagen", "Clinic")
        assertEquals(Instant.parse("2026-11-05T09:00:00Z"), verified.startsAt)
        assertTrue(runCatching { GmailAppointmentReview.fields(invitation, "Clinic", "2026-03-29", "02:30", "Europe/Copenhagen", "Clinic") }.isFailure)
    }

    @Test fun matchesRequireFreshCompleteBookedDebitCurrencyMerchantAndUniqueBothDirections() {
        val receipt = receipt(); val bank = snapshot()
        assertEquals(listOf(ReceiptBankMatch(receipt.messageId, "account:one")), receiptBankCandidates(listOf(receipt), bank, emptyMap(), now))
        assertTrue(receiptBankCandidates(listOf(receipt, receipt.copy(messageId = "second")), bank, emptyMap(), now).isEmpty())
        assertTrue(receiptBankCandidates(listOf(receipt), bank.copy(transactions = bank.transactions + bank.transactions.single().copy(id = "two")), emptyMap(), now).isEmpty())
        assertTrue(receiptBankCandidates(listOf(receipt), bank, mapOf("other" to "account:one"), now).isEmpty())
        assertTrue(receiptBankCandidates(listOf(receipt), bank.copy(cache = bank.cache.copy(stale = true)), emptyMap(), now).isEmpty())
        assertTrue(receiptBankCandidates(listOf(receipt), bank.copy(partialAccounts = setOf("account")), emptyMap(), now).isEmpty())
        listOf(bank.transactions.single().copy(status = "PDNG"), bank.transactions.single().copy(currency = "DKK"), bank.transactions.single().copy(description = "Other merchant")).forEach {
            assertTrue(receiptBankCandidates(listOf(receipt), bank.copy(transactions = listOf(it)), emptyMap(), now).isEmpty())
        }
    }
}
