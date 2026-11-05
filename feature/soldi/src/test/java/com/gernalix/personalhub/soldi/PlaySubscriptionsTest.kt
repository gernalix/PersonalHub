package com.gernalix.personalhub.soldi

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaySubscriptionsTest {
    private fun exported(price: String = "DKK 123.45", state: String = "Active", renewal: String = "2027-01-31T12:00:00Z") =
        """{"subscription":{"doc":{"documentType":"Subscription","title":"Synthetic service"},"state":"$state","renewalDate":"$renewal","pricing":[{"price":"DKK 0.00","period":{"count":7,"unit":"DAY"},"repeatedPricing":false},{"price":"$price","period":{"count":1,"unit":"MONTH"},"repeatedPricing":true}],"userChangeRecord":[{"date":"2026-01-01T12:00:00Z","type":"UpdatePaymentMethod"}]}}"""
    @Test fun actualExportShapeUnwrapsAndUsesRecurringPriceWithoutMergingByTitle() {
        val source = exported(price = "DKK\u00a01,234.50")
        val sub = PlaySubscriptionJson.parse("[$source,$source]").single()
        assertEquals(BigDecimal("1234.50"), sub.amount)
        assertEquals("DKK", sub.currency)
        assertEquals(java.time.Period.ofMonths(1), sub.period)
        assertEquals(0, BigDecimal("14814.00").compareTo(sub.annualCost()))
        assertTrue(sub.key.startsWith("snapshot:"))
        assertTrue(playChangeDescriptions(sub.sourceJson).single().startsWith("Metodo di pagamento modificato · "))
        assertEquals(sub, PlaySubscriptionJson.parse("[${sub.sourceJson}]").single())
        assertEquals(2, PlaySubscriptionJson.parse("[$source,${exported(state = "Canceled")}]").size)
        assertNull(PlaySubscriptionJson.parse("[${exported(price = "unrecognised")}]").single().amount)
    }
    @Test fun metadataArchiveFromPortabilityDoesNotAbortCombinedDownload() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("Portability/archive_browser.json"))
            zip.write("""{"creationTime":"2026-01-01T00:00:00Z","serviceStatus":[]}""".toByteArray())
            zip.closeEntry()
        }
        assertTrue(PlaySubscriptionJson.read(ByteArrayInputStream(bytes.toByteArray())).isEmpty())
    }
    private fun record(id: String = "app-one", state: String = "ACTIVE", pricing: String = """{"amount":"9.99","currencyCode":"EUR","period":"P1M"}""") =
        """{"doc":{"docid":"$id","title":"Example music","packageName":"example.music"},"state":"$state","renewalDate":"2026-01-31T12:00:00Z","pricing":$pricing,"userChangeRecord":[{"type":"REACTIVATION"}]}"""
    private fun parse(row: String = record()) = PlaySubscriptionJson.parse("[$row]").single()
    @Test fun importsStructuredSourceWithoutInventingUnknownPriceOrPeriod() {
        val sub = parse(record(pricing = "{}"))
        assertNull(sub.amount); assertNull(sub.currency); assertNull(sub.period); assertNull(sub.annualCost())
        assertTrue(sub.sourceJson.contains("REACTIVATION"))
        assertEquals(listOf("Riattivazione"), playChangeDescriptions(sub.sourceJson))
        assertEquals("Example music", sub.title)
    }
    @Test fun reimportPreservesIdentityWhenPriceOrStateChanges() {
        val active = parse()
        val cancelled = parse(record(state = "CANCELED", pricing = """{"amount":"12.00","currencyCode":"EUR","period":"P1M"}"""))
        assertEquals(active.key, cancelled.key)
        assertNotEquals(active.key, parse(record(id = "another-subscription")).key)
        assertNull(cancelled.annualCost()); assertTrue(cancelled.renewalDates(LocalDate.of(2026,1,1), LocalDate.of(2026,12,31)).isEmpty())
    }
    @Test fun monthEndProjectionDoesNotDriftAndCurrenciesAreNeverConverted() {
        val sub = parse()
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), sub.renewalDates(LocalDate.of(2026,1,1), LocalDate.of(2026,3,31)).map { it.toString() })
        assertEquals(0, BigDecimal("119.88").compareTo(sub.annualCost()))
        assertEquals("EUR", sub.currency)
    }
    @Test fun readsTakeoutArchiveWithoutExtractingPaths() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("Takeout/Google Play Store/Subscriptions.json")); zip.write("[${record()}]".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("../../ignored.txt")); zip.write("unused".toByteArray()); zip.closeEntry()
        }
        assertEquals(parse().key, PlaySubscriptionJson.read(ByteArrayInputStream(bytes.toByteArray())).single().key)
    }
    @Test fun rejectsUnrelatedAndMalformedRecordsInsteadOfImportingPartialData() {
        listOf("{}", "[{\"doc\":{\"title\":\"No stable ID\"}}]", "[${record()},{}]", "[${record().replace("2026-01-31T12:00:00Z", "invalid-date")}]").forEach { source ->
            assertTrue(runCatching { PlaySubscriptionJson.parse(source) }.isFailure)
        }
    }
    private fun snapshot(rows: List<BankingTransaction>, stale: Boolean = false) = BankingSnapshot(emptyList(), emptyList(), rows,
        BankingCacheInfo(Instant.now(), 0, stale, true, null, null))
    private fun debit(id: String = "tx", amount: String = "-9.99", status: String = "BOOK", currency: String = "EUR") =
        BankingTransaction(id, "revolut", "Revolut", BigDecimal(amount), currency, "DBIT", status, LocalDate.of(2026,1,31), "GOOGLE*Example music")
    @Test fun matchesOnlyBookedUnambiguousSameCurrencyDebitsAndNeverReusesLink() {
        val sub = parse(); val bank = snapshot(listOf(debit()))
        assertEquals(1, playBankMatches(listOf(sub), bank, emptySet()).size)
        assertTrue(playBankMatches(listOf(sub), bank, setOf("revolut:tx")).isEmpty())
        assertTrue(playBankMatches(listOf(sub, parse(record(id = "second"))), bank, emptySet()).isEmpty())
        assertTrue(playBankMatches(listOf(sub), snapshot(listOf(debit(status = "PDNG"))), emptySet()).isEmpty())
        assertTrue(playBankMatches(listOf(sub), snapshot(listOf(debit(currency = "DKK"))), emptySet()).isEmpty())
        assertTrue(playBankMatches(listOf(sub), snapshot(listOf(debit()), stale = true), emptySet()).isEmpty())
        assertTrue(playBankMatches(listOf(sub), snapshot(listOf(debit(amount = "-10.00"))), emptySet()).isEmpty())
    }
    @Test fun refusesConflictingDuplicateIdentitiesAndAcceptsEmptyTakeout() {
        assertTrue(runCatching { PlaySubscriptionJson.parse("[${record()},${record(state = "CANCELED")}]" ) }.isFailure)
        assertEquals(1, PlaySubscriptionJson.parse("[${record()},${record()}]").size)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry("Subscriptions.json")); zip.write("[]".toByteArray()); zip.closeEntry() }
        assertTrue(PlaySubscriptionJson.read(ByteArrayInputStream(bytes.toByteArray())).isEmpty())
    }

}
