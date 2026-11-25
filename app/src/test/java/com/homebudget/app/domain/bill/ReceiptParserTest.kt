package com.homebudget.app.domain.bill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ReceiptParserTest {

    /** Builds an OCR line at a given row (y) and column (x), like ML Kit returns for column layouts. */
    private fun at(text: String, x: Int, y: Int, w: Int = text.length * 12, h: Int = 24) = OcrLine(text, x, y, x + w, y + h)

    private fun plain(vararg rows: String) = rows.map { OcrLine(it) }

    @Test
    fun columnLayoutReceiptIsRebuiltIntoRowsAndItems() {
        // Names, quantities and amounts come back as separate lines in separate columns; rows slightly skewed.
        val lines = listOf(
            at("FRESH MART", 120, 10),
            at("GSTIN 27ABCDE1234F1Z5", 60, 40),
            at("Date: 18/11/2026 19:05", 40, 70),
            at("Item", 10, 110), at("Qty", 220, 111), at("Rate", 300, 110), at("Amount", 400, 112),
            at("Veggie", 10, 140), at("1", 220, 142), at("40.00", 300, 141), at("40.00", 400, 143),
            at("Tomato", 10, 170), at("2.000", 220, 171), at("15.00", 300, 170), at("30.00", 400, 172),
            at("Milk 500ml", 10, 200), at("1", 220, 199), at("56.00", 300, 201), at("56.00", 400, 200),
            at("Sub Total", 10, 240), at("126.00", 400, 241),
            at("CGST 2.5%", 10, 270), at("3.15", 400, 270),
            at("SGST 2.5%", 10, 300), at("3.15", 400, 301),
            at("Grand Total", 10, 330), at("132.30", 400, 331),
            at("Cash", 10, 360), at("200.00", 400, 360),
            at("Change", 10, 390), at("67.70", 400, 391),
        ).shuffled(java.util.Random(7)) // OCR order is not guaranteed

        val r = ReceiptParser.parse(lines)

        assertEquals("FRESH MART", r.merchant)
        assertEquals(LocalDate.of(2026, 11, 18), r.date)
        assertEquals(13_230L, r.total)
        assertEquals(
            listOf(ParsedItem("Veggie", 4_000), ParsedItem("Tomato", 3_000), ParsedItem("Milk", 5_600)),
            r.items,
        )
        assertEquals(12_600L, r.itemsTotal)
    }

    @Test
    fun tiltedPhotoStillPairsPricesWithItemNames() {
        // A straight two-column receipt, rotated 4 degrees (rising to the right) like a hand-held photo.
        // Prices are ~400 px right of names, so they end up ~28 px higher: more than half a line height.
        val deg = -4.0
        val a = Math.toRadians(deg)
        fun tilted(text: String, x: Int, y: Int): OcrLine {
            val w = text.length * 12
            val h = 24
            val cx = x + w / 2.0
            val cy = y + h / 2.0
            val rx = cx * kotlin.math.cos(a) - cy * kotlin.math.sin(a)
            val ry = cx * kotlin.math.sin(a) + cy * kotlin.math.cos(a)
            return OcrLine(text, (rx - w / 2).toInt(), (ry - h / 2).toInt(), (rx + w / 2).toInt(), (ry + h / 2).toInt(), deg.toFloat())
        }
        val lines = listOf(
            tilted("GREEN BASKET", 20, 100),
            tilted("Veggie", 20, 160), tilted("40.00", 450, 160),
            tilted("Tomato 1kg", 20, 200), tilted("32.00", 450, 200),
            tilted("Onion 2kg", 20, 240), tilted("70.00", 450, 240),
            tilted("Grand Total", 20, 300), tilted("142.00", 440, 300),
        )
        // Sanity check: without the angle the rows would not line up.
        val flat = ReceiptParser.parse(lines.map { it.copy(angleDegrees = 0f) })
        assertTrue("rows should be broken without de-skewing", flat.items != listOf(ParsedItem("Veggie", 4_000), ParsedItem("Tomato", 3_200), ParsedItem("Onion", 7_000)))

        val r = ReceiptParser.parse(lines)
        assertEquals(listOf(ParsedItem("Veggie", 4_000), ParsedItem("Tomato", 3_200), ParsedItem("Onion", 7_000)), r.items)
        assertEquals(14_200L, r.total)
        assertEquals("GREEN BASKET", r.merchant)
    }

    @Test
    fun handlesCurrencySymbolsIndianGroupingAndCommaDecimals() {
        val r = ReceiptParser.parse(
            plain(
                "City Electronics",
                "1. Mixer grinder Rs. 2,450.00",
                "2. Extension cord ₹ 1,250.50",
                "3. Batteries 4pcs 120,00",
                "Total INR 3,820.50",
            )
        )
        assertEquals(
            listOf(ParsedItem("Mixer grinder", 245_000), ParsedItem("Extension cord", 125_050), ParsedItem("Batteries", 12_000)),
            r.items,
        )
        assertEquals(382_050L, r.total)
    }

    @Test
    fun receiptsWithoutDecimalsUseWholeAmounts() {
        val r = ReceiptParser.parse(plain("Tea Stall", "Tea 20", "Samosa x2 30", "Total 50"))
        assertEquals(listOf(ParsedItem("Tea", 2_000), ParsedItem("Samosa", 3_000)), r.items)
        assertEquals(5_000L, r.total)
    }

    @Test
    fun ignoresLinesAfterTotalAndAmountsLargerThanTotal() {
        val r = ReceiptParser.parse(
            plain(
                "Bakery",
                "Bread 45.00",
                "Phone 9876543210.00", // phone-like number misread as amount: larger than total
                "Total 45.00",
                "Paid by UPI 45.00",
                "Thank you 0.00",
            )
        )
        assertEquals(listOf(ParsedItem("Bread", 4_500)), r.items)
    }

    @Test
    fun withoutATotalAllAmountLinesAreItemsAndTotalIsNull() {
        val r = ReceiptParser.parse(plain("Veggie 40.00", "Curd 30.00"))
        assertNull(r.total)
        assertEquals(2, r.items.size)
    }

    @Test
    fun skipsTaxDiscountAndPaymentRowsAsItems() {
        val r = ReceiptParser.parse(
            plain("Rice 5kg 320.00", "Discount 20.00", "GST 5% 15.00", "Round off 0.40", "Card 315.40", "Net Amount 315.40")
        )
        assertEquals(listOf(ParsedItem("Rice", 32_000)), r.items)
        assertEquals(31_540L, r.total)
    }

    @Test
    fun grandTotalWinsOverPlainTotal() {
        val r = ReceiptParser.parse(plain("Apple 100.00", "Total 100.00", "Grand Total 105.00"))
        assertEquals(10_500L, r.total)
    }

    @Test
    fun readsCommonDateFormats() {
        assertEquals(LocalDate.of(2026, 11, 18), ReceiptParser.findDate("Bill dt 18-11-26 10:30"))
        assertEquals(LocalDate.of(2026, 11, 18), ReceiptParser.findDate("2026-11-18T10:00"))
        assertEquals(LocalDate.of(2026, 11, 18), ReceiptParser.findDate("18 Nov 2026"))
        assertEquals(LocalDate.of(2026, 11, 18), ReceiptParser.findDate("18.11.2026"))
        assertEquals(LocalDate.of(2026, 3, 4), ReceiptParser.findDate("04/03/2026")) // day first (India)
        assertEquals(LocalDate.of(2026, 11, 18), ReceiptParser.findDate("11/18/2026")) // impossible as d/m, so m/d
        assertNull(ReceiptParser.findDate("Invoice 1234"))
        assertNull(ReceiptParser.findDate("31/02/2026"))
    }

    @Test
    fun readsTimeOnlyFromDateOrTimeRows() {
        assertEquals(LocalTime.of(19, 5), ReceiptParser.findTime("FRESH MART\nDate: 18/11/2026 19:05\nVeggie 40.00"))
        assertEquals(LocalTime.of(19, 5), ReceiptParser.findTime("Time: 7:05 PM"))
        assertEquals(LocalTime.of(0, 30), ReceiptParser.findTime("Dt 18-11-26 12:30 am"))
        assertNull(ReceiptParser.findTime("Veggie 12.30")) // an amount, not a time
        assertNull(ReceiptParser.findTime("Date: 18/11/2026"))
        assertEquals(LocalTime.of(19, 5), ReceiptParser.parse(plain("Shop", "Date: 18/11/2026 19:05", "Tea 20.00")).time)
    }

    @Test
    fun strayCharactersAfterTheNameAreDropped() {
        // OCR read the quantity "2" as "N".
        assertEquals(listOf(ParsedItem("Milk", 5_600)), ReceiptParser.parse(plain("Milk 500ml N 56.00")).items)
        // A one-letter word inside a name is kept.
        assertEquals(listOf(ParsedItem("Vitamin C tablets", 9_900)), ReceiptParser.parse(plain("Vitamin C tablets 99.00")).items)
    }

    @Test
    fun amountConversionIsExact() {
        assertEquals(4_000L, ReceiptParser.toMinor("40.00"))
        assertEquals(4_050L, ReceiptParser.toMinor("40.5"))
        assertEquals(12_345_678L, ReceiptParser.toMinor("1,23,456.78"))
        assertEquals(4_000L, ReceiptParser.toMinor("40,00"))
        assertEquals(400_000L, ReceiptParser.toMinor("4,000"))
    }

    @Test
    fun emptyInputGivesEmptyResult() {
        val r = ReceiptParser.parse(emptyList())
        assertEquals(emptyList<ParsedItem>(), r.items)
        assertNull(r.merchant)
        assertNull(r.total)
    }
}
