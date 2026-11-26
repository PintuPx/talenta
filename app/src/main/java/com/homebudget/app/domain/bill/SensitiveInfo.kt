package com.homebudget.app.domain.bill

/**
 * Finds payment secrets in text read from a photo, so the app can warn the user and never store a full
 * card number. A card number is 13 to 19 digits (spaces or dashes allowed between groups) that pass
 * the Luhn checksum; ordinary bill numbers rarely do. Keywords catch PINs, OTPs and CVVs.
 */
object SensitiveInfo {
    enum class Kind(val label: String) { CARD_NUMBER("a card number"), PIN("a PIN"), OTP("an OTP"), CVV("a CVV"), EXPIRY("a card expiry date") }

    private val CANDIDATE = Regex("(?<![\\d])(?:\\d[ -]?){12,18}\\d(?![\\d])")
    private val KEYWORDS = listOf(
        Kind.CVV to Regex("\\b(cvv2?|cvc2?|card verification)\\b", RegexOption.IGNORE_CASE),
        Kind.PIN to Regex("\\b(upi\\s*pin|atm\\s*pin|m-?pin|pin\\s*(no|number|code)?\\s*[:#]?\\s*\\d{4,6})\\b", RegexOption.IGNORE_CASE),
        Kind.OTP to Regex("\\b(otp|one[- ]time password)\\b", RegexOption.IGNORE_CASE),
        Kind.EXPIRY to Regex("\\b(valid\\s*thru|valid\\s*through|expiry|exp\\.?\\s*date)\\b", RegexOption.IGNORE_CASE),
    )

    fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19 || digits.any { !it.isDigit() }) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    /** Card numbers in [text] (digits only). */
    fun cardNumbers(text: String): List<String> =
        CANDIDATE.findAll(text).map { m -> m.value.filter(Char::isDigit) }.filter(::luhn).distinct().toList()

    /** What sensitive things [text] seems to contain, in a stable order. */
    fun find(text: String): Set<Kind> {
        val out = LinkedHashSet<Kind>()
        if (cardNumbers(text).isNotEmpty()) out += Kind.CARD_NUMBER
        KEYWORDS.forEach { (kind, re) -> if (re.containsMatchIn(text)) out += kind }
        return out
    }

    /** Replaces every card number with grouped X's except its last four digits. */
    fun mask(text: String): String = CANDIDATE.replace(text) { m ->
        val digits = m.value.filter(Char::isDigit)
        if (!luhn(digits)) return@replace m.value
        ("X".repeat(digits.length - 4) + digits.takeLast(4)).chunked(4).joinToString(" ")
    }

    /** "a card number and a PIN" for the warning dialog. */
    fun describe(kinds: Set<Kind>): String {
        val labels = kinds.map { it.label }
        return when (labels.size) {
            0 -> ""
            1 -> labels[0]
            else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
        }
    }
}
