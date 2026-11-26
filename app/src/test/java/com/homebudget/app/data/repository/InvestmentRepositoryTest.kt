package com.homebudget.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.TestRepositories
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SharedSpaceProvider
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.family.FamilyRole
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class InvestmentRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepositories
    private lateinit var investments: RoomInvestmentRepository
    private val clock = MutableClock(Instant.parse("2026-11-20T10:00:00Z"))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        repos = TestRepositories(db, clock)
        investments = RoomInvestmentRepository(db, clock, repos.writer, SharedSpaceProvider)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun addAccount(
        name: String,
        type: AccountType,
        opening: Long = 0,
    ): Long = repos.accounts().save(Account(name = name, type = type, openingBalance = opening))

    private fun transfer(from: Long, to: Long, amount: Long) = Transaction(
        type = TransactionType.TRANSFER,
        amount = amount,
        accountId = from,
        toAccountId = to,
        occurredAt = LocalDateTime.of(2026, 11, 10, 9, 0),
    )

    @Test
    fun recordingValueForInvestmentAccountSucceedsAndIsObservable() = runTest {
        val id = addAccount("Fake fund", AccountType.INVESTMENT, 10_000)
        investments.recordValue(id, 12_345)

        val latest = investments.observeLatestValues().first().single()
        assertEquals(id, latest.accountId)
        assertEquals(12_345L, latest.valueMinor)
        assertEquals(clock.millis(), latest.recordedAt)
    }

    @Test
    fun savedValueReplacesLedgerBalanceInAccountBalancesAndTotal() = runTest {
        val fund = addAccount("Fake fund", AccountType.INVESTMENT, 10_000)
        val cash = addAccount("Fake wallet", AccountType.CASH, 5_000)
        investments.recordValue(fund, 14_000)

        val accounts = repos.accounts()
        val balances = accounts.observeBalances().first()
        assertEquals(14_000L, balances[fund])
        assertEquals(5_000L, balances[cash])
        // The ledger balance is untouched: it is the contributions figure.
        assertEquals(10_000L, accounts.observeLedgerBalances().first()[fund])
        assertEquals(19_000L, FinanceRules.totalBalance(accounts.observeAccounts().first(), balances))
    }

    @Test
    fun accountWithoutValueKeepsLedgerBalance() = runTest {
        val fund = addAccount("Fake fund", AccountType.INVESTMENT, 10_000)
        assertEquals(10_000L, repos.accounts().observeBalances().first()[fund])
        assertTrue(investments.observeLatestValues().first().isEmpty())
    }

    @Test
    fun nonInvestmentAccountIsRejected() = runTest {
        val cash = addAccount("Fake wallet", AccountType.CASH)
        expectIntegrity { investments.recordValue(cash, 1_000) }
        assertTrue(db.investmentValueDao().getAll().isEmpty())
        assertTrue(versions().isEmpty())
    }

    @Test
    fun unknownAccountIsRejected() = runTest {
        expectIntegrity { investments.recordValue(987_654, 1_000) }
        assertTrue(db.investmentValueDao().getAll().isEmpty())
    }

    @Test
    fun negativeValueIsRejected() = runTest {
        val id = addAccount("Fake fund", AccountType.INVESTMENT)
        expectIntegrity { investments.recordValue(id, -1) }
        assertTrue(db.investmentValueDao().getAll().isEmpty())
        assertTrue(versions().isEmpty())
    }

    @Test
    fun valueAboveMaxAmountIsRejectedButBoundariesAreAccepted() = runTest {
        val id = addAccount("Fake fund", AccountType.INVESTMENT)
        expectIntegrity { investments.recordValue(id, Money.MAX_AMOUNT + 1) }
        assertTrue(db.investmentValueDao().getAll().isEmpty())

        investments.recordValue(id, 0)
        assertEquals(0L, investments.observeLatestValues().first().single().valueMinor)
        clock.advanceMillis(1_000)
        investments.recordValue(id, Money.MAX_AMOUNT)
        assertEquals(Money.MAX_AMOUNT, investments.observeLatestValues().first().single().valueMinor)
    }

    @Test
    fun laterValuationSupersedesEarlierOneButHistoryIsKept() = runTest {
        val id = addAccount("Fake fund", AccountType.INVESTMENT)
        investments.recordValue(id, 1_000)
        clock.advanceMillis(60_000)
        investments.recordValue(id, 800)

        assertEquals(800L, investments.observeLatestValues().first().single().valueMinor)
        assertEquals(800L, repos.accounts().observeBalances().first()[id])
        assertEquals(listOf(1_000L, 800L), db.investmentValueDao().getAll().sortedBy { it.recordedAt }.map { it.valueMinor })
    }

    @Test
    fun valuationsRecordedInTheSameMillisecondUseTheLastWritten() = runTest {
        val id = addAccount("Fake fund", AccountType.INVESTMENT)
        investments.recordValue(id, 1_000)
        investments.recordValue(id, 2_000)
        assertEquals(2_000L, investments.observeLatestValues().first().single().valueMinor)
    }

    @Test
    fun eachAccountKeepsItsOwnLatestValue() = runTest {
        val a = addAccount("Fake fund A", AccountType.INVESTMENT)
        val b = addAccount("Fake fund B", AccountType.INVESTMENT)
        investments.recordValue(a, 100)
        clock.advanceMillis(1_000)
        investments.recordValue(b, 200)
        clock.advanceMillis(1_000)
        investments.recordValue(a, 150)

        assertEquals(mapOf(a to 150L, b to 200L), investments.observeLatestValues().first().associate { it.accountId to it.valueMinor })
    }

    @Test
    fun gainIsLatestValueMinusNetTransferContributions() = runTest {
        val fund = addAccount("Fake fund", AccountType.INVESTMENT, 0)
        val bank = addAccount("Fake bank", AccountType.CURRENT, 100_000)
        val txns = repos.transactions()
        txns.save(transfer(bank, fund, 5_000))
        txns.save(transfer(fund, bank, 2_000))

        val contributions = repos.accounts().observeLedgerBalances().first().getValue(fund)
        assertEquals(3_000L, contributions)

        investments.recordValue(fund, 3_500)
        assertEquals(500L, investments.observeLatestValues().first().single().valueMinor - contributions)

        clock.advanceMillis(1_000)
        investments.recordValue(fund, 2_500)
        assertEquals(-500L, investments.observeLatestValues().first().single().valueMinor - contributions)

        // The other account is never revalued: bank = 100_000 - 5_000 + 2_000.
        assertEquals(97_000L, repos.accounts().observeBalances().first()[bank])
    }

    @Test
    fun openingBalanceCountsAsContributionInGainLoss() = runTest {
        val fund = addAccount("Fake fund", AccountType.INVESTMENT, 10_000)
        val bank = addAccount("Fake bank", AccountType.CURRENT, 50_000)
        repos.transactions().save(transfer(bank, fund, 1_000))
        investments.recordValue(fund, 12_000)

        val contributions = repos.accounts().observeLedgerBalances().first().getValue(fund)
        assertEquals(11_000L, contributions)
        assertEquals(1_000L, investments.observeLatestValues().first().single().valueMinor - contributions)
    }

    @Test
    fun everyWriteCreatesAnApprovedEntityVersionWithoutOverwritingHistory() = runTest {
        val id = addAccount("Fake fund", AccountType.INVESTMENT)
        investments.recordValue(id, 1_000)
        clock.advanceMillis(1_000)
        investments.recordValue(id, 2_000)

        val rows = db.investmentValueDao().getAll()
        assertEquals(2, rows.size)
        val history = versions()
        assertEquals(2, history.size)
        assertTrue(history.all { it.status == "APPROVED" && it.kind == "ADD" && !it.deleted && !it.isPrivate })
        assertTrue(history.all { it.version == 1 })
        assertEquals(rows.map { it.uid }.toSet(), history.map { it.entityUid }.toSet())
        assertTrue(history.any { it.snapshot.contains("1000") })
        assertTrue(history.any { it.snapshot.contains("2000") })
        assertTrue(history.all { it.authorId == "me" })
    }

    @Test
    fun privateValuationIsNotVisibleFromSharedSpace() = runTest {
        val space = TestSpaceProvider()
        val writer = VersionedWriter(db, clock, adminIdentity(), space, SnapshotJsonCodec(db))
        val privateAccounts = AccountRepository(db, clock, writer, space)
        val privateInvestments = RoomInvestmentRepository(db, clock, writer, space)

        space.value.value = Space.PRIVATE
        val privateFund = privateAccounts.save(Account(name = "Fake private fund", type = AccountType.INVESTMENT, openingBalance = 100))
        privateInvestments.recordValue(privateFund, 777)
        assertEquals(777L, privateInvestments.observeLatestValues().first().single().valueMinor)
        val privateRow = db.investmentValueDao().getAll().single()
        assertTrue(privateRow.isPrivate)
        assertEquals(1, db.entityVersionDao().latestApprovedForSpace("investment_value", privateRow.uid, true)?.version)
        assertNull(db.entityVersionDao().latestApprovedForSpace("investment_value", privateRow.uid, false))

        space.value.value = Space.SHARED
        assertTrue(privateInvestments.observeLatestValues().first().isEmpty())
        assertTrue(privateAccounts.observeBalances().first()[privateFund] == null)
        // The shared space cannot even address the private account.
        expectIntegrity { privateInvestments.recordValue(privateFund, 1) }
        assertEquals(1, db.investmentValueDao().getAll().size)
    }

    @Test
    fun sharedValuationIsNotVisibleFromPrivateSpaceAndCannotTargetSharedAccount() = runTest {
        val sharedFund = addAccount("Fake shared fund", AccountType.INVESTMENT)
        investments.recordValue(sharedFund, 500)

        val space = TestSpaceProvider()
        val writer = VersionedWriter(db, clock, adminIdentity(), space, SnapshotJsonCodec(db))
        val privateInvestments = RoomInvestmentRepository(db, clock, writer, space)
        space.value.value = Space.PRIVATE
        assertTrue(privateInvestments.observeLatestValues().first().isEmpty())
        expectIntegrity { privateInvestments.recordValue(sharedFund, 1) }
        assertEquals(1, db.investmentValueDao().getAll().size)
    }

    private suspend fun versions() = db.entityVersionDao().getAll().filter { it.entityType == "investment_value" }

    private suspend fun expectIntegrity(block: suspend () -> Unit) {
        try {
            block()
            fail("expected IntegrityException")
        } catch (e: IntegrityException) {
            assertNotNull(e.message)
        }
    }

    private fun adminIdentity() = object : IdentityProvider {
        override suspend fun current() = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.PERSONAL)
    }

    private class TestSpaceProvider : SpaceProvider {
        val value = MutableStateFlow(Space.SHARED)
        override val current: StateFlow<Space> get() = value
    }

    private class MutableClock(private var now: Instant) : Clock() {
        fun advanceMillis(ms: Long) { now = now.plusMillis(ms) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
