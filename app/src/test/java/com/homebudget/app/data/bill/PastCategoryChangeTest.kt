package com.homebudget.app.data.bill

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.TestRepositories
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.IdentityProvider
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.data.repository.RoomExpenseFamilyRepository
import com.homebudget.app.data.repository.SnapshotJsonCodec
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.repository.VersionedWriter
import com.homebudget.app.data.repository.WriteIdentity
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.backup.BackupStorage
import com.homebudget.app.domain.backup.BackupValidator
import com.homebudget.app.domain.bill.BillTextRecognizer
import com.homebudget.app.domain.bill.OcrLine
import com.homebudget.app.domain.family.FamilyRole
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BillStatus
import com.homebudget.app.domain.model.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * BillRepository.countPastCategoryMatches / changePastCategories / undoPastCategoryChange against an
 * in-memory Room database. Individual mode: the user is Admin, so closed months are NOT skipped.
 */
@RunWith(AndroidJUnit4::class)
class PastCategoryChangeTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var images: BillImageStore
    private lateinit var repos: TestRepositories
    private lateinit var bills: BillRepository
    private lateinit var transactions: TransactionRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        bills = BillRepository(db, clock, images, noOcr, repos.writer, repos.expenseFamily)
        transactions = repos.transactions()
    }

    @After
    fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    private suspend fun cats(): List<Category> = repos.categories().observeCategories().first()
    private suspend fun cat(name: String) = cats().first { it.name == name }.id
    private suspend fun cashId() = repos.accounts().observeAccounts().first().first().id
    private val nov18 = LocalDateTime.of(2026, 11, 18, 19, 5)
    private val nov19 = LocalDateTime.of(2026, 11, 19, 8, 0)

    private fun line(position: Int, description: String, amount: Long, categoryId: Long?, included: Boolean = true) =
        BillItem(position = position, description = description, amount = amount, categoryId = categoryId, included = included)

    private suspend fun post(merchant: String, date: LocalDateTime, vararg lines: BillItem, repo: BillRepository = bills): Long {
        val id = repo.createDraft(null, cashId())
        repo.approve(repo.get(id)!!.copy(merchant = merchant, billDate = date), lines.toList())
        return id
    }

    private suspend fun expectIntegrity(block: suspend () -> Unit) {
        try {
            block()
            fail("expected IntegrityException")
        } catch (e: IntegrityException) { /* expected */ }
    }

    private suspend fun itemCategories(billId: Long) = db.billDao().getItems(billId).map { it.description to it.categoryId }

    private suspend fun txByCategory(billId: Long) = db.transactionDao().getForBill(billId).associateBy { it.categoryId }

    // ---- countPastCategoryMatches ----

    @Test
    fun countsOnlyPostedIncludedLinesWithTheSameKeyInAnotherCategory() = runTest {
        val veg = cat("Vegetables")
        val dairy = cat("Dairy & Eggs")
        val dining = cat("Dining")
        val a = post(" Fresh Mart ", nov18, line(0, "Veggie", 4_000, veg), line(1, "Milk", 5_600, dairy))
        post("Corner Shop", nov19, line(0, "VEGGIE!", 3_000, veg), line(1, "Rice", 2_000, cat("Groceries")))
        // Not counted: a draft bill, an excluded line, and a line already in the target group.
        val draft = bills.createDraft(null, cashId())
        bills.saveDraft(bills.get(draft)!!.copy(billDate = nov19), listOf(line(0, "Veggie", 1_000, veg)))
        post("Third Shop", nov19, line(0, "Veggie", 500, veg, included = false), line(1, "Soap", 700, dairy))

        assertEquals(2, bills.countPastCategoryMatches("Veggie", dining))
        assertEquals(2, bills.countPastCategoryMatches("  veggie ", dairy)) // key ignores case and spaces
        assertEquals(0, bills.countPastCategoryMatches("Veggie", veg)) // already in that group
        assertEquals(1, bills.countPastCategoryMatches("Veggie", dining, excludeBillId = a))
        assertEquals(0, bills.countPastCategoryMatches("Unknown thing", dining))
        assertEquals(0, bills.countPastCategoryMatches("V", dining)) // key shorter than 2 characters
        assertEquals(0, bills.countPastCategoryMatches("123", dining)) // no letters, empty key
    }

    // ---- changePastCategories ----

    @Test
    fun changeRegroupsMatchingLinesAndRebuildsTheirGroupedTransactionsWithOneBulkId() = runTest {
        val veg = cat("Vegetables")
        val dairy = cat("Dairy & Eggs")
        val a = post(" Fresh Mart ", nov18, line(0, "Veggie", 4_000, veg), line(1, "Tomato", 3_000, veg), line(2, "Milk", 5_600, dairy))
        val b = post("Corner Shop", nov19, line(0, "Veggie", 2_500, veg))
        assertEquals(listOf(5_600L, 7_000L), db.transactionDao().getForBill(a).map { it.amount }.sorted())
        val spendBefore = db.transactionDao().getAll().sumOf { it.amount }
        assertEquals(15_100L, spendBefore)

        val result = bills.changePastCategories("Veggie", dairy, previousCategoryId = veg)

        assertEquals(2, result.foundEntries)
        assertEquals(2, result.changedEntries)
        assertEquals(0, result.skippedClosedMonths)
        assertEquals(0, result.writeResult.skippedClosedMonths)
        assertEquals(0, result.writeResult.pendingApproval)

        // Lines moved; the others did not.
        assertEquals(listOf("Veggie" to dairy, "Tomato" to veg, "Milk" to dairy), itemCategories(a))
        assertEquals(listOf("Veggie" to dairy), itemCategories(b))

        // Bill A: Veggie + Milk now share one Dairy transaction; Tomato stays alone in Vegetables.
        val aTx = txByCategory(a)
        assertEquals(setOf<Long?>(dairy, veg), aTx.keys)
        assertEquals(9_600L, aTx.getValue(dairy).amount)
        assertEquals("Bill: Veggie, Milk", aTx.getValue(dairy).note)
        assertEquals(3_000L, aTx.getValue(veg).amount)
        assertEquals("Bill: Tomato", aTx.getValue(veg).note)
        assertTrue(aTx.values.all { it.payee == "Fresh Mart" && it.billId == a })

        // Bill B: its only transaction moved from Vegetables to Dairy (old one removed, new one added).
        val bTx = db.transactionDao().getForBill(b)
        assertEquals(1, bTx.size)
        assertEquals(dairy, bTx.single().categoryId)
        assertEquals(2_500L, bTx.single().amount)
        assertEquals("Bill: Veggie", bTx.single().note)
        assertEquals("Corner Shop", bTx.single().payee)
        assertEquals(b, bTx.single().billId)
        assertEquals(nov19.toEpochSecond(ZoneOffset.UTC), bTx.single().occurredAt)

        // Money is conserved exactly.
        assertEquals(spendBefore, db.transactionDao().getAll().sumOf { it.amount })
        // The change does not touch learned rules (the caller decides whether to learn).
        assertEquals(veg, bills.learnedRules()["veggie"])
        assertEquals(setOf(a, b), db.billDao().getAll().filter { it.status == BillStatus.POSTED }.map { it.id }.toSet())

        // One bulk id covers every versioned change: 2 bill lines + (2 edits on A) + (1 delete + 1 add on B).
        val bulkId = result.writeResult.bulkId
        assertTrue(bulkId.isNotBlank())
        assertEquals(6, result.writeResult.applied)
        val versions = db.entityVersionDao().getForBulk(bulkId)
        assertEquals(6, versions.size)
        assertTrue(versions.all { it.kind == "BULK" && it.status == "APPROVED" && !it.isPrivate })
        assertEquals(2, versions.count { it.entityType == "bill_item" })
        assertEquals(4, versions.count { it.entityType == "transaction" })
        assertEquals(1, versions.count { it.deleted })
        assertEquals(setOf(bulkId), db.entityVersionDao().getAll().mapNotNull { it.bulkId }.toSet())
        assertEquals(6, db.entityVersionDao().getAll().count { it.bulkId != null })
    }

    @Test
    fun nothingToChangeReturnsZerosAndOffersNoUndo() = runTest {
        val veg = cat("Vegetables")
        post("Fresh Mart", nov18, line(0, "Veggie", 4_000, veg))
        val itemsBefore = db.billDao().getAllItems()
        val txBefore = db.transactionDao().getAll()
        val versionsBefore = db.entityVersionDao().getAll().size

        val result = bills.changePastCategories("Veggie", veg) // already in that group
        val unmatched = bills.changePastCategories("Something else", cat("Dining"))

        for (r in listOf(result, unmatched)) {
            assertEquals(0, r.foundEntries)
            assertEquals(0, r.changedEntries)
            assertEquals(0, r.writeResult.applied)
            assertEquals(0, r.writeResult.pendingApproval)
            expectIntegrity { bills.undoPastCategoryChange(r.writeResult.bulkId) }
        }
        assertEquals(itemsBefore, db.billDao().getAllItems())
        assertEquals(txBefore, db.transactionDao().getAll())
        assertEquals(versionsBefore, db.entityVersionDao().getAll().size)
    }

    @Test
    fun draftsExcludedLinesAndOtherLinesAreLeftAlone() = runTest {
        val veg = cat("Vegetables")
        val dairy = cat("Dairy & Eggs")
        val dining = cat("Dining")
        val draft = bills.createDraft(null, cashId())
        bills.saveDraft(bills.get(draft)!!.copy(billDate = nov19), listOf(line(0, "Veggie", 1_000, veg)))
        val skipped = post("Third Shop", nov19, line(0, "Veggie", 500, veg, included = false), line(1, "Soap", 700, dairy))
        val matched = post("Fresh Mart", nov18, line(0, "Veggie", 4_000, veg))
        val skippedTxBefore = db.transactionDao().getForBill(skipped)

        val result = bills.changePastCategories("Veggie", dining)

        assertEquals(1, result.foundEntries)
        assertEquals(listOf("Veggie" to veg), itemCategories(draft)) // draft untouched
        assertEquals(listOf("Veggie" to veg, "Soap" to dairy), itemCategories(skipped)) // excluded line untouched
        assertEquals(skippedTxBefore, db.transactionDao().getForBill(skipped))
        assertEquals(listOf("Veggie" to dining), itemCategories(matched))
        assertEquals(listOf(dining), db.transactionDao().getForBill(matched).map { it.categoryId })
        assertTrue(db.transactionDao().getForBill(draft).isEmpty())
    }

    @Test
    fun excludeBillIdKeepsThatBillUnchanged() = runTest {
        val veg = cat("Vegetables")
        val dining = cat("Dining")
        val a = post("Fresh Mart", nov18, line(0, "Veggie", 4_000, veg))
        val b = post("Corner Shop", nov19, line(0, "Veggie", 2_500, veg))
        val aTxBefore = db.transactionDao().getForBill(a)

        val result = bills.changePastCategories("Veggie", dining, excludeBillId = a)

        assertEquals(1, result.foundEntries)
        assertEquals(listOf("Veggie" to veg), itemCategories(a))
        assertEquals(aTxBefore, db.transactionDao().getForBill(a))
        assertEquals(listOf("Veggie" to dining), itemCategories(b))
        assertEquals(listOf(dining), db.transactionDao().getForBill(b).map { it.categoryId })
    }

    @Test
    fun invalidRequestsAreRejectedAndChangeNothing() = runTest {
        val veg = cat("Vegetables")
        post("Fresh Mart", nov18, line(0, "Veggie", 4_000, veg))
        val itemsBefore = db.billDao().getAllItems()
        val txBefore = db.transactionDao().getAll()

        expectIntegrity { bills.changePastCategories("V", cat("Dining")) } // key too short
        expectIntegrity { bills.changePastCategories("123", cat("Dining")) } // no letters
        expectIntegrity { bills.changePastCategories("Veggie", cat("Salary")) } // income category
        expectIntegrity { bills.changePastCategories("Veggie", 999_999) } // unknown category
        // Individual mode has no family Admin to apply skipped entries for.
        expectIntegrity { bills.changePastCategories("Veggie", cat("Dining"), requireSharedSpace = true) }

        assertEquals(itemsBefore, db.billDao().getAllItems())
        assertEquals(txBefore, db.transactionDao().getAll())
        assertEquals(0, db.entityVersionDao().getAll().count { it.bulkId != null })
    }

    @Test
    fun closedMonthsAreNotSkippedForTheAdminInIndividualMode() = runTest {
        val veg = cat("Vegetables")
        val dairy = cat("Dairy & Eggs")
        val dining = cat("Dining")
        val a = post("Fresh Mart", nov18, line(0, "Veggie", 4_000, veg), line(1, "Milk", 5_600, dairy))
        repos.monthLocks.close(YearMonth.of(2026, 11))
        assertTrue(repos.monthLocks.isClosed(YearMonth.of(2026, 11)))
        val dairyTxBefore = txByCategory(a).getValue(dairy)

        val result = bills.changePastCategories("Veggie", dining)

        assertEquals(1, result.foundEntries)
        assertEquals(1, result.changedEntries)
        assertEquals(0, result.skippedClosedMonths)
        assertEquals(0, result.writeResult.skippedClosedMonths)
        assertEquals(listOf("Veggie" to dining, "Milk" to dairy), itemCategories(a))
        val after = txByCategory(a)
        assertEquals(setOf<Long?>(dining, dairy), after.keys)
        assertEquals(4_000L, after.getValue(dining).amount)
        assertEquals(dairyTxBefore, after.getValue(dairy)) // unchanged group is not rewritten
        assertNull(db.syncStateDao().get("admin_skipped_bulk_changes")) // no "skipped entries" notice
    }

    // ---- undoPastCategoryChange ----

    @Test
    fun undoRestoresThePreviousGroupsTransactionsAndHistoryExactly() = runTest {
        val veg = cat("Vegetables")
        val dairy = cat("Dairy & Eggs")
        val a = post(" Fresh Mart ", nov18, line(0, "Veggie", 4_000, veg), line(1, "Tomato", 3_000, veg), line(2, "Milk", 5_600, dairy))
        val b = post("Corner Shop", nov19, line(0, "Veggie", 2_500, veg))
        val itemsBefore = db.billDao().getAllItems()
        val txBefore = db.transactionDao().getAll().sortedBy { it.id }
        val txTotalBefore = txBefore.sumOf { it.amount }

        val change = bills.changePastCategories("Veggie", dairy, previousCategoryId = veg)
        assertEquals(6, change.writeResult.applied)
        assertFalse(txBefore == db.transactionDao().getAll().sortedBy { it.id }) // the change really did something

        val undo = bills.undoPastCategoryChange(change.writeResult.bulkId)

        assertEquals(6, undo.applied)
        assertEquals(0, undo.pendingApproval)
        assertEquals(itemsBefore, db.billDao().getAllItems())
        // Same rows, same ids, same amounts, notes and timestamps; the added transaction is gone and the deleted one is back.
        assertEquals(txBefore, db.transactionDao().getAll().sortedBy { it.id })
        assertEquals(txTotalBefore, db.transactionDao().getAll().sumOf { it.amount })
        assertEquals(listOf("Veggie" to veg, "Tomato" to veg, "Milk" to dairy), itemCategories(a))
        assertEquals(listOf("Veggie" to veg), itemCategories(b))
        assertEquals(setOf<Long?>(veg, dairy), txByCategory(a).keys)
        assertEquals(listOf(veg), db.transactionDao().getForBill(b).map { it.categoryId })

        // Undo is itself versioned: 6 UNDO rows under their own bulk id; the original BULK rows stay for the audit trail.
        assertTrue(undo.bulkId.isNotBlank())
        assertTrue(undo.bulkId != change.writeResult.bulkId)
        val undoVersions = db.entityVersionDao().getForBulk(undo.bulkId)
        assertEquals(6, undoVersions.size)
        assertTrue(undoVersions.all { it.kind == "UNDO" && it.status == "APPROVED" })
        assertEquals(1, undoVersions.count { it.deleted }) // removing the transaction the change had added
        assertEquals(6, db.entityVersionDao().getForBulk(change.writeResult.bulkId).size)

        // A change can be undone only once.
        expectIntegrity { bills.undoPastCategoryChange(change.writeResult.bulkId) }
        assertEquals(txBefore, db.transactionDao().getAll().sortedBy { it.id })
    }

    @Test
    fun undoOfAnUnknownBulkIdIsRejected() = runTest {
        expectIntegrity { bills.undoPastCategoryChange("no-such-bulk") }
    }

    @Test
    fun aChangeCanBeUndoneAndThenRepeated() = runTest {
        val veg = cat("Vegetables")
        val dining = cat("Dining")
        val a = post("Fresh Mart", nov18, line(0, "Veggie", 4_000, veg))

        val first = bills.changePastCategories("Veggie", dining)
        bills.undoPastCategoryChange(first.writeResult.bulkId)
        val second = bills.changePastCategories("Veggie", dining)

        assertEquals(1, second.foundEntries)
        assertTrue(second.writeResult.bulkId != first.writeResult.bulkId)
        assertEquals(listOf("Veggie" to dining), itemCategories(a))
        assertEquals(listOf(dining), db.transactionDao().getForBill(a).map { it.categoryId })
        assertEquals(4_000L, db.transactionDao().getForBill(a).single().amount)
    }

    // ---- Private space ----

    private val spaceState = MutableStateFlow(Space.SHARED)
    private val space = object : SpaceProvider {
        override val current: StateFlow<Space> = spaceState
    }
    private val identity = object : IdentityProvider {
        override suspend fun current() = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.PERSONAL)
    }

    @Test
    fun theChangeOnlyTouchesBillsOfTheCurrentSpace() = runTest {
        val veg = cat("Vegetables")
        val dairy = cat("Dairy & Eggs")
        val writer = VersionedWriter(db, clock, identity, space, SnapshotJsonCodec(db))
        val spaceBills = BillRepository(db, clock, images, noOcr, writer, RoomExpenseFamilyRepository(db, clock, writer), space)
        val spaceAccounts = AccountRepository(db, clock, writer, space)

        val shared = post("Shared Mart", nov18, line(0, "Veggie", 4_000, veg), repo = spaceBills)
        spaceState.value = Space.PRIVATE
        val wallet = spaceAccounts.save(Account(name = "Private Wallet", type = AccountType.CASH, openingBalance = 5_000))
        val privateBill = spaceBills.createDraft(null, wallet)
        spaceBills.approve(
            spaceBills.get(privateBill)!!.copy(merchant = "Private Mart", billDate = nov19),
            listOf(line(0, "Veggie", 1_000, veg)),
        )
        assertTrue(db.billDao().getById(privateBill)!!.isPrivate)
        assertTrue(db.transactionDao().getForBill(privateBill).all { it.isPrivate })

        // Shared space: only the shared bill is seen and changed.
        spaceState.value = Space.SHARED
        assertEquals(1, spaceBills.countPastCategoryMatches("Veggie", dairy))
        val sharedResult = spaceBills.changePastCategories("Veggie", dairy)
        assertEquals(1, sharedResult.foundEntries)
        assertEquals(listOf("Veggie" to dairy), itemCategories(shared))
        assertEquals(listOf("Veggie" to veg), itemCategories(privateBill))
        assertEquals(listOf(veg), db.transactionDao().getForBill(privateBill).map { it.categoryId })

        // Private space: only the private bill is seen and changed, and the transaction stays private.
        spaceState.value = Space.PRIVATE
        assertEquals(1, spaceBills.countPastCategoryMatches("Veggie", dairy))
        val privateResult = spaceBills.changePastCategories("Veggie", dairy)
        assertEquals(1, privateResult.foundEntries)
        assertEquals(listOf("Veggie" to dairy), itemCategories(privateBill))
        val privateTx = db.transactionDao().getForBill(privateBill).single()
        assertEquals(dairy, privateTx.categoryId)
        assertEquals(1_000L, privateTx.amount)
        assertTrue(privateTx.isPrivate)
        assertEquals(wallet, privateTx.accountId)
        assertEquals(listOf(dairy), db.transactionDao().getForBill(shared).map { it.categoryId })
        assertTrue(db.transactionDao().getForBill(shared).none { it.isPrivate })
        spaceState.value = Space.SHARED
    }

    // EXPECTED TO FAIL: for a PRIVATE bill, changePastCategories records the bill_item version with
    // isPrivate = bill.isPrivate (BillRepository.kt:203-210, flag at :206), while every other bill_item write records it with
    // isPrivate = false (BillRepository.kt:531 in writeBill, :538, :487 in delete). Versions are chained per space
    // (EntityVersionDao.latestApprovedForSpace), so the same item uid gets version 1 in both chains: the history of
    // one bill line is split across the shared and private spaces and the version numbers collide.
    @Test
    fun aBillLineKeepsASingleVersionChainWhenItsGroupIsChangedInPrivateSpace() = runTest {
        val veg = cat("Vegetables")
        val dairy = cat("Dairy & Eggs")
        val writer = VersionedWriter(db, clock, identity, space, SnapshotJsonCodec(db))
        val spaceBills = BillRepository(db, clock, images, noOcr, writer, RoomExpenseFamilyRepository(db, clock, writer), space)
        spaceState.value = Space.PRIVATE
        val wallet = AccountRepository(db, clock, writer, space).save(Account(name = "Private Wallet", type = AccountType.CASH))
        val privateBill = spaceBills.createDraft(null, wallet)
        spaceBills.approve(
            spaceBills.get(privateBill)!!.copy(merchant = "Private Mart", billDate = nov19),
            listOf(line(0, "Veggie", 1_000, veg)),
        )
        spaceBills.changePastCategories("Veggie", dairy)
        spaceState.value = Space.SHARED

        val itemUid = db.billDao().getItems(privateBill).single().uid
        val history = db.entityVersionDao().getAll().filter { it.entityType == "bill_item" && it.entityUid == itemUid }
        assertEquals("version numbers of one entity must not repeat", history.size, history.map { it.version }.toSet().size)
        assertEquals("one bill line must live in one space", 1, history.map { it.isPrivate }.toSet().size)
    }

    // ---- Interaction with family shares (backup validity) ----

    // EXPECTED TO FAIL: changePastCategories rewrites a grouped transaction's amount (BillRepository.kt:246-260) but never
    // updates its transaction_members shares. BackupValidator requires the shares to add up to the transaction amount
    // (BackupValidator.kt:285-293), so after the change the app's own backup is rejected by its own validator
    // ("Transaction member shares do not match their transaction") and can no longer be restored.
    @Test
    fun afterAPastChangeTheBackupIsStillValidBecauseSharesFollowTheNewAmount() = runTest {
        val veg = cat("Vegetables")
        val dairy = cat("Dairy & Eggs")
        val a = post("Fresh Mart", nov18, line(0, "Veggie", 4_000, veg), line(1, "Milk", 5_600, dairy))
        val person = repos.expenseFamily.addPerson("Fake Person A")
        val dairyTx = transactions.getForBill(a).single { it.categoryId == dairy }
        transactions.save(dairyTx, memberUids = setOf(person))
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = dsScope) { File(tmp.root, "s.preferences_pb") })
        val backup = repos.backup(MemoryStorage(), settings, images)
        assertNull("precondition: backup valid before the change", BackupValidator.validate(backup.createSnapshot()))

        bills.changePastCategories("Veggie", dairy) // Dairy transaction becomes 9_600

        val snapshot = backup.createSnapshot()
        assertEquals(9_600L, snapshot.transactions.single { it.billId == a && it.categoryId == dairy }.amount)
        assertNull(BackupValidator.validate(snapshot))
        assertEquals(9_600L, snapshot.transactionMembers.sumOf { it.shareAmount })
    }
}
