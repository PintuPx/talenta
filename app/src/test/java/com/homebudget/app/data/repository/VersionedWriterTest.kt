package com.homebudget.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.AccountEntity
import com.homebudget.app.data.db.EntityVersionEntity
import com.homebudget.app.data.db.MonthLockEntity
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.db.TransactionEntity
import com.homebudget.app.data.db.toStoredMonth
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.family.FamilyRole
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class VersionedWriterTest {
    private lateinit var db: AppDatabase
    private val identity = TestIdentityProvider()
    private val space = TestSpaceProvider()
    private lateinit var writer: VersionedWriter
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        writer = VersionedWriter(db, clock, identity, space, SnapshotJsonCodec(db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun adminWritesAreAppliedAndVersionsIncreaseFromTheLatestApprovedValue() = runTest {
        val first = change("entry-1")
        assertEquals(WriteResult.APPLIED, writer.apply(first))

        val second = change("entry-1", expectedBaseVersion = 1)
        assertEquals(WriteResult.APPLIED, writer.apply(second))

        val latest = db.entityVersionDao().latestApprovedForSpace("transaction", "entry-1", false)
        assertEquals(2, latest?.version)
        assertEquals(1, latest?.baseVersion)
    }

    @Test
    fun reviewerChangesArePendingAndReadOnlyChangesAreBlocked() = runTest {
        identity.value = WriteIdentity("reviewer", "Reviewer", FamilyRole.REVIEWER, AppMode.SHARED)
        assertEquals(WriteResult.PENDING_APPROVAL, writer.apply(change("reviewer-entry")))
        assertEquals(1, db.entityVersionDao().getPendingForEntity("transaction", "reviewer-entry").size)

        identity.value = WriteIdentity("reader", "Reader", FamilyRole.READ_ONLY, AppMode.SHARED)
        assertEquals(WriteResult.BLOCKED_READ_ONLY, writer.apply(change("reader-entry")))
        assertTrue(db.entityVersionDao().getPendingForEntity("transaction", "reader-entry").isEmpty())
    }

    @Test
    fun staleBaseAndClosedMonthAreRejectedWithoutWritingHistory() = runTest {
        writer.apply(change("stale-entry"))
        assertEquals(WriteResult.STALE_BASE, writer.apply(change("stale-entry", expectedBaseVersion = 0)))

        identity.value = WriteIdentity("reviewer", "Reviewer", FamilyRole.REVIEWER, AppMode.SHARED)
        val month = YearMonth.of(2026, 11)
        db.monthLockDao().insert(MonthLockEntity(month.toStoredMonth(), clock.millis(), "Admin"))
        assertEquals(
            WriteResult.BLOCKED_MONTH_CLOSED,
            writer.apply(change("closed-entry", month = month.toStoredMonth())),
        )
        assertNull(db.entityVersionDao().latestApprovedForSpace("transaction", "closed-entry", false))
    }

    @Test
    fun monthCloseAndReopenAreVersionedAndIdempotent() = runTest {
        val month = YearMonth.of(2026, 11)
        val repository = RoomMonthLockRepository(db, clock, writer)

        repository.close(month)
        repository.close(month)
        assertTrue(repository.isClosed(month))
        assertEquals(
            1,
            db.entityVersionDao().latestApprovedForSpace("month_lock", month.toStoredMonth().toString(), false)?.version,
        )

        repository.reopen(month)
        assertFalse(repository.isClosed(month))
        val history = db.entityVersionDao().observeForEntityInSpace("month_lock", month.toStoredMonth().toString(), privateSpace = false).first()
        assertEquals(2, history.first().version)
        assertTrue(history.first().deleted)
    }

    @Test
    fun historyShowsFieldDiffsAndExcludesPrivateRowsFromHouseholdHistory() = runTest {
        db.entityVersionDao().insert(historyVersion("history-1", version = 1, name = "Old", createdAt = 1))
        db.entityVersionDao().insert(historyVersion("history-1", version = 2, name = "New", createdAt = 2))
        db.entityVersionDao().insert(
            historyVersion("private-history", version = 1, name = "Private", createdAt = 3, isPrivate = true),
        )

        val repository = RoomHistoryRepository(db)
        val entry = repository.observeEntity("transaction", "history-1").first()!!
        val change = entry.versions.first().changes.single()
        assertEquals(2, entry.versions.first().version)
        assertEquals("Name", change.field)
        assertEquals("Old", change.before)
        assertEquals("New", change.after)
        assertTrue(entry.versions.first().changes.none { it.field == "Uid" })

        val householdHistory = repository.observeAll().first()
        assertEquals(listOf("history-1", "history-1"), householdHistory.map { it.entityUid })
    }

    @Test
    fun familyPeopleAndTagsRejectDuplicatesAndSharesUseExactRemainderRule() = runTest {
        val family = RoomExpenseFamilyRepository(db, clock, writer)
        val alice = family.addPerson(" Alice ")
        val bob = family.addPerson("Bob")
        val cara = family.addPerson("Cara")
        try {
            family.addPerson("alice")
            fail("duplicate member should be rejected")
        } catch (_: IntegrityException) {
            assertEquals(3, family.observeActivePeople().first().size)
        }

        val tag = family.addTag("Household")
        try {
            family.addTag(" household ")
            fail("duplicate tag should be rejected")
        } catch (_: IntegrityException) {
            assertEquals(1, family.observeActiveTags().first().size)
        }
        try {
            family.addTag("All")
            fail("the built-in All tag should not be created")
        } catch (_: IntegrityException) {
            assertTrue(family.observeActiveTags().first().any { it.name == "Household" })
        }
        family.setTagMember(tag, alice, assigned = true)
        assertEquals(1, family.observeTagLinks().first().size)

        // Production saves shares right after the transaction row (RoomTransactionRepository.save).
        db.transactionDao().insert(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amount = 10,
                accountId = db.accountDao().getAll().first().id,
                toAccountId = null,
                categoryId = db.categoryDao().getAll().first { it.kind == CategoryKind.EXPENSE }.id,
                isRefund = false,
                occurredAt = 0,
                note = "",
                payee = "",
                createdAt = clock.millis(),
                updatedAt = clock.millis(),
                uid = "transaction-uid",
            ),
        )
        family.saveTransactionShares(
            transactionUid = "transaction-uid",
            totalMinor = 10,
            personUids = setOf(alice, bob, cara),
            privateSpace = false,
            month = YearMonth.of(2026, 11).toStoredMonth(),
        )
        assertEquals(
            mapOf(alice to 4L, bob to 3L, cara to 3L),
            family.transactionShares("transaction-uid", privateSpace = false).associate { it.personUid to it.amount },
        )

        // Shares have no transaction FK; writing them before the transaction row exists must not crash
        // and must still record an approved version from the change's own snapshot.
        family.saveTransactionShares(
            transactionUid = "unsaved-transaction-uid",
            totalMinor = 5,
            personUids = setOf(alice),
            privateSpace = false,
            month = YearMonth.of(2026, 11).toStoredMonth(),
        )
        val orphan = db.familyAllocationDao().getTransactionMembers("unsaved-transaction-uid").single()
        val orphanVersion = db.entityVersionDao().latestApprovedForSpace("transaction_member", orphan.uid, false)
        assertEquals(1, orphanVersion?.version)
        assertTrue(orphanVersion?.snapshot?.contains(orphan.uid) == true)
    }

    @Test
    fun investmentValuationsAreVersionedAndRequireAnInvestmentAccount() = runTest {
        val accountId = db.accountDao().insert(
            AccountEntity(
                name = "Index fund",
                type = AccountType.INVESTMENT,
                openingBalance = 25_000,
                includeInTotal = true,
                archived = false,
                sortOrder = 1,
                createdAt = clock.millis(),
                updatedAt = clock.millis(),
                uid = "investment-account-uid",
            ),
        )
        val investments = RoomInvestmentRepository(db, clock, writer, space)
        investments.recordValue(accountId, 27_500)

        assertEquals(27_500L, investments.observeLatestValues().first().single().valueMinor)
        assertEquals(1, db.entityVersionDao().latestApprovedForSpace("investment_value", db.investmentValueDao().getAll().single().uid, false)?.version)

        try {
            investments.recordValue(accountId, -1)
            fail("negative investment values should be rejected")
        } catch (_: IntegrityException) {
            assertEquals(1, db.investmentValueDao().getAll().size)
        }
    }

    @Test
    fun bulkWritesKeepTheBulkIdAndIdempotentEventUid() = runTest {
        identity.value = WriteIdentity("reviewer", "Reviewer", FamilyRole.REVIEWER, AppMode.SHARED)
        val result = writer.applyBulk(listOf(change("bulk-entry")), bulkId = "batch-1")
        assertEquals(1, result.pendingApproval)
        assertEquals("batch-1", result.bulkId)
        assertEquals("batch-1", db.entityVersionDao().getForBulk("batch-1").single().bulkId)

        identity.value = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.PERSONAL)
        val original = change("idempotent-entry")
        val stableEvent = original.copy(event = original.event.copy(eventUid = "stable-event"))
        assertEquals(WriteResult.APPLIED, writer.apply(stableEvent))
        assertEquals(WriteResult.APPLIED, writer.apply(stableEvent))
        assertEquals(1, db.entityVersionDao().observeAll().first().count { it.eventUid == "stable-event" })
    }

    @Test
    fun bulkWritesSkipClosedMonthsAndPersistTheAdminNotice() = runTest {
        identity.value = WriteIdentity("reviewer", "Reviewer", FamilyRole.REVIEWER, AppMode.SHARED)
        val closedMonth = YearMonth.of(2026, 11).toStoredMonth()
        db.monthLockDao().insert(MonthLockEntity(closedMonth, clock.millis(), "Admin"))

        val result = writer.applyBulk(
            listOf(change("locked-bulk-entry", month = closedMonth), change("open-bulk-entry")),
            bulkId = "batch-with-skips",
        )

        assertEquals(1, result.skippedClosedMonths)
        assertEquals(1, result.pendingApproval)
        assertEquals("1", db.syncStateDao().get(ADMIN_SKIPPED_BULK_NOTICE_KEY)?.value)
        assertTrue(db.entityVersionDao().getForBulk("batch-with-skips").all { it.bulkId == "batch-with-skips" })
    }

    @Test
    fun privateEntriesAreNotVisibleInSharedHistoryAndMakePrivateWritesASharedTombstone() = runTest {
        space.value.value = Space.PRIVATE
        val privateChange = change("private-entry", isPrivate = true)
        assertEquals(WriteResult.APPLIED, writer.apply(privateChange))
        assertNull(db.entityVersionDao().latestApprovedForSpace("transaction", "private-entry", false))
        assertEquals(1, db.entityVersionDao().latestApprovedForSpace("transaction", "private-entry", true)?.version)

        space.value.value = Space.SHARED
        writer.apply(change("moving-entry"))
        val makePrivate = change(
            "moving-entry",
            isPrivate = true,
            kind = "MAKE_PRIVATE",
            sourceIsPrivate = false,
        )
        assertEquals(WriteResult.APPLIED, writer.apply(makePrivate))

        val sharedTombstone = db.entityVersionDao().latestApprovedForSpace("transaction", "moving-entry", false)
        val privateSnapshot = db.entityVersionDao().latestApprovedForSpace("transaction", "moving-entry", true)
        assertEquals(2, sharedTombstone?.version)
        assertTrue(sharedTombstone?.deleted == true)
        assertTrue(sharedTombstone?.snapshot?.contains("tombstone") == true)
        assertEquals(1, privateSnapshot?.version)
        assertFalse(privateSnapshot?.deleted ?: true)
    }

    private suspend fun change(
        uid: String,
        isPrivate: Boolean = false,
        kind: String = "EDIT",
        month: Int? = null,
        expectedBaseVersion: Int? = null,
        sourceIsPrivate: Boolean? = null,
    ): VersionedChange = writer.change(
        entityType = "transaction",
        entityUid = uid,
        isPrivate = isPrivate,
        kind = kind,
        entity = Unit,
        deleted = true,
        month = month,
        expectedBaseVersion = expectedBaseVersion,
        sourceIsPrivate = sourceIsPrivate,
        snapshotOverride = """{"uid":"$uid"}""",
        applyCurrent = {},
    )

    private suspend fun historyVersion(
        uid: String,
        version: Int,
        name: String,
        createdAt: Long,
        isPrivate: Boolean = false,
    ) = EntityVersionEntity(
        entityType = "transaction",
        entityUid = uid,
        version = version,
        status = "APPROVED",
        baseVersion = version - 1,
        kind = if (version == 1) "ADD" else "EDIT",
        deleted = false,
        snapshot = """{"uid":"$uid","name":"$name","amount":500}""",
        snapshotFormat = 1,
        authorId = "member-1",
        authorName = "Alice",
        createdAt = createdAt,
        isPrivate = isPrivate,
        eventUid = "history-$uid-$version",
    )

    private class TestIdentityProvider : IdentityProvider {
        var value = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.PERSONAL)
        override suspend fun current(): WriteIdentity = value
    }

    private class TestSpaceProvider : SpaceProvider {
        val value = MutableStateFlow(Space.SHARED)
        override val current: StateFlow<Space> get() = value
    }
}
