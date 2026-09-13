package com.gernalix.personalhub.soldi.receipt

import com.gernalix.personalhub.core.database.capsules.soldi.FinanceProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceiptImportTest {
    @Test
    fun remaReceiptParsesLocallyWithoutChatGpt() {
        val parsed = ReceiptParser().parse(
            """
            REMA 1000
            MANDEL IS
            27,23
            2 CREAMS CACAO
            à 10,00
            20,00
            SHOPPINGPOSE
            10,00
            AT BETALE
            57,23
            VISA
            57,23
            """.trimIndent()
        )

        assertEquals(ReceiptChain.REMA_1000, parsed.chain)
        assertEquals("57,23", parsed.total)
        assertEquals(listOf("MANDEL IS", "CREAMS CACAO", "SHOPPINGPOSE"), parsed.lines.map { it.description })
        assertEquals(2.0, parsed.lines[1].quantity ?: 0.0, 0.0)
    }

    @Test
    fun localMatcherReusesStableKnownProduct() {
        val products = listOf(FinanceProduct(id = 47, name = "Milbona Yogurt 1L", uuid = "00000000-0000-0000-0000-000000000047"))
        val match = ReceiptProductMatcher.match("MILBONA YOGURT 1 L", products)
        assertEquals(47L, match?.id)
    }

    @Test
    fun chatGptResponseCanOnlyReuseKnownProductIds() {
        val products = listOf(FinanceProduct(id = 47, name = "Milbona Yogurt 1L", uuid = "00000000-0000-0000-0000-000000000047"))
        val draft = ReceiptImportDraft(
            merchant = "LIDL",
            dateTime = null,
            total = "10,00",
            currency = "DKK",
            rawText = "MILB YOG 10,00",
            lines = listOf(ReceiptImportLine("MILB YOG", "MILB YOG", "10,00", 0.7f)),
        )

        val enriched = ReceiptChatGptBridge.applyResponse(
            """{"lines":[{"index":0,"productId":47,"canonicalName":"Milbona Yogurt 1L"}]}""",
            draft,
            products,
        )
        assertEquals(47L, enriched.lines.single().productId)
        assertEquals("Milbona Yogurt 1L", enriched.lines.single().description)

        assertThrows(IllegalArgumentException::class.java) {
            ReceiptChatGptBridge.applyResponse(
                """{"lines":[{"index":0,"productId":999,"canonicalName":"Invented"}]}""",
                draft,
                products,
            )
        }
        assertNull(draft.lines.single().productId)
    }
}
