package com.homebudget.app.data.repository

import androidx.room.withTransaction
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.toStoredMonth
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpaceTransitionRepository @Inject constructor(
    private val db: AppDatabase,
    private val writer: VersionedWriter,
) {
    // REVIEW: each shared row needs a tombstone before it can leave the shared space.
    suspend fun makeExistingSharedRowsPrivate() {
        db.withTransaction {
            val changes = buildList {
                db.accountDao().getAll().filterNot { it.isPrivate }.forEach { row ->
                    val privateRow = row.copy(isPrivate = true)
                    add(
                        writer.change("account", row.uid, false, "MAKE_PRIVATE", privateRow) {
                            db.accountDao().update(privateRow)
                        },
                    )
                }
                db.transactionDao().getAll().filterNot { it.isPrivate }.forEach { row ->
                    val privateRow = row.copy(isPrivate = true)
                    add(
                        writer.change(
                            "transaction",
                            row.uid,
                            false,
                            "MAKE_PRIVATE",
                            privateRow,
                            month = YearMonth.from(FloatingTime.fromStored(row.occurredAt)).toStoredMonth(),
                        ) { db.transactionDao().update(privateRow) },
                    )
                }
                val transactionMonths = db.transactionDao().getAll().associate { row ->
                    row.uid to YearMonth.from(FloatingTime.fromStored(row.occurredAt)).toStoredMonth()
                }
                db.familyAllocationDao().getAllTransactionMembersForBackup().filterNot { it.isPrivate }.forEach { row ->
                    val privateRow = row.copy(isPrivate = true)
                    add(
                        writer.change(
                            "transaction_member",
                            row.uid,
                            false,
                            "MAKE_PRIVATE",
                            privateRow,
                            month = transactionMonths[row.transactionUid],
                        ) { db.familyAllocationDao().updateTransactionMember(privateRow) },
                    )
                }
                db.investmentValueDao().getAll().filterNot { it.isPrivate }.forEach { row ->
                    val privateRow = row.copy(isPrivate = true)
                    add(
                        writer.change(
                            "investment_value",
                            row.uid,
                            false,
                            "MAKE_PRIVATE",
                            privateRow,
                        ) { db.investmentValueDao().update(privateRow) },
                    )
                }
                db.billDao().getAll().filterNot { it.isPrivate }.forEach { row ->
                    val privateRow = row.copy(isPrivate = true)
                    val month = YearMonth.from(FloatingTime.fromStored(row.billDate)).toStoredMonth()
                    add(
                        writer.change("bill", row.uid, false, "MAKE_PRIVATE", privateRow, month = month) {
                            db.billDao().update(privateRow)
                        },
                    )
                }
                db.budgetDao().getAll().filterNot { it.isPrivate }.forEach { row ->
                    val privateRow = row.copy(isPrivate = true)
                    add(
                        writer.change(
                            "budget",
                            row.uid,
                            false,
                            "MAKE_PRIVATE",
                            privateRow,
                            month = row.month,
                        ) { db.budgetDao().update(privateRow) },
                    )
                }
                db.recurringDao().getAll().filterNot { it.isPrivate }.forEach { row ->
                    val privateRow = row.copy(isPrivate = true)
                    add(
                        writer.change("recurring_rule", row.uid, false, "MAKE_PRIVATE", privateRow) {
                            db.recurringDao().update(privateRow)
                        },
                    )
                }
            }
            writer.applyBulk(changes)
        }
    }
}
