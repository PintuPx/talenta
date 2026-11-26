package com.homebudget.app.data

import com.homebudget.app.data.backup.BackupRepository
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.DataMaintenanceRepository
import com.homebudget.app.data.repository.IdentityProvider
import com.homebudget.app.data.repository.RoomExpenseFamilyRepository
import com.homebudget.app.data.repository.RoomMonthLockRepository
import com.homebudget.app.data.repository.SnapshotJsonCodec
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.repository.VersionedWriter
import com.homebudget.app.data.repository.WriteIdentity
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.data.settings.SharedSpaceProvider
import com.homebudget.app.domain.family.FamilyRole
import java.time.Clock

/**
 * Wires the repositories the way the app does for Robolectric tests: Individual mode, Admin role,
 * shared space and no closed months (unless a test closes one through [monthLocks]).
 */
class TestRepositories(val db: AppDatabase, val clock: Clock) {
    private val identity = object : IdentityProvider {
        override suspend fun current() = WriteIdentity("me", "You", FamilyRole.ADMIN, AppMode.PERSONAL)
    }
    val writer = VersionedWriter(db, clock, identity, SharedSpaceProvider, SnapshotJsonCodec(db))
    val expenseFamily = RoomExpenseFamilyRepository(db, clock, writer)
    val monthLocks = RoomMonthLockRepository(db, clock, writer)

    fun accounts() = AccountRepository(db, clock, writer)
    fun categories() = CategoryRepository(db, clock, writer)
    fun transactions() = TransactionRepository(db, clock, writer, expenseFamily)
    fun maintenance(images: BillImageStore) = DataMaintenanceRepository(db, clock, images, writer)
    fun backup(storage: com.homebudget.app.domain.backup.BackupStorage, settings: SettingsRepository, images: BillImageStore) =
        BackupRepository(db, storage, settings, clock, images, writer)
}
