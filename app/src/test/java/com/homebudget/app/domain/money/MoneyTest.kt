package com.homebudget.app.domain.money

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MoneyTest {

    private fun valid(minor: Long) = AmountParseResult.Valid(minor)

    @Test
    fun `parses whole and decimal amounts into minor units`() {
        assertEquals(valid(12_345), AmountParser.parse("123.45"))
        assertEquals(valid(12_340), AmountParser.parse("123.4"))
        assertEquals(valid(12_300), AmountParser.parse("123"))
        assertEquals(valid(50), AmountParser.parse(".5"))
        assertEquals(valid(100), AmountParser.parse(" 1.00 "))
    }

    @Test
    fun `handles grouping and comma decimals`() {
        assertEquals(valid(123_456_789), AmountParser.parse("1,234,567.89"))
        assertEquals(valid(10_000_000), AmountParser.parse("1,00,000"))
        assertEquals(valid(1_250), AmountParser.parse("12,5"))
        assertEquals(valid(1_250), AmountParser.parse("12,50"))
        assertEquals(valid(1_250_000), AmountParser.parse("12,500"))
    }

    @Test
    fun `rejects invalid amounts`() {
        assertEquals(AmountParseResult.Empty, AmountParser.parse(""))
        assertEquals(AmountParseResult.Empty, AmountParser.parse("   "))
        assertEquals(AmountParseResult.NotANumber, AmountParser.parse("abc"))
        assertEquals(AmountParseResult.NotANumber, AmountParser.parse("1.2.3"))
        assertEquals(AmountParseResult.NotANumber, AmountParser.parse("."))
        assertEquals(AmountParseResult.NotANumber, AmountParser.parse("1e5"))
        assertEquals(AmountParseResult.NotPositive, AmountParser.parse("0"))
        assertEquals(AmountParseResult.NotPositive, AmountParser.parse("0.00"))
        assertEquals(AmountParseResult.NotPositive, AmountParser.parse("-5"))
        assertEquals(AmountParseResult.TooManyDecimals, AmountParser.parse("1.234"))
        assertEquals(AmountParseResult.TooLarge, AmountParser.parse("1000000000000.01"))
    }

    @Test
    fun `trailing zeros beyond allowed decimals are accepted`() {
        assertEquals(valid(150), AmountParser.parse("1.500"))
    }

    @Test
    fun `zero-decimal currency rejects fractions`() {
        assertEquals(AmountParseResult.TooManyDecimals, AmountParser.parse("10.5", maxFractionDigits = 0))
        assertEquals(valid(1_000), AmountParser.parse("10", maxFractionDigits = 0))
    }

    @Test
    fun `maximum amount is accepted`() {
        assertEquals(valid(Money.MAX_AMOUNT), AmountParser.parse("1000000000000"))
    }

    @Test
    fun `signed parser allows zero and negatives for opening balances`() {
        assertEquals(valid(0), AmountParser.parseSigned(""))
        assertEquals(valid(0), AmountParser.parseSigned("0"))
        assertEquals(valid(-2_550), AmountParser.parseSigned("-25.50"))
        assertEquals(valid(2_550), AmountParser.parseSigned("25.5"))
        assertEquals(AmountParseResult.NotANumber, AmountParser.parseSigned("--5"))
    }

    @Test
    fun `editable text round trips`() {
        assertEquals("123.45", Money.toEditableText(12_345))
        assertEquals("12", Money.toEditableText(1_200))
        assertEquals("0.05", Money.toEditableText(5))
        assertEquals("-3.5", Money.toEditableSignedText(-350))
        listOf(1L, 99L, 100L, 12_345L, Money.MAX_AMOUNT).forEach {
            assertEquals(valid(it), AmountParser.parse(Money.toEditableText(it)))
        }
    }

    @Test
    fun `INR uses Indian grouping and rupee symbol`() {
        val f = MoneyFormatter("INR")
        assertEquals(2, f.fractionDigits)
        assertEquals("₹1,23,45,678.90", f.format(12_345_678_90))
        assertEquals("₹1,00,000.00", f.format(10_000_000))
        assertEquals("₹99,999.99", f.format(9_999_999))
        assertEquals("₹999.05", f.format(99_905))
        assertEquals("₹0.00", f.format(0))
        assertEquals("₹10,00,00,00,00,000.00", f.format(Money.MAX_AMOUNT))
        assertEquals("−₹1,000.50", f.format(-100_050))
        assertEquals("+₹1,000.50", f.formatSigned(100_050))
    }

    @Test
    fun `formats negative and signed values with explicit signs`() {
        val f = MoneyFormatter("USD", Locale.US)
        assertEquals("$1,234.50", f.format(123_450))
        assertEquals("−$10.00", f.format(-1_000))
        assertEquals("+$10.00", f.formatSigned(1_000))
        assertEquals("−$10.00", f.formatSigned(-1_000))
        assertEquals("$0.00", f.formatSigned(0))
    }

    @Test
    fun `JPY shows no decimals`() {
        val f = MoneyFormatter("JPY", Locale.US)
        assertEquals(0, f.fractionDigits)
        assertEquals(true, f.format(150_000).endsWith("1,500"))
    }

    @Test
    fun `all supported currencies have zero or two decimals`() {
        MoneyFormatter.SUPPORTED.forEach { code ->
            val digits = java.util.Currency.getInstance(code).defaultFractionDigits
            assertEquals("$code has $digits digits", true, digits == 0 || digits == 2)
        }
    }
}
