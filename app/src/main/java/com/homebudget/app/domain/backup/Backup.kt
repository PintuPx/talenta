package com.homebudget.app.domain.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Backup file format (JSON). [formatVersion] is independent of the Room schema version so the file
 * format can stay stable across database migrations. Readers must reject newer formats.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT_ID,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAtEpochMillis: Long,
    val currencyCode: String,
    val accounts: List<BackupAccount>,
    val categories: List<BackupCategory>,
    val transactions: List<BackupTransaction>,
    val budgets: List<BackupBudget> = emptyList(),
    /** v2. Bill photos themselves are NOT included; [BackupBill.imageFile] is only a reference. */
    val bills: List<BackupBill> = emptyList(),
    val billItems: List<BackupBillItem> = emptyList(),
    val itemRules: List<BackupItemRule> = emptyList(),
    /** v3. */
    val recurringRules: List<BackupRecurringRule> = emptyList(),
    /** v4. */
    val inventoryItems: List<BackupInventoryItem> = emptyList(),
    val purchaseRequests: List<BackupPurchaseRequest> = emptyList(),
    val proposals: List<BackupProposal> = emptyList(),
) {
    companion object {
        const val FORMAT_ID = "homebudget.backup"

        /** 1: Phase 1. 2: adds category groups, bills, bill items and learned item rules. 3: adds recurring rules. 4: adds inventory items, purchase requests and proposals. */
        const val CURRENT_FORMAT_VERSION = 4
    }
}

@Serializable
data class BackupAccount(
    val id: Long, val name: String, val type: String, val openingBalance: Long,
    val includeInTotal: Boolean, val archived: Boolean, val sortOrder: Int,
    val createdAt: Long, val updatedAt: Long,
)

@Serializable
data class BackupCategory(
    val id: Long, val name: String, val kind: String, val iconKey: String, val colorArgb: Long,
    val sortOrder: Int, val active: Boolean, val createdAt: Long, val updatedAt: Long,
    /** v2: main group id for a group. */
    val parentId: Long? = null,
)

@Serializable
data class BackupTransaction(
    val id: Long, val type: String, val amount: Long, val accountId: Long,
    val toAccountId: Long? = null, val categoryId: Long? = null, val isRefund: Boolean = false,
    /** Floating local date-time in ISO-8601 (yyyy-MM-ddTHH:mm:ss), no zone. */
    @SerialName("occurredAt") val occurredAtIso: String,
    val note: String = "", val payee: String = "", val createdAt: Long, val updatedAt: Long,
    /** v2. */
    val billId: Long? = null,
    /** v3. */
    val recurringId: Long? = null,
)

@Serializable
data class BackupBudget(val id: Long, val month: Int, val categoryId: Long? = null, val limitAmount: Long)

@Serializable
data class BackupBill(
    val id: Long, val merchant: String,
    /** Floating local date-time in ISO-8601, like [BackupTransaction.occurredAtIso]. */
    val billDate: String,
    val total: Long? = null, val accountId: Long? = null, val imageFile: String? = null, val ocrText: String? = null,
    val status: String, val createdAt: Long, val updatedAt: Long,
)

@Serializable
data class BackupBillItem(
    val id: Long, val billId: Long, val position: Int, val description: String, val amount: Long,
    val categoryId: Long? = null, val included: Boolean = true,
)

@Serializable
data class BackupRecurringRule(
    val id: Long, val type: String, val amount: Long, val accountId: Long,
    val toAccountId: Long? = null, val categoryId: Long? = null, val isRefund: Boolean = false,
    val note: String = "", val payee: String = "",
    val frequency: String, val interval: Int,
    /** Floating local date-time in ISO-8601 of the first occurrence. */
    val startAt: String,
    val postedCount: Int, val active: Boolean, val createdAt: Long, val updatedAt: Long,
)

@Serializable
data class BackupInventoryItem(
    val id: Long, val name: String, val unit: String, val quantityMilli: Long, val lowStockMilli: Long = 0,
    val note: String = "", val archived: Boolean = false, val createdAt: Long, val updatedAt: Long,
)

@Serializable
data class BackupPurchaseRequest(
    val id: Long, val itemId: Long, val quantityMilli: Long, val note: String = "", val status: String,
    val requestedBy: String = "", val decisionNote: String = "", val createdAt: Long, val updatedAt: Long,
)

@Serializable
data class BackupProposal(
    val id: Long, val uuid: String, val kind: String, val status: String, val authorId: String, val authorName: String,
    val payload: String, val summary: String, val reason: String = "", val createdAt: Long,
    val decidedAt: Long? = null, val resultId: Long? = null,
)

@Serializable
data class BackupItemRule(val key: String, val categoryId: Long, val useCount: Int, val updatedAt: Long)

/** Result of decoding + validating a backup file. Nothing touches the database until this is Valid. */
sealed interface BackupDecodeResult {
    data class Valid(val file: BackupFile) : BackupDecodeResult
    data class Invalid(val reason: String) : BackupDecodeResult
}

/**
 * Where backup bytes are read from / written to. The local implementation uses the Storage Access
 * Framework; a future cloud provider (e.g. Google Drive) implements the same interface without any
 * change to serialization, validation or the database import logic.
 */
interface BackupStorage {
    suspend fun write(target: String, bytes: ByteArray)
    suspend fun read(source: String): ByteArray
}

object BackupCodec {
    /** Hard cap to avoid loading arbitrarily large files into memory. */
    const val MAX_BYTES = 50 * 1024 * 1024

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(file: BackupFile): ByteArray = json.encodeToString(BackupFile.serializer(), file).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray, validator: (BackupFile) -> String?): BackupDecodeResult {
        if (bytes.isEmpty()) return BackupDecodeResult.Invalid("The file is empty")
        if (bytes.size > MAX_BYTES) return BackupDecodeResult.Invalid("The file is too large to be a backup")
        val file = try {
            json.decodeFromString(BackupFile.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            return BackupDecodeResult.Invalid("This is not a valid backup file")
        } catch (e: IllegalArgumentException) {
            return BackupDecodeResult.Invalid("This is not a valid backup file")
        }
        if (file.format != BackupFile.FORMAT_ID) return BackupDecodeResult.Invalid("This is not a backup created by this app")
        if (file.formatVersion > BackupFile.CURRENT_FORMAT_VERSION) {
            return BackupDecodeResult.Invalid("This backup was created by a newer version of the app. Please update the app first.")
        }
        if (file.formatVersion < 1) return BackupDecodeResult.Invalid("Unsupported backup version")
        validator(file)?.let { return BackupDecodeResult.Invalid(it) }
        return BackupDecodeResult.Valid(file)
    }
}
