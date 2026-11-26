package com.homebudget.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.backup.BackupRepository
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.TransactionEntity
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.DataMaintenanceRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.data.repository.TransactionFilter
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.repository.TransactionSort
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.backup.BackupCodec
import com.homebudget.app.domain.backup.BackupDecodeResult
import com.homebudget.app.domain.backup.BackupStorage
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** Room-backed repository tests running on the JVM through Robolectric (real SQLite, in-memory DB). */
@RunWith(AndroidJUnit4::class)
class RepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var images: BillImageStore
    private lateinit var repos: TestRepositories
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        repos = TestRepositories(db, clock)
        accounts = repos.accounts()
        categories = repos.categories()
        transactions = repos.transactions()
        images = BillImageStore(context)
    }

    @After
    fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    private suspend fun cashId() = accounts.observeAccounts().first().first { it.type == AccountType.CASH }.id
    private suspend fun expenseCat() = categories.observeCategories().first().first { it.kind == CategoryKind.EXPENSE }.id
    private suspend fun incomeCat() = categories.observeCategories().first().first { it.kind == CategoryKind.INCOME }.id
    private val nov10 = LocalDateTime.of(2026, 11, 10, 9, 30)

    @Test
    fun seedsDefaultCategoriesAndCashAccount() = runTest {
        val cats = categories.observeCategories().first()
        assertEquals(14, cats.count { it.kind == CategoryKind.EXPENSE && it.parentId == null })
        val groceries = cats.first { it.name == "Groceries" }
        assertEquals(10, cats.count { it.parentId == groceries.id })
        assertTrue(cats.any { it.name == "Vegetables" && it.parentId == groceries.id })
        assertEquals(6, cats.count { it.kind == CategoryKind.INCOME })
        assertEquals(listOf("Cash"), accounts.observeAccounts().first().map { it.name })
    }

    @Test
    fun insertPersistsAndBalancesUpdateThroughFlow() = runTest {
        val cash = cashId()
        transactions.save(Transaction(type = TransactionType.INCOME, amount = 100_000, accountId = cash, categoryId = incomeCat(), occurredAt = nov10))
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 25_050, accountId = cash, categoryId = expenseCat(), occurredAt = nov10, note = " lunch "))

        val stored = transactions.observeAll().first()
        assertEquals(2, stored.size)
        assertEquals("lunch", stored.first { it.type == TransactionType.EXPENSE }.note)
        assertEquals(clock.millis(), stored.first().createdAt)
        assertEquals(74_950L, accounts.observeBalances().first()[cash])

        val month = transactions.observeRange(DateRange.month(YearMonth.of(2026, 11))).first()
        val s = FinanceRules.summarize(month, DateRange.month(YearMonth.of(2026, 11)))
        assertEquals(100_000, s.income)
        assertEquals(25_050, s.netExpense)
    }

    @Test
    fun floatingLocalTimeIsStoredExactly() = runTest {
        val t = LocalDateTime.of(2026, 3, 31, 23, 59)
        val id = transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 1, accountId = cashId(), categoryId = expenseCat(), occurredAt = t))
        assertEquals(t, transactions.get(id)!!.occurredAt)
    }

    @Test
    fun transactionDaoObservePayeesRanksAndGroupsPayees() = runTest {
        val cash = cashId()
        val category = expenseCat()
        suspend fun insert(payee: String, at: LocalDateTime) {
            db.transactionDao().insert(
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amount = 1,
                    accountId = cash,
                    toAccountId = null,
                    categoryId = category,
                    isRefund = false,
                    occurredAt = FloatingTime.toStored(at),
                    note = "",
                    payee = payee,
                    createdAt = 0,
                    updatedAt = 0,
                )
            )
        }

        insert("Fresh Mart", nov10)
        insert(" fresh mart ", nov10.plusDays(1))
        insert("FRESH MART", nov10.plusDays(2))
        insert("Bakery", nov10.plusDays(5))
        insert("Bakery", nov10.plusDays(8))
        insert("Cafe", nov10.plusDays(3))
        insert("Cafe", nov10.plusDays(4))
        insert("   ", nov10.plusDays(9))
        insert("", nov10.plusDays(10))

        assertEquals(
            listOf("FRESH MART", "Bakery", "Cafe"),
            db.transactionDao().observePayees().first(),
        )
    }

    @Test
    fun editUpdatesValuesAndKeepsCreatedAt() = runTest {
        val id = transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 1_000, accountId = cashId(), categoryId = expenseCat(), occurredAt = nov10))
        val original = transactions.get(id)!!
        transactions.save(original.copy(amount = 2_000, note = "edited", createdAt = 0))
        val edited = transactions.get(id)!!
        assertEquals(2_000, edited.amount)
        assertEquals("edited", edited.note)
        assertEquals(original.createdAt, edited.createdAt)
        assertEquals(1, transactions.observeAll().first().size)
    }

    @Test
    fun deleteRemovesAndRestoreBringsBackSameRecord() = runTest {
        val cash = cashId()
        val id = transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 5_000, accountId = cash, categoryId = expenseCat(), occurredAt = nov10))
        val removed = transactions.delete(id)
        assertNotNull(removed)
        assertNull(transactions.get(id))
        assertEquals(0L, accounts.observeBalances().first()[cash])
        transactions.restore(removed!!)
        assertEquals(removed, transactions.get(id))
        transactions.restore(removed) // second restore must not duplicate
        assertEquals(1, transactions.observeAll().first().size)
        assertNull(transactions.delete(9_999))
    }

    @Test
    fun transferKeepsTotalAndIsNotIncomeOrExpense() = runTest {
        val cash = cashId()
        val bank = accounts.save(Account(name = "Bank", type = AccountType.SAVINGS, openingBalance = 500_000))
        val before = FinanceRules.totalBalance(accounts.observeAccounts().first(), accounts.observeBalances().first())
        transactions.save(
            Transaction(type = TransactionType.TRANSFER, amount = 200_000, accountId = bank, toAccountId = cash, categoryId = expenseCat(), isRefund = true, occurredAt = nov10)
        )
        val stored = transactions.observeAll().first().single()
        assertNull("category stripped from transfer", stored.categoryId)
        assertFalse(stored.isRefund)
        val balances = accounts.observeBalances().first()
        assertEquals(200_000L, balances[cash])
        assertEquals(300_000L, balances[bank])
        assertEquals(before, FinanceRules.totalBalance(accounts.observeAccounts().first(), balances))
    }

    @Test
    fun creditCardPaymentIsNotDoubleCounted() = runTest {
        val bank = accounts.save(Account(name = "Bank", type = AccountType.CURRENT, openingBalance = 1_000_000))
        val card = accounts.save(Account(name = "Card", type = AccountType.CREDIT_CARD, openingBalance = 0))
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 40_000, accountId = card, categoryId = expenseCat(), occurredAt = nov10))
        transactions.save(Transaction(type = TransactionType.TRANSFER, amount = 40_000, accountId = bank, toAccountId = card, occurredAt = nov10.plusDays(5)))
        val all = transactions.observeAll().first()
        assertEquals(40_000, FinanceRules.summarize(all, DateRange.ALL).netExpense)
        val b = accounts.observeBalances().first()
        assertEquals(0L, b[card])
        assertEquals(960_000L, b[bank])
    }

    @Test
    fun integrityRulesAreEnforced() = runTest {
        val cash = cashId()
        suspend fun expectFail(tx: Transaction) {
            try { transactions.save(tx); fail("expected IntegrityException for $tx") } catch (e: IntegrityException) { /* expected */ }
        }
        expectFail(Transaction(type = TransactionType.EXPENSE, amount = 0, accountId = cash, categoryId = expenseCat(), occurredAt = nov10))
        expectFail(Transaction(type = TransactionType.EXPENSE, amount = -5, accountId = cash, categoryId = expenseCat(), occurredAt = nov10))
        expectFail(Transaction(type = TransactionType.EXPENSE, amount = 5, accountId = cash, categoryId = incomeCat(), occurredAt = nov10))
        expectFail(Transaction(type = TransactionType.EXPENSE, amount = 5, accountId = cash, categoryId = null, occurredAt = nov10))
        expectFail(Transaction(type = TransactionType.EXPENSE, amount = 5, accountId = 999, categoryId = expenseCat(), occurredAt = nov10))
        expectFail(Transaction(type = TransactionType.TRANSFER, amount = 5, accountId = cash, toAccountId = cash, occurredAt = nov10))
        expectFail(Transaction(type = TransactionType.TRANSFER, amount = 5, accountId = cash, toAccountId = null, occurredAt = nov10))
        assertTrue(transactions.observeAll().first().isEmpty())
    }

    @Test
    fun incomeCannotBeMarkedRefund() = runTest {
        val id = transactions.save(Transaction(type = TransactionType.INCOME, amount = 5, accountId = cashId(), categoryId = incomeCat(), isRefund = true, occurredAt = nov10))
        assertFalse(transactions.get(id)!!.isRefund)
    }

    @Test
    fun accountWithTransactionsCannotBeDeleted() = runTest {
        val cash = cashId()
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 5, accountId = cash, categoryId = expenseCat(), occurredAt = nov10))
        assertFalse(accounts.deleteIfUnused(cash))
        val empty = accounts.save(Account(name = "Unused", type = AccountType.OTHER))
        assertTrue(accounts.deleteIfUnused(empty))
    }

    @Test
    fun deactivatedCategoryKeepsHistoryAndKindChangeIsBlockedWhenUsed() = runTest {
        val catId = expenseCat()
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 5, accountId = cashId(), categoryId = catId, occurredAt = nov10))
        categories.setActive(catId, false)
        assertFalse(categories.observeCategories().first().first { it.id == catId }.active)
        assertEquals(catId, transactions.observeAll().first().single().categoryId)
        val cat = categories.observeCategories().first().first { it.id == catId }
        try {
            categories.save(cat.copy(kind = CategoryKind.INCOME)); fail("expected IntegrityException")
        } catch (e: IntegrityException) { /* expected */ }
    }

    @Test
    fun filterSearchAndSort() = runTest {
        val cash = cashId()
        val bank = accounts.save(Account(name = "Bank", type = AccountType.SAVINGS))
        val food = expenseCat()
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 300, accountId = cash, categoryId = food, occurredAt = nov10, note = "Coffee beans"))
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 100, accountId = cash, categoryId = food, occurredAt = nov10.plusDays(1), payee = "Bakery"))
        transactions.save(Transaction(type = TransactionType.INCOME, amount = 900, accountId = bank, categoryId = incomeCat(), occurredAt = nov10.plusDays(2)))
        transactions.save(Transaction(type = TransactionType.TRANSFER, amount = 50, accountId = bank, toAccountId = cash, occurredAt = LocalDateTime.of(2026, 10, 1, 8, 0)))

        val nov = DateRange.month(YearMonth.of(2026, 11))
        assertEquals(3, transactions.observeFiltered(TransactionFilter(range = nov)).first().size)
        assertEquals(4, transactions.observeFiltered(TransactionFilter()).first().size)
        assertEquals(listOf(100L, 300L), transactions.observeFiltered(TransactionFilter(type = TransactionType.EXPENSE, sort = TransactionSort.SMALLEST)).first().map { it.amount })
        assertEquals(listOf(900L, 300L, 100L, 50L), transactions.observeFiltered(TransactionFilter(sort = TransactionSort.LARGEST)).first().map { it.amount })
        assertEquals(listOf(900L, 100L, 300L, 50L), transactions.observeFiltered(TransactionFilter(sort = TransactionSort.NEWEST)).first().map { it.amount })
        assertEquals(1, transactions.observeFiltered(TransactionFilter(query = "coffee")).first().size)
        assertEquals(1, transactions.observeFiltered(TransactionFilter(query = "bakery")).first().size)
        // account filter includes transfers into the account
        assertEquals(3, transactions.observeFiltered(TransactionFilter(accountId = cash)).first().size)
        assertEquals(2, transactions.observeFiltered(TransactionFilter(categoryId = food)).first().size)
    }

    @Test
    fun clearAllDataRemovesEverythingAndReseeds() = runTest {
        accounts.save(Account(name = "Bank", type = AccountType.SAVINGS))
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 5, accountId = cashId(), categoryId = expenseCat(), occurredAt = nov10))
        repos.maintenance(images).clearAllData()
        assertTrue(transactions.observeAll().first().isEmpty())
        assertEquals(listOf("Cash"), accounts.observeAccounts().first().map { it.name })
        assertEquals(30, categories.observeCategories().first().size)
    }

    private class MemoryStorage : BackupStorage {
        val files = HashMap<String, ByteArray>()
        override suspend fun write(target: String, bytes: ByteArray) { files[target] = bytes }
        override suspend fun read(source: String): ByteArray = files[source] ?: throw java.io.IOException("missing")
    }

    @Test
    fun backupExportAndRestoreRoundTripWithoutDuplicates() = runTest {
        val storage = MemoryStorage()
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = dsScope) { File(tmp.root, "test.preferences_pb") })
        settings.setCurrency("USD")
        val backup = repos.backup(storage, settings, images)

        val cash = cashId()
        val bank = accounts.save(Account(name = "Bank", type = AccountType.SAVINGS, openingBalance = 10_000))
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 1_234, accountId = cash, categoryId = expenseCat(), occurredAt = nov10, note = "Ünïcode ✓"))
        transactions.save(Transaction(type = TransactionType.TRANSFER, amount = 500, accountId = bank, toAccountId = cash, occurredAt = nov10))
        val balancesBefore = accounts.observeBalances().first()
        val txBefore = transactions.observeAll().first().sortedBy { it.id }

        backup.exportTo("file://backup")
        // change data after the backup
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 99, accountId = cash, categoryId = expenseCat(), occurredAt = nov10))
        settings.setCurrency("INR")

        val inspected = backup.inspect("file://backup")
        assertTrue(inspected is BackupDecodeResult.Valid)
        val file = (inspected as BackupDecodeResult.Valid).file
        backup.restore(file)
        backup.restore(file) // importing the same file twice must not duplicate

        assertEquals(txBefore, transactions.observeAll().first().sortedBy { it.id })
        assertEquals(balancesBefore, accounts.observeBalances().first())
        assertEquals("USD", settings.settings.first().currencyCode)
    }

    @Test
    fun invalidBackupLeavesDatabaseUntouched() = runTest {
        val storage = MemoryStorage()
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = dsScope) { File(tmp.root, "t2.preferences_pb") })
        val backup = repos.backup(storage, settings, images)
        transactions.save(Transaction(type = TransactionType.EXPENSE, amount = 5, accountId = cashId(), categoryId = expenseCat(), occurredAt = nov10))

        storage.files["bad"] = "{\"format\":\"homebudget.backup\"}".toByteArray()
        assertTrue(backup.inspect("bad") is BackupDecodeResult.Invalid)
        val snapshot = backup.createSnapshot()
        storage.files["broken-ref"] = BackupCodec.encode(snapshot.copy(transactions = snapshot.transactions.map { it.copy(accountId = 777) }))
        assertTrue(backup.inspect("broken-ref") is BackupDecodeResult.Invalid)
        assertTrue(backup.inspect("missing") is BackupDecodeResult.Invalid)
        assertEquals(1, transactions.observeAll().first().size)
    }
}
