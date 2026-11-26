package com.homebudget.app.data.bill

import androidx.room.withTransaction
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.BillEntity
import com.homebudget.app.data.db.ItemRuleEntity
import com.homebudget.app.data.db.TransactionEntity
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.toDomain
import com.homebudget.app.data.db.toEntity
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.data.repository.BulkWriteResult
import com.homebudget.app.data.repository.VersionedChange
import com.homebudget.app.data.repository.VersionedWriter
import com.homebudget.app.data.repository.requireAccepted
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.SharedSpaceProvider
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.bill.BillSplit
import com.homebudget.app.domain.family.ExpenseFamilyRepository
import com.homebudget.app.domain.bill.BillTextRecognizer
import com.homebudget.app.domain.bill.CategorySuggester
import com.homebudget.app.domain.review.SkippedBulkChangeNotice
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.bill.ParsedReceipt
import com.homebudget.app.domain.bill.ReceiptParser
import com.homebudget.app.domain.bill.SensitiveInfo
import com.homebudget.app.domain.model.Bill
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BillStatus
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.family.FamilyRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What reading a bill photo produced: parsed fields plus line items with suggested categories. */
data class BillReading(
    val receipt: ParsedReceipt,
    val items: List<BillItem>,
    val sensitiveKinds: Set<SensitiveInfo.Kind> = emptySet(),
)

data class PastCategoryChangeResult(
    val foundEntries: Int,
    val changedEntries: Int,
    val skippedClosedMonths: Int,
    val writeResult: BulkWriteResult,
)

