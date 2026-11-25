package com.homebudget.app.data.bill

import androidx.room.withTransaction
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.ItemRuleEntity
import com.homebudget.app.data.db.TransactionEntity
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.toDomain
import com.homebudget.app.data.db.toEntity
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.domain.bill.BillSplit
import com.homebudget.app.domain.bill.BillTextRecognizer
import com.homebudget.app.domain.bill.CategorySuggester
import com.homebudget.app.domain.bill.ParsedReceipt
import com.homebudget.app.domain.bill.ReceiptParser
import com.homebudget.app.domain.model.Bill
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BillStatus
import com.homebudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What reading a bill photo produced: parsed fields plus line items with suggested categories. */
data class BillReading(val receipt: ParsedReceipt, val items: List<BillItem>)

@Singleton
class BillRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val images: BillImageStore,
    private val recognizer: BillTextRecognizer,
) {
    private val dao = db.billDao()

    fun observeBills(): Flow<List<Bill>> = dao.observeAll().map { l -> l.map { it.toDomain() } }

    fun observeBill(id: Long): Flow<Bill?> = dao.observeById(id).map { it?.toDomain() }

    suspend fun get(id: Long): Bill? = dao.getById(id)?.toDomain()

    suspend fun items(billId: Long): List<BillItem> = dao.getItems(billId).map { it.toDomain() }

    suspend fun transactionCount(billId: Long): Int = db.transactionDao().getForBill(billId).size

    /** Creates a draft bill, optionally with a photo already imported by [BillImageStore.import]. */
    suspend fun createDraft(imageFile: String?, accountId: Long?): Long {
        val now = clock.millis()
        return dao.insert(
            Bill(
                billDate = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES),
                accountId = accountId,
                imageFile = imageFile,
                createdAt = now,
                updatedAt = now,
            ).toEntity()
        )
    }

    /**
     * Runs on-device text recognition on the bill photo and turns it into line items with suggested
     * categories. Only the recognised text is stored; items are returned for the user to review.
     */
    suspend fun read(billId: Long): BillReading {
        val bill = dao.getById(billId) ?: throw IntegrityException("Bill not found")
        val file = bill.imageFile?.let(images::file) ?: throw IOException("This bill has no photo")
        val receipt = ReceiptParser.parse(recognizer.recognize(file))
        dao.update(bill.copy(ocrText = receipt.text.take(MAX_OCR_TEXT), updatedAt = clock.millis()))

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
        return BillReading(receipt, items)
    }

    suspend fun learnedRules(): Map<String, Long> = db.itemRuleDao().getAll().associate { it.key to it.categoryId }

    /** Saves the bill fields and replaces its line items. Only drafts can be saved without approving. */
    suspend fun saveDraft(bill: Bill, items: List<BillItem>) = db.withTransaction {
        val existing = dao.getById(bill.id) ?: throw IntegrityException("Bill not found")
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
    suspend fun approve(bill: Bill, items: List<BillItem>): Int = db.withTransaction {
        val existing = dao.getById(bill.id) ?: throw IntegrityException("Bill not found")
        val categories = db.categoryDao().getAll().map { it.toDomain() }.associateBy { it.id }
        val accountId = bill.accountId?.takeIf { db.accountDao().getById(it) != null }
        BillSplit.problems(items, accountId, categories).firstOrNull()?.let { throw IntegrityException(it) }

        val now = clock.millis()
        writeBill(
            bill.copy(status = BillStatus.POSTED, imageFile = existing.imageFile, ocrText = existing.ocrText, createdAt = existing.createdAt),
            items,
        )
        val txDao = db.transactionDao()
        txDao.deleteForBill(bill.id)
        val lines = BillSplit.postingLines(items)
        lines.forEach { line ->
            txDao.insert(
                TransactionEntity(
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
                )
            )
        }

        val rules = db.itemRuleDao()
        items.filter { it.included && it.categoryId != null }.forEach { item ->
            val key = CategorySuggester.key(item.description)
            if (key.length >= 2) {
                val count = rules.get(key)?.takeIf { it.categoryId == item.categoryId }?.useCount ?: 0
                rules.upsert(ItemRuleEntity(key, item.categoryId!!, count + 1, now))
            }
        }
        lines.size
    }

    /** Deletes the bill, its line items, the transactions created from it and its photo. */
    suspend fun delete(billId: Long): Int {
        var image: String? = null
        val removed = db.withTransaction {
            val bill = dao.getById(billId) ?: return@withTransaction 0
            image = bill.imageFile
            val n = db.transactionDao().deleteForBill(billId)
            dao.delete(billId)
            n
        }
        images.delete(image)
        return removed
    }

    private suspend fun writeBill(bill: Bill, items: List<BillItem>) {
        dao.update(
            bill.copy(
                merchant = bill.merchant.trim().take(100),
                updatedAt = clock.millis(),
            ).toEntity()
        )
        dao.deleteItems(bill.id)
        dao.insertItems(
            items.mapIndexed { i, item ->
                item.copy(id = 0, billId = bill.id, position = i, description = item.description.trim().take(MAX_DESCRIPTION)).toEntity()
            }
        )
    }

    companion object {
        const val MAX_DESCRIPTION = 80
        const val MAX_OCR_TEXT = 20_000
    }
}
