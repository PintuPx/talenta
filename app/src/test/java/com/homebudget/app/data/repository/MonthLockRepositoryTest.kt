package com.homebudget.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.TestRepositories
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.db.toStoredMonth
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.SharedSpaceProvider
import com.homebudget.app.domain.family.FamilyRole
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class MonthLockRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepositories
    private lateinit var locks: RoomMonthLockRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)
    private val november = YearMonth.of(2026, 11)
    private val october = YearMonth.of(2026, 10)
    private val identity = MutableIdentity()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        repos = TestRepositories(db, clock)
        locks = repos.monthLocks
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun lockVersions(month: YearMonth) =
        db.entityVersionDao().getAll()
            .filter { it.entityType == "month_lock" && it.entityUid == month.toStoredMonth().toString() }
            .sortedBy { it.version }

    /** A writer whose role can change; used to act as a non-Admin on the same database. */
    private fun roleBasedRepositories(role: FamilyRole, mode: AppMode = AppMode.SHARED): Triple<VersionedWriter, RoomMonthLockRepository, TransactionRepository> {
        identity.value = WriteIdentity("member-1", "Fake Member", role, mode)
        val writer = VersionedWriter(db, clock, identity, SharedSpaceProvider, SnapshotJsonCodec(db))
        val family = RoomExpenseFamilyRepository(db, clock, writer)
        return Triple(writer, RoomMonthLockRepository(db, clock, writer), TransactionRepository(db, clock, writer, family))
    }

    private suspend fun expenseIn(month: YearMonth, accountId: Long, amount: Long = 1_234): Transaction {
        val category = db.categoryDao().getAll().first { it.kind == CategoryKind.EXPENSE }
        return Transaction(
            type = TransactionType.EXPENSE,
            amount = amount,
            accountId = accountId,
            categoryId = category.id,
            occurredAt = LocalDateTime.of(month.year, month.month, 10, 9, 0),
        )
    }

    private suspend fun fakeAccount() = repos.accounts().save(Account(name = "Fake wallet", type = AccountType.CASH, openingBalance = 100_000))

    @Test
    fun closeMakesMonthClosedAndOtherMonthsStayOpen() = runTest {
        assertFalse(locks.isClosed(november))
        locks.close(november)

        assertTrue(locks.isClosed(november))
        assertTrue(locks.observeClosed(november).first())
        assertFalse(locks.isClosed(october))
        assertFalse(locks.observeClosed(october).first())
    }

    @Test
    fun closeRecordsWhoAndWhen() = runTest {
        locks.close(november)
        val lock = locks.observeLocks().first().single()
        assertEquals(november, lock.month)
        assertEquals(clock.millis(), lock.closedAt)
        assertEquals("You", lock.closedBy)
    }

    @Test
    fun reopenMakesMonthOpenAgain() = runTest {
        locks.close(november)
        locks.reopen(november)

        assertFalse(locks.isClosed(november))
        assertFalse(locks.observeClosed(november).first())
        assertTrue(locks.observeLocks().first().isEmpty())
    }

    @Test
    fun closeAndReopenAreVersionedInEntityVersions() = runTest {
        locks.close(november)
        locks.reopen(november)

        val history = lockVersions(november)
        assertEquals(listOf(1, 2), history.map { it.version })
        assertEquals(listOf("CLOSE_MONTH", "REOPEN_MONTH"), history.map { it.kind })
        assertEquals(listOf(false, true), history.map { it.deleted })
        assertTrue(history.all { it.status == "APPROVED" && !it.isPrivate && it.authorId == "me" })
        assertEquals(listOf(0, 1), history.map { it.baseVersion })
    }

    @Test
    fun closingAgainAfterReopenContinuesTheVersionSequence() = runTest {
        locks.close(november)
        locks.reopen(november)
        locks.close(november)

        assertTrue(locks.isClosed(november))
        assertEquals(listOf(1, 2, 3), lockVersions(november).map { it.version })
        assertEquals("CLOSE_MONTH", lockVersions(november).last().kind)
    }

    @Test
    fun closingAnAlreadyClosedMonthAndReopeningAnOpenOneAreNoOps() = runTest {
        locks.reopen(november)
        assertTrue(lockVersions(november).isEmpty())

        locks.close(november)
        locks.close(november)
        assertEquals(1, lockVersions(november).size)
        assertEquals(1, locks.observeLocks().first().size)
    }

    @Test
    fun observeLocksListsAllClosedMonths() = runTest {
        locks.close(october)
        locks.close(november)
        assertEquals(setOf(october, november), locks.observeLocks().first().map { it.month }.toSet())
        locks.reopen(october)
        assertEquals(listOf(november), locks.observeLocks().first().map { it.month })
    }

    @Test
    fun adminCanStillAddEditAndDeleteInAClosedMonth() = runTest {
        val account = fakeAccount()
        val txns = repos.transactions()
        val id = txns.save(expenseIn(november, account))
        locks.close(november)

        // Add
        val addedId = txns.save(expenseIn(november, account, amount = 500))
        assertTrue(addedId > 0)
        // Edit
        val edited = txns.get(id)!!
        txns.save(edited.copy(amount = 2_000))
        assertEquals(2_000L, txns.get(id)!!.amount)
        // Delete
        assertNotNull(txns.delete(addedId))
        assertEquals(null, txns.get(addedId))
        assertTrue(locks.isClosed(november))
    }

    @Test
    fun adminInPersonalModeIsNotBlockedEvenWithANonAdminRole() = runTest {
        // The writer treats every mode other than SHARED as Admin (VersionedWriter.writeInTransaction).
        val account = fakeAccount()
        locks.close(november)
        val (_, _, txns) = roleBasedRepositories(FamilyRole.REVIEWER, AppMode.PERSONAL)
        val id = txns.save(expenseIn(november, account))
        assertTrue(id > 0)
    }

    @Test
    fun nonAdminCannotAddEditOrDeleteInAClosedMonth() = runTest {
        val account = fakeAccount()
        val existingId = repos.transactions().save(expenseIn(november, account))
        locks.close(november)
        val countBefore = db.transactionDao().getAll().size
        val versionsBefore = db.entityVersionDao().getAll().size

        val (_, _, txns) = roleBasedRepositories(FamilyRole.REVIEWER)
        expectIntegrity("closed") { txns.save(expenseIn(november, account)) }
        expectIntegrity("closed") { txns.save(txns.get(existingId)!!.copy(amount = 9_999)) }
        expectIntegrity("closed") { txns.delete(existingId) }

        assertEquals(countBefore, db.transactionDao().getAll().size)
        assertEquals(1_234L, db.transactionDao().getAll().single { it.id == existingId }.amount)
        assertEquals(versionsBefore, db.entityVersionDao().getAll().size)
    }

    @Test
    fun nonAdminCanStillWriteInAnOpenMonth() = runTest {
        val account = fakeAccount()
        locks.close(november)
        val (_, _, txns) = roleBasedRepositories(FamilyRole.REVIEWER)

        // October is open: a Reviewer's change is not blocked by the lock (it waits for approval).
        txns.save(expenseIn(october, account))
        val pending = db.entityVersionDao().getAll().filter { it.entityType == "transaction" && it.status == "PENDING" }
        assertEquals(1, pending.size)
    }

    @Test
    fun onlyAdminCanCloseOrReopenAMonthInSharedMode() = runTest {
        locks.close(october)
        for (role in listOf(FamilyRole.REVIEWER, FamilyRole.READ_ONLY)) {
            val (_, memberLocks, _) = roleBasedRepositories(role)
            expectIntegrity { memberLocks.close(november) }
            expectIntegrity { memberLocks.reopen(october) }
        }
        assertFalse(locks.isClosed(november))
        assertTrue(locks.isClosed(october))
        assertEquals(1, db.entityVersionDao().getAll().count { it.entityType == "month_lock" })
    }

    @Test
    fun bulkChangesSkipClosedMonthsForNonAdminAndNotForAdmin() = runTest {
        locks.close(november)
        val closed = november.toStoredMonth()
        val openMonth = october.toStoredMonth()

        val (writer, _, _) = roleBasedRepositories(FamilyRole.REVIEWER)
        val reviewerResult = writer.applyBulk(
            listOf(bulkChange(writer, "bulk-closed", closed), bulkChange(writer, "bulk-open", openMonth)),
            bulkId = "reviewer-batch",
        )
        assertEquals(1, reviewerResult.skippedClosedMonths)
        assertEquals(1, reviewerResult.pendingApproval)
        assertEquals(0, reviewerResult.applied)

        identity.value = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.SHARED)
        val adminResult = writer.applyBulk(
            listOf(bulkChange(writer, "admin-bulk-closed", closed)),
            bulkId = "admin-batch",
        )
        assertEquals(0, adminResult.skippedClosedMonths)
        assertEquals(1, adminResult.applied)
        assertNotNull(db.entityVersionDao().latestApprovedForSpace("transaction", "admin-bulk-closed", false))
    }

    private suspend fun bulkChange(writer: VersionedWriter, uid: String, month: Int) = writer.change(
        entityType = "transaction",
        entityUid = uid,
        isPrivate = false,
        kind = "EDIT",
        entity = Unit,
        deleted = true,
        month = month,
        snapshotOverride = """{"uid":"$uid"}""",
        applyCurrent = {},
    )

    private suspend fun expectIntegrity(messagePart: String? = null, block: suspend () -> Unit) {
        try {
            block()
            fail("expected IntegrityException")
        } catch (e: IntegrityException) {
            if (messagePart != null) assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(messagePart))
        }
    }

    private class MutableIdentity : IdentityProvider {
        var value = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.PERSONAL)
        override suspend fun current(): WriteIdentity = value
    }
}
