package com.homebudget.app.domain.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Money is stored as a [Long] count of hundredths of the selected currency unit (paise for INR,
 * cents for USD). Floating point is never used for stored values or arithmetic.
 *
 * The scale is fixed at 100 for every supported currency, so changing the display currency never
 * re-scales stored values. Zero-decimal currencies (e.g. JPY) simply always have a remainder of 0.
 * Currencies with three decimals (e.g. KWD) are not supported in this version.
 */
object Money {
    const val SCALE = 100L
    const val MAX_FRACTION_DIGITS = 2

    /** Largest single amount accepted: 1,000,000,000,000.00 (10^14 minor units). Keeps sums far from Long overflow. */
    const val MAX_AMOUNT = 100_000_000_000_000L

    fun toBigDecimal(minor: Long): BigDecimal = BigDecimal.valueOf(minor, 2)

    /** Plain editable text for a stored amount, e.g. 12345 -> "123.45", 1200 -> "12". */
    fun toEditableText(minor: Long): String = toBigDecimal(minor).stripTrailingZeros().toPlainString()

    /** Like [toEditableText] but allows negative values (opening balances). */
    fun toEditableSignedText(minor: Long): String =
        if (minor < 0) "-" + toEditableText(-minor) else toEditableText(minor)
}

sealed interface AmountParseResult {
    data class Valid(val minor: Long) : AmountParseResult
    data object Empty : AmountParseResult
    data object NotANumber : AmountParseResult
    data object TooManyDecimals : AmountParseResult
    data object NotPositive : AmountParseResult
    data object TooLarge : AmountParseResult
}

object AmountParser {
    private val allowed = Regex("^[0-9]*([.][0-9]*)?$")

    /**
     * Parses user input into strictly positive minor units without floating point.
     * Accepts "." as the decimal separator; "," is treated as a decimal separator only when it is
     * the sole separator and is followed by 1-2 digits (e.g. "12,5"), otherwise as a grouping mark.
     */
    fun parse(input: String, maxFractionDigits: Int = Money.MAX_FRACTION_DIGITS): AmountParseResult {
        val r = parseMagnitude(input, maxFractionDigits)
        return if (r is AmountParseResult.Valid && r.minor == 0L) AmountParseResult.NotPositive else r
    }

    /**
     * Parses a signed amount where zero is allowed (used for opening balances).
     * A leading "-" makes the value negative.
     */
    fun parseSigned(input: String, maxFractionDigits: Int = Money.MAX_FRACTION_DIGITS): AmountParseResult {
        val t = input.trim()
        if (t.isEmpty()) return AmountParseResult.Valid(0)
        val negative = t.startsWith("-") || t.startsWith("−")
        val body = if (negative) t.substring(1) else t
        if (body.startsWith("-") || body.startsWith("−")) return AmountParseResult.NotANumber
        return when (val r = parseMagnitude(body, maxFractionDigits)) {
            is AmountParseResult.Valid -> AmountParseResult.Valid(if (negative) -r.minor else r.minor)
            else -> r
        }
    }

    private fun parseMagnitude(input: String, maxFractionDigits: Int): AmountParseResult {
        var s = input.trim().replace(" ", "").replace(" ", "")
        if (s.isEmpty()) return AmountParseResult.Empty
        if (s.startsWith("-") || s.startsWith("−")) return AmountParseResult.NotPositive
        s = when {
            s.contains('.') -> s.replace(",", "")
            s.count { it == ',' } == 1 && s.substringAfter(',').length in 1..2 -> s.replace(',', '.')
            else -> s.replace(",", "")
        }
        if (s.isEmpty() || s == "." || !allowed.matches(s)) return AmountParseResult.NotANumber
        val value = try {
            BigDecimal(if (s.startsWith(".")) "0$s" else s)
        } catch (e: NumberFormatException) {
            return AmountParseResult.NotANumber
        }
        if (value.signum() != 0 && value.stripTrailingZeros().scale() > maxFractionDigits) {
            return AmountParseResult.TooManyDecimals
        }
        val minor = value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY)
        if (minor > BigDecimal.valueOf(Money.MAX_AMOUNT)) return AmountParseResult.TooLarge
        return AmountParseResult.Valid(minor.longValueExact())
    }
}

/**
 * Locale-aware currency formatter. Default currency INR uses en-IN grouping (1,00,000.00).
 */
class MoneyFormatter(
    val currencyCode: String,
    locale: Locale = defaultLocaleFor(currencyCode),
) {
    private val currency: Currency = Currency.getInstance(currencyCode)

    /** Fraction digits shown and accepted for input: 0 or 2. */
    val fractionDigits: Int = currency.defaultFractionDigits.coerceIn(0, Money.MAX_FRACTION_DIGITS)

    private val format: NumberFormat = NumberFormat.getCurrencyInstance(locale).apply {
        currency = this@MoneyFormatter.currency
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
        roundingMode = RoundingMode.HALF_EVEN
    }

    val symbol: String = if (currencyCode == "INR") "₹" else currency.getSymbol(locale)

    /** Formats minor units, e.g. 123456 -> "₹1,234.56". Negative values use a leading minus sign. */
    fun format(minor: Long): String =
        if (minor < 0) "−" + formatAbs(-minor) else formatAbs(minor)

    /** Formats with an explicit sign: "+₹10.00" / "−₹10.00" / "₹0.00". */
    fun formatSigned(minor: Long): String = when {
        minor > 0 -> "+" + formatAbs(minor)
        minor < 0 -> "−" + formatAbs(-minor)
        else -> formatAbs(0)
    }

    private fun formatAbs(minor: Long): String =
        if (currencyCode == "INR") formatIndian(minor)
        else synchronized(format) { format.format(Money.toBigDecimal(minor)) }

    /**
     * Deterministic Indian grouping (lakh/crore): ₹1,23,45,678.90. Implemented directly because
     * java.text does not support variable grouping sizes and platform output differs across versions.
     */
    private fun formatIndian(minor: Long): String {
        val rupees = (minor / 100).toString()
        val paise = (minor % 100).toString().padStart(2, '0')
        val grouped = if (rupees.length <= 3) rupees else {
            val last3 = rupees.takeLast(3)
            val rest = rupees.dropLast(3)
            rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
        }
        return "₹$grouped.$paise"
    }

    companion object {
        const val DEFAULT_CURRENCY = "INR"

        /** Currencies offered in settings. All have 0 or 2 decimal places (see [Money]). */
        val SUPPORTED = listOf(
            "INR", "USD", "EUR", "GBP", "AED", "AUD", "CAD", "SGD", "JPY", "CHF", "CNY",
            "SAR", "QAR", "MYR", "NZD", "ZAR", "LKR", "NPR", "BDT", "PKR",
        )

        fun defaultLocaleFor(code: String): Locale = when (code) {
            "INR" -> Locale.forLanguageTag("en-IN")
            else -> Locale.getDefault()
        }
    }
}