@Singleton
class BillRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val images: BillImageStore,
    private val recognizer: BillTextRecognizer,
    private val writer: VersionedWriter,
    private val expenseFamily: ExpenseFamilyRepository,
    private val spaceProvider: SpaceProvider = SharedSpaceProvider,
) {
    private val dao = db.billDao()
    private val privateSpace get() = spaceProvider.current.value == Space.PRIVATE
    private val bulkUndos = ConcurrentHashMap<String, PendingBulkUndo>()

    fun observeBills(): Flow<List<Bill>> =
        spaceProvider.current.flatMapLatest { dao.observeAllInSpace(it == Space.PRIVATE) }
            .map { l -> l.map { it.toDomain() } }

    fun observeBill(id: Long): Flow<Bill?> =
        spaceProvider.current.flatMapLatest { dao.observeByIdInSpace(id, it == Space.PRIVATE) }
            .map { it?.toDomain() }

    suspend fun get(id: Long): Bill? = dao.getByIdInSpace(id, privateSpace)?.toDomain()

    suspend fun items(billId: Long): List<BillItem> =
        dao.getItemsInSpace(billId, privateSpace).map { it.toDomain() }

    suspend fun transactionCount(billId: Long): Int =
        db.transactionDao().getForBillInSpace(billId, privateSpace).size

    /** Creates a draft bill, optionally with a photo already imported by [BillImageStore.import]. */
    suspend fun createDraft(imageFile: String?, accountId: Long?): Long {
        if (accountId != null && db.accountDao().getByIdInSpace(accountId, privateSpace) == null) {
            throw IntegrityException("Account not found")
        }
        val now = clock.millis()
        val entity = Bill(
            billDate = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES),
            accountId = accountId,
            imageFile = imageFile,
            createdAt = now,
            updatedAt = now,
        ).toEntity().copy(isPrivate = privateSpace)
        var insertedId = 0L
        val result = writer.apply(
            writer.change("bill", entity.uid, entity.isPrivate, "ADD", entity) { insertedId = dao.insert(entity) },
        )
        result.requireAccepted()
        return if (result == com.homebudget.app.data.repository.WriteResult.PENDING_APPROVAL) 0L else insertedId
    }

    /**
     * Runs on-device text recognition on the bill photo and turns it into line items with suggested
     * categories. Only the recognised text is stored; items are returned for the user to review.
     */
    suspend fun read(billId: Long): BillReading {
        val bill = dao.getByIdInSpace(billId, privateSpace) ?: throw IntegrityException("Bill not found")
        val file = bill.imageFile?.let(images::file) ?: throw IOException("This bill has no photo")
        val receipt = ReceiptParser.parse(recognizer.recognize(file))
        val sensitiveKinds = SensitiveInfo.find(receipt.text)
        val safeReceipt = receipt.copy(text = SensitiveInfo.mask(receipt.text))
        val updated = bill.copy(ocrText = safeReceipt.text.take(MAX_OCR_TEXT), updatedAt = clock.millis())
        writer.apply(writer.change("bill", updated.uid, updated.isPrivate, "EDIT", updated, month = billMonth(updated)) {
            dao.update(updated)
        }).requireAccepted()

        val categories = db.categoryDao().getAll().map { it.toDomain() }
        val learned = learnedRules()
        val items = receipt.items.mapIndexed { i, item ->
            BillItem(
                billId = billId,
                position = i,
                description = item.description.take(MAX_DESCRIPTION),
                amount = item.amount,
                categoryId = CategorySuggester.suggest(item.description, learned, categories),
            )
        }
        return BillReading(safeReceipt, items, sensitiveKinds)
    }

    suspend fun learnedRules(): Map<String, Long> = db.itemRuleDao().getAll().associate { it.key to it.categoryId }

    suspend fun countPastCategoryMatches(description: String, categoryId: Long, excludeBillId: Long? = null): Int {
        val key = CategorySuggester.key(description)
        if (key.length < 2) return 0
        val currentSpace = privateSpace
        val postedBills = db.billDao().getAll()
            .filter { it.isPrivate == currentSpace && it.status == BillStatus.POSTED }
            .mapTo(HashSet()) { it.id }
        return db.billDao().getAllItems().count { item ->
            item.billId in postedBills && item.billId != excludeBillId && item.included && item.categoryId != categoryId &&
                CategorySuggester.key(item.description) == key
        }
    }

    // REVIEW: this rebuilds posted transaction splits and preserves one versioned bulk operation.
    suspend fun changePastCategories(
        description: String,
        categoryId: Long,
        previousCategoryId: Long? = null,
        requireSharedSpace: Boolean = false,
        excludeBillId: Long? = null,
    ): PastCategoryChangeResult = db.withTransaction {
        val key = CategorySuggester.key(description)
        if (key.length < 2) throw IntegrityException("Item name is too short to change past entries")
        val category = db.categoryDao().getById(categoryId)
            ?: throw IntegrityException("Category not found")
        if (category.kind != CategoryKind.EXPENSE) throw IntegrityException("Category is not an expense group")

        val currentSpace = privateSpace
        if (requireSharedSpace && currentSpace) {
            throw IntegrityException("Switch off Private mode to apply the skipped family entries.")
        }
        val postedBills = db.billDao().getAll()
            .filter { it.id != excludeBillId && it.isPrivate == currentSpace && it.status == BillStatus.POSTED }
        val allItems = db.billDao().getAllItems().groupBy { it.billId }
        val identity = writer.currentIdentity()
        if (requireSharedSpace && (identity.appMode != AppMode.SHARED || identity.role != FamilyRole.ADMIN)) {
            throw IntegrityException("Only the Family Admin can apply skipped family entries.")
        }
        val admin = identity.appMode != AppMode.SHARED || identity.role == FamilyRole.ADMIN
        val changes = mutableListOf<VersionedChange>()
        val undoChanges = mutableListOf<VersionedChange>()
        var foundEntries = 0
        var skippedEntries = 0

        postedBills.forEach { bill ->
            val billItems = allItems[bill.id].orEmpty()
            val matching = billItems.filter {
                it.included && it.categoryId != categoryId && CategorySuggester.key(it.description) == key
            }
            if (matching.isEmpty()) return@forEach
            foundEntries += matching.size
            val month = billMonth(bill)
            if (!admin && db.monthLockDao().getByMonth(month) != null) {
                skippedEntries += matching.size
                return@forEach
            }

            val updatedItems = billItems.map { item ->
                if (item in matching) item.copy(categoryId = categoryId) else item
            }
            matching.forEach { old ->
                val updated = old.copy(categoryId = categoryId)
                changes += writer.change(
                    "bill_item",
                    updated.uid,
                    bill.isPrivate,
                    "EDIT",
                    updated,
                    month = month,
                ) { db.billDao().updateItem(updated) }
                undoChanges += writer.change(
                    "bill_item",
                    old.uid,
                    bill.isPrivate,
                    "UNDO",
                    old,
                    month = month,
                ) { db.billDao().updateItem(old) }
            }

            val oldTransactions = db.transactionDao().getForBillInSpace(bill.id, bill.isPrivate)
            val oldByCategory = oldTransactions.associateBy { it.categoryId }
            val newLines = BillSplit.postingLines(updatedItems.map { it.toDomain() })
            val newByCategory = newLines.associateBy { it.categoryId }
            oldTransactions.forEach { old ->
                val line = old.categoryId?.let(newByCategory::get)
                if (line == null) {
                    changes += writer.change(
                        "transaction",
                        old.uid,
                        old.isPrivate,
                        "DELETE",
                        old,
                        deleted = true,
                        month = transactionMonth(old),
                    ) { db.transactionDao().delete(old.id) }
                    undoChanges += writer.change(
                        "transaction",
                        old.uid,
                        old.isPrivate,
                        "UNDO",
                        old,
                        month = transactionMonth(old),
                    ) { db.transactionDao().insert(old) }
                } else {
                    val updated = old.copy(
                        amount = line.amount,
                        note = BillSplit.note(line),
                        payee = bill.merchant.trim().take(100),
                        updatedAt = clock.millis(),
                    )
                    if (updated.amount != old.amount || updated.note != old.note || updated.payee != old.payee) {
                        changes += writer.change(
                            "transaction",
                            updated.uid,
                            updated.isPrivate,
                            "EDIT",
                            updated,
                            month = transactionMonth(old),
                        ) { db.transactionDao().update(updated) }
                        undoChanges += writer.change(
                            "transaction",
                            old.uid,
                            old.isPrivate,
                            "UNDO",
                            old,
                            month = transactionMonth(old),
                        ) { db.transactionDao().update(old) }
                    }
                }
            }
            newLines.filter { it.categoryId !in oldByCategory }.forEach { line ->
                val accountId = bill.accountId
                    ?: throw IntegrityException("A posted bill has no payment account")
                val now = clock.millis()
                val added = TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amount = line.amount,
                    accountId = accountId,
                    toAccountId = null,
                    categoryId = line.categoryId,
                    isRefund = false,
                    occurredAt = bill.billDate,
                    note = BillSplit.note(line),
                    payee = bill.merchant.trim().take(100),
                    createdAt = now,
                    updatedAt = now,
                    billId = bill.id,
                    isPrivate = bill.isPrivate,
                )
                changes += writer.change(
                    "transaction",
                    added.uid,
                    added.isPrivate,
                    "ADD",
                    added,
                    month = month,
                ) { db.transactionDao().insert(added) }
                undoChanges += writer.change(
                    "transaction",
                    added.uid,
                    added.isPrivate,
                    "UNDO",
                    added,
                    deleted = true,
                    month = month,
                ) {
                    db.transactionDao().getAll().firstOrNull { it.uid == added.uid }?.let {
                        db.transactionDao().delete(it.id)
                    }
                }
            }
        }

        val result = writer.applyBulk(changes)
        resyncShares(changes.filter { it.event.entityType == "transaction" }.map { it.event.entityUid })
        if (skippedEntries > 0) {
            val previousCategory = previousCategoryId?.let { db.categoryDao().getById(it) }
            writer.recordSkippedBulkNotice(
                SkippedBulkChangeNotice(
                    id = UUID.randomUUID().toString(),
                    memberName = identity.displayName,
                    itemDescription = description,
                    previousCategoryId = previousCategory?.id ?: categoryId,
                    previousCategoryName = previousCategory?.name ?: category.name,
                    categoryId = categoryId,
                    categoryName = category.name,
                    skippedEntries = skippedEntries,
                    sourceBillId = excludeBillId,
                ),
            )
        }
        if (changes.isNotEmpty() && (result.applied > 0 || result.pendingApproval > 0)) {
            bulkUndos[result.bulkId] = PendingBulkUndo(undoChanges)
        }
        PastCategoryChangeResult(
            foundEntries = foundEntries,
            changedEntries = foundEntries - skippedEntries,
            skippedClosedMonths = skippedEntries,
            writeResult = result.copy(skippedClosedMonths = result.skippedClosedMonths + skippedEntries),
        )
    }

    // REVIEW: undo creates inverse versions and cancels only requests that are still pending.
    suspend fun undoPastCategoryChange(bulkId: String): BulkWriteResult = db.withTransaction {
        val undo = bulkUndos[bulkId] ?: throw IntegrityException("Undo is no longer available")
        val originalVersions = db.entityVersionDao().getForBulk(bulkId)
        db.entityVersionDao().deletePendingBulk(bulkId)
        val approvedEntities = originalVersions
            .filter { it.status == "APPROVED" }
            .mapTo(HashSet()) { it.entityType to it.entityUid }
        val changes = undo.changes.filter {
            (it.event.entityType to it.event.entityUid) in approvedEntities
        }
        val result = writer.applyBulk(changes)
        resyncShares(changes.filter { it.event.entityType == "transaction" }.map { it.event.entityUid })
        bulkUndos.remove(bulkId)
        result
    }

    /**
     * Keeps family member shares equal to their transaction amount after a bill regroup or its undo
     * changed that amount; otherwise the shares no longer add up and the backup fails validation.
     * Uses the same people, so saveTransactionShares recomputes the exact Long split.
     */
    private suspend fun resyncShares(transactionUids: List<String>) {
        if (transactionUids.isEmpty()) return
        val wanted = transactionUids.toHashSet()
        db.transactionDao().getAll().filter { it.uid in wanted }.forEach { tx ->
            val shares = expenseFamily.transactionShares(tx.uid, tx.isPrivate)
            if (shares.isEmpty() || shares.sumOf { it.amount } == tx.amount) return@forEach
            expenseFamily.saveTransactionShares(
                transactionUid = tx.uid,
                totalMinor = tx.amount,
                personUids = shares.mapTo(HashSet()) { it.personUid },
                privateSpace = tx.isPrivate,
                month = transactionMonth(tx),
            )
        }
    }

    private data class PendingBulkUndo(
        val changes: List<VersionedChange>,
    )

    suspend fun rememberCategory(description: String, categoryId: Long) = db.withTransaction {
        val key = CategorySuggester.key(description)
        if (key.length < 2) throw IntegrityException("Item name is too short to learn a group")
        val category = db.categoryDao().getById(categoryId) ?: throw IntegrityException("Category not found")
        if (category.kind != CategoryKind.EXPENSE) throw IntegrityException("Category is not an expense group")
        val rules = db.itemRuleDao()
        val existing = rules.get(key)
        val updated = ItemRuleEntity(
            key = key,
            categoryId = categoryId,
            useCount = if (existing?.categoryId == categoryId) existing.useCount + 1 else 1,
            updatedAt = clock.millis(),
        )
        val saved = existing?.let { updated.copy(uid = it.uid) } ?: updated
        writer.apply(
            writer.change("item_rule", saved.uid, false, if (existing == null) "ADD" else "EDIT", saved) {
                rules.upsert(saved)
            },
        ).requireAccepted()
    }

    /** Saves the bill fields and replaces its line items. Only drafts can be saved without approving. */
    suspend fun saveDraft(bill: Bill, items: List<BillItem>) = db.withTransaction {
        val existing = dao.getByIdInSpace(bill.id, privateSpace) ?: throw IntegrityException("Bill not found")
        if (existing.status == BillStatus.POSTED) {
            throw IntegrityException("This bill has already been added. Use \"Update transactions\" to save changes.")
        }
        writeBill(bill.copy(status = BillStatus.DRAFT, imageFile = existing.imageFile, ocrText = existing.ocrText, createdAt = existing.createdAt), items)
    }

    /**
     * Approves the bill: saves it, replaces any transactions previously created from it with one
     * expense per category (see [BillSplit]), and remembers each item's category for next time.
     * Everything happens in one database transaction. Returns the number of transactions created.
     */
    suspend fun approve(
        bill: Bill,
        items: List<BillItem>,
        preserveLearnedKeys: Set<String> = emptySet(),
    ): Int = db.withTransaction {
        val existing = dao.getByIdInSpace(bill.id, privateSpace) ?: throw IntegrityException("Bill not found")
        val categories = db.categoryDao().getAll().map { it.toDomain() }.associateBy { it.id }
        val accountId = bill.accountId?.takeIf { db.accountDao().getByIdInSpace(it, privateSpace) != null }
        BillSplit.problems(items, accountId, categories).firstOrNull()?.let { throw IntegrityException(it) }

        val now = clock.millis()
        writeBill(
            bill.copy(status = BillStatus.POSTED, imageFile = existing.imageFile, ocrText = existing.ocrText, createdAt = existing.createdAt),
            items,
        )
        val txDao = db.transactionDao()
        txDao.getForBillInSpace(bill.id, existing.isPrivate).forEach { transaction ->
            writer.apply(
                writer.change(
                    "transaction", transaction.uid, transaction.isPrivate, "DELETE", transaction,
                    deleted = true, month = transactionMonth(transaction),
                ) { txDao.delete(transaction.id) },
            ).requireAccepted()
        }
        val lines = BillSplit.postingLines(items)
        lines.forEach { line ->
            val transaction = TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amount = line.amount,
                    accountId = accountId!!,
                    toAccountId = null,
                    categoryId = line.categoryId,
                    isRefund = false,
                    occurredAt = FloatingTime.toStored(bill.billDate),
                    note = BillSplit.note(line),
                    payee = bill.merchant.trim().take(100),
                    createdAt = now,
                    updatedAt = now,
                    billId = bill.id,
                    isPrivate = existing.isPrivate,
                )
            writer.apply(
                writer.change(
                    "transaction", transaction.uid, transaction.isPrivate, "ADD", transaction,
                    month = transactionMonth(transaction),
                ) { txDao.insert(transaction) },
            ).requireAccepted()
        }

        val rules = db.itemRuleDao()
        items.filter { it.included && it.categoryId != null }.forEach { item ->
            val key = CategorySuggester.key(item.description)
            if (key.length >= 2 && key !in preserveLearnedKeys) {
                val previous = rules.get(key)
                val count = previous?.takeIf { it.categoryId == item.categoryId }?.useCount ?: 0
                val updated = ItemRuleEntity(
                    key = key,
                    categoryId = item.categoryId!!,
                    useCount = count + 1,
                    updatedAt = now,
                )
                val rule = previous?.let { updated.copy(uid = it.uid) } ?: updated
                writer.apply(
                    writer.change("item_rule", rule.uid, false, if (previous == null) "ADD" else "EDIT", rule) {
                        rules.upsert(rule)
                    },
                ).requireAccepted()
            }
        }
        lines.size
    }

    /** Deletes the bill, its line items, the transactions created from it and its photo. */
    suspend fun delete(billId: Long): Int {
        var image: String? = null
        val removed = db.withTransaction {
            val bill = dao.getByIdInSpace(billId, privateSpace) ?: return@withTransaction 0
            image = bill.imageFile
            val transactions = db.transactionDao().getForBillInSpace(billId, bill.isPrivate)
            transactions.forEach { transaction ->
                writer.apply(
                    writer.change(
                        "transaction", transaction.uid, transaction.isPrivate, "DELETE", transaction,
                        deleted = true, month = transactionMonth(transaction),
                    ) { db.transactionDao().delete(transaction.id) },
                ).requireAccepted()
            }
            dao.getItemsInSpace(billId, bill.isPrivate).forEach { item ->
                writer.apply(
                    // Bill lines inherit their bill's space; recording them as shared would leak private lines.
                    writer.change("bill_item", item.uid, bill.isPrivate, "DELETE", item, deleted = true) {
                        dao.deleteItem(item.id)
                    },
                ).requireAccepted()
            }
            writer.apply(
                writer.change("bill", bill.uid, bill.isPrivate, "DELETE", bill, deleted = true, month = billMonth(bill)) {
                    dao.delete(billId)
                },
            ).requireAccepted()
            transactions.size
        }
        images.delete(image)
        return removed
    }

    private suspend fun writeBill(bill: Bill, items: List<BillItem>) {
        val existing = dao.getByIdInSpace(bill.id, privateSpace) ?: throw IntegrityException("Bill not found")
        val updatedBill = bill.copy(
            merchant = bill.merchant.trim().take(100),
            updatedAt = clock.millis(),
        ).toEntity().copy(uid = existing.uid, isPrivate = existing.isPrivate)
        writer.apply(
            writer.change("bill", updatedBill.uid, updatedBill.isPrivate, "EDIT", updatedBill, month = billMonth(updatedBill)) {
                dao.update(updatedBill)
            },
        ).requireAccepted()

        val oldItems = dao.getItemsInSpace(bill.id, existing.isPrivate).associateBy { it.id }
        val retainedIds = mutableSetOf<Long>()
        items.forEachIndexed { index, item ->
            val old = oldItems[item.id]
            if (item.id != 0L && old == null) throw IntegrityException("A bill line no longer exists")
            val entity = item.copy(
                id = old?.id ?: 0,
                billId = bill.id,
                position = index,
                description = item.description.trim().take(MAX_DESCRIPTION),
            ).toEntity().let { fresh ->
                if (old == null) fresh else fresh.copy(uid = old.uid)
            }
            if (old != null) retainedIds += old.id
            val kind = if (old == null) "ADD" else "EDIT"
            writer.apply(
                // Bill lines inherit their bill's space; recording them as shared would leak private lines.
                writer.change("bill_item", entity.uid, existing.isPrivate, kind, entity) {
                    if (old == null) dao.insertItem(entity) else dao.updateItem(entity)
                },
            ).requireAccepted()
        }
        oldItems.values.filter { it.id !in retainedIds }.forEach { item ->
            writer.apply(
                writer.change("bill_item", item.uid, existing.isPrivate, "DELETE", item, deleted = true) {
                    dao.deleteItem(item.id)
                },
            ).requireAccepted()
        }
    }

    private fun billMonth(bill: BillEntity): Int {
        val month = YearMonth.from(FloatingTime.fromStored(bill.billDate))
        return month.year * 100 + month.monthValue
    }

    private fun billMonth(bill: Bill): Int = bill.billDate.year * 100 + bill.billDate.monthValue

    private fun transactionMonth(transaction: TransactionEntity): Int =
        YearMonth.from(FloatingTime.fromStored(transaction.occurredAt)).year * 100 +
            FloatingTime.fromStored(transaction.occurredAt).monthValue

    companion object {
        const val MAX_DESCRIPTION = 80
        const val MAX_OCR_TEXT = 20_000
    }
}
