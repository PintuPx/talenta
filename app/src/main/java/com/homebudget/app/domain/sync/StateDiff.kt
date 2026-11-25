package com.homebudget.app.domain.sync

import com.homebudget.app.domain.backup.BackupFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The household state that is shared between phones, as JSON lists keyed by id, built from the backup
 * format (which is already validated). Bills, bill items and proposals are NOT shared: bill photos stay
 * on the phone that scanned them, and proposals travel as their own events. Shared transactions lose
 * their bill link for the same reason.
 */
object StateDiff {
    /** Shared lists, in the order rows must be inserted (parents first). Deletes run in reverse. */
    val ENTITIES = listOf("accounts", "categories", "recurringRules", "transactions", "budgets", "itemRules", "inventoryItems", "purchaseRequests")
    const val SETTING_CURRENCY = "currencyCode"

    private fun keyOf(entity: String, row: JsonObject): String =
        (if (entity == "itemRules") row["key"] else row["id"])?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Row without key in $entity")

    /** Extracts the shared state from a backup snapshot. */
    fun stateOf(file: BackupFile): JsonObject {
        val all = SyncJson.json.encodeToJsonElement(BackupFile.serializer(), file).jsonObject
        return buildJsonObject {
            put(SETTING_CURRENCY, file.currencyCode)
            ENTITIES.forEach { e ->
                val rows = all[e]?.jsonArray ?: JsonArray(emptyList())
                put(e, if (e == "transactions") JsonArray(rows.map { r -> JsonObject(r.jsonObject + ("billId" to JsonNull)) }) else rows)
            }
        }
    }

    fun emptyState(currency: String): JsonObject = buildJsonObject {
        put(SETTING_CURRENCY, currency)
        ENTITIES.forEach { put(it, JsonArray(emptyList())) }
    }

    private fun rows(state: JsonObject, entity: String): Map<String, JsonObject> =
        (state[entity]?.jsonArray ?: JsonArray(emptyList())).associate { keyOf(entity, it.jsonObject) to it.jsonObject }

    /** A change without an offset yet. */
    data class Change(val kind: String, val entity: String?, val key: String?, val data: JsonElement?)

    /** Changes that turn [old] into [new]: upserts in parent-first order, then deletes in child-first order. */
    fun diff(old: JsonObject, new: JsonObject): List<Change> {
        val out = mutableListOf<Change>()
        val oldCurrency = old[SETTING_CURRENCY]?.jsonPrimitive?.contentOrNull
        val newCurrency = new[SETTING_CURRENCY]?.jsonPrimitive?.contentOrNull
        if (newCurrency != null && newCurrency != oldCurrency) out += Change(SyncEvent.SETTING, SETTING_CURRENCY, null, JsonPrimitive(newCurrency))
        val olds = ENTITIES.associateWith { rows(old, it) }
        val news = ENTITIES.associateWith { rows(new, it) }
        ENTITIES.forEach { e ->
            val o = olds.getValue(e)
            // Categories: main groups before groups so the parent exists first.
            val ordered = news.getValue(e).entries.let { entries ->
                if (e == "categories") entries.sortedBy { it.value["parentId"].let { p -> p != null && p !is JsonNull } } else entries.toList()
            }
            ordered.forEach { (k, row) -> if (o[k] != row) out += Change(SyncEvent.UPSERT, e, k, row) }
        }
        ENTITIES.asReversed().forEach { e ->
            val n = news.getValue(e)
            val deleted = olds.getValue(e).keys.filter { it !in n }
            // Groups before their main groups.
            val ordered = if (e == "categories") deleted.sortedBy { olds.getValue(e)[it]?.get("parentId").let { p -> p == null || p is JsonNull } } else deleted
            ordered.forEach { k -> out += Change(SyncEvent.DELETE, e, k, null) }
        }
        return out
    }

    /** Applies admin events to [state]. Unknown kinds and entities are ignored (newer app versions). */
    fun apply(state: JsonObject, events: List<SyncEvent>): JsonObject {
        val lists = ENTITIES.associateWith { LinkedHashMap(rows(state, it)) }
        var currency = state[SETTING_CURRENCY]?.jsonPrimitive?.contentOrNull
        events.forEach { ev ->
            when (ev.kind) {
                SyncEvent.SETTING -> if (ev.entity == SETTING_CURRENCY) currency = ev.data?.jsonPrimitive?.contentOrNull ?: currency
                SyncEvent.UPSERT -> {
                    val list = lists[ev.entity] ?: return@forEach
                    val row = ev.data as? JsonObject ?: return@forEach
                    list[ev.key ?: keyOf(ev.entity!!, row)] = row
                }
                SyncEvent.DELETE -> lists[ev.entity]?.remove(ev.key)
            }
        }
        return buildJsonObject {
            currency?.let { put(SETTING_CURRENCY, it) }
            ENTITIES.forEach { e -> put(e, JsonArray(lists.getValue(e).values.toList())) }
        }
    }

    /**
     * Builds a backup file from shared [state] plus this phone's local-only data (its own bills, bill
     * items and proposals), ready for [com.homebudget.app.data.backup.BackupRepository.restore].
     * Local bills that point at accounts or categories that no longer exist lose that link.
     */
    fun toBackup(state: JsonObject, local: BackupFile, now: Long): BackupFile {
        val obj = buildJsonObject {
            put("exportedAtEpochMillis", now)
            put("currencyCode", state[SETTING_CURRENCY]?.jsonPrimitive?.contentOrNull ?: local.currencyCode)
            ENTITIES.forEach { e -> put(e, state[e] ?: JsonArray(emptyList())) }
        }
        val shared = SyncJson.json.decodeFromJsonElement(BackupFile.serializer(), obj)
        val accountIds = shared.accounts.map { it.id }.toSet()
        val categoryIds = shared.categories.map { it.id }.toSet()
        return shared.copy(
            bills = local.bills.map { if (it.accountId != null && it.accountId !in accountIds) it.copy(accountId = null) else it },
            billItems = local.billItems.map { if (it.categoryId != null && it.categoryId !in categoryIds) it.copy(categoryId = null) else it },
            proposals = local.proposals,
        )
    }
}
