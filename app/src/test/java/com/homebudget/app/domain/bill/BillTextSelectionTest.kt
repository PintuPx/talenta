package com.homebudget.app.domain.bill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillTextSelectionTest {
    @Test
    fun splitLinesTrimsAndRemovesBlankLines() {
        assertEquals(
            listOf("FRESH MART", "Milk 500ml 56.00", "Grand Total 649.50"),
            BillTextSelection.splitLines("  FRESH MART  \r\n\r\nMilk 500ml 56.00\rGrand Total 649.50\n"),
        )
    }

    @Test
    fun extractAmountMinorUsesExistingParserWithoutDoubleMath() {
        assertEquals(64950L, BillTextSelection.extractAmountMinor("Grand Total ₹649.50", 2))
        assertEquals(4000L, BillTextSelection.extractAmountMinor("Milk 40", 2))
        assertEquals(123450L, BillTextSelection.extractAmountMinor("Amount Rs. 1,234.50", 2))
        assertNull(BillTextSelection.extractAmountMinor("Fresh Mart", 2))
    }
}
