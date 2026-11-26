package com.homebudget.app.data.repository

import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.Frequency
import com.homebudget.app.domain.money.MoneyFormatter
import com.homebudget.app.domain.recurring.RepeatOption
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Turns stored snapshot fields (see the *Snapshot classes in data/db/Mappers.kt) into the text shown in
 * history diffs. Display only: stored snapshots are never changed.
 *
 * - Money (Long minor units) goes through [MoneyFormatter] in the selected currency.
 * - Floating local times (epoch seconds, see [FloatingTime]) and real instants (epoch millis) become
 *   readable dates and times, using the same localized styles as `ui.common.DateFormats`.
 * - Internal fields (ids, colours, icons, sort order, counters, format versions) are hidden.
 */
class HistoryFieldFormatter(
    private val money: MoneyFormatter,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun isHidden(key: String): Boolean =
        key in HIDDEN_FIELDS || key.endsWith("Uid") || key.endsWith("_uid") || key.endsWith("_id") || key.endsWith("Id")

    /** "occurredAt" / "occurred_at" -> "Occurred at" (sentence case, like the rest of the UI). */
    fun label(key: String): String = FIELD_LABELS[key] ?: key
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar { it.uppercase() }

    /** Display text for [key] in [snapshot] (a snapshot of an [entityType] row); "—" when absent. */
    fun value(entityType: String, key: String, snapshot: JsonObject): String {
        val element = snapshot[key] ?: return NONE
        if (element == JsonNull) return NONE
        val primitive = element as? JsonPrimitive ?: return if (element is JsonObject) "[details]" else element.toString()
        val text = primitive.content
        if (!primitive.isString) {
            primitive.booleanOrNull?.let { return if (it) "Yes" else "No" }
            val number = primitive.longOrNull
            if (number != null) {
                when (key) {
                    in MONEY_FIELDS -> return money.format(number)
                    in FLOATING_TIME_FIELDS -> return dateTime(FloatingTime.fromStored(number))
                    in INSTANT_FIELDS -> return dateTime(Instant.ofEpochMilli(number).atZone(zone).toLocalDateTime())
                    in MONTH_FIELDS -> month(number)?.let { return it }
                }
            }
        }
        return enumLabel(entityType, key, text, snapshot) ?: text
    }

    private fun enumLabel(entityType: String, key: String, value: String, snapshot: JsonObject): String? {
        if (key !in ENUM_FIELDS || !ENUM_VALUE.matches(value)) return null
        return when {
            key == "type" && entityType == "account" ->
                runCatching { AccountType.valueOf(value) }.getOrNull()?.let(::accountTypeLabel)
            key == "status" && entityType == "bill" -> when (value) {
                "DRAFT" -> "Draft"
                "POSTED" -> "Added to expenses"
                else -> null
            }
            key == "frequency" -> runCatching { Frequency.valueOf(value) }.getOrNull()?.let { frequency ->
                val interval = (snapshot["interval"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: 1
                RepeatOption.label(frequency, interval)
            }
            else -> null
        } ?: sentenceCase(value)
    }

    private fun dateTime(t: LocalDateTime): String = "${t.format(DATE)} · ${t.format(TIME)}"

    private fun month(stored: Long): String? {
        val year = (stored / 100).toInt()
        val monthValue = (stored % 100).toInt()
        if (year !in 1..9999 || monthValue !in 1..12) return null
        return YearMonth.of(year, monthValue).format(MONTH)
    }

    companion object {
        private const val NONE = "—"

        /** Same styles as `ui.common.DateFormats.date` / `.time` / `.month`. */
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

        private val ENUM_VALUE = Regex("^[A-Z][A-Z0-9_]*$")

        val MONEY_FIELDS = setOf("amount", "openingBalance", "limitAmount", "valueMinor", "shareAmount", "total")

        /** Floating local date-times stored as epoch seconds (TransactionEntity, BillEntity, RecurringRuleEntity). */
        val FLOATING_TIME_FIELDS = setOf("occurredAt", "startAt", "billDate")

        /** Real instants stored as epoch millis (Clock.millis()). */
        val INSTANT_FIELDS = setOf("createdAt", "updatedAt", "closedAt", "recordedAt", "lastSeenAt")

        /** yyyyMM ints (BudgetEntity, MonthLockEntity). */
        val MONTH_FIELDS = setOf("month")

        val ENUM_FIELDS = setOf("type", "kind", "status", "role", "frequency")

        val HIDDEN_FIELDS = setOf(
            "id", "uid", "createdAt", "created_at", "updatedAt", "updated_at", "isPrivate", "is_private",
            "imageFile", "image_file", "ocrText", "ocr_text", "sortOrder", "sort_order",
            "colorArgb", "color_argb", "iconKey", "icon_key", "position", "tombstone",
            // Shown as part of "Repeats" (frequency).
            "interval",
            // Internal counters and app/sync format versions.
            "postedCount", "useCount", "syncFormat", "appVersionCode", "snapshotFormat", "snapshot_format",
        )

        private val FIELD_LABELS = mapOf(
            "isRefund" to "Refund",
            "includeInTotal" to "Include in total balance",
            "limitAmount" to "Limit",
            "valueMinor" to "Value",
            "shareAmount" to "Share",
            "frequency" to "Repeats",
            "appVersionName" to "App version",
        )

        /** Same labels as `ui.common.label()` for [AccountType] (kept in step by a unit test). */
        fun accountTypeLabel(type: AccountType): String = when (type) {
            AccountType.CASH -> "Cash"
            AccountType.SAVINGS -> "Savings account"
            AccountType.CURRENT -> "Current account"
            AccountType.CREDIT_CARD -> "Credit card"
            AccountType.INVESTMENT -> "Investment"
            AccountType.OTHER -> "Other"
        }

        /** "CREDIT_CARD" -> "Credit card". */
        fun sentenceCase(value: String): String =
            value.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
}
