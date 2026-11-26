package com.homebudget.app.domain.bill

import com.homebudget.app.domain.money.AmountParseResult
import com.homebudget.app.domain.money.AmountParser

object BillTextSelection {
    fun splitLines(text: String): List<String> =
        text.split(Regex("\\r\\n|\\r|\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun extractAmountMinor(line: String, fractionDigits: Int): Long? {
        val matches = Regex("""(?:₹\s*|Rs\.?\s*|\$\s*|€\s*)?[0-9][0-9]*(?:[,.][0-9]+)*""")
            .findAll(line)
            .map { it.value.trim().removePrefix("₹").removePrefix("$").removePrefix("€").replace(Regex("""^Rs\.?\s*"""), "").trim() }
            .toList()

        return matches
            .asReversed()
            .mapNotNull { candidate ->
                (AmountParser.parse(candidate, fractionDigits) as? AmountParseResult.Valid)?.minor
            }
            .firstOrNull()
    }
}
