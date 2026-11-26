package com.homebudget.app.domain.backup

import com.homebudget.app.domain.inventory.InventoryItem
import com.homebudget.app.domain.inventory.InventoryRules
import com.homebudget.app.domain.inventory.RequestStatus
import com.homebudget.app.domain.inventory.StockUnit
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.proposal.ProposalKind
import com.homebudget.app.domain.proposal.ProposalStatus
import com.homebudget.app.domain.model.BillStatus
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Frequency
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.Money
import com.homebudget.app.domain.money.MoneyFormatter
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * Full structural and financial validation of a backup before anything is written to the database.
 * Returns a user-presentable reason for the first problem found, or null when the file is valid.
 */
object BackupValidator {

    fun validate(f: BackupFile): String? {
        // Repeated from BackupCodec.decode so restore() is safe even for a BackupFile built in code.
        if (f.format != BackupFile.FORMAT_ID) return "This is not a backup created by this app"
        if (f.formatVersion > BackupFile.CURRENT_FORMAT_VERSION) {
            return "This backup was created by a newer version of the app. Please update the app first."
        }
        if (f.formatVersion < 1) return "Unsupported backup version"
        if (f.currencyCode !in MoneyFormatter.SUPPORTED) return "Unsupported currency '${f.currencyCode}'"

        val accountIds = HashSet<Long>()
        val accountTypesById = HashMap<Long, String>()
        for (a in f.accounts) {
            if (a.id <= 0 || !accountIds.add(a.id)) return "Duplicate or invalid account id ${a.id}"
            if (a.name.isBlank()) return "An account has no name"
            if (AccountType.entries.none { it.name == a.type }) return "Unknown account type '${a.type}'"
            if (kotlin.math.abs(a.openingBalance) > Money.MAX_AMOUNT) return "Account '${a.name}' has an invalid opening balance"
            if (a.type == AccountType.INVESTMENT.name && a.openingBalance < 0) {
                return "Investment account '${a.name}' has an invalid opening balance"
            }
            accountTypesById[a.id] = a.type
        }

        val categoryKinds = HashMap<Long, CategoryKind>()
        for (c in f.categories) {
            if (c.id <= 0 || categoryKinds.containsKey(c.id)) return "Duplicate or invalid category id ${c.id}"
            if (c.name.isBlank()) return "A category has no name"
            val kind = CategoryKind.entries.firstOrNull { it.name == c.kind } ?: return "Unknown category kind '${c.kind}'"
            categoryKinds[c.id] = kind
        }
        val parents = f.categories.associate { it.id to it.parentId }
        for (c in f.categories) {
            val parentId = c.parentId ?: continue
            val parentKind = categoryKinds[parentId] ?: return "Category '${c.name}' refers to a missing main group"
            if (parents[parentId] != null) return "Category '${c.name}' is nested more than two levels deep"
            if (parentKind != categoryKinds[c.id]) return "Category '${c.name}' has a main group of a different type"
        }

        val billIds = HashSet<Long>()
        for (b in f.bills) {
            if (b.id <= 0 || !billIds.add(b.id)) return "Duplicate or invalid bill id ${b.id}"
            if (BillStatus.entries.none { it.name == b.status }) return "Bill ${b.id} has an unknown status"
            if (b.accountId != null && b.accountId !in accountIds) return "Bill ${b.id} refers to a missing account"
            if (b.total != null && (b.total < 0 || b.total > Money.MAX_AMOUNT)) return "Bill ${b.id} has an invalid total"
            if (b.imageFile != null && !b.imageFile.matches(Regex("[A-Za-z0-9._-]+"))) return "Bill ${b.id} has an invalid photo name"
            try {
                LocalDateTime.parse(b.billDate)
            } catch (e: DateTimeParseException) {
                return "Bill ${b.id} has an invalid date"
            }
        }
        val billItemIds = HashSet<Long>()
        for (i in f.billItems) {
            if (i.id <= 0 || !billItemIds.add(i.id)) return "Duplicate or invalid bill item id ${i.id}"
            if (i.billId !in billIds) return "Bill item ${i.id} refers to a missing bill"
            if (i.amount < 0 || i.amount > Money.MAX_AMOUNT) return "Bill item ${i.id} has an invalid amount"
            if (i.categoryId != null && categoryKinds[i.categoryId] != CategoryKind.EXPENSE) {
                return "Bill item ${i.id} refers to a missing or non-expense category"
            }
        }
        val ruleKeys = HashSet<String>()
        for (r in f.itemRules) {
            if (r.key.isBlank() || !ruleKeys.add(r.key)) return "Duplicate or empty item rule"
            if (categoryKinds[r.categoryId] == null) return "Item rule '${r.key}' refers to a missing category"
        }

        val ruleIds = HashSet<Long>()
        for (r in f.recurringRules) {
            if (r.id <= 0 || !ruleIds.add(r.id)) return "Duplicate or invalid repeating transaction id ${r.id}"
            val type = TransactionType.entries.firstOrNull { it.name == r.type } ?: return "Repeating transaction ${r.id} has an unknown type"
            if (r.amountChanges && (type != TransactionType.EXPENSE || r.isRefund)) {
                return "Repeating transaction ${r.id} can change amounts only for non-refund expenses"
            }
            if (Frequency.entries.none { it.name == r.frequency }) return "Repeating transaction ${r.id} has an unknown frequency"
            if (r.interval !in 1..366 || r.postedCount < 0) return "Repeating transaction ${r.id} has an invalid schedule"
            if (r.amount <= 0 || r.amount > Money.MAX_AMOUNT) return "Repeating transaction ${r.id} has an invalid amount"
            if (r.accountId !in accountIds) return "Repeating transaction ${r.id} refers to a missing account"
            try {
                LocalDateTime.parse(r.startAt)
            } catch (e: DateTimeParseException) {
                return "Repeating transaction ${r.id} has an invalid start date"
            }
            when (type) {
                TransactionType.TRANSFER -> {
                    val to = r.toAccountId ?: return "Repeating transfer ${r.id} has no destination account"
                    if (to !in accountIds || to == r.accountId) return "Repeating transfer ${r.id} has an invalid destination account"
                }
                TransactionType.INCOME, TransactionType.EXPENSE -> {
                    if (accountTypesById[r.accountId] == AccountType.INVESTMENT.name) {
                        return "Repeating income or expense ${r.id} cannot use an investment account"
                    }
                    val kind = r.categoryId?.let { categoryKinds[it] } ?: return "Repeating transaction ${r.id} refers to a missing category"
                    val expected = if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
                    if (kind != expected) return "Repeating transaction ${r.id} has a category of the wrong kind"
                }
            }
        }

        val txIds = HashSet<Long>()
        for (t in f.transactions) {
            if (t.id <= 0 || !txIds.add(t.id)) return "Duplicate or invalid transaction id ${t.id}"
            val type = TransactionType.entries.firstOrNull { it.name == t.type } ?: return "Unknown transaction type '${t.type}'"
            if (t.amount <= 0 || t.amount > Money.MAX_AMOUNT) return "Transaction ${t.id} has an invalid amount"
            if (t.accountId !in accountIds) return "Transaction ${t.id} refers to a missing account"
            if (t.billId != null && t.billId !in billIds) return "Transaction ${t.id} refers to a missing bill"
            if (t.recurringId != null && t.recurringId !in ruleIds) return "Transaction ${t.id} refers to a missing repeating transaction"
            try {
                LocalDateTime.parse(t.occurredAtIso)
            } catch (e: DateTimeParseException) {
                return "Transaction ${t.id} has an invalid date"
            }
            when (type) {
                TransactionType.TRANSFER -> {
                    val to = t.toAccountId ?: return "Transfer ${t.id} has no destination account"
                    if (to !in accountIds) return "Transfer ${t.id} refers to a missing account"
                    if (to == t.accountId) return "Transfer ${t.id} has the same source and destination"
                    if (t.categoryId != null || t.isRefund) return "Transfer ${t.id} must not have a category or refund flag"
                }
                TransactionType.INCOME, TransactionType.EXPENSE -> {
                    if (accountTypesById[t.accountId] == AccountType.INVESTMENT.name) {
                        return "Income or expense ${t.id} cannot use an investment account"
                    }
                    if (t.toAccountId != null) return "Transaction ${t.id} must not have a destination account"
                    val catId = t.categoryId ?: return "Transaction ${t.id} has no category"
                    val kind = categoryKinds[catId] ?: return "Transaction ${t.id} refers to a missing category"
                    val expected = if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
                    if (kind != expected) return "Transaction ${t.id} has a category of the wrong kind"
                    if (type == TransactionType.INCOME && t.isRefund) return "Income ${t.id} cannot be a refund"
                }
            }
        }

        val budgetIds = HashSet<Long>()
        for (b in f.budgets) {
            if (b.id <= 0 || !budgetIds.add(b.id)) return "Duplicate or invalid budget id ${b.id}"
            val month = b.month % 100
            if (b.month / 100 !in 1900..2999 || month !in 1..12) return "Budget ${b.id} has an invalid month"
            if (b.limitAmount < 0 || b.limitAmount > Money.MAX_AMOUNT) return "Budget ${b.id} has an invalid limit"
            if (b.categoryId != null && categoryKinds[b.categoryId] != CategoryKind.EXPENSE) {
                return "Budget ${b.id} refers to a missing or non-expense category"
            }
        }
        val itemIds = HashSet<Long>()
        for (i in f.inventoryItems) {
            if (i.id <= 0 || !itemIds.add(i.id)) return "Duplicate or invalid stock item id ${i.id}"
            if (StockUnit.entries.none { it.name == i.unit }) return "Stock item ${i.id} has an unknown unit"
            InventoryRules.validate(InventoryItem(i.id, i.name, StockUnit.valueOf(i.unit), i.quantityMilli, i.lowStockMilli, i.note))
                ?.let { return "Stock item ${i.id}: $it" }
        }
        val requestIds = HashSet<Long>()
        for (r in f.purchaseRequests) {
            if (r.id <= 0 || !requestIds.add(r.id)) return "Duplicate or invalid purchase request id ${r.id}"
            if (r.itemId !in itemIds) return "Purchase request ${r.id} refers to a missing stock item"
            if (RequestStatus.entries.none { it.name == r.status }) return "Purchase request ${r.id} has an unknown status"
            if (r.quantityMilli <= 0 || r.quantityMilli > InventoryRules.MAX_QUANTITY_MILLI) return "Purchase request ${r.id} has an invalid quantity"
        }
        val proposalIds = HashSet<Long>()
        val proposalUuids = HashSet<String>()
        for (p in f.proposals) {
            if (p.id <= 0 || !proposalIds.add(p.id)) return "Duplicate or invalid request id ${p.id}"
            if (p.uuid.isBlank() || !proposalUuids.add(p.uuid)) return "Duplicate request ${p.id}"
            if (ProposalKind.entries.none { it.name == p.kind }) return "Request ${p.id} has an unknown kind"
            if (ProposalStatus.entries.none { it.name == p.status }) return "Request ${p.id} has an unknown status"
        }

        if (f.formatVersion >= 5) {
            fun invalidUids(values: List<String>): Boolean =
                values.any { it.isBlank() } || values.size != values.toSet().size

            if (invalidUids(f.accounts.map { it.uid })) return "Accounts have missing or duplicate identities"
            if (invalidUids(f.categories.map { it.uid })) return "Categories have missing or duplicate identities"
            if (invalidUids(f.transactions.map { it.uid })) return "Transactions have missing or duplicate identities"
            if (invalidUids(f.bills.map { it.uid })) return "Bills have missing or duplicate identities"
            if (invalidUids(f.billItems.map { it.uid })) return "Bill lines have missing or duplicate identities"
            if (invalidUids(f.budgets.map { it.uid })) return "Budgets have missing or duplicate identities"
            if (invalidUids(f.recurringRules.map { it.uid })) return "Repeating transactions have missing or duplicate identities"
            if (invalidUids(f.itemRules.map { it.uid })) return "Learned rules have missing or duplicate identities"

            val versionIds = HashSet<Long>()
            val eventUids = HashSet<String>()
            val versionStatuses = setOf("APPROVED", "PENDING", "REJECTED", "SUPERSEDED")
            for (v in f.entityVersions) {
                if (v.id <= 0 || !versionIds.add(v.id)) return "Duplicate or invalid history id ${v.id}"
                if (v.entityType.isBlank() || v.entityUid.isBlank()) return "A history entry has no entity identity"
                if (v.version < 0 || v.baseVersion < 0 || v.snapshotFormat < 1) return "History entry ${v.id} has invalid version data"
                if (v.status !in versionStatuses) return "History entry ${v.id} has an unknown status"
                if (v.snapshot.isBlank()) return "History entry ${v.id} has no snapshot"
                if (v.eventUid.isBlank() || !eventUids.add(v.eventUid)) return "Duplicate or empty history event identity"
            }

            val lockedMonths = HashSet<Int>()
            for (lock in f.monthLocks) {
                val month = lock.month % 100
                if (lock.month / 100 !in 1900..2999 || month !in 1..12 || !lockedMonths.add(lock.month)) {
                    return "A month lock has an invalid or duplicate month"
                }
            }

            val memberIds = HashSet<String>()
            for (member in f.members) {
                if (member.memberId.isBlank() || !memberIds.add(member.memberId)) return "Duplicate or empty family member identity"
                if (member.name.isBlank() || member.email.isBlank()) return "A family member has no name or email"
                if (member.role !in setOf("ADMIN", "REVIEWER", "READ_ONLY")) return "A family member has an unknown role"
                if (member.appVersionCode < 0 || member.syncFormat < 1) return "A family member has invalid version information"
            }
            if (f.members.any { it.active } && f.members.count { it.active && it.role == "ADMIN" } != 1) {
                return "A family backup must have exactly one active Admin"
            }
        }
        if (f.formatVersion >= 6) {
            val peopleByUid = HashMap<String, BackupFamilyPerson>()
            val personIds = HashSet<Long>()
            for (person in f.familyPeople) {
                if (person.id <= 0 || !personIds.add(person.id)) return "Duplicate or invalid expense member id"
                if (person.uid.isBlank() || peopleByUid.put(person.uid, person) != null) {
                    return "Expense members have missing or duplicate identities"
                }
                if (person.name.isBlank() || person.name.length > 50) return "An expense member has an invalid name"
            }
            val personNames = f.familyPeople.map { it.name.trim().replace(Regex("\\s+"), " ").lowercase() }
            if (personNames.size != personNames.toSet().size) return "Expense member names must be unique"

            val tagsByUid = HashMap<String, BackupFamilyTag>()
            val tagIds = HashSet<Long>()
            for (tag in f.familyTags) {
                if (tag.id <= 0 || !tagIds.add(tag.id)) return "Duplicate or invalid expense tag id"
                if (tag.uid.isBlank() || tagsByUid.put(tag.uid, tag) != null) {
                    return "Expense tags have missing or duplicate identities"
                }
                if (tag.name.isBlank() || tag.name.length > 50 || tag.name.equals("All", ignoreCase = true)) {
                    return "An expense tag has an invalid name"
                }
            }
            val tagNames = f.familyTags.map { it.name.trim().replace(Regex("\\s+"), " ").lowercase() }
            if (tagNames.size != tagNames.toSet().size) return "Expense tag names must be unique"
            val familyTagMemberIds = HashSet<Long>()
            if (f.familyTagMembers.any {
                    it.id <= 0 || !familyTagMemberIds.add(it.id) ||
                        it.uid.isBlank() || it.tagUid !in tagsByUid || it.personUid !in peopleByUid
                }
            ) {
                return "An expense tag refers to a missing member or tag"
            }
            val tagLinks = f.familyTagMembers.map { it.tagUid to it.personUid }
            if (tagLinks.size != tagLinks.toSet().size ||
                f.familyTagMembers.map { it.uid }.let { it.size != it.toSet().size }
            ) {
                return "Expense tag links have duplicate identities"
            }

            val transactionsByUid = f.transactions.associateBy { it.uid }
            val sharesByTransaction = f.transactionMembers.groupBy { it.transactionUid }
            val transactionMemberIds = HashSet<Long>()
            if (f.transactionMembers.any {
                    it.id <= 0 || !transactionMemberIds.add(it.id) ||
                        it.uid.isBlank() || it.transactionUid !in transactionsByUid || it.personUid !in peopleByUid ||
                        it.shareAmount < 0 || it.shareAmount > Money.MAX_AMOUNT
                }
            ) {
                return "A transaction member link has invalid references or amount"
            }
            if (f.transactionMembers.map { it.uid }.let { it.size != it.toSet().size }) {
                return "Transaction member links have duplicate identities"
            }
            if (f.transactionMembers.map { it.transactionUid to it.personUid }.let { it.size != it.toSet().size }) {
                return "A transaction assigns the same member more than once"
            }
            sharesByTransaction.forEach { (transactionUid, shares) ->
                val transaction = transactionsByUid.getValue(transactionUid)
                if (transaction.type == TransactionType.TRANSFER.name ||
                    shares.any { it.isPrivate != transaction.isPrivate } ||
                    shares.sumOf { it.shareAmount } != transaction.amount
                ) {
                    return "Transaction member shares do not match their transaction"
                }
            }

        }
        if (f.formatVersion >= 7) {
            val accountsByUid = f.accounts.associateBy { it.uid }
            val investmentValueIds = HashSet<Long>()
            val investmentValueUids = HashSet<String>()
            for (value in f.investmentValues) {
                val account = accountsByUid[value.accountUid]
                    ?: return "An investment value refers to a missing account"
                if (value.id <= 0 || !investmentValueIds.add(value.id) ||
                    value.uid.isBlank() || !investmentValueUids.add(value.uid)
                ) {
                    return "Investment values have missing or duplicate identities"
                }
                if (account.type != AccountType.INVESTMENT.name ||
                    value.valueMinor < 0 || value.valueMinor > Money.MAX_AMOUNT ||
                    value.recordedAt < 0 || value.isPrivate != account.isPrivate
                ) {
                    return "An investment value is invalid"
                }
            }
        }
        return null
    }
}
