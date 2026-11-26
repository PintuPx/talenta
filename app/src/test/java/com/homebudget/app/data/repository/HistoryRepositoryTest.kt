package com.homebudget.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.EntityVersionEntity
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.settings.CurrencyProvider
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.history.HistoryChange
import com.homebudget.app.domain.history.HistoryMarker
import com.homebudget.app.domain.money.MoneyFormatter
import com.homebudget.app.ui.common.DateFormats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class HistoryRepositoryTest {
    private lateinit var db: AppDatabase
    private val space = TestSpaceProvider()
    private lateinit var repository: RoomHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        repository = RoomHistoryRepository(db, space)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun editsAreOrderedNewestFirstWithFieldDiffsAndNoInternalFields() = runTest {
        insert("tx-1", 1, 1_000, """{"uid":"tx-1","accountUid":"acc-1","isPrivate":false,"payee":"Fake Cafe","amount":500,"occurredAt":$OCCURRED_1}""", kind = "ADD")
        insert("tx-1", 2, 2_000, """{"uid":"tx-1","accountUid":"acc-2","isPrivate":false,"payee":"Fake Cafe","amount":750,"occurredAt":$OCCURRED_1}""")
        insert("tx-1", 3, 3_000, """{"uid":"tx-1","accountUid":"acc-2","isPrivate":false,"payee":"Fake Deli","amount":750,"occurredAt":$OCCURRED_2}""")

        val entry = repository.observeEntity("transaction", "tx-1").first()!!

        assertEquals(listOf(3, 2, 1), entry.versions.map { it.version })
        assertEquals("Fake Deli", entry.label)

        val latest = entry.versions[0].changes
        assertEquals(listOf("Occurred at", "Payee"), latest.map { it.field })
        assertEquals("Fake Cafe", latest.single { it.field == "Payee" }.before)
        assertEquals("Fake Deli", latest.single { it.field == "Payee" }.after)
        assertEquals(floating(OCCURRED_1), latest.single { it.field == "Occurred at" }.before)
        assertEquals(floating(OCCURRED_2), latest.single { it.field == "Occurred at" }.after)

        val middle = entry.versions[1].changes
        assertEquals(listOf("Amount"), middle.map { it.field }) // accountUid change is internal and hidden
        assertEquals("₹5.00", middle.single().before)
        assertEquals("₹7.50", middle.single().after)

        val first = entry.versions[2].changes
        assertTrue(first.all { it.before == "—" })
        assertTrue(first.none { it.field in setOf("Uid", "Account uid", "Is private") })
        assertTrue(first.any { it.field == "Amount" && it.after == "₹5.00" })
    }

    @Test
    fun unknownEntityEmitsNullAndMergedRequestRowsAreHidden() = runTest {
        assertNull(repository.observeEntity("transaction", "missing").first())

        insert("tx-merge", 1, 1_000, """{"uid":"tx-merge","payee":"Fake"}""")
        insert("tx-merge", 1, 2_000, """{"uid":"tx-merge","payee":"Merged"}""", kind = "MERGED_REQUEST")

        val entry = repository.observeEntity("transaction", "tx-merge").first()!!
        assertEquals(1, entry.versions.size)
        assertEquals(1, repository.observeAll().first().size)
    }

    @Test
    fun deleteAndRestoreMarkersAreReported() = runTest {
        insert("tx-2", 1, 1_000, """{"uid":"tx-2","payee":"Fake"}""", kind = "ADD")
        insert("tx-2", 2, 2_000, """{"uid":"tx-2","payee":"Fake"}""", kind = "DELETE", deleted = true)
        insert("tx-2", 3, 3_000, """{"uid":"tx-2","payee":"Fake"}""", kind = "RESTORE")
        insert("tx-2", 4, 4_000, """{"uid":"tx-2","payee":"Fake"}""", kind = "UNDO")

        val markers = repository.observeEntity("transaction", "tx-2").first()!!.versions.associate { it.version to it.marker }

        assertEquals(HistoryMarker.NORMAL, markers[1])
        assertEquals(HistoryMarker.DELETED, markers[2])
        assertEquals(HistoryMarker.RESTORED, markers[3])
        assertEquals(HistoryMarker.RESTORED, markers[4])
        assertEquals(
            listOf(HistoryMarker.RESTORED, HistoryMarker.RESTORED, HistoryMarker.DELETED, HistoryMarker.NORMAL),
            repository.observeAll().first().map { it.marker },
        )
    }

    @Test
    fun entryLabelPrefersNameThenMerchantThenPayeeAndFallsBackToTypeAndUid() = runTest {
        insert("lbl-1", 1, 1, """{"uid":"lbl-1","payee":"Fake Payee","merchant":"Fake Merchant","name":"Fake Name"}""")
        insert("lbl-2", 1, 1, """{"uid":"lbl-2","payee":"Fake Payee","merchant":"Fake Merchant"}""")
        insert("lbl-3", 1, 1, """{"uid":"lbl-3","payee":"Fake Payee"}""")
        insert("lbl-4-long-uid", 1, 1, """{"uid":"lbl-4-long-uid","amount":5}""", type = "recurring_rule")

        assertEquals("Fake Name", repository.observeEntity("transaction", "lbl-1").first()!!.label)
        assertEquals("Fake Merchant", repository.observeEntity("transaction", "lbl-2").first()!!.label)
        assertEquals("Fake Payee", repository.observeEntity("transaction", "lbl-3").first()!!.label)
        assertEquals("recurring rule lbl-4-lo", repository.observeEntity("recurring_rule", "lbl-4-long-uid").first()!!.label)
    }

    @Test
    fun sharedSpaceHidesVersionsRecordedWhilePrivateAndPrivateSpaceShowsThem() = runTest {
        // Shared era (v1, v2), then made private: shared tombstone v3 and private copy v1, then a private edit v2.
        insert("tx-3", 1, 1_000, """{"uid":"tx-3","payee":"Fake Cafe","amount":500}""", kind = "ADD")
        insert("tx-3", 2, 2_000, """{"uid":"tx-3","payee":"Fake Cafe","amount":600}""")
        insert("tx-3", 3, 3_000, """{"uid":"tx-3","tombstone":true}""", kind = "MAKE_PRIVATE", deleted = true)
        insert("tx-3", 1, 3_000, """{"uid":"tx-3","payee":"Fake Cafe","amount":600}""", kind = "MAKE_PRIVATE", isPrivate = true)
        insert("tx-3", 2, 4_000, """{"uid":"tx-3","payee":"Secret Clinic","amount":99900}""", isPrivate = true)

        space.value.value = Space.SHARED
        val shared = repository.observeEntity("transaction", "tx-3").first()!!
        assertEquals(listOf(3, 2, 1), shared.versions.map { it.version })
        val sharedText = shared.versions.flatMap { it.changes }.joinToString { "${it.before}|${it.after}" }
        assertTrue("private payee leaked into shared history", !sharedText.contains("Secret Clinic"))
        assertTrue("private amount leaked into shared history", !sharedText.contains("99900"))
        assertTrue("private amount leaked into shared history", !sharedText.contains("₹999.00"))
        assertEquals(HistoryMarker.DELETED, shared.versions.first().marker)

        space.value.value = Space.PRIVATE
        val privateView = repository.observeEntity("transaction", "tx-3").first()!!
        assertEquals(listOf(2, 1), privateView.versions.map { it.version })
        assertEquals("Secret Clinic", privateView.label)
        val edit = privateView.versions.first().changes
        assertEquals(listOf("Amount", "Payee"), edit.map { it.field })
        assertEquals("₹999.00", edit.single { it.field == "Amount" }.after)
    }

    @Test
    fun entryThatOnlyExistsPrivatelyHasNoSharedHistory() = runTest {
        insert("tx-only-private", 1, 1_000, """{"uid":"tx-only-private","payee":"Secret"}""", isPrivate = true)

        space.value.value = Space.SHARED
        assertNull(repository.observeEntity("transaction", "tx-only-private").first())
        space.value.value = Space.PRIVATE
        assertEquals(1, repository.observeEntity("transaction", "tx-only-private").first()!!.versions.size)
    }

    @Test
    fun switchingTheSpaceFlowReEmitsTheNewSpacesVersions() = runTest {
        insert("tx-4", 1, 1_000, """{"uid":"tx-4","payee":"Fake Shared"}""")
        insert("tx-4", 1, 2_000, """{"uid":"tx-4","payee":"Fake Private"}""", isPrivate = true)
        insert("tx-4", 2, 3_000, """{"uid":"tx-4","payee":"Fake Private 2"}""", isPrivate = true)
        space.value.value = Space.SHARED

        var switched = false
        val emissions = repository.observeEntity("transaction", "tx-4")
            .onEach {
                if (!switched) {
                    switched = true
                    space.value.value = Space.PRIVATE
                }
            }
            .take(2)
            .toList()

        assertEquals(1, emissions[0]!!.versions.size)
        assertEquals("Fake Shared", emissions[0]!!.label)
        assertEquals(2, emissions[1]!!.versions.size)
        assertEquals("Fake Private 2", emissions[1]!!.label)
    }

    @Test
    fun householdHistoryReturnsOnlySharedRows() = runTest {
        insert("tx-5", 1, 1_000, """{"uid":"tx-5","payee":"Fake Shared"}""")
        insert("tx-5", 1, 2_000, """{"uid":"tx-5","payee":"Secret"}""", isPrivate = true)
        insert("acc-5", 1, 3_000, """{"uid":"acc-5","name":"Secret Account"}""", type = "account", isPrivate = true)

        space.value.value = Space.PRIVATE // the admin household view must not depend on the active space
        val rows = repository.observeAll().first()

        assertEquals(1, rows.size)
        assertEquals("tx-5", rows.single().entityUid)
        assertEquals("Fake Shared", rows.single().entryLabel)
    }

    @Test
    fun householdHistoryHonoursMemberEntityTypeAndDateFilters() = runTest {
        insert("tx-6", 1, 1_000, """{"uid":"tx-6","amount":100}""", author = "Alice")
        insert("tx-6", 2, 2_000, """{"uid":"tx-6","amount":200}""", author = "Bob")
        insert("acc-6", 1, 3_000, """{"uid":"acc-6","name":"Fake Wallet"}""", type = "account", author = "Alice")
        insert("tx-7", 1, 4_000, """{"uid":"tx-7","amount":300}""", author = "Alice")

        assertEquals(4, repository.observeAll().first().size)
        assertEquals(4, repository.observeAll(member = " ", entityType = "").first().size)

        assertEquals(3, repository.observeAll(member = "alice").first().size)
        assertEquals(listOf("tx-6"), repository.observeAll(member = "BOB").first().map { it.entityUid })
        assertEquals(listOf("acc-6"), repository.observeAll(entityType = "ACCOUNT").first().map { it.entityUid })
        assertEquals(listOf("acc-6"), repository.observeAll(member = "Alice", entityType = "account").first().map { it.entityUid })

        // startAt inclusive, endAt exclusive.
        assertEquals(listOf("acc-6", "tx-6"), repository.observeAll(startAt = 2_000, endAt = 4_000).first().map { it.entityUid })
        assertEquals(listOf("tx-7"), repository.observeAll(startAt = 4_000).first().map { it.entityUid })
        assertEquals(listOf("tx-6"), repository.observeAll(endAt = 1_001).first().map { it.entityUid })
        assertTrue(repository.observeAll(member = "Nobody").first().isEmpty())
    }

    @Test
    fun householdHistoryRowsDiffAgainstThePreviousVersionEvenWhenFiltered() = runTest {
        insert("tx-8", 1, 1_000, """{"uid":"tx-8","amount":100,"payee":"Fake"}""", author = "Alice")
        insert("tx-8", 2, 2_000, """{"uid":"tx-8","amount":250,"payee":"Fake"}""", author = "Bob")

        val row = repository.observeAll(member = "Bob").first().single()

        assertEquals(2, row.version)
        assertEquals("Bob", row.member)
        assertEquals("transaction", row.entityType)
        val change = row.changes.single()
        assertEquals("Amount", change.field)
        assertEquals("₹1.00", change.before)
        assertEquals("₹2.50", change.after)
        assertEquals(HistoryMarker.NORMAL, row.marker)
        assertEquals("APPROVED", row.status)
    }

    @Test
    fun transactionDiffShowsFormattedMoneyReadableDatesSentenceCaseEnumsAndYesNo() = runTest {
        insert("tx-fmt", 1, 1_000, transactionJson(amount = 120_000, occurredAt = OCCURRED_1, type = "EXPENSE", isRefund = false))
        insert("tx-fmt", 2, 2_000, transactionJson(amount = 150_050, occurredAt = OCCURRED_2, type = "INCOME", isRefund = true))

        val versions = repository.observeEntity("transaction", "tx-fmt").first()!!.versions
        val edit = versions[0].changes.byField()

        assertEquals(listOf("Amount", "Occurred at", "Refund", "Type"), versions[0].changes.map { it.field })
        assertEquals("₹1,200.00" to "₹1,500.50", edit.getValue("Amount"))
        assertEquals(floating(OCCURRED_1) to floating(OCCURRED_2), edit.getValue("Occurred at"))
        assertEquals("No" to "Yes", edit.getValue("Refund"))
        assertEquals("Expense" to "Income", edit.getValue("Type"))

        val created = versions[1].changes
        assertTrue(created.any { it.field == "Amount" && it.after == "₹1,200.00" })
        val allText = versions.flatMap { it.changes }.joinToString { "${it.field}|${it.before}|${it.after}" }
        assertFalse("raw minor units shown", allText.contains("120000"))
        assertFalse("raw epoch seconds shown", allText.contains(OCCURRED_1.toString()))
        assertFalse(
            "internal fields shown",
            listOf("Uid", "Account uid", "Is private", "Created at", "Updated at", "Id|").any(allText::contains),
        )
    }

    @Test
    fun moneyUsesTheSelectedCurrency() = runTest {
        val usd = RoomHistoryRepository(db, space, FixedCurrency("USD"))
        insert("tx-usd", 1, 1_000, """{"uid":"tx-usd","amount":1234}""")
        insert("tx-usd", 2, 2_000, """{"uid":"tx-usd","amount":5678}""")

        val change = usd.observeEntity("transaction", "tx-usd").first()!!.versions.first().changes.single()

        val expected = MoneyFormatter("USD")
        assertEquals(expected.format(1234) to expected.format(5678), change.before to change.after)
        assertFalse(change.after.contains("₹"))
        assertEquals(expected.format(5678), usd.observeAll().first().first().changes.single().after)
    }

    @Test
    fun categoryDiffHidesColourIconAndSortOrder() = runTest {
        insert("cat-1", 1, 1_000, categoryJson(kind = "INCOME", color = 4_283_215_696, icon = "food", sortOrder = 1, active = true), type = "category")
        insert("cat-1", 2, 2_000, categoryJson(kind = "EXPENSE", color = 4_294_198_070, icon = "shopping", sortOrder = 7, active = false), type = "category")

        val changes = repository.observeEntity("category", "cat-1").first()!!.versions.first().changes
        assertEquals(listOf("Active", "Kind"), changes.map { it.field })
        assertEquals("Yes" to "No", changes.byField().getValue("Active"))
        assertEquals("Income" to "Expense", changes.byField().getValue("Kind"))
        val text = changes.joinToString { "${it.field}|${it.before}|${it.after}" }
        assertFalse(listOf("4283215696", "4294198070", "food", "shopping", "Color", "Icon", "Sort").any(text::contains))

        // A colour/icon/order-only edit has nothing to show.
        insert("cat-2", 1, 1_000, categoryJson(kind = "EXPENSE", color = 1, icon = "food", sortOrder = 1, active = true), type = "category")
        insert("cat-2", 2, 2_000, categoryJson(kind = "EXPENSE", color = 2, icon = "bus", sortOrder = 2, active = true), type = "category")
        assertTrue(repository.observeEntity("category", "cat-2").first()!!.versions.first().changes.isEmpty())
    }

    @Test
    fun accountRecurringBudgetMonthLockAndBillFieldsUseAppLabels() = runTest {
        insert("acc-f", 1, 1_000, """{"uid":"acc-f","name":"Fake Card","type":"SAVINGS","openingBalance":0,"includeInTotal":true,"sortOrder":0}""", type = "account")
        insert("acc-f", 2, 2_000, """{"uid":"acc-f","name":"Fake Card","type":"CREDIT_CARD","openingBalance":-50000,"includeInTotal":false,"sortOrder":3}""", type = "account")
        val account = repository.observeEntity("account", "acc-f").first()!!.versions.first().changes.byField()
        assertEquals(setOf("Include in total balance", "Opening balance", "Type"), account.keys)
        assertEquals("Savings account" to "Credit card", account.getValue("Type"))
        assertEquals("₹0.00" to "−₹500.00", account.getValue("Opening balance"))
        assertEquals("Yes" to "No", account.getValue("Include in total balance"))

        insert("rr-f", 1, 1_000, """{"uid":"rr-f","amount":99900,"frequency":"MONTHLY","interval":1,"startAt":$OCCURRED_1,"postedCount":2}""", type = "recurring_rule")
        insert("rr-f", 2, 2_000, """{"uid":"rr-f","amount":99900,"frequency":"MONTHLY","interval":3,"startAt":$OCCURRED_2,"postedCount":5}""", type = "recurring_rule")
        val rule = repository.observeEntity("recurring_rule", "rr-f").first()!!.versions.first().changes.byField()
        assertEquals(setOf("Repeats", "Start at"), rule.keys) // interval folds into Repeats; postedCount is internal
        assertEquals("Every month" to "Every 3 months", rule.getValue("Repeats"))
        assertEquals(floating(OCCURRED_1) to floating(OCCURRED_2), rule.getValue("Start at"))

        insert("bud-f", 1, 1_000, """{"uid":"bud-f","month":202611,"limitAmount":500000}""", type = "budget")
        insert("bud-f", 2, 2_000, """{"uid":"bud-f","month":202612,"limitAmount":750000}""", type = "budget")
        val budget = repository.observeEntity("budget", "bud-f").first()!!.versions.first().changes.byField()
        assertEquals("₹5,000.00" to "₹7,500.00", budget.getValue("Limit"))
        assertEquals("November 2026" to "December 2026", budget.getValue("Month"))

        val closed1 = 1_795_000_000_000L
        val closed2 = 1_795_100_000_000L
        insert("202611", 1, 1_000, """{"month":202611,"closedAt":$closed1,"closedBy":"Alice"}""", type = "month_lock")
        insert("202611", 2, 2_000, """{"month":202611,"closedAt":$closed2,"closedBy":"Alice"}""", type = "month_lock")
        val lock = repository.observeEntity("month_lock", "202611").first()!!
        assertEquals("November 2026", lock.label)
        assertEquals(instant(closed1) to instant(closed2), lock.versions.first().changes.byField().getValue("Closed at"))

        insert("bill-f", 1, 1_000, """{"uid":"bill-f","merchant":"Fake Mart","status":"DRAFT","total":null,"billDate":$OCCURRED_1}""", type = "bill")
        insert("bill-f", 2, 2_000, """{"uid":"bill-f","merchant":"Fake Mart","status":"POSTED","total":25050,"billDate":$OCCURRED_1}""", type = "bill")
        val bill = repository.observeEntity("bill", "bill-f").first()!!.versions.first().changes.byField()
        assertEquals(setOf("Status", "Total"), bill.keys)
        assertEquals("Draft" to "Added to expenses", bill.getValue("Status"))
        assertEquals("—" to "₹250.50", bill.getValue("Total"))
    }

    private fun List<HistoryChange>.byField(): Map<String, Pair<String, String>> = associate { it.field to (it.before to it.after) }

    /** Floating times as the rest of the app shows them (DateFormats, FloatingTime). */
    private fun floating(stored: Long): String = dateTime(FloatingTime.fromStored(stored))

    private fun instant(epochMillis: Long): String =
        dateTime(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime())

    private fun dateTime(t: LocalDateTime) = "${t.format(DateFormats.date)} · ${t.format(DateFormats.time)}"

    private fun transactionJson(amount: Long, occurredAt: Long, type: String, isRefund: Boolean) =
        """{"id":7,"uid":"tx-fmt","type":"$type","amount":$amount,"accountUid":"acc-1","toAccountUid":null,""" +
            """"categoryUid":"cat-1","isRefund":$isRefund,"occurredAt":$occurredAt,"note":"","payee":"Fake Shop",""" +
            """"createdAt":1000,"updatedAt":$occurredAt,"billUid":null,"recurringUid":null,"isPrivate":false}"""

    private fun categoryJson(kind: String, color: Long, icon: String, sortOrder: Int, active: Boolean) =
        """{"id":3,"uid":"cat","name":"Fake Food","kind":"$kind","iconKey":"$icon","colorArgb":$color,""" +
            """"sortOrder":$sortOrder,"active":$active,"createdAt":1,"updatedAt":2,"parentUid":null}"""

    private suspend fun insert(
        uid: String,
        version: Int,
        createdAt: Long,
        snapshot: String,
        kind: String = if (version == 1) "ADD" else "EDIT",
        deleted: Boolean = false,
        isPrivate: Boolean = false,
        type: String = "transaction",
        author: String = "Alice",
    ) {
        db.entityVersionDao().insert(
            EntityVersionEntity(
                entityType = type,
                entityUid = uid,
                version = version,
                status = "APPROVED",
                baseVersion = version - 1,
                kind = kind,
                deleted = deleted,
                snapshot = snapshot,
                snapshotFormat = 1,
                authorId = "member-$author",
                authorName = author,
                createdAt = createdAt,
                isPrivate = isPrivate,
                eventUid = "evt-$type-$uid-$version-$kind-$isPrivate-$createdAt",
            ),
        )
    }

    private class FixedCurrency(code: String) : CurrencyProvider {
        override val currencyCode: Flow<String> = MutableStateFlow(code)
    }

    private companion object {
        /** Floating epoch seconds: 2026-11-26 00:23 and 2026-11-27 00:24 (local wall-clock time). */
        const val OCCURRED_1 = 1_795_652_580L
        const val OCCURRED_2 = 1_795_739_040L
    }

    private class TestSpaceProvider : SpaceProvider {
        val value = MutableStateFlow(Space.SHARED)
        override val current: StateFlow<Space> get() = value
    }
}
