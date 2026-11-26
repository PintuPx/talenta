package com.homebudget.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.db.AccountEntity
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.EntityVersionEntity
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.db.TransactionEntity
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.family.FamilyRole
import com.homebudget.app.domain.history.HistoryMarker
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
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class SpaceTransitionRepositoryTest {
    private lateinit var db: AppDatabase
    private val space = TestSpaceProvider()
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var writer: VersionedWriter
    private lateinit var transitions: SpaceTransitionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        val identity = object : IdentityProvider {
            override suspend fun current() = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.PERSONAL)
        }
        writer = VersionedWriter(db, clock, identity, space, SnapshotJsonCodec(db))
        transitions = SpaceTransitionRepository(db, writer)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sharedTransactionMovesToPrivateAndSharedTombstoneIsRedacted() = runTest {
        seedSharedTransaction("tx-move", amount = 12_345, payee = "Fake Cafe", note = "fake private note")

        transitions.makeExistingSharedRowsPrivate()

        val row = db.transactionDao().getAll().single { it.uid == "tx-move" }
        assertTrue(row.isPrivate)
        assertEquals(12_345L, row.amount) // the data itself is preserved, only its space changes

        val dao = db.entityVersionDao()
        val sharedLatest = dao.latestApprovedForSpace("transaction", "tx-move", false)!!
        assertEquals(2, sharedLatest.version)
        assertEquals(1, sharedLatest.baseVersion)
        assertTrue(sharedLatest.deleted)
        assertEquals("MAKE_PRIVATE", sharedLatest.kind)
        assertFalse(sharedLatest.isPrivate)
        // Privacy: nothing but the uid and a tombstone flag may remain in the latest shared snapshot.
        for (secret in listOf("12345", "Fake Cafe", "fake private note", "amount", "payee", "note")) {
            assertFalse("shared tombstone leaks '$secret': ${sharedLatest.snapshot}", sharedLatest.snapshot.contains(secret))
        }
        assertTrue(sharedLatest.snapshot.contains("tombstone"))
        assertTrue(sharedLatest.snapshot.contains("tx-move"))

        val privateLatest = dao.latestApprovedForSpace("transaction", "tx-move", true)!!
        assertEquals(1, privateLatest.version)
        assertEquals("MAKE_PRIVATE", privateLatest.kind)
        assertTrue(privateLatest.isPrivate)
        assertFalse(privateLatest.deleted)
        assertTrue(privateLatest.snapshot.contains("12345"))
        assertTrue(privateLatest.snapshot.contains("Fake Cafe"))
    }

    @Test
    fun sharedAccountMovesToPrivateWithRedactedTombstone() = runTest {
        seedSharedAccount("acc-move", name = "Fake Savings", openingBalance = 987_654)

        transitions.makeExistingSharedRowsPrivate()

        assertTrue(db.accountDao().getAll().single { it.uid == "acc-move" }.isPrivate)
        val sharedLatest = db.entityVersionDao().latestApprovedForSpace("account", "acc-move", false)!!
        assertTrue(sharedLatest.deleted)
        for (secret in listOf("987654", "Fake Savings", "openingBalance", "name")) {
            assertFalse("shared account tombstone leaks '$secret': ${sharedLatest.snapshot}", sharedLatest.snapshot.contains(secret))
        }
        val privateLatest = db.entityVersionDao().latestApprovedForSpace("account", "acc-move", true)!!
        assertTrue(privateLatest.snapshot.contains("Fake Savings"))
        assertTrue(privateLatest.snapshot.contains("987654"))
    }

    @Test
    fun everyMovedRowGetsMakePrivateHistoryWithOneSharedBulkId() = runTest {
        seedSharedAccount("acc-bulk", name = "Fake Bank", openingBalance = 1_000)
        seedSharedTransaction("tx-bulk-1", amount = 100, payee = "Fake A", note = "")
        seedSharedTransaction("tx-bulk-2", amount = 200, payee = "Fake B", note = "")

        transitions.makeExistingSharedRowsPrivate()

        val moved = db.entityVersionDao().getAll().filter { it.kind == "MAKE_PRIVATE" }
        val bulkIds = moved.map { it.bulkId }.toSet()
        assertEquals(1, bulkIds.size)
        assertNotNull(bulkIds.single())
        assertTrue(bulkIds.single()!!.isNotBlank())
        // Two versions (shared tombstone + private copy) for each of the three seeded rows.
        for (uid in listOf("acc-bulk", "tx-bulk-1", "tx-bulk-2")) {
            val perRow = moved.filter { it.entityUid == uid }
            assertEquals(uid, 2, perRow.size)
            assertEquals(uid, setOf(false, true), perRow.map { it.isPrivate }.toSet())
        }
        // No shared account/transaction is left behind.
        assertTrue(db.accountDao().getAll().all { it.isPrivate })
        assertTrue(db.transactionDao().getAll().all { it.isPrivate })
        // Space-change history is attributed to the writing identity.
        assertTrue(moved.all { it.authorName == "You" && it.status == "APPROVED" })
    }

    @Test
    fun householdHistoryAfterTheMoveShowsADeletedMarkerButNeverThePrivateValues() = runTest {
        seedSharedTransaction("tx-hist", amount = 12_345, payee = "Fake Cafe", note = "fake private note")
        transitions.makeExistingSharedRowsPrivate()
        // A later private edit must never reach shared history.
        space.value.value = Space.PRIVATE
        val moved = db.transactionDao().getAll().single { it.uid == "tx-hist" }
        val edited = moved.copy(amount = 99_900, payee = "Secret Clinic")
        writer.apply(
            writer.change("transaction", "tx-hist", true, "EDIT", edited) { db.transactionDao().update(edited) },
        )

        val history = RoomHistoryRepository(db, space)

        space.value.value = Space.SHARED
        val sharedEntry = history.observeEntity("transaction", "tx-hist").first()!!
        assertEquals(HistoryMarker.DELETED, sharedEntry.versions.first().marker)
        val sharedChanges = sharedEntry.versions.first().changes
        assertTrue("shared history must not show new private values", sharedChanges.none { it.after in setOf("12345", "₹123.45", "Fake Cafe", "fake private note") })
        val everythingShared = sharedEntry.versions.flatMap { it.changes }.joinToString { "${it.before}|${it.after}" }
        assertFalse(everythingShared.contains("Secret Clinic"))
        assertFalse(everythingShared.contains("99900"))
        assertFalse(everythingShared.contains("₹999.00"))

        val household = history.observeAll().first().filter { it.entityUid == "tx-hist" }
        assertEquals(listOf(HistoryMarker.DELETED, HistoryMarker.NORMAL), household.map { it.marker })
        assertFalse(household.flatMap { it.changes }.joinToString { "${it.before}|${it.after}" }.let {
            it.contains("Secret Clinic") || it.contains("99900") || it.contains("₹999.00")
        })

        space.value.value = Space.PRIVATE
        val privateEntry = history.observeEntity("transaction", "tx-hist").first()!!
        assertEquals(listOf(2, 1), privateEntry.versions.map { it.version })
        assertTrue(privateEntry.versions.first().changes.any { it.field == "Payee" && it.after == "Secret Clinic" })
        assertTrue(privateEntry.versions.first().changes.any { it.field == "Amount" && it.after == "₹999.00" })
    }

    @Test
    fun rowsThatAreAlreadyPrivateAreLeftUntouched() = runTest {
        val privateRow = insertTransaction("tx-already-private", 555, "Fake Private", "", isPrivate = true)
        val versionsBefore = db.entityVersionDao().getAll().size

        transitions.makeExistingSharedRowsPrivate()

        assertEquals(privateRow, db.transactionDao().getAll().single { it.uid == "tx-already-private" })
        assertTrue(db.entityVersionDao().getAll().none { it.entityUid == "tx-already-private" })
        assertNull(db.entityVersionDao().latestApprovedForSpace("transaction", "tx-already-private", false))
        assertTrue(db.entityVersionDao().getAll().size >= versionsBefore)
    }

    @Test
    fun runningTheTransitionTwiceDoesNotWriteMoreHistory() = runTest {
        seedSharedTransaction("tx-twice", amount = 100, payee = "Fake", note = "")
        transitions.makeExistingSharedRowsPrivate()
        val afterFirst = db.entityVersionDao().getAll().size

        transitions.makeExistingSharedRowsPrivate()

        assertEquals(afterFirst, db.entityVersionDao().getAll().size)
    }

    @Test
    fun failureMidwayRollsBackEverythingAlreadyMoved() = runTest {
        seedSharedAccount("acc-rollback", name = "Fake Rollback", openingBalance = 4_200)
        seedSharedTransaction("tx-rollback", amount = 321, payee = "Fake", note = "")
        val versionsBefore = db.entityVersionDao().getAll()
        // Accounts are moved before transactions, so this makes the second step fail after the first succeeded.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_private_transaction BEFORE UPDATE ON transactions " +
                "WHEN NEW.is_private = 1 BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
        )

        try {
            transitions.makeExistingSharedRowsPrivate()
            fail("the forced failure should propagate")
        } catch (_: Exception) {
            // expected
        }

        assertTrue("accounts must stay shared", db.accountDao().getAll().none { it.isPrivate })
        assertTrue("transactions must stay shared", db.transactionDao().getAll().none { it.isPrivate })
        assertEquals(versionsBefore, db.entityVersionDao().getAll())
        assertTrue(db.entityVersionDao().getAll().none { it.kind == "MAKE_PRIVATE" })
    }

    private suspend fun seedSharedAccount(uid: String, name: String, openingBalance: Long) {
        val row = AccountEntity(
            name = name,
            type = AccountType.CASH,
            openingBalance = openingBalance,
            includeInTotal = true,
            archived = false,
            sortOrder = 50,
            createdAt = clock.millis(),
            updatedAt = clock.millis(),
            uid = uid,
        )
        val stored = row.copy(id = db.accountDao().insert(row))
        recordSharedVersionOne("account", uid, stored)
    }

    private suspend fun seedSharedTransaction(uid: String, amount: Long, payee: String, note: String) {
        val stored = insertTransaction(uid, amount, payee, note, isPrivate = false)
        recordSharedVersionOne("transaction", uid, stored)
    }

    private suspend fun insertTransaction(
        uid: String,
        amount: Long,
        payee: String,
        note: String,
        isPrivate: Boolean,
    ): TransactionEntity {
        val row = TransactionEntity(
            type = TransactionType.EXPENSE,
            amount = amount,
            accountId = db.accountDao().getAll().first().id,
            toAccountId = null,
            categoryId = db.categoryDao().getAll().first { it.kind == CategoryKind.EXPENSE }.id,
            isRefund = false,
            occurredAt = FloatingTime.toStored(LocalDateTime.of(2026, 11, 5, 12, 0)),
            note = note,
            payee = payee,
            createdAt = clock.millis(),
            updatedAt = clock.millis(),
            uid = uid,
            isPrivate = isPrivate,
        )
        return row.copy(id = db.transactionDao().insert(row))
    }

    private suspend fun recordSharedVersionOne(type: String, uid: String, entity: Any) {
        val result = writer.apply(writer.change(type, uid, false, "ADD", entity) {})
        assertEquals(WriteResult.APPLIED, result)
        val v1: EntityVersionEntity = db.entityVersionDao().latestApprovedForSpace(type, uid, false)!!
        assertEquals(1, v1.version)
    }

    private class TestSpaceProvider : SpaceProvider {
        val value = MutableStateFlow(Space.SHARED)
        override val current: StateFlow<Space> get() = value
    }
}
