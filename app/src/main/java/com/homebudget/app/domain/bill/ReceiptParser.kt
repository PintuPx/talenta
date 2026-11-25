package com.homebudget.app.domain.bill

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * One line of text found by OCR, with its bounding box in image pixels. Item names and prices are
 * often recognised as separate lines (different columns), so boxes are used to rebuild rows.
 * A box of all zeros means "position unknown"; such lines are kept in the given order.
 * [angleDegrees] is the text baseline's slope (negative = rising to the right), from a tilted photo.
 */
data class OcrLine(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val angleDegrees: Float = 0f,
) {
    val hasBox: Boolean get() = bottom > top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Int get() = bottom - top
}

data class ParsedItem(val description: String, val amount: Long)

data class ParsedReceipt(
    val merchant: String?,
    val date: LocalDate?,
    /** Time printed on the bill (e.g. 19:05 or 7:05 PM), if found. */
    val time: LocalTime? = null,
    /** Total printed on the bill (minor units), if one was found. */
    val total: Long?,
    val items: List<ParsedItem>,
    /** Reconstructed rows, one per line, kept so the user can see what was read. */
    val text: String,
) {
    val itemsTotal: Long get() = items.sumOf { it.amount }
}

/**
 * Turns OCR output from a shop receipt into merchant, date, total and line items.
 *
 * It is heuristic, so every result is shown to the user for review before anything is saved.
 * Rules:
 * - Rows are rebuilt by grouping lines whose vertical centres are close; then each row is read
 *   left to right.
 * - A row is an item when it ends in an amount and has a name with letters. Quantity / rate
 *   columns before the amount are dropped from the name.
 * - Amounts need two decimals (40.00 / 40,00) unless the bill has no decimal amounts at all.
 * - Rows with total / tax / payment words are not items. The bill total is taken from the
 *   strongest total row (grand total > net amount > total); rows after it are ignored.
 * - Dates are read as day/month/year (Indian convention) unless that is impossible.
 */
object ReceiptParser {

