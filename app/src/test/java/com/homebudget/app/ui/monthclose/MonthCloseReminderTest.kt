package com.homebudget.app.ui.monthclose

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
import com.homebudget.app.data.repository.RoomExpenseFamilyRepository
import com.homebudget.app.data.repository.SnapshotJsonCodec
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.repository.VersionedWriter
import com.homebudget.app.data.repository.WriteIdentity
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.family.FamilyRole
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/** The Home month-close reminder must not nag when the previous month has no transactions. */
@RunWith(AndroidJUnit4::class)
class MonthCloseReminderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepositories
    private lateinit var transactions: TransactionRepository
    private lateinit var settings: SettingsRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Switchable space, as in BackupFormatRestoreTest. The reminder observes it like the app does.
    private val spaceState = MutableStateFlow(Space.SHARED)
    private val space = object : SpaceProvider {
        override val current: StateFlow<Space> = spaceState
    }
    private val identity = object : IdentityProvider {
        override suspend fun current() = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.PERSONAL)
    }
    private lateinit var accounts: AccountRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        repos = TestRepositories(db, clock)
        val writer = VersionedWriter(db, clock, identity, space, SnapshotJsonCodec(db))
        accounts = AccountRepository(db, clock, writer, space)
        transactions = TransactionRepository(db, clock, writer, RoomExpenseFamilyRepository(db, clock, writer), space)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = dsScope) { File(tmp.root, "m.preferences_pb") })
    }

    @After
    fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    /** Same wiring as MonthCloseViewModel, with a single clock tick. */
    private suspend fun state(month: YearMonth = october) = monthCloseStateFlow(
        month = month,
        closed = repos.monthLocks.observeClosed(month),
        hasTransactions = transactions.observeHasAnyInRange(DateRange.month(month)),
        settings = settings.settings,
        clockTicks = flowOf(clock.millis()),
    ).first()

    private suspend fun addExpenseIn(month: YearMonth) {
        val accountId = accounts.save(Account(name = "Fake wallet", type = AccountType.CASH, openingBalance = 100_000))
        val category = db.categoryDao().getAll().first { it.kind == CategoryKind.EXPENSE }
        transactions.save(
            Transaction(
                type = TransactionType.EXPENSE,
                amount = 1_234,
                accountId = accountId,
                categoryId = category.id,
                occurredAt = LocalDateTime.of(month.year, month.month, 10, 9, 0),
            ),
        )
    }

    @Test
    fun noTransactionsInPreviousMonthShowsNoReminder() = runTest {
        val s = state()
        assertFalse(s.showReminder)
        assertFalse(s.closed)
    }

    @Test
    fun transactionOnlyInCurrentMonthShowsNoReminder() = runTest {
        addExpenseIn(november)
        assertFalse(state().showReminder)
    }

    @Test
    fun oneTransactionInPreviousMonthShowsReminder() = runTest {
        addExpenseIn(october)
        assertTrue(state().showReminder)
    }

    @Test
    fun transactionCountsOnlyInItsOwnSpace() = runTest {
        spaceState.value = Space.PRIVATE
        addExpenseIn(october)
        assertTrue(state().showReminder)
        spaceState.value = Space.SHARED
        assertFalse(state().showReminder)
    }

    @Test
    fun closedMonthShowsNoReminder() = runTest {
        addExpenseIn(october)
        repos.monthLocks.close(october)
        val s = state()
        assertFalse(s.showReminder)
        assertTrue(s.closed)
    }

    @Test
    fun snoozedReminderStaysHiddenUntilSnoozeEnds() = runTest {
        addExpenseIn(october)
        settings.setMonthCloseReminderUntil(clock.millis() + Duration.ofDays(3).toMillis())
        assertFalse(state().showReminder)
        settings.setMonthCloseReminderUntil(clock.millis())
        assertTrue(state().showReminder)
    }
}
