package com.homebudget.app.domain.household

/**
 * How this phone is used. [UNSET] only before the first-launch choice. In [PERSONAL] mode there are no
 * members, no sign-in and no sync: the owner can do everything.
 */
enum class AppMode { UNSET, PERSONAL, SHARED }

/** Roles in a shared household. Exactly one [ADMIN] per household. */
enum class Role(val label: String) {
    ADMIN("Admin"),
    REVIEWER("Reviewer"),
    READ_ONLY("Read-only"),
}

/**
 * What the person using this phone may do. All screens and view models ask this object, so the rules
 * live in one place (and are unit-tested).
 *
 * - Personal mode: everything.
 * - Admin: everything; changes count immediately.
 * - Reviewer: proposes transactions (add, edit, delete) and purchase requests; nothing counts until the
 *   Admin approves. Cannot manage accounts, categories, budgets, repeating transactions, members,
 *   backup/restore or clear data.
 * - Read-only: views and reports only.
 */
data class Access(val mode: AppMode, val role: Role?) {
    private val owner get() = mode != AppMode.SHARED || role == Role.ADMIN
    val isShared get() = mode == AppMode.SHARED

    /** Writes go straight to the database. */
    val canEditDirectly get() = owner

    /** Transaction changes are allowed, but as proposals for the Admin. */
    val proposesChanges get() = mode == AppMode.SHARED && role == Role.REVIEWER

    /** Can start adding/editing/deleting a transaction (directly or as a proposal). */
    val canChangeTransactions get() = canEditDirectly || proposesChanges

    /** Accounts, categories, budgets, repeating transactions, inventory items. */
    val canManageSetup get() = owner

    val canScanBills get() = canChangeTransactions
    val canRequestPurchase get() = canChangeTransactions
    val canApprove get() = mode == AppMode.SHARED && role == Role.ADMIN
    val canManageMembers get() = mode == AppMode.SHARED && role == Role.ADMIN
    val canBackupRestore get() = owner
    val canClearData get() = owner

    /** Repeating transactions are posted only on the phone that owns the data. */
    val postsRecurring get() = owner
    val isReadOnly get() = mode == AppMode.SHARED && role == Role.READ_ONLY

    companion object {
        val PERSONAL = Access(AppMode.PERSONAL, null)
    }
}

/** A person in the household roster. [memberId] is also their sync partition id. */
data class RosterEntry(val memberId: String, val email: String, val name: String, val role: Role)

object HouseholdRules {
    const val MAX_NAME = 40
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isValidEmail(email: String) = EMAIL.matches(email.trim())

    /** Returns an error message, or null when the roster is valid. Exactly one Admin; unique emails and ids. */
    fun validateRoster(roster: List<RosterEntry>): String? {
        if (roster.count { it.role == Role.ADMIN } != 1) return "A household must have exactly one Admin"
        if (roster.map { it.email.trim().lowercase() }.toSet().size != roster.size) return "Each member needs a different email"
        if (roster.map { it.memberId }.toSet().size != roster.size) return "Duplicate member id"
        roster.forEach {
            if (!isValidEmail(it.email)) return "Invalid email: ${it.email}"
            if (it.name.isBlank() || it.name.length > MAX_NAME) return "A member name must be 1 to $MAX_NAME characters"
        }
        return null
    }

    /** Adds a member; a second Admin is refused. */
    fun addMember(roster: List<RosterEntry>, entry: RosterEntry): Result<List<RosterEntry>> {
        if (entry.role == Role.ADMIN && roster.any { it.role == Role.ADMIN }) return Result.failure(IllegalArgumentException("There can be only one Admin"))
        val next = roster + entry.copy(email = entry.email.trim().lowercase(), name = entry.name.trim())
        return validateRoster(next)?.let { Result.failure(IllegalArgumentException(it)) } ?: Result.success(next)
    }

    /** Changes a member's role. The Admin's own role cannot be changed, and nobody else can become Admin. */
    fun changeRole(roster: List<RosterEntry>, memberId: String, role: Role): Result<List<RosterEntry>> {
        val target = roster.find { it.memberId == memberId } ?: return Result.failure(IllegalArgumentException("Unknown member"))
        if (target.role == Role.ADMIN || role == Role.ADMIN) return Result.failure(IllegalArgumentException("There can be only one Admin"))
        return Result.success(roster.map { if (it.memberId == memberId) it.copy(role = role) else it })
    }

    fun removeMember(roster: List<RosterEntry>, memberId: String): Result<List<RosterEntry>> {
        val target = roster.find { it.memberId == memberId } ?: return Result.failure(IllegalArgumentException("Unknown member"))
        if (target.role == Role.ADMIN) return Result.failure(IllegalArgumentException("The Admin cannot be removed"))
        return Result.success(roster - target)
    }
}
