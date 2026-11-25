package com.homebudget.app.domain.inventory

/** Unit of a stock item. Quantities are stored as Long thousandths of the unit (no floating point). */
enum class StockUnit(val label: String, val decimals: Int) {
    KG("kg", 3), G("g", 0), L("L", 3), ML("ml", 0), PCS("pcs", 0), PACK("pack", 0),
}

data class InventoryItem(
    val id: Long = 0,
    val name: String,
    val unit: StockUnit,
    /** Thousandths of [unit]: 1.5 kg = 1500. */
    val quantityMilli: Long,
    /** Show "low" at or below this (thousandths). 0 = no warning. */
    val lowStockMilli: Long = 0,
    val note: String = "",
    val archived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** REQUESTED: waiting for the Admin. APPROVED: on the shopping list. BOUGHT: done (stock increased). */
enum class RequestStatus(val label: String) {
    REQUESTED("Waiting for approval"), APPROVED("To buy"), REJECTED("Rejected"), BOUGHT("Bought"),
}

data class PurchaseRequest(
    val id: Long = 0,
    val itemId: Long,
    val quantityMilli: Long,
    val note: String = "",
    val status: RequestStatus,
    val requestedBy: String = "",
    val decisionNote: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

object InventoryRules {
    const val MAX_NAME = 60
    const val MAX_NOTE = 200
    /** 1,000,000 units: far beyond any household quantity, keeps arithmetic safe. */
    const val MAX_QUANTITY_MILLI = 1_000_000_000L

    fun isLow(item: InventoryItem) = item.lowStockMilli > 0 && item.quantityMilli <= item.lowStockMilli

    /** Returns an error message, or null. */
    fun validate(item: InventoryItem): String? = when {
        item.name.isBlank() -> "Enter a name"
        item.name.trim().length > MAX_NAME -> "Name must be at most $MAX_NAME characters"
        item.quantityMilli !in 0..MAX_QUANTITY_MILLI -> "Quantity is out of range"
        item.lowStockMilli !in 0..MAX_QUANTITY_MILLI -> "Low-stock level is out of range"
        item.note.length > MAX_NOTE -> "Note must be at most $MAX_NOTE characters"
        else -> null
    }

    fun validateRequest(r: PurchaseRequest): String? = when {
        r.quantityMilli <= 0 -> "Enter a quantity"
        r.quantityMilli > MAX_QUANTITY_MILLI -> "Quantity is out of range"
        r.note.length > MAX_NOTE -> "Note must be at most $MAX_NOTE characters"
        else -> null
    }

    /** Stock after buying; capped at [MAX_QUANTITY_MILLI]. */
    fun addStock(current: Long, bought: Long): Long = (current + bought).coerceIn(0, MAX_QUANTITY_MILLI)

    /** Stock after using some; never below zero. */
    fun useStock(current: Long, used: Long): Long = (current - used).coerceAtLeast(0)

    /** "1.5", "2", "250" for thousandths; trailing zeros trimmed. */
    fun formatQuantity(milli: Long, unit: StockUnit): String {
        val whole = milli / 1000
        val frac = milli % 1000
        val text = if (frac == 0L) whole.toString() else "$whole.${frac.toString().padStart(3, '0').trimEnd('0')}"
        return "$text ${unit.label}"
    }

    /**
     * Parses user input like "1.5" or "2" into thousandths. Returns null for invalid input or more than
     * three decimals. Units without decimals (g, ml, pcs, pack) accept whole numbers only.
     */
    fun parseQuantity(input: String, unit: StockUnit): Long? {
        val s = input.trim().replace(',', '.')
        if (!Regex("^\\d{1,7}(\\.\\d{1,3})?$").matches(s)) return null
        val parts = s.split('.')
        if (parts.size == 2 && unit.decimals == 0) return null
        val whole = parts[0].toLong()
        val frac = parts.getOrNull(1)?.padEnd(3, '0')?.toLong() ?: 0L
        return (whole * 1000 + frac).takeIf { it <= MAX_QUANTITY_MILLI }
    }
}
