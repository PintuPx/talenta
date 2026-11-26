package com.homebudget.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.backup.BackupRepository
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.bill.BillRepository
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.data.repository.TransactionFilter
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.backup.BackupCodec
import com.homebudget.app.domain.backup.BackupDecodeResult
import com.homebudget.app.domain.backup.BackupStorage
import com.homebudget.app.domain.backup.BackupValidator
import com.homebudget.app.domain.bill.BillTextRecognizer
import com.homebudget.app.domain.bill.OcrLine
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BillStatus
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.model.mainGroupId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Bill workflow against a real (in-memory) Room database, with a fake text recognizer. Native graphics
 * mode makes BitmapFactory behave like a device (legacy shadows hid a bounds-decoding bug).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class BillRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var images: BillImageStore
    private lateinit var repos: TestRepositories
    private lateinit var bills: BillRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var categories: CategoryRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ocrLines: List<OcrLine> = emptyList()

    private val fakeRecognizer = object : BillTextRecognizer {
        override suspend fun recognize(image: File): List<OcrLine> = ocrLines
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        images = BillImageStore(context)
        images.deleteAllExcept(emptySet())
        repos = TestRepositories(db, clock)
        bills = BillRepository(db, clock, images, fakeRecognizer, repos.writer, repos.expenseFamily)
        transactions = repos.transactions()
        categories = repos.categories()
    }

    @After
    fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    private suspend fun cats(): List<Category> = categories.observeCategories().first()
    private suspend fun cat(name: String) = cats().first { it.name == name }.id
    private suspend fun cashId() = repos.accounts().observeAccounts().first().first().id
    private val billDate = LocalDateTime.of(2026, 11, 18, 19, 5)

    private suspend fun storePhoto(): String {
        val f = File(context.cacheDir, "src.jpg")
        f.outputStream().use { Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return images.import(android.net.Uri.fromFile(f))
    }

    @Test
    fun approveSplitsBillIntoOneExpensePerGroupAndLearnsItems() = runTest {
        val id = bills.createDraft(null, cashId())
        val veg = cat("Vegetables")
        val dairy = cat("Dairy & Eggs")
        val items = listOf(
            BillItem(position = 0, description = "Veggie", amount = 4_000, categoryId = veg),
            BillItem(position = 1, description = "Tomato 1kg", amount = 3_000, categoryId = veg),
            BillItem(position = 2, description = "Milk", amount = 5_600, categoryId = dairy),
            BillItem(position = 3, description = "Misread line", amount = 99_900, categoryId = null, included = false),
        )
        val bill = bills.get(id)!!.copy(merchant = " Fresh Mart ", billDate = billDate, total = 12_600)

        assertEquals(2, bills.approve(bill, items))

        val txns = transactions.getForBill(id).sortedBy { it.amount }
        assertEquals(listOf(5_600L, 7_000L), txns.map { it.amount })
        assertTrue(txns.all { it.type == TransactionType.EXPENSE && it.payee == "Fresh Mart" && it.occurredAt == billDate && it.billId == id })
        assertEquals("Bill: Veggie, Tomato 1kg", txns.first { it.categoryId == veg }.note)
        assertEquals(BillStatus.POSTED, bills.get(id)!!.status)
        assertEquals(4, bills.items(id).size) // excluded line is kept on the bill, not posted

        // Both groups roll up into Groceries on the dashboard.
        val byId = cats().associateBy { it.id }
        val spend = FinanceRules.spendingByCategory(transactions.observeAll().first(), DateRange.month(YearMonth.of(2026, 11))) { byId.mainGroupId(it) }
        assertEquals(listOf(cat("Groceries") to 12_600L), spend.map { it.categoryId to it.amount })

        // The approval taught the suggester.
        val learned = bills.learnedRules()
        assertEquals(veg, learned["veggie"])
        assertEquals(veg, learned["tomato"])
        assertEquals(dairy, learned["milk"])
        assertNull(learned["misread line"])

        // Filtering the transaction list by the main group includes its groups.
        assertEquals(2, transactions.observeFiltered(TransactionFilter(categoryId = cat("Groceries"))).first().size)
    }

    @Test
    fun reApprovingReplacesTransactionsInsteadOfDuplicating() = runTest {
        val id = bills.createDraft(null, cashId())
        val veg = cat("Vegetables")
        val bill = bills.get(id)!!.copy(billDate = billDate)
        bills.approve(bill, listOf(BillItem(position = 0, description = "Veggie", amount = 4_000, categoryId = veg)))
        bills.approve(bill, listOf(BillItem(position = 0, description = "Veggie", amount = 4_500, categoryId = veg)))
        assertEquals(listOf(4_500L), transactions.observeAll().first().map { it.amount })
    }

    @Test
    fun onlyThisTimePreservesLearnedGroupWhileAlwaysUpdatesIt() = runTest {
        val id = bills.createDraft(null, cashId())
        val vegetables = cat("Vegetables")
        val dining = cat("Dining")
        val bill = bills.get(id)!!.copy(billDate = billDate)
        val key = com.homebudget.app.domain.bill.CategorySuggester.key("Veggie")
        val original = BillItem(position = 0, description = "Veggie", amount = 4_000, categoryId = vegetables)
        val changed = original.copy(categoryId = dining)

        bills.approve(bill, listOf(original))
        bills.approve(bill, listOf(changed), preserveLearnedKeys = setOf(key))
        assertEquals(vegetables, bills.learnedRules()[key])
        assertEquals(dining, transactions.getForBill(id).single().categoryId)

        bills.approve(bill, listOf(changed))
        assertEquals(dining, bills.learnedRules()[key])
    }

    @Test
    fun approvalIsRejectedWhenALineHasNoCategoryOrNoAccount() = runTest {
        val id = bills.createDraft(null, null)
        val bill = bills.get(id)!!
        try {
            bills.approve(bill, listOf(BillItem(position = 0, description = "Veggie", amount = 4_000, categoryId = cat("Vegetables"))))
            fail("expected IntegrityException for missing account")
        } catch (e: IntegrityException) { /* expected */ }
        try {
            bills.approve(bill.copy(accountId = cashId()), listOf(BillItem(position = 0, description = "Thing", amount = 4_000, categoryId = null)))
            fail("expected IntegrityException for missing category")
        } catch (e: IntegrityException) { /* expected */ }
        try {
            bills.approve(bill.copy(accountId = cashId()), listOf(BillItem(position = 0, description = "Pay", amount = 4_000, categoryId = cat("Salary"))))
            fail("expected IntegrityException for income category")
        } catch (e: IntegrityException) { /* expected */ }
        assertTrue(transactions.observeAll().first().isEmpty())
        assertEquals(BillStatus.DRAFT, bills.get(id)!!.status)
    }

    @Test
    fun readUsesOcrThenSuggestsGroupsFromKeywordsAndLearnedRules() = runTest {
        val photo = storePhoto()
        val id = bills.createDraft(photo, cashId())
        // Learn that "paneer tikka" is Dining, overriding the built-in dairy keyword.
        bills.approve(
            bills.get(id)!!.copy(billDate = billDate),
            listOf(BillItem(position = 0, description = "Paneer Tikka", amount = 25_000, categoryId = cat("Dining"))),
        )
        ocrLines = listOf("FRESH MART", "Veggie 40.00", "Paneer Tikka 250.00", "Widget 10.00", "TOTAL 300.00").map { OcrLine(it) }

        val reading = bills.read(id)

        assertEquals("FRESH MART", reading.receipt.merchant)
        assertEquals(30_000L, reading.receipt.total)
        assertEquals(listOf("Veggie", "Paneer Tikka", "Widget"), reading.items.map { it.description })
        assertEquals(listOf(cat("Vegetables"), cat("Dining"), null), reading.items.map { it.categoryId })
        assertNotNull(bills.get(id)!!.ocrText)
    }

    @Test
    fun deletingABillRemovesItsTransactionsItemsAndPhoto() = runTest {
        val photo = storePhoto()
        assertNotNull(images.file(photo))
        val id = bills.createDraft(photo, cashId())
        bills.approve(bills.get(id)!!.copy(billDate = billDate), listOf(BillItem(position = 0, description = "Veggie", amount = 4_000, categoryId = cat("Vegetables"))))

        assertEquals(1, bills.delete(id))

        assertNull(bills.get(id))
        assertTrue(bills.items(id).isEmpty())
        assertTrue(transactions.observeAll().first().isEmpty())
        assertNull(images.file(photo))
    }

    @Test
    fun groupsMustBelongToAMainGroupOfTheSameKind() = runTest {
        val groceries = cat("Groceries")
        val veg = cat("Vegetables")
        val salary = cat("Salary")
        suspend fun bad(c: Category) {
            try {
                categories.save(c)
                fail("expected IntegrityException for $c")
            } catch (e: IntegrityException) { /* expected */ }
        }
        bad(Category(name = "Nested", kind = CategoryKind.EXPENSE, iconKey = "misc", colorArgb = 0, parentId = veg)) // 3 levels
        bad(Category(name = "Bonus", kind = CategoryKind.EXPENSE, iconKey = "misc", colorArgb = 0, parentId = salary)) // wrong kind
        bad(cats().first { it.id == groceries }.copy(parentId = cat("Dining"))) // main group with groups cannot move
        val newId = categories.save(Category(name = "Frozen", kind = CategoryKind.EXPENSE, iconKey = "misc", colorArgb = 0, parentId = groceries))
        assertEquals(groceries, cats().first { it.id == newId }.parentId)
    }

    private class MemoryStorage : BackupStorage {
        val files = HashMap<String, ByteArray>()
        override suspend fun write(target: String, bytes: ByteArray) { files[target] = bytes }
        override suspend fun read(source: String): ByteArray = files[source] ?: throw java.io.IOException("missing")
    }

    @Test
    fun backupRoundTripKeepsBillsGroupsAndRules() = runTest {
        val storage = MemoryStorage()
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = dsScope) { File(tmp.root, "b.preferences_pb") })
        val backup = repos.backup(storage, settings, images)
        val photo = storePhoto()
        val id = bills.createDraft(photo, cashId())
        bills.approve(bills.get(id)!!.copy(merchant = "Fresh Mart", billDate = billDate), listOf(BillItem(position = 0, description = "Veggie", amount = 4_000, categoryId = cat("Vegetables"))))
        val txBefore = transactions.observeAll().first()
        val catsBefore = cats().sortedBy { it.id }

        backup.exportTo("f")
        val file = (backup.inspect("f") as BackupDecodeResult.Valid).file
        assertEquals(8, file.formatVersion) // bump deliberately when the backup format changes
        assertEquals(1, file.bills.size)
        assertEquals(1, file.billItems.size)
        assertEquals(1, file.itemRules.size)

        bills.delete(id) // also deletes the photo, so the restored bill must drop its photo reference
        backup.restore(file)

        assertEquals(txBefore, transactions.observeAll().first())
        assertEquals(catsBefore, cats().sortedBy { it.id })
        val restored = bills.get(id)!!
        assertEquals("Fresh Mart", restored.merchant)
        assertEquals(BillStatus.POSTED, restored.status)
        assertNull(restored.imageFile)
        assertEquals(listOf("Veggie"), bills.items(id).map { it.description })
        assertEquals(cat("Vegetables"), bills.learnedRules()["veggie"])
    }

    @Test
    fun phase1BackupWithoutBillFieldsStillRestores() = runTest {
        val storage = MemoryStorage()
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = dsScope) { File(tmp.root, "c.preferences_pb") })
        val backup = repos.backup(storage, settings, images)
        val v1 = """
            {"format":"homebudget.backup","formatVersion":1,"exportedAtEpochMillis":1,"currencyCode":"INR",
             "accounts":[{"id":1,"name":"Cash","type":"CASH","openingBalance":0,"includeInTotal":true,"archived":false,"sortOrder":0,"createdAt":1,"updatedAt":1}],
             "categories":[{"id":1,"name":"Groceries","kind":"EXPENSE","iconKey":"groceries","colorArgb":4281236786,"sortOrder":0,"active":true,"createdAt":1,"updatedAt":1}],
             "transactions":[{"id":1,"type":"EXPENSE","amount":500,"accountId":1,"categoryId":1,"occurredAt":"2026-11-01T10:00:00","createdAt":1,"updatedAt":1}],
             "budgets":[]}
        """.trimIndent()
        storage.files["v1"] = v1.toByteArray()
        val result = backup.inspect("v1")
        assertTrue(result.toString(), result is BackupDecodeResult.Valid)
        backup.restore((result as BackupDecodeResult.Valid).file)
        assertEquals(listOf(500L), transactions.observeAll().first().map { it.amount })
        assertEquals(listOf("Groceries"), cats().map { it.name })
    }

    @Test
    fun validatorRejectsBrokenBillReferences() = runTest {
        val id = bills.createDraft(null, cashId())
        bills.approve(bills.get(id)!!.copy(billDate = billDate), listOf(BillItem(position = 0, description = "Veggie", amount = 4_000, categoryId = cat("Vegetables"))))
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = dsScope) { File(tmp.root, "d.preferences_pb") })
        val snapshot = repos.backup(MemoryStorage(), settings, images).createSnapshot()
        assertNull(BackupValidator.validate(snapshot))
        assertNotNull(BackupValidator.validate(snapshot.copy(bills = emptyList())))
        assertNotNull(BackupValidator.validate(snapshot.copy(billItems = snapshot.billItems.map { it.copy(categoryId = cat("Salary")) })))
        assertNotNull(BackupValidator.validate(snapshot.copy(bills = snapshot.bills.map { it.copy(imageFile = "../../etc/passwd") })))
        assertNotNull(BackupValidator.validate(snapshot.copy(categories = snapshot.categories.map { if (it.name == "Vegetables") it.copy(parentId = 9_999) else it })))
        // Round-trips through the codec unchanged.
        assertTrue(BackupCodec.decode(BackupCodec.encode(snapshot), BackupValidator::validate) is BackupDecodeResult.Valid)
    }
}
