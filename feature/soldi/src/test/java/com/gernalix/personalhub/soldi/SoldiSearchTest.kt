package com.gernalix.personalhub.soldi

import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAttachment
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransaction
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoldiSearchTest {
    private val row = TransactionView(
        value = FinanceTransaction(
            id = 42,
            accountId = "acc-1",
            uuid = "tx-jacket-uuid",
            titleId = null,
            productId = 7,
            amount = "-1299",
            currency = "DKK",
            chainId = 9,
            placeId = "place-1",
            notes = "winter purchase",
            occurredAt = 1_790_000_000_000,
            createdAt = 1_790_000_000_100,
            updatedAt = 1_790_000_000_200,
            personId = 12,
            macroId = "macro-2",
            recurrenceId = null,
            occurrenceKey = null,
            reminderAt = null,
            category = "Clothing",
        ),
        title = "Black jacket",
        chain = "Outdoor Shop",
        place = "Copenhagen",
        person = "Daniele",
    )

    private val account = FinanceAccount(
        id = "acc-1",
        name = "Revolut",
        currency = "DKK",
    )

    private val photo = FinanceAttachment(
        id = "photo-1",
        transactionId = 42,
        kind = "PHOTO_URL",
        uri = "https://storage.example/jacket.jpg",
        title = "front view",
        mimeType = "image/jpeg",
        createdAt = 1_790_000_000_300,
    )

    @Test
    fun searchesAcrossJoinedAndPersistedFields() {
        assertTrue(financeTransactionMatches("jacket", row, listOf("wardrobe"), account, listOf(photo)))
        assertTrue(financeTransactionMatches("outdoor clothing", row, listOf("wardrobe"), account, listOf(photo)))
        assertTrue(financeTransactionMatches("revolut", row, listOf("wardrobe"), account, listOf(photo)))
        assertTrue(financeTransactionMatches("wardrobe", row, listOf("wardrobe"), account, listOf(photo)))
        assertTrue(financeTransactionMatches("tx-jacket-uuid", row, emptyList(), account, listOf(photo)))
        assertTrue(financeTransactionMatches("1299", row, emptyList(), account, listOf(photo)))
        assertTrue(financeTransactionMatches("front view", row, emptyList(), account, listOf(photo)))
        assertTrue(financeTransactionMatches("image/jpeg", row, emptyList(), account, listOf(photo)))
    }

    @Test
    fun allQueryTokensMustMatchSomewhereInTheTransactionDocument() {
        assertTrue(financeTransactionMatches("black revolut copenhagen", row, emptyList(), account, listOf(photo)))
        assertFalse(financeTransactionMatches("black stockholm", row, emptyList(), account, listOf(photo)))
    }

    @Test
    fun blankQueryReturnsAllTransactionsForLiveFiltering() {
        assertTrue(financeTransactionMatches("", row, emptyList(), account, emptyList()))
    }
}
