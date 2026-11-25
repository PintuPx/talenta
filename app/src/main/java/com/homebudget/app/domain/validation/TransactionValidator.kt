package com.homebudget.app.domain.validation

import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.AmountParseResult
import com.homebudget.app.domain.money.AmountParser

enum class TxField { AMOUNT, ACCOUNT, TO_ACCOUNT, CATEGORY }

data class FieldError(val field: TxField, val message: String)

/** Raw form input for a transaction, before validation. */
data class TransactionDraft(
    val type: TransactionType,
    val amountText: String,
    val account: Account?,
    val toAccount: Account?,
    val category: Category?,
)

sealed interface ValidationResult {
    data class Valid(val amountMinor: Long) : ValidationResult
    data class Invalid(val errors: List<FieldError>) : ValidationResult
}

object TransactionValidator {

    fun amountError(result: AmountParseResult, fractionDigits: Int): String? = when (result) {
        is AmountParseResult.Valid -> null
        AmountParseResult.Empty -> "Enter an amount"
        AmountParseResult.NotANumber -> "Enter a valid number"
        AmountParseResult.TooManyDecimals ->
            if (fractionDigits == 0) "This currency has no decimal places"
            else "Use at most $fractionDigits decimal places"
        AmountParseResult.NotPositive -> "Amount must be greater than zero"
        AmountParseResult.TooLarge -> "Amount is too large"
    }

    fun validate(draft: TransactionDraft, fractionDigits: Int = 2): ValidationResult {
        val errors = mutableListOf<FieldError>()
        val parsed = AmountParser.parse(draft.amountText, fractionDigits)
        amountError(parsed, fractionDigits)?.let { errors += FieldError(TxField.AMOUNT, it) }

        if (draft.account == null) errors += FieldError(TxField.ACCOUNT, "Choose an account")

        when (draft.type) {
            TransactionType.TRANSFER -> when {
                draft.toAccount == null ->
                    errors += FieldError(TxField.TO_ACCOUNT, "Choose the destination account")
                draft.account != null && draft.toAccount.id == draft.account.id ->
                    errors += FieldError(TxField.TO_ACCOUNT, "Source and destination must be different")
            }
            TransactionType.INCOME, TransactionType.EXPENSE -> {
                val needed = if (draft.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
                when {
                    draft.category == null -> errors += FieldError(TxField.CATEGORY, "Choose a category")
                    draft.category.kind != needed ->
                        errors += FieldError(TxField.CATEGORY, "Category does not match the transaction type")
                }
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid((parsed as AmountParseResult.Valid).minor)
        else ValidationResult.Invalid(errors)
    }
}
