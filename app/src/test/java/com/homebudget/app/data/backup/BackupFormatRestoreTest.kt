package com.homebudget.app.data.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.TestRepositories
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.bill.BillRepository
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.MemberEntity
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.db.toDomain
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.BudgetRepository
import com.homebudget.app.data.repository.IdentityProvider
import com.homebudget.app.data.repository.RecurringRepository
import com.homebudget.app.data.repository.RoomExpenseFamilyRepository
import com.homebudget.app.data.repository.RoomInvestmentRepository
import com.homebudget.app.data.repository.SnapshotJsonCodec
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.repository.VersionedWriter
import com.homebudget.app.data.repository.WriteIdentity
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.backup.BackupDecodeResult
import com.homebudget.app.domain.backup.BackupFile
import com.homebudget.app.domain.backup.BackupStorage
import com.homebudget.app.domain.bill.BillTextRecognizer
import com.homebudget.app.domain.bill.OcrLine
import com.homebudget.app.domain.family.FamilyRole
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Frequency
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Backup file formats 3..8 must stay restorable (CLAUDE.md guardrail 8). Each format version is built
 * as raw JSON containing only the fields that version had, so later fields really are absent, then
 * restored into an in-memory Room database. Restore REPLACES data, so nothing may be lost or duplicated.
 */
