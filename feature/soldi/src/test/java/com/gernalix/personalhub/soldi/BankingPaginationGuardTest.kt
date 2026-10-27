package com.gernalix.personalhub.soldi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BankingPaginationGuardTest {
    @Test fun validPagesAdvanceAndNullEndsSearch() {
        assertEquals(2, checkedBankingNextPage(1, 2))
        assertEquals(100, checkedBankingNextPage(99, 100))
        assertEquals(0, checkedBankingNextPage(100, null))
    }

    @Test fun invalidOrRepeatedPagesAreRejected() {
        for ((current, next) in listOf(1 to 1, 4 to 2, 100 to 101, 2 to -1)) {
            assertEquals(BankingFailure.INVALID_RESPONSE,
                assertThrows(BankingException::class.java) { checkedBankingNextPage(current, next) }.kind)
        }
    }
}
