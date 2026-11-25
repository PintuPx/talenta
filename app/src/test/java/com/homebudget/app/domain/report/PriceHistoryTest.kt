package com.homebudget.app.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PriceHistoryTest {

    private fun line(desc: String, amount: Long, date: LocalDate, merchant: String = "Fresh Mart") =
        PurchasedLine(desc, amount, date, merchant)

    private val d1 = LocalDate.of(2026, 9, 1)
    private val d2 = LocalDate.of(2026, 10, 1)
    private val d3 = LocalDate.of(2026, 11, 1)

    @Test
    fun `differently written names are grouped as one item sorted by date`() {
        val items = PriceHistory.build(
            listOf(
                line("SUGAR", 5_200, d3),
                line("Sugar 1kg", 4_800, d1),
                line("sugar 1 kg", 5_000, d2),
            ),
        )
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("sugar", item.key)
        assertEquals("Sugar", item.label)
        assertEquals(listOf(d1, d2, d3), item.points.map { it.date })
        assertEquals(listOf(4_800L, 5_000L, 5_200L), item.points.map { it.amount })
    }

    @Test
    fun `latest lowest highest and average`() {
        val item = PriceHistory.build(
            listOf(line("Milk", 5_000, d1), line("Milk", 4_000, d2), line("Milk", 6_001, d3)),
        ).single()
        assertEquals(6_001L, item.latest)
        assertEquals(4_000L, item.lowest)
        assertEquals(6_001L, item.highest)
        assertEquals(5_000L, item.average) // 15001 / 3 rounded down
    }

    @Test
    fun `changePercent is from first to latest and truncated`() {
        val up = PriceHistory.build(listOf(line("Rice", 10_000, d1), line("Rice", 12_550, d2))).single()
        assertEquals(25, up.changePercent)
        val down = PriceHistory.build(listOf(line("Rice", 10_000, d1), line("Rice", 7_500, d2))).single()
        assertEquals(-25, down.changePercent)
    }

    @Test
    fun `changePercent is null with a single point`() {
        val item = PriceHistory.build(listOf(line("Rice", 10_000, d1))).single()
        assertNull(item.changePercent)
    }

    @Test
    fun `changePercent is null when the first amount is zero`() {
        val item = PriceHistory.build(listOf(line("Rice", 0, d1), line("Rice", 100, d2))).single()
        assertNull(item.changePercent)
    }

    @Test
    fun `lines with no usable name are skipped`() {
        val items = PriceHistory.build(listOf(line("1 kg", 100, d1), line("123", 100, d1)))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `items are sorted by purchase count descending then label`() {
        val items = PriceHistory.build(
            listOf(
                line("Tea", 100, d1),
                line("Milk", 100, d1),
                line("Milk", 110, d2),
                line("Milk", 120, d3),
                line("Bread", 100, d1),
                line("Bread", 100, d2),
            ),
        )
        assertEquals(listOf("Milk", "Bread", "Tea"), items.map { it.label })
        assertEquals(listOf(3, 2, 1), items.map { it.points.size })
    }

    @Test
    fun `points keep merchant and original description`() {
        val item = PriceHistory.build(listOf(line("Sugar 1kg", 4_800, d1, merchant = "Big Bazaar"))).single()
        assertEquals("Big Bazaar", item.points.single().merchant)
        assertEquals("Sugar 1kg", item.points.single().description)
    }

    @Test
    fun `search matches a partial name case-insensitively`() {
        val items = PriceHistory.build(listOf(line("Sugar 1kg", 4_800, d1), line("Milk", 100, d1)))
        assertEquals(listOf("sugar"), PriceHistory.search(items, "sug").map { it.key })
        assertEquals(listOf("sugar"), PriceHistory.search(items, "SUGAR").map { it.key })
        assertTrue(PriceHistory.search(items, "zzz").isEmpty())
    }

    @Test
    fun `blank search returns all items`() {
        val items = PriceHistory.build(listOf(line("Sugar", 4_800, d1), line("Milk", 100, d1)))
        assertEquals(items, PriceHistory.search(items, "  "))
    }
}