    private val decimalMoney = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{2,3})+\.\d{1,2}|\d+\.\d{1,2}|\d+,\d{2})(?![\d.,]*\d)""")
    private val integerMoney = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{2,3})+|\d+)(?![\d.,]*\d)""")

    /** Highest priority first. */
    private val totalKeys = listOf(
        Regex("""\bgrand\s*total\b"""),
        Regex("""\bnet\s*(amount|amt|payable|total)\b|\b(amount|amt)\s*payable\b|\bbill\s*(amount|amt)\b|\btotal\s*(amount|amt|rs|inr)\b"""),
        Regex("""(?<!sub)(?<!sub\s)\btotal\b"""),
    )

    private val nonItemWords = Regex(
        """\b(sub\s*total|subtotal|tax|taxable|gst|cgst|sgst|igst|utgst|cess|vat|discount|disc|round|rounding|roundoff|""" +
            """change|cash|card|upi|paid|paytm|gpay|tender|tendered|balance|saving|savings|saved|payment|qty|quantity|""" +
            """items|invoice|gstin|fssai|phone|ph|tel|mob|mobile|thank|thanks|visit|bill\s*no|receipt\s*no|token|table)\b"""
    )
    private val merchantStopWords = Regex(
        """\b(gstin|invoice|tax|bill|receipt|cash\s*memo|phone|ph|tel|mob|date|time|welcome|order|cashier|counter|fssai)\b"""
    )
    private val unitOrQty = Regex(
        """^([\d.,]+|x|@|\*|[\d.,]+x|x[\d.,]+|[\d.,]*(kg|kgs|g|gm|gms|gram|grams|pc|pcs|nos|no|ltr|lt|l|ml|pkt)|each|ea|rs\.?|₹)$"""
    )
    private val leadingIndex = Regex("""^(\d{1,3}[.)]?|[a-z]?\d{4,})$""")
    private val currencyNoise = Regex("""₹|\bINR\b|\bRs\.?(?=\s|\d)""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<OcrLine>): ParsedReceipt {
        val rows = buildRows(lines.filter { it.text.isNotBlank() })
        val cleaned = rows.map { currencyNoise.replace(it, " ").replace(Regex("""\s+"""), " ").trim() }
        val decimalMode = cleaned.any { decimalMoney.containsMatchIn(it) }
        val money = if (decimalMode) decimalMoney else integerMoney

        // Bill total: strongest total keyword; within the same strength the largest amount.
        var totalRowIndex = -1
        var total: Long? = null
        for (key in totalKeys) {
            var best: Pair<Int, Long>? = null
            cleaned.forEachIndexed { i, row ->
                val lower = row.lowercase()
                if (!key.containsMatchIn(lower) || Regex("""\bsub\s*total|subtotal""").containsMatchIn(lower)) return@forEachIndexed
                val amount = lastAmount(row, money)?.second ?: return@forEachIndexed
                if (best == null || amount > best!!.second) best = i to amount
            }
            if (best != null) {
                totalRowIndex = best!!.first
                total = best!!.second
                break
            }
        }

        val items = ArrayList<ParsedItem>()
        val firstTotalLike = cleaned.indexOfFirst { row -> totalKeys.any { it.containsMatchIn(row.lowercase()) } }
        val itemsEnd = when {
            totalRowIndex >= 0 && firstTotalLike >= 0 -> minOf(totalRowIndex, firstTotalLike)
            totalRowIndex >= 0 -> totalRowIndex
            else -> cleaned.size
        }
        for (i in 0 until itemsEnd) {
            val row = cleaned[i]
            if (nonItemWords.containsMatchIn(row.lowercase())) continue
            val (start, amount) = lastAmount(row, money) ?: continue
            // Far above the bill total means a misread (e.g. a phone number); slightly above can be a discounted item.
            if (amount <= 0 || (total != null && amount > total * 2)) continue
            val name = cleanDescription(row.substring(0, start)) ?: continue
            items += ParsedItem(name, amount)
        }

        return ParsedReceipt(
            merchant = findMerchant(cleaned, money),
            date = findDate(cleaned.joinToString("\n")),
            time = findTime(cleaned.joinToString("\n")),
            total = total,
            items = items,
            text = rows.joinToString("\n"),
        )
    }

    /**
     * Groups OCR lines into visual rows (top to bottom), each read left to right. Positions are first
     * rotated by the median text angle, so a price on a tilted photo still joins its item name even
     * though it sits higher or lower in the raw image.
     */
    fun buildRows(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (lines.none { it.hasBox }) return lines.map { it.text.trim() }
        val boxed = lines.filter { it.hasBox }
        val angle = Math.toRadians(boxed.map { it.angleDegrees.toDouble() }.sorted()[boxed.size / 2])
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        // De-rotated centre coordinates (rotating by -angle).
        fun y(l: OcrLine) = (l.centerY * cos - l.centerX * sin).toFloat()
        fun x(l: OcrLine) = (l.centerX * cos + l.centerY * sin).toFloat()

        val heights = boxed.map { it.height }.sorted()
        val tolerance = heights[heights.size / 2] * 0.5f
        val rows = ArrayList<MutableList<OcrLine>>()
        var rowCenter = 0f
        for (line in boxed.sortedBy { y(it) }) {
            val current = rows.lastOrNull()
            if (current != null && kotlin.math.abs(y(line) - rowCenter) <= tolerance) {
                current += line
                rowCenter = current.map { y(it) }.average().toFloat()
            } else {
                rows += mutableListOf(line)
                rowCenter = y(line)
            }
        }
        return rows.map { row -> row.sortedBy { x(it) }.joinToString(" ") { it.text.trim() } }
    }

    /** Start index and minor-unit value of the last amount in [row], if the row ends with one. */
    private fun lastAmount(row: String, money: Regex): Pair<Int, Long>? {
        val match = money.findAll(row).lastOrNull() ?: return null
        // The amount must be the last thing on the row (ignoring trailing symbols such as "*" or "A").
        val tail = row.substring(match.range.last + 1).trim()
        if (tail.length > 2 || tail.any { it.isDigit() }) return null
        val minor = toMinor(match.value) ?: return null
        return match.range.first to minor
    }

    fun toMinor(token: String): Long? {
        val normalized = when {
            Regex("""^\d+,\d{2}$""").matches(token) -> token.replace(',', '.')
            else -> token.replace(",", "")
        }
        return try {
            BigDecimal(normalized).setScale(2, java.math.RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun cleanDescription(raw: String): String? {
        val tokens = raw.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toMutableList()
        // Trailing quantity / unit columns, and stray single characters (often a misread digit, e.g. "2" -> "N").
        while (tokens.size > 1 && (unitOrQty.matches(tokens.last().lowercase()) || tokens.last().length == 1)) tokens.removeAt(tokens.lastIndex)
        while (tokens.isNotEmpty() && unitOrQty.matches(tokens.last().lowercase())) tokens.removeAt(tokens.lastIndex)
        while (tokens.size > 1 && leadingIndex.matches(tokens.first().lowercase())) tokens.removeAt(0)
        val name = tokens.joinToString(" ").trim(' ', '-', ':', '.', ',', '*', '|')
        return name.takeIf { n -> n.count { it.isLetter() } >= 2 }
    }

    private fun findMerchant(rows: List<String>, money: Regex): String? =
        rows.take(4).firstOrNull { row ->
            row.count { it.isLetter() } >= 3 &&
                !money.containsMatchIn(row) &&
                !merchantStopWords.containsMatchIn(row.lowercase()) &&
                findDate(row) == null
        }?.take(60)

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val numericDate = Regex("""(?<!\d)(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4}|\d{2})(?!\d)""")
    private val isoDate = Regex("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)""")
    private val textDate = Regex("""(?<!\d)(\d{1,2})[\s\-]*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\s\-,']*(\d{4}|\d{2})(?!\d)""")

    fun findDate(text: String): LocalDate? {
        val lower = text.lowercase()
        isoDate.find(lower)?.let { m ->
            date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it }
        }
        for (m in numericDate.findAll(lower)) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val y = year(m.groupValues[3])
            (date(y, b, a) ?: date(y, a, b))?.let { return it } // day/month first, else month/day
        }
        textDate.find(lower)?.let { m ->
            date(year(m.groupValues[3]), months.indexOf(m.groupValues[2]) + 1, m.groupValues[1].toInt())?.let { return it }
        }
        return null
    }

    private val clockTime = Regex("""(?<![\d:.])([01]?\d|2[0-3])[:.]([0-5]\d)(?::[0-5]\d)?\s*(am|pm|a\.m\.|p\.m\.)?(?![\d.,]*\d)""", RegexOption.IGNORE_CASE)

    /** First clock time on a row that is not an amount row, e.g. "Date: 18/11/2026 19:05" or "Time 7:05 PM". */
    fun findTime(text: String): LocalTime? {
        for (row in text.lines()) {
            val lower = row.lowercase()
            val looksLikeDateOrTimeRow = findDate(row) != null || Regex("""\b(time|tm|date|dt)\b""").containsMatchIn(lower)
            if (!looksLikeDateOrTimeRow) continue
            val m = clockTime.find(row) ?: continue
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val ampm = m.groupValues[3].lowercase().replace(".", "")
            if (ampm.isNotEmpty() && hour !in 1..12) continue
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return LocalTime.of(hour, minute)
        }
        return null
    }

    private fun year(s: String): Int = if (s.length == 2) 2000 + s.toInt() else s.toInt()

    private fun date(y: Int, m: Int, d: Int): LocalDate? =
        if (y !in 2000..2100) null else try {
            LocalDate.of(y, m, d)
        } catch (e: DateTimeException) {
            null
        }
}
