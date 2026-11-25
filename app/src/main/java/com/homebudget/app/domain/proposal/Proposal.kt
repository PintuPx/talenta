package com.homebudget.app.domain.proposal

/**
 * A change a Reviewer asks the Admin to make. Nothing in a proposal counts in balances, budgets or
 * reports until the Admin approves it and the change is applied to the household data.
 */
enum class ProposalKind(val label: String) {
    ADD_TRANSACTION("New transaction"),
    EDIT_TRANSACTION("Edit transaction"),
    DELETE_TRANSACTION("Delete transaction"),
    PURCHASE_REQUEST("Purchase request"),
}

enum class ProposalStatus(val label: String) { PENDING("Waiting for approval"), APPROVED("Approved"), REJECTED("Rejected") }

data class Proposal(
    val id: Long = 0,
    /** Globally unique (UUID); the same on the Reviewer's and the Admin's phone. */
    val uuid: String,
    val kind: ProposalKind,
    val status: ProposalStatus,
    val authorId: String,
    val authorName: String,
    /** JSON payload: a BackupTransaction (add/edit), {"id":n} (delete) or a purchase request. */
    val payload: String,
    /** Short human-readable description shown in lists. Never logged. */
    val summary: String,
    val reason: String = "",
    val createdAt: Long,
    val decidedAt: Long? = null,
    /** Id of the row the approval created or changed. */
    val resultId: Long? = null,
)

object ProposalRules {
    const val MAX_REASON = 200

    /** A decision can only be made once, on a pending proposal. */
    fun canDecide(p: Proposal) = p.status == ProposalStatus.PENDING

    fun decide(p: Proposal, approve: Boolean, reason: String, at: Long, resultId: Long? = null): Proposal {
        require(canDecide(p)) { "This request was already decided" }
        require(reason.length <= MAX_REASON) { "Reason must be at most $MAX_REASON characters" }
        return p.copy(
            status = if (approve) ProposalStatus.APPROVED else ProposalStatus.REJECTED,
            reason = reason.trim(), decidedAt = at, resultId = resultId,
        )
    }
}