@RunWith(AndroidJUnit4::class)
class BackupFormatRestoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var images: BillImageStore
    private lateinit var repos: TestRepositories
    private lateinit var settings: SettingsRepository
    private lateinit var backup: BackupRepository
    private val storage = MemoryStorage()
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // A second writer whose space can be switched to PRIVATE (TestRepositories is fixed to the shared space).
    private val spaceState = MutableStateFlow(Space.SHARED)
    private val space = object : SpaceProvider {
        override val current: StateFlow<Space> = spaceState
    }
    private val identity = object : IdentityProvider {
        override suspend fun current() = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.PERSONAL)
    }
    private lateinit var spaceWriter: VersionedWriter
    private lateinit var spaceAccounts: AccountRepository
    private lateinit var spaceTransactions: TransactionRepository
    private lateinit var spaceInvestments: RoomInvestmentRepository

    private val noOcr = object : BillTextRecognizer {
        override suspend fun recognize(image: File): List<OcrLine> = emptyList()
    }

    private class MemoryStorage : BackupStorage {
        val files = HashMap<String, ByteArray>()
        override suspend fun write(target: String, bytes: ByteArray) { files[target] = bytes }
        override suspend fun read(source: String): ByteArray = files[source] ?: throw java.io.IOException("missing")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        images = BillImageStore(context)
        repos = TestRepositories(db, clock)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = dsScope) { File(tmp.root, "s.preferences_pb") })
        backup = repos.backup(storage, settings, images)
        spaceWriter = VersionedWriter(db, clock, identity, space, SnapshotJsonCodec(db))
        val spaceFamily = RoomExpenseFamilyRepository(db, clock, spaceWriter)
        spaceAccounts = AccountRepository(db, clock, spaceWriter, space)
        spaceTransactions = TransactionRepository(db, clock, spaceWriter, spaceFamily, space)
        spaceInvestments = RoomInvestmentRepository(db, clock, spaceWriter, space)
    }

    @After
    fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    // ---------------------------------------------------------------------------------------------
    // Raw JSON fixtures. Field lists follow domain/backup/Backup.kt:
    //   v1/v2 base fields + v2 bill data (Backup.kt:22-26, parentId :67, billId :80)
    //   v3 recurring rules + transaction.recurringId (:26-27, :82)
    //   v4 inventory/purchaseRequests/proposals (:28-31) - always empty arrays here
    //   v5 uid/isPrivate on entities (:57-59, :68-69, :83-85, :94-95, :105-107, :114-115, :127-129, :159),
    //      entityVersions/monthLocks/members (:32-35)
    //   v6 familyPeople/familyTags/familyTagMembers/transactionMembers (:36-40)
    //   v7 investmentValues (:41-42)
    //   v8 recurringRules[].amountChanges (:130-131)
    // ---------------------------------------------------------------------------------------------

    private val t0 = 1_000L

    private fun accountJson(v: Int, id: Long, name: String, type: String, opening: Long, sort: Int, priv: Boolean = false) =
        buildJsonObject {
            put("id", id); put("name", name); put("type", type); put("openingBalance", opening)
            put("includeInTotal", true); put("archived", false); put("sortOrder", sort)
            put("createdAt", t0); put("updatedAt", t0 + 1)
            if (v >= 5) { put("uid", "acc-$id"); put("isPrivate", priv) }
        }

    private fun categoryJson(v: Int, id: Long, name: String, kind: String, parent: Long?, sort: Int) = buildJsonObject {
        put("id", id); put("name", name); put("kind", kind); put("iconKey", "misc"); put("colorArgb", 4281236786L)
        put("sortOrder", sort); put("active", true); put("createdAt", t0); put("updatedAt", t0 + 1)
        if (parent != null) put("parentId", parent)
        if (v >= 5) put("uid", "cat-$id")
    }

    private fun txJson(
        v: Int, id: Long, type: String, amount: Long, accountId: Long, at: String,
        toAccountId: Long? = null, categoryId: Long? = null, note: String = "", payee: String = "",
        billId: Long? = null, recurringId: Long? = null, priv: Boolean = false,
    ) = buildJsonObject {
        put("id", id); put("type", type); put("amount", amount); put("accountId", accountId)
        if (toAccountId != null) put("toAccountId", toAccountId)
        if (categoryId != null) put("categoryId", categoryId)
        put("isRefund", false); put("occurredAt", at); put("note", note); put("payee", payee)
        put("createdAt", t0); put("updatedAt", t0 + 1)
        if (billId != null) put("billId", billId)
        if (recurringId != null) put("recurringId", recurringId)
        if (v >= 5) { put("uid", "tx-$id"); put("isPrivate", priv) }
    }

    private fun budgetJson(v: Int, id: Long, month: Int, categoryId: Long?, limit: Long) = buildJsonObject {
        put("id", id); put("month", month)
        if (categoryId != null) put("categoryId", categoryId)
        put("limitAmount", limit)
        if (v >= 5) { put("uid", "bud-$id"); put("isPrivate", false) }
    }

    private fun billJson(v: Int) = buildJsonObject {
        put("id", 1L); put("merchant", "Fake Mart"); put("billDate", "2026-11-02T18:30:00")
        put("total", 7_000L); put("accountId", 1L); put("status", "POSTED")
        put("createdAt", t0); put("updatedAt", t0 + 1)
        if (v >= 5) { put("uid", "bill-1"); put("isPrivate", false) }
    }

    private fun billItemJson(v: Int, id: Long, position: Int, description: String, amount: Long) = buildJsonObject {
        put("id", id); put("billId", 1L); put("position", position); put("description", description)
        put("amount", amount); put("categoryId", 2L); put("included", true)
        if (v >= 5) put("uid", "item-$id")
    }

    private fun itemRuleJson(v: Int) = buildJsonObject {
        put("key", "fake veg"); put("categoryId", 2L); put("useCount", 2); put("updatedAt", 5L)
        if (v >= 5) put("uid", "irule-1")
    }

    private fun recurringJson(v: Int) = buildJsonObject {
        put("id", 1L); put("type", "EXPENSE"); put("amount", 50_000L); put("accountId", 2L); put("categoryId", 4L)
        put("note", "Rent"); put("payee", "Fake Landlord"); put("frequency", "MONTHLY"); put("interval", 1)
        put("startAt", "2026-09-05T09:00:00"); put("postedCount", 3); put("active", true)
        put("createdAt", t0); put("updatedAt", t0 + 1)
        if (v >= 5) { put("uid", "rule-1"); put("isPrivate", false) }
        if (v >= 8) put("amountChanges", true)
    }

    private fun versionJson(
        id: Long, type: String, uid: String, version: Int, kind: String, base: Int, eventUid: String,
        priv: Boolean = false, bulk: String? = null,
    ) = buildJsonObject {
        put("id", id); put("entityType", type); put("entityUid", uid); put("version", version)
        put("status", "APPROVED"); put("baseVersion", base); put("kind", kind); put("deleted", false)
        put("snapshot", """{"uid":"$uid"}"""); put("snapshotFormat", 1)
        put("authorId", "member-1"); put("authorName", "Fake Admin"); put("createdAt", 1_100L)
        put("decidedBy", "member-1"); put("decidedAt", 1_100L)
        if (bulk != null) put("bulkId", bulk)
        put("isPrivate", priv); put("eventUid", eventUid)
    }

    private fun personJson(id: Long, name: String, uid: String) = buildJsonObject {
        put("id", id); put("name", name); put("active", true); put("createdAt", t0); put("updatedAt", t0 + 1); put("uid", uid)
    }

    private fun tagMemberJson(id: Long, personUid: String) = buildJsonObject {
        put("id", id); put("tagUid", "tag-1"); put("personUid", personUid); put("createdAt", t0); put("uid", "tagm-$id")
    }

    private fun txMemberJson(id: Long, txUid: String, personUid: String, share: Long, priv: Boolean) = buildJsonObject {
        put("id", id); put("transactionUid", txUid); put("personUid", personUid)
        put("shareAmount", share); put("isPrivate", priv); put("uid", "txm-$id")
    }

    private fun investmentValueJson(id: Long, value: Long, recordedAt: Long) = buildJsonObject {
        put("id", id); put("accountUid", "acc-3"); put("valueMinor", value); put("recordedAt", recordedAt)
        put("isPrivate", false); put("uid", "iv-$id")
    }

    private fun arr(items: List<JsonElement>) = JsonArray(items)

    private fun fixture(v: Int, formatVersion: Int = v): JsonObject = buildJsonObject {
        put("format", "homebudget.backup")
        put("formatVersion", formatVersion)
        put("exportedAtEpochMillis", 1_700_000_000_000L)
        put("currencyCode", "USD")
        put(
            "accounts",
            arr(
                buildList<JsonElement> {
                    add(accountJson(v, 1, "Fake Wallet", "CASH", 10_000, 0))
                    add(accountJson(v, 2, "Fake Bank", "SAVINGS", 250_000, 1))
                    if (v >= 7) add(accountJson(v, 3, "Fake Fund", "INVESTMENT", 50_000, 2))
                    if (v >= 5) add(accountJson(v, 4, "Fake Private Wallet", "CASH", 5_000, 3, priv = true))
                },
            ),
        )
        put(
            "categories",
            arr(
                listOf(
                    categoryJson(v, 1, "Groceries", "EXPENSE", null, 0),
                    categoryJson(v, 2, "Vegetables", "EXPENSE", 1, 0),
                    categoryJson(v, 3, "Salary", "INCOME", null, 1),
                    categoryJson(v, 4, "Rent", "EXPENSE", null, 2),
                ),
            ),
        )
        put(
            "transactions",
            arr(
                buildList<JsonElement> {
                    add(txJson(v, 1, "INCOME", 300_000, 2, "2026-11-01T09:00:00", categoryId = 3, note = "Fake pay", payee = "Fake Employer"))
                    add(txJson(v, 2, "EXPENSE", 7_000, 1, "2026-11-02T18:30:00", categoryId = 2, note = "Bill: Fake Veg, Fake Chips", payee = "Fake Mart", billId = 1))
                    add(txJson(v, 3, "EXPENSE", 50_000, 2, "2026-10-05T09:00:00", categoryId = 4, note = "Rent Oct", recurringId = 1))
                    add(txJson(v, 4, "TRANSFER", 20_000, 2, "2026-11-03T10:00:00", toAccountId = 1))
                    if (v >= 7) add(txJson(v, 5, "TRANSFER", 20_000, 2, "2026-11-04T10:00:00", toAccountId = 3))
                    if (v >= 5) add(txJson(v, 6, "EXPENSE", 1_500, 4, "2026-11-05T12:00:00", categoryId = 1, priv = true))
                },
            ),
        )
        put(
            "budgets",
            arr(listOf(budgetJson(v, 1, 202611, 1, 40_000), budgetJson(v, 2, 202611, null, 100_000))),
        )
        // v2 bill data
        put("bills", arr(listOf(billJson(v))))
        put("billItems", arr(listOf(billItemJson(v, 1, 0, "Fake Veg", 4_000), billItemJson(v, 2, 1, "Fake Chips", 3_000))))
        put("itemRules", arr(listOf(itemRuleJson(v))))
        // v3
        if (v >= 3) put("recurringRules", arr(listOf(recurringJson(v))))
        // v4 staged lists (always empty)
        if (v >= 4) {
            put("inventoryItems", arr(emptyList()))
            put("purchaseRequests", arr(emptyList()))
            put("proposals", arr(emptyList()))
        }
        // v5
        if (v >= 5) {
            put(
                "entityVersions",
                arr(
                    listOf(
                        versionJson(1, "account", "acc-1", 1, "ADD", 0, "ev-1"),
                        versionJson(2, "account", "acc-1", 2, "EDIT", 1, "ev-2", bulk = "bulk-1"),
                        versionJson(3, "transaction", "tx-6", 1, "ADD", 0, "ev-3", priv = true),
                    ),
                ),
            )
            put(
                "monthLocks",
                arr(listOf(buildJsonObject { put("month", 202610); put("closedAt", 5_000L); put("closedBy", "Fake Admin") })),
            )
            put(
                "members",
                arr(
                    listOf(
                        buildJsonObject {
                            put("memberId", "member-1"); put("email", "fake@example.invalid"); put("name", "Fake Admin")
                            put("role", "ADMIN"); put("active", true); put("appVersionCode", 1)
                            put("appVersionName", "0.1.2"); put("syncFormat", 1)
                        },
                    ),
                ),
            )
        }
        // v6
        if (v >= 6) {
            put("familyPeople", arr(listOf(personJson(1, "Fake Person A", "person-a"), personJson(2, "Fake Person B", "person-b"))))
            put(
                "familyTags",
                arr(listOf(buildJsonObject { put("id", 1L); put("name", "Fake Tag"); put("active", true); put("createdAt", t0); put("updatedAt", t0 + 1); put("uid", "tag-1") })),
            )
            put("familyTagMembers", arr(listOf(tagMemberJson(1, "person-a"), tagMemberJson(2, "person-b"))))
            put(
                "transactionMembers",
                arr(
                    listOf(
                        txMemberJson(1, "tx-2", "person-a", 3_500, false),
                        txMemberJson(2, "tx-2", "person-b", 3_500, false),
                        txMemberJson(3, "tx-6", "person-a", 1_500, true),
                    ),
                ),
            )
        }
        // v7
        if (v >= 7) {
            put("investmentValues", arr(listOf(investmentValueJson(1, 55_000, 100), investmentValueJson(2, 61_250, 200))))
        }
    }

    private suspend fun load(name: String, json: JsonObject): BackupDecodeResult {
        storage.files[name] = json.toString().toByteArray()
        return backup.inspect(name)
    }

    private suspend fun restoreFixture(v: Int): BackupFile {
        val result = load("v$v", fixture(v))
        assertTrue("v$v fixture must be accepted: $result", result is BackupDecodeResult.Valid)
        val file = (result as BackupDecodeResult.Valid).file
        assertEquals(v, file.formatVersion)
        val summary = backup.restore(file)
        assertEquals(2 + (if (v >= 7) 1 else 0) + (if (v >= 5) 1 else 0), summary.accounts)
        assertEquals(4, summary.categories)
        assertEquals(4 + (if (v >= 7) 1 else 0) + (if (v >= 5) 1 else 0), summary.transactions)
        return file
    }

    // ---------------------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------------------

    private suspend fun assertCommonData(v: Int) {
        // Accounts (the seeded Cash account must have been replaced, not kept next to the backup's).
        val accounts = db.accountDao().getAll()
        val expectedAccounts = buildList {
            add(listOf(1L, "Fake Wallet", "CASH", 10_000L))
            add(listOf(2L, "Fake Bank", "SAVINGS", 250_000L))
            if (v >= 7) add(listOf(3L, "Fake Fund", "INVESTMENT", 50_000L))
            if (v >= 5) add(listOf(4L, "Fake Private Wallet", "CASH", 5_000L))
        }
        assertEquals(expectedAccounts, accounts.map { listOf(it.id, it.name, it.type.name, it.openingBalance) })
        assertEquals(if (v >= 5) listOf(4L) else emptyList<Long>(), accounts.filter { it.isPrivate }.map { it.id })
        assertTrue(accounts.all { it.includeInTotal && !it.archived })

        // Categories (main group + group hierarchy preserved).
        assertEquals(
            listOf(
                Triple(1L, "Groceries", null as Long?),
                Triple(2L, "Vegetables", 1L),
                Triple(3L, "Salary", null),
                Triple(4L, "Rent", null),
            ),
            db.categoryDao().getAll().map { Triple(it.id, it.name, it.parentId) },
        )
        assertEquals(
            listOf("EXPENSE", "EXPENSE", "INCOME", "EXPENSE"),
            db.categoryDao().getAll().map { it.kind.name },
        )

        // Transactions
        val txs = db.transactionDao().getAll().associateBy { it.id }
        val expectedAmounts = buildMap {
            put(1L, 300_000L); put(2L, 7_000L); put(3L, 50_000L); put(4L, 20_000L)
            if (v >= 7) put(5L, 20_000L)
            if (v >= 5) put(6L, 1_500L)
        }
        assertEquals(expectedAmounts, txs.mapValues { it.value.amount })
        val t1 = txs.getValue(1L)
        assertEquals("INCOME", t1.type.name)
        assertEquals(3L, t1.categoryId)
        assertEquals("Fake Employer", t1.payee)
        assertEquals("Fake pay", t1.note)
        assertEquals(FloatingTime.toStored(LocalDateTime.parse("2026-11-01T09:00:00")), t1.occurredAt)
        val t2 = txs.getValue(2L)
        assertEquals(1L, t2.billId)
        assertEquals(1L, t2.accountId)
        assertEquals(2L, t2.categoryId)
        assertEquals("Fake Mart", t2.payee)
        assertEquals(FloatingTime.toStored(LocalDateTime.parse("2026-11-02T18:30:00")), t2.occurredAt)
        val t3 = txs.getValue(3L)
        assertEquals(1L, t3.recurringId)
        assertEquals("Rent Oct", t3.note)
        val t4 = txs.getValue(4L)
        assertEquals("TRANSFER", t4.type.name)
        assertEquals(1L, t4.toAccountId)
        assertNull(t4.categoryId)
        assertEquals(if (v >= 5) setOf(6L) else emptySet<Long>(), txs.filter { it.value.isPrivate }.keys)

        // Budgets
        assertEquals(
            listOf(listOf(1L, 202611L, 1L, 40_000L), listOf(2L, 202611L, -1L, 100_000L)),
            db.budgetDao().getAll().sortedBy { it.id }.map { listOf(it.id, it.month.toLong(), it.categoryId ?: -1L, it.limitAmount) },
        )

        // Recurring rule
        val rule = db.recurringDao().getAll().single()
        assertEquals(1L, rule.id)
        assertEquals(50_000L, rule.amount)
        assertEquals("MONTHLY", rule.frequency.name)
        assertEquals(1, rule.interval)
        assertEquals(3, rule.postedCount)
        assertTrue(rule.active)
        assertEquals(2L, rule.accountId)
        assertEquals(4L, rule.categoryId)
        assertEquals("Fake Landlord", rule.payee)
        assertEquals(FloatingTime.toStored(LocalDateTime.parse("2026-09-05T09:00:00")), rule.startAt)
        assertEquals("amountChanges only exists from v8", v >= 8, rule.amountChanges)
        assertFalse(rule.isPrivate)

        // Bill, lines, learned rule
        val bill = db.billDao().getAll().single()
        assertEquals("Fake Mart", bill.merchant)
        assertEquals(7_000L, bill.total)
        assertEquals("POSTED", bill.status.name)
        assertNull(bill.imageFile)
        assertEquals(
            listOf(Triple("Fake Veg", 4_000L, 2L), Triple("Fake Chips", 3_000L, 2L)),
            db.billDao().getAllItems().map { Triple(it.description, it.amount, it.categoryId) },
        )
        val itemRule = db.itemRuleDao().getAll().single()
        assertEquals("fake veg", itemRule.key)
        assertEquals(2L, itemRule.categoryId)
        assertEquals(2, itemRule.useCount)

        // Exact money (Long minor units)
        val balances = FinanceRules.accountBalances(accounts.map { it.toDomain() }, txs.values.map { it.toDomain() })
        assertEquals(23_000L, balances[1L]) // 10_000 - 7_000 + 20_000
        assertEquals(if (v >= 7) 460_000L else 480_000L, balances[2L]) // 250_000 + 300_000 - 50_000 - 20_000 (- 20_000 into the fund)
        if (v >= 7) assertEquals(70_000L, balances[3L]) // 50_000 + 20_000
        if (v >= 5) assertEquals(3_500L, balances[4L]) // 5_000 - 1_500

        assertEquals("USD", settings.settings.first().currencyCode)
    }

    private suspend fun assertUidsAndHistory(v: Int) {
        val accounts = db.accountDao().getAll()
        val categories = db.categoryDao().getAll()
        val txs = db.transactionDao().getAll()
        val bills = db.billDao().getAll()
        val items = db.billDao().getAllItems()
        val budgets = db.budgetDao().getAll()
        val rules = db.recurringDao().getAll()
        val itemRules = db.itemRuleDao().getAll()
        val versions = db.entityVersionDao().getAll()

        if (v >= 5) {
            // Identities come from the file untouched.
            assertEquals(accounts.map { "acc-${it.id}" }, accounts.map { it.uid })
            assertEquals(categories.map { "cat-${it.id}" }, categories.map { it.uid })
            assertEquals(txs.map { "tx-${it.id}" }, txs.map { it.uid })
            assertEquals(listOf("bill-1"), bills.map { it.uid })
            assertEquals(items.map { "item-${it.id}" }, items.map { it.uid })
            assertEquals(budgets.sortedBy { it.id }.map { "bud-${it.id}" }, budgets.sortedBy { it.id }.map { it.uid })
            assertEquals(listOf("rule-1"), rules.map { it.uid })
            assertEquals(listOf("irule-1"), itemRules.map { it.uid })

            // The file's own history is restored as-is: no extra generated rows, no duplicates.
            assertEquals(listOf(1L, 2L, 3L), versions.map { it.id })
            assertEquals(listOf("ev-1", "ev-2", "ev-3"), versions.map { it.eventUid })
            assertEquals(listOf(1, 2, 1), versions.map { it.version })
            assertEquals(listOf("acc-1", "acc-1", "tx-6"), versions.map { it.entityUid })
            assertEquals(listOf<String?>(null, "bulk-1", null), versions.map { it.bulkId })
            assertEquals(listOf(false, false, true), versions.map { it.isPrivate })
            assertEquals("""{"uid":"tx-6"}""", versions[2].snapshot)
            assertTrue(versions.all { it.status == "APPROVED" && it.snapshotFormat == 1 && it.authorName == "Fake Admin" })
        } else {
            // Formats < 5 have no identities: every row gets a fresh, non-blank, unique uid.
            val uidsByTable = mapOf(
                "accounts" to accounts.map { it.uid },
                "categories" to categories.map { it.uid },
                "transactions" to txs.map { it.uid },
                "bills" to bills.map { it.uid },
                "bill items" to items.map { it.uid },
                "budgets" to budgets.map { it.uid },
                "recurring" to rules.map { it.uid },
                "item rules" to itemRules.map { it.uid },
            )
            uidsByTable.forEach { (name, uids) ->
                assertTrue("$name uids must be non-blank", uids.all { it.isNotBlank() })
                assertEquals("$name uids must be unique", uids.size, uids.toSet().size)
            }
            // ...and a version-1 history row per entity, matching those uids.
            assertEquals(uidsByTable.values.sumOf { it.size }, versions.size)
            assertTrue(
                versions.all {
                    it.version == 1 && it.baseVersion == 0 && it.status == "APPROVED" && it.kind == "ADD" &&
                        !it.deleted && it.snapshotFormat == 1 && it.snapshot.isNotBlank()
                },
            )
            assertEquals(versions.size, versions.map { it.eventUid }.toSet().size)
            fun uidsOf(type: String) = versions.filter { it.entityType == type }.map { it.entityUid }.toSet()
            assertEquals(accounts.map { it.uid }.toSet(), uidsOf("account"))
            assertEquals(categories.map { it.uid }.toSet(), uidsOf("category"))
            assertEquals(txs.map { it.uid }.toSet(), uidsOf("transaction"))
            assertEquals(bills.map { it.uid }.toSet(), uidsOf("bill"))
            assertEquals(items.map { it.uid }.toSet(), uidsOf("bill_item"))
            assertEquals(budgets.map { it.uid }.toSet(), uidsOf("budget"))
            assertEquals(rules.map { it.uid }.toSet(), uidsOf("recurring_rule"))
            assertEquals(itemRules.map { it.uid }.toSet(), uidsOf("item_rule"))
            assertTrue(versions.none { it.isPrivate })
        }
    }

    private suspend fun assertLaterTables(v: Int) {
        // v5: month locks and members
        if (v >= 5) {
            assertEquals(
                listOf(Triple(202610, 5_000L, "Fake Admin")),
                db.monthLockDao().getAll().map { Triple(it.month, it.closedAt, it.closedBy) },
            )
            val member = db.memberDao().getAll().single()
            assertEquals("member-1", member.memberId)
            assertEquals("fake@example.invalid", member.email)
            assertEquals("Fake Admin", member.name)
            assertEquals("ADMIN", member.role)
            assertTrue(member.active)
            assertEquals(1, member.appVersionCode)
            assertEquals("0.1.2", member.appVersionName)
            assertEquals(1, member.syncFormat)
            assertNull(member.lastSeenAt)
        } else {
            assertTrue(db.monthLockDao().getAll().isEmpty())
            assertTrue(db.memberDao().getAll().isEmpty())
        }

        // v6: family people, tags, shares
        val family = db.familyAllocationDao()
        if (v >= 6) {
            assertEquals(
                listOf(Triple(1L, "Fake Person A", "person-a"), Triple(2L, "Fake Person B", "person-b")),
                family.getAllPeopleForBackup().sortedBy { it.id }.map { Triple(it.id, it.name, it.uid) },
            )
            assertEquals(
                listOf(Triple(1L, "Fake Tag", "tag-1")),
                family.getAllTagsForBackup().map { Triple(it.id, it.name, it.uid) },
            )
            assertEquals(
                listOf(Triple("tag-1", "person-a", "tagm-1"), Triple("tag-1", "person-b", "tagm-2")),
                family.getAllTagMembersForBackup().sortedBy { it.id }.map { Triple(it.tagUid, it.personUid, it.uid) },
            )
            val shares = family.getAllTransactionMembersForBackup().sortedBy { it.id }
            assertEquals(
                listOf(
                    listOf("tx-2", "person-a", "3500", "false"),
                    listOf("tx-2", "person-b", "3500", "false"),
                    listOf("tx-6", "person-a", "1500", "true"),
                ),
                shares.map { listOf(it.transactionUid, it.personUid, it.shareAmount.toString(), it.isPrivate.toString()) },
            )
            assertEquals(7_000L, shares.filter { it.transactionUid == "tx-2" }.sumOf { it.shareAmount })
        } else {
            assertTrue(family.getAllPeopleForBackup().isEmpty())
            assertTrue(family.getAllTagsForBackup().isEmpty())
            assertTrue(family.getAllTagMembersForBackup().isEmpty())
            assertTrue(family.getAllTransactionMembersForBackup().isEmpty())
        }

        // v7: investment values
        val values = db.investmentValueDao().getAllForBackup().sortedBy { it.id }
        if (v >= 7) {
            assertEquals(
                listOf(listOf(1L, 3L, 55_000L, 100L, 0L), listOf(2L, 3L, 61_250L, 200L, 0L)),
                values.map { listOf(it.id, it.accountId, it.valueMinor, it.recordedAt, if (it.isPrivate) 1L else 0L) },
            )
            assertEquals(listOf("iv-1", "iv-2"), values.map { it.uid })
            // The latest saved value is what balances show for the fund (exact Long).
            assertEquals(61_250L, db.investmentValueDao().getLatest(3L, false)?.valueMinor)
        } else {
            assertTrue(values.isEmpty())
        }
    }

    private suspend fun assertFixtureRestored(v: Int) {
        assertCommonData(v)
        assertUidsAndHistory(v)
        assertLaterTables(v)
    }

    // ---------------------------------------------------------------------------------------------
    // One restore test per supported format version
    // ---------------------------------------------------------------------------------------------

    @Test
    fun format3RestoresAllDataAndGivesFreshUidsAndVersionOneHistory() = runTest {
        restoreFixture(3)
        assertFixtureRestored(3)
    }

    @Test
    fun format4RestoresAllDataAndGivesFreshUidsAndVersionOneHistory() = runTest {
        restoreFixture(4)
        assertFixtureRestored(4)
    }

    @Test
    fun format5RestoresIdentitiesHistoryMonthLocksAndMembers() = runTest {
        restoreFixture(5)
        assertFixtureRestored(5)
    }

    @Test
    fun format6RestoresFamilyPeopleTagsAndShares() = runTest {
        restoreFixture(6)
        assertFixtureRestored(6)
    }

    @Test
    fun format7RestoresInvestmentValues() = runTest {
        restoreFixture(7)
        assertFixtureRestored(7)
    }

    @Test
    fun format8RestoresChangingAmountRuleFlag() = runTest {
        restoreFixture(8)
        assertFixtureRestored(8)
        assertTrue(db.recurringDao().getAll().single().amountChanges)
    }

    @Test
    fun formatsBefore8RestoreRulesWithAmountChangesOff() = runTest {
        for (v in 3..7) {
            restoreFixture(v)
            assertFalse("v$v rule must default amountChanges to false", db.recurringDao().getAll().single().amountChanges)
        }
    }

    @Test
    fun formatsBefore5KeepTheMembersAlreadyOnThisPhone() = runTest {
        val member = MemberEntity("member-keep", "keeper@example.invalid", "Fake Keeper", "ADMIN", true, 1, "0.1", 1)
        db.memberDao().insert(member)
        restoreFixture(4)
        assertEquals(listOf(member), db.memberDao().getAll())
        // ...and they get a history row like every other entity.
        assertEquals(listOf("member-keep"), db.entityVersionDao().getAll().filter { it.entityType == "member" }.map { it.entityUid })
    }

    // ---------------------------------------------------------------------------------------------
    // Replace semantics: no duplicates, no leftovers
    // ---------------------------------------------------------------------------------------------

    private suspend fun rowCounts(): Map<String, Int> = mapOf(
        "accounts" to db.accountDao().getAll().size,
        "categories" to db.categoryDao().getAll().size,
        "transactions" to db.transactionDao().getAll().size,
        "bills" to db.billDao().getAll().size,
        "billItems" to db.billDao().getAllItems().size,
        "budgets" to db.budgetDao().getAll().size,
        "recurring" to db.recurringDao().getAll().size,
        "itemRules" to db.itemRuleDao().getAll().size,
        "versions" to db.entityVersionDao().getAll().size,
        "monthLocks" to db.monthLockDao().getAll().size,
        "members" to db.memberDao().getAll().size,
        "people" to db.familyAllocationDao().getAllPeopleForBackup().size,
        "tags" to db.familyAllocationDao().getAllTagsForBackup().size,
        "tagMembers" to db.familyAllocationDao().getAllTagMembersForBackup().size,
        "txMembers" to db.familyAllocationDao().getAllTransactionMembersForBackup().size,
        "investmentValues" to db.investmentValueDao().getAllForBackup().size,
    )

    @Test
    fun restoringAnOldFormatTwiceGivesTheSameRowCounts() = runTest {
        for (v in listOf(3, 4)) {
            restoreFixture(v)
            val first = rowCounts()
            restoreFixture(v)
            assertEquals("v$v row counts after second restore", first, rowCounts())
            assertFixtureRestored(v)
        }
    }

    @Test
    fun restoringTheCurrentFormatTwiceGivesTheSameRowsAndContents() = runTest {
        for (v in 5..8) {
            restoreFixture(v)
            val firstCounts = rowCounts()
            val firstSnapshot = backup.createSnapshot()
            restoreFixture(v)
            assertEquals("v$v row counts after second restore", firstCounts, rowCounts())
            assertEquals("v$v contents after second restore", firstSnapshot, backup.createSnapshot())
            assertFixtureRestored(v)
        }
    }

    @Test
    fun restoreReplacesSeededAndExistingDataInsteadOfMerging() = runTest {
        // The database starts with the seeded categories and Cash account.
        assertTrue(db.categoryDao().getAll().size > 4)
        restoreFixture(8)
        assertEquals(4, db.categoryDao().getAll().size)
        assertEquals(listOf("Fake Wallet", "Fake Bank", "Fake Fund", "Fake Private Wallet"), db.accountDao().getAll().map { it.name })

        // Restoring an older, smaller file afterwards removes everything the newer file added.
        restoreFixture(3)
        assertEquals(listOf("Fake Wallet", "Fake Bank"), db.accountDao().getAll().map { it.name })
        assertEquals(4, db.transactionDao().getAll().size)
        assertTrue(db.investmentValueDao().getAllForBackup().isEmpty())
        assertTrue(db.familyAllocationDao().getAllPeopleForBackup().isEmpty())
        assertTrue(db.familyAllocationDao().getAllTransactionMembersForBackup().isEmpty())
        assertTrue(db.monthLockDao().getAll().isEmpty())
        // (Members are deliberately kept when restoring a pre-v5 file; see formatsBefore5KeepTheMembersAlreadyOnThisPhone.)
    }

    // ---------------------------------------------------------------------------------------------
    // Full v8 round trip with real repositories
    // ---------------------------------------------------------------------------------------------

    private suspend fun dump(): Map<String, List<Any>> = mapOf(
        "accounts" to db.accountDao().getAll(),
        "categories" to db.categoryDao().getAll(),
        "transactions" to db.transactionDao().getAll(),
        "bills" to db.billDao().getAll(),
        "billItems" to db.billDao().getAllItems(),
        "budgets" to db.budgetDao().getAll().sortedBy { it.id },
        "recurring" to db.recurringDao().getAll(),
        "itemRules" to db.itemRuleDao().getAll(),
        "versions" to db.entityVersionDao().getAll(),
        "monthLocks" to db.monthLockDao().getAll(),
        "members" to db.memberDao().getAll(),
        "people" to db.familyAllocationDao().getAllPeopleForBackup().sortedBy { it.id },
        "tags" to db.familyAllocationDao().getAllTagsForBackup().sortedBy { it.id },
        "tagMembers" to db.familyAllocationDao().getAllTagMembersForBackup().sortedBy { it.id },
        "txMembers" to db.familyAllocationDao().getAllTransactionMembersForBackup().sortedBy { it.id },
        "investmentValues" to db.investmentValueDao().getAllForBackup().sortedBy { it.id },
    )

    private fun assertSameDump(expected: Map<String, List<Any>>, actual: Map<String, List<Any>>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (table, rows) -> assertEquals("table $table", rows, actual.getValue(table)) }
    }

    private data class RoundTripIds(val privateWallet: Long, val privateFund: Long, val bank: Long, val fund: Long)

    private suspend fun buildFullData(): RoundTripIds {
        settings.setCurrency("USD")
        val accounts = repos.accounts()
        val categories = repos.categories().observeCategories().first()
        fun cat(name: String) = categories.first { it.name == name }.id
        val cash = accounts.observeAccounts().first().first().id
        val bank = accounts.save(Account(name = "Fake Bank", type = AccountType.SAVINGS, openingBalance = 250_000))
        val fund = accounts.save(Account(name = "Fake Fund", type = AccountType.INVESTMENT, openingBalance = 50_000))
        val investments = RoomInvestmentRepository(db, clock, repos.writer, space)
        investments.recordValue(fund, 55_000)
        investments.recordValue(fund, 61_250)

        val family = repos.expenseFamily
        val personA = family.addPerson("Fake Person A")
        val personB = family.addPerson("Fake Person B")
        val tag = family.addTag("Fake Tag")
        family.setTagMember(tag, personA, true)
        family.setTagMember(tag, personB, true)

        val transactions = repos.transactions()
        transactions.save(
            Transaction(type = TransactionType.INCOME, amount = 300_000, accountId = bank, categoryId = cat("Salary"),
                occurredAt = LocalDateTime.of(2026, 11, 1, 9, 0), payee = "Fake Employer"),
        )
        // Odd amount: the equal split gives one person an extra minor unit.
        transactions.save(
            Transaction(type = TransactionType.EXPENSE, amount = 7_001, accountId = cash, categoryId = cat("Dining"),
                occurredAt = LocalDateTime.of(2026, 11, 2, 20, 0), note = "Fake dinner"),
            memberUids = setOf(personA, personB),
        )
        transactions.save(
            Transaction(type = TransactionType.EXPENSE, amount = 12_345, accountId = bank, categoryId = cat("Groceries"),
                occurredAt = LocalDateTime.of(2026, 10, 15, 11, 0)),
        )
        transactions.save(
            Transaction(type = TransactionType.TRANSFER, amount = 20_000, accountId = bank, toAccountId = fund,
                occurredAt = LocalDateTime.of(2026, 11, 3, 10, 0)),
        )

        val recurring = RecurringRepository(db, transactions, clock, repos.writer, monthLocks = repos.monthLocks)
        val rent = RecurringRule(
            type = TransactionType.EXPENSE, amount = 50_000, accountId = cash, categoryId = cat("Groceries"),
            frequency = Frequency.MONTHLY, startAt = LocalDateTime.of(2026, 9, 20, 9, 0), payee = "Fake Landlord",
        )
        recurring.save(rent) // posts Sep, Oct and Nov occurrences
        recurring.save(rent.copy(amount = 9_900, startAt = LocalDateTime.of(2027, 1, 20, 9, 0), amountChanges = true))

        BudgetRepository(db, repos.writer).save(BudgetLimit(month = YearMonth.of(2026, 11), categoryId = cat("Groceries"), limit = 40_000))

        val bills = BillRepository(db, clock, images, noOcr, repos.writer, repos.expenseFamily)
        val billId = bills.createDraft(null, cash)
        bills.approve(
            bills.get(billId)!!.copy(merchant = "Fake Mart", billDate = LocalDateTime.of(2026, 11, 2, 18, 30)),
            listOf(
                BillItem(position = 0, description = "Fake Veg", amount = 4_000, categoryId = cat("Vegetables")),
                BillItem(position = 1, description = "Fake Milk", amount = 5_600, categoryId = cat("Dairy & Eggs")),
            ),
        )

        // Private space: account, investment account + value, and a transaction with a private share.
        spaceState.value = Space.PRIVATE
        val privateWallet = spaceAccounts.save(Account(name = "Fake Private Wallet", type = AccountType.CASH, openingBalance = 5_000))
        val privateFund = spaceAccounts.save(Account(name = "Fake Private Fund", type = AccountType.INVESTMENT, openingBalance = 0))
        spaceInvestments.recordValue(privateFund, 9_999)
        spaceTransactions.save(
            Transaction(type = TransactionType.EXPENSE, amount = 1_500, accountId = privateWallet, categoryId = cat("Groceries"),
                occurredAt = LocalDateTime.of(2026, 11, 5, 12, 0), note = "Fake private purchase"),
            memberUids = setOf(personA),
        )
        spaceState.value = Space.SHARED

        // A closed month (all data for it already exists).
        repos.monthLocks.close(YearMonth.of(2026, 10))
        return RoundTripIds(privateWallet, privateFund, bank, fund)
    }

    @Test
    fun fullV8RoundTripKeepsEverythingAndPrivateRowsStayPrivate() = runTest {
        val ids = buildFullData()
        val before = dump()
        val balancesBefore = FinanceRules.accountBalances(
            db.accountDao().getAll().map { it.toDomain() },
            db.transactionDao().getAll().map { it.toDomain() },
        )

        // Sanity: the data really contains every kind of record that format 8 can carry.
        assertEquals(listOf(61_250L, 55_000L, 9_999L).sorted(), db.investmentValueDao().getAllForBackup().map { it.valueMinor }.sorted())
        assertEquals(3, db.transactionDao().getAll().count { it.recurringId != null })
        assertEquals(1, db.monthLockDao().getAll().size)
        assertTrue(db.recurringDao().getAll().any { it.amountChanges })
        assertEquals(2, db.familyAllocationDao().getAllTagMembersForBackup().size)
        assertEquals(3, db.familyAllocationDao().getAllTransactionMembersForBackup().size)
        assertEquals(517_655L, balancesBefore[ids.bank]) // 250_000 + 300_000 - 12_345 - 20_000
        assertEquals(70_000L, balancesBefore[ids.fund])

        backup.exportTo("full")
        val file = (backup.inspect("full") as BackupDecodeResult.Valid).file
        assertEquals(8, file.formatVersion)
        assertEquals(1, file.transactions.count { it.isPrivate })
        assertEquals(2, file.accounts.count { it.isPrivate })
        assertEquals(1, file.investmentValues.count { it.isPrivate })
        assertEquals(3, file.transactionMembers.size)
        assertEquals(1, file.transactionMembers.count { it.isPrivate })
        assertEquals(1, file.monthLocks.size)
        assertEquals(3, file.investmentValues.size)

        repos.maintenance(images).clearAllData()
        assertTrue(db.transactionDao().getAll().isEmpty())
        assertTrue(db.investmentValueDao().getAllForBackup().isEmpty())
        assertTrue(db.familyAllocationDao().getAllPeopleForBackup().isEmpty())
        settings.setCurrency("GBP")

        backup.restore(file)

        assertSameDump(before, dump())
        assertEquals(file, backup.createSnapshot()) // re-exporting the restored data gives the identical file
        assertEquals("USD", settings.settings.first().currencyCode)
        assertEquals(
            balancesBefore,
            FinanceRules.accountBalances(
                db.accountDao().getAll().map { it.toDomain() },
                db.transactionDao().getAll().map { it.toDomain() },
            ),
        )

        // The private rows are still private everywhere, and invisible in the shared space.
        val privateTx = db.transactionDao().getAll().single { it.isPrivate }
        assertEquals(1_500L, privateTx.amount)
        assertEquals(ids.privateWallet, privateTx.accountId)
        assertEquals(setOf(ids.privateWallet, ids.privateFund), db.accountDao().getAll().filter { it.isPrivate }.map { it.id }.toSet())
        assertTrue(db.familyAllocationDao().getAllTransactionMembersForBackup().filter { it.transactionUid == privateTx.uid }.all { it.isPrivate })
        assertTrue(db.familyAllocationDao().getAllTransactionMembersForBackup().filter { it.transactionUid != privateTx.uid }.none { it.isPrivate })
        assertEquals(listOf(9_999L), db.investmentValueDao().getAllForBackup().filter { it.isPrivate }.map { it.valueMinor })
        assertTrue(db.entityVersionDao().getAll().filter { it.entityUid == privateTx.uid }.let { it.isNotEmpty() && it.all { v -> v.isPrivate } })

        assertFalse(spaceTransactions.observeAll().first().any { it.id == privateTx.id })
        spaceState.value = Space.PRIVATE
        assertEquals(listOf(privateTx.id), spaceTransactions.observeAll().first().map { it.id })
        spaceState.value = Space.SHARED
    }

    @Test
    fun fullV8RoundTripRestoredTwiceDoesNotDuplicateAnything() = runTest {
        buildFullData()
        backup.exportTo("full")
        val file = (backup.inspect("full") as BackupDecodeResult.Valid).file

        backup.restore(file)
        val counts = rowCounts()
        val once = dump()
        backup.restore(file)

        assertEquals(counts, rowCounts())
        assertSameDump(once, dump())
    }

    @Test
    fun aRejectedRestoreLeavesExistingDataUntouched() = runTest {
        val file = restoreFixture(8)
        val before = dump()

        try {
            backup.restore(file.copy(transactions = file.transactions + file.transactions.first()))
            fail("expected IllegalArgumentException for a duplicate transaction id")
        } catch (e: IllegalArgumentException) { /* expected */ }
        try {
            backup.restore(file.copy(transactions = file.transactions.map { if (it.id == 2L) it.copy(accountId = 99_999) else it }))
            fail("expected IllegalArgumentException for a missing account")
        } catch (e: IllegalArgumentException) { /* expected */ }

        assertSameDump(before, dump())
    }

    // ---------------------------------------------------------------------------------------------
    // Version gate
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aNewerFormatVersionIsRejectedBeforeAnythingIsRead() = runTest {
        val newer = JsonObject(fixture(8, formatVersion = 9) + ("someFutureList" to JsonArray(listOf(JsonPrimitive("x")))))
        val result = load("v9", newer)
        assertTrue(result.toString(), result is BackupDecodeResult.Invalid)
        assertTrue((result as BackupDecodeResult.Invalid).reason.contains("newer version"))
        // inspect() must not have modified the database.
        assertTrue(db.categoryDao().getAll().size > 4)
    }

    @Test
    fun formatVersionZeroAndNegativeAreRejected() = runTest {
        for (bad in listOf(0, -1)) {
            val result = load("bad$bad", fixture(8, formatVersion = bad))
            assertTrue("formatVersion $bad: $result", result is BackupDecodeResult.Invalid)
            assertEquals("Unsupported backup version", (result as BackupDecodeResult.Invalid).reason)
        }
    }

    @Test
    fun theCurrentFormatVersionIsStillAccepted() = runTest {
        val result = load("v8", fixture(8))
        assertTrue(result.toString(), result is BackupDecodeResult.Valid)
        assertEquals(BackupFile.CURRENT_FORMAT_VERSION, 8)
    }

    @Test
    fun aWrongFormatIdIsRejected() = runTest {
        val wrong = JsonObject(fixture(8) + ("format" to JsonPrimitive("someone.elses.backup")))
        val result = load("wrong", wrong)
        assertTrue(result is BackupDecodeResult.Invalid)
    }

    // EXPECTED TO FAIL: BackupRepository.restore() only calls BackupValidator.validate(), and the validator never
    // looks at formatVersion (BackupValidator.kt:25-317); the version gate lives only in BackupCodec.decode()
    // (Backup.kt:294-297). A BackupFile built in code with formatVersion 9 is therefore restored (and the current
    // data replaced) instead of throwing. Guardrail 9 says repositories are the last line of defence.
    @Test
    fun restoreItselfRejectsANewerFormatVersion() = runTest {
        val file = (load("v8", fixture(8)) as BackupDecodeResult.Valid).file
        try {
            backup.restore(file.copy(formatVersion = BackupFile.CURRENT_FORMAT_VERSION + 1))
            fail("restore() must reject a backup from a newer format")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    // EXPECTED TO FAIL: same reason as above for formatVersion 0 (validator never checks it, restore() proceeds and
    // treats it like a pre-v5 file).
    @Test
    fun restoreItselfRejectsFormatVersionZero() = runTest {
        val file = (load("v8", fixture(8)) as BackupDecodeResult.Valid).file
        try {
            backup.restore(file.copy(formatVersion = 0))
            fail("restore() must reject formatVersion 0")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ---------------------------------------------------------------------------------------------
    // v5+ identities are validated, not silently repaired
    // ---------------------------------------------------------------------------------------------

    private fun replaceAccountUid(json: JsonObject, uid: (Long) -> String): JsonObject {
        val accounts = (json.getValue("accounts") as JsonArray).map { element ->
            val obj = element as JsonObject
            val id = (obj.getValue("id") as JsonPrimitive).content.toLong()
            JsonObject(obj + ("uid" to JsonPrimitive(uid(id))))
        }
        return JsonObject(json + ("accounts" to JsonArray(accounts)))
    }

    @Test
    fun aVersion5FileWithBlankOrDuplicateUidsIsRejected() = runTest {
        val blank = load("blank", replaceAccountUid(fixture(5)) { id -> if (id == 1L) "" else "acc-$id" })
        assertTrue(blank.toString(), blank is BackupDecodeResult.Invalid)
        val duplicate = load("dup", replaceAccountUid(fixture(5)) { "same-uid" })
        assertTrue(duplicate.toString(), duplicate is BackupDecodeResult.Invalid)
        // The same blank uids in a pre-v5 file are legitimate (they are generated during restore).
        val old = load("old", fixture(4))
        assertTrue(old.toString(), old is BackupDecodeResult.Valid)
    }

    @Test
    fun aVersion6FileWhoseSharesDoNotAddUpToTheTransactionIsRejected() = runTest {
        val json = fixture(6)
        val shares = (json.getValue("transactionMembers") as JsonArray).map { element ->
            val obj = element as JsonObject
            if ((obj.getValue("id") as JsonPrimitive).content == "1") JsonObject(obj + ("shareAmount" to JsonPrimitive(3_499))) else obj
        }
        val result = load("badshares", JsonObject(json + ("transactionMembers" to JsonArray(shares))))
        assertTrue(result.toString(), result is BackupDecodeResult.Invalid)
    }

    @Test
    fun aVersion7InvestmentValueOnAnOrdinaryAccountIsRejected() = runTest {
        val json = fixture(7)
        val values = (json.getValue("investmentValues") as JsonArray).map { element ->
            JsonObject((element as JsonObject) + ("accountUid" to JsonPrimitive("acc-1")))
        }
        val result = load("badinv", JsonObject(json + ("investmentValues" to JsonArray(values))))
        assertTrue(result.toString(), result is BackupDecodeResult.Invalid)
    }
}
