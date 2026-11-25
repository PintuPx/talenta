package com.homebudget.app.domain.recurring

import com.homebudget.app.domain.model.Frequency
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import java.time.LocalDateTime

/** The repeat choices offered in the app. Each maps to a frequency and interval. */
enum class RepeatOption(val label: String, val frequency: Frequency, val interval: Int) {
    DAILY("Every day", Frequency.DAILY, 1),
    WEEKLY("Every week", Frequency.WEEKLY, 1),
    BIWEEKLY("Every 2 weeks", Frequency.WEEKLY, 2),
    MONTHLY("Every month", Frequency.MONTHLY, 1),
    QUARTERLY("Every 3 months", Frequency.MONTHLY, 3),
    YEARLY("Every year", Frequency.YEARLY, 1),
    ;

    companion object {
        fun of(frequency: Frequency, interval: Int): RepeatOption? =
            entries.firstOrNull { it.frequency == frequency && it.interval == interval }

        /** "Every month", or a generic label for an interval the app does not offer (e.g. from a backup). */
        fun label(frequency: Frequency, interval: Int): String = of(frequency, interval)?.label ?: run {
            val unit = when (frequency) {
                Frequency.DAILY -> "days"
                Frequency.WEEKLY -> "weeks"
                Frequency.MONTHLY -> "months"
                Frequency.YEARLY -> "years"
            }
            "Every $interval $unit"
        }
    }
}

object Recurrence {
    /** Safety cap on how many occurrences one rule may add in a single catch-up run. */
    const val MAX_CATCH_UP = 400

    /** Date-time of occurrence [n] (0-based). Always counted from the start, so month ends never drift. */
    fun occurrence(startAt: LocalDateTime, frequency: Frequency, interval: Int, n: Int): LocalDateTime {
        require(interval >= 1) { "interval must be at least 1" }
        require(n >= 0) { "n must not be negative" }
        val steps = n.toLong() * interval
        return when (frequency) {
            Frequency.DAILY -> startAt.plusDays(steps)
            Frequency.WEEKLY -> startAt.plusWeeks(steps)
            Frequency.MONTHLY -> startAt.plusMonths(steps)
            Frequency.YEARLY -> startAt.plusYears(steps)
        }
    }

    /** When the rule will next add a transaction. */
    fun nextDue(rule: RecurringRule): LocalDateTime = occurrence(rule.startAt, rule.frequency, rule.interval, rule.postedCount)

    /**
     * Occurrences of [rule] that are due at [now] and not yet added, oldest first (at most [max]).
     * A paused rule has none.
     */
    fun dueOccurrences(rule: RecurringRule, now: LocalDateTime, max: Int = MAX_CATCH_UP): List<LocalDateTime> {
        if (!rule.active) return emptyList()
        val result = ArrayList<LocalDateTime>()
        var n = rule.postedCount
        while (result.size < max) {
            val at = occurrence(rule.startAt, rule.frequency, rule.interval, n)
            if (at > now) break
            result += at
            n++
        }
        return result
    }

    /** The transaction a rule adds for the occurrence at [at]. */
    fun toTransaction(rule: RecurringRule, at: LocalDateTime): Transaction = Transaction(
        type = rule.type,
        amount = rule.amount,
        accountId = rule.accountId,
        toAccountId = if (rule.type == TransactionType.TRANSFER) rule.toAccountId else null,
        categoryId = if (rule.type == TransactionType.TRANSFER) null else rule.categoryId,
        isRefund = rule.type == TransactionType.EXPENSE && rule.isRefund,
        occurredAt = at,
        note = rule.note,
        payee = rule.payee,
        recurringId = rule.id,
    )
}
