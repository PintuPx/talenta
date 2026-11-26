package com.homebudget.app.data.repository

import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.money.MoneyFormatter
import com.homebudget.app.ui.common.DateFormats
import com.homebudget.app.ui.common.label
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class HistoryFieldFormatterTest {
    private val formatter = HistoryFieldFormatter(MoneyFormatter("INR"), ZoneOffset.UTC)

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun accountTypeLabelsMatchTheRestOfTheApp() {
        AccountType.entries.forEach { assertEquals(it.label(), HistoryFieldFormatter.accountTypeLabel(it)) }
    }

    @Test
    fun everyMoneyFieldIsFormattedAndNullMoneyIsADash() {
        HistoryFieldFormatter.MONEY_FIELDS.forEach { key ->
            assertEquals(key, "₹1,200.00", formatter.value("transaction", key, json("""{"$key":120000}""")))
        }
        assertEquals("—", formatter.value("bill", "total", json("""{"total":null}""")))
        assertEquals("—", formatter.value("bill", "total", json("""{}""")))
    }

    @Test
    fun floatingAndInstantTimesUseTheAppDateFormats() {
        val stored = 1_795_652_580L
        val local = FloatingTime.fromStored(stored)
        val expected = "${local.format(DateFormats.date)} · ${local.format(DateFormats.time)}"
        listOf("occurredAt", "startAt", "billDate").forEach { key ->
            assertEquals(key, expected, formatter.value("transaction", key, json("""{"$key":$stored}""")))
        }
        // Real instants are epoch millis; the UTC zone above makes the wall clock equal to the floating one.
        listOf("closedAt", "recordedAt", "lastSeenAt").forEach { key ->
            assertEquals(key, expected, formatter.value("month_lock", key, json("""{"$key":${stored * 1000}}""")))
        }
    }

    @Test
    fun booleansAndEnumsReadNaturallyButUserTextIsLeftAlone() {
        assertEquals("Yes", formatter.value("transaction", "isRefund", json("""{"isRefund":true}""")))
        assertEquals("No", formatter.value("category", "active", json("""{"active":false}""")))
        assertEquals("Transfer", formatter.value("transaction", "type", json("""{"type":"TRANSFER"}""")))
        assertEquals("Admin", formatter.value("member", "role", json("""{"role":"ADMIN"}""")))
        assertEquals("Every 2 weeks", formatter.value("recurring_rule", "frequency", json("""{"frequency":"WEEKLY","interval":2}""")))
        // Text the user typed is never re-cased, even when it looks like a constant.
        assertEquals("ATM", formatter.value("transaction", "payee", json("""{"payee":"ATM"}""")))
        assertEquals("true", formatter.value("transaction", "note", json("""{"note":"true"}""")))
        assertEquals("120000", formatter.value("transaction", "note", json("""{"note":"120000"}""")))
    }

    @Test
    fun internalFieldsAreHiddenAndLabelsAreSentenceCase() {
        listOf(
            "id", "uid", "isPrivate", "colorArgb", "iconKey", "sortOrder", "createdAt", "updatedAt", "accountUid",
            "memberId", "interval", "postedCount", "syncFormat", "tombstone", "imageFile", "ocrText",
        ).forEach { assertTrue(it, formatter.isHidden(it)) }
        listOf("amount", "payee", "note", "occurredAt", "name", "closedAt", "month").forEach { assertFalse(it, formatter.isHidden(it)) }
        assertEquals("Occurred at", formatter.label("occurredAt"))
        assertEquals("Opening balance", formatter.label("openingBalance"))
        assertEquals("Limit", formatter.label("limitAmount"))
        assertEquals("Repeats", formatter.label("frequency"))
    }
}
