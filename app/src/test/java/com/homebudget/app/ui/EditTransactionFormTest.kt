package com.homebudget.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.validation.TransactionDraft
import com.homebudget.app.domain.validation.TransactionValidator
import com.homebudget.app.domain.validation.ValidationResult
import com.homebudget.app.ui.edit.EditFormActions
import com.homebudget.app.ui.edit.EditFormState
import com.homebudget.app.ui.edit.EditTransactionForm
import com.homebudget.app.ui.edit.suggestPayees
import com.homebudget.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose UI test of the transaction form, run on the JVM via Robolectric. */
@RunWith(AndroidJUnit4::class)
class EditTransactionFormTest {

    @get:Rule val compose = createComposeRule()

    private val cash = Account(1, "Cash", AccountType.CASH)
    private val bank = Account(2, "Bank", AccountType.CREDIT_CARD)
    private val food = Category(10, "Groceries", CategoryKind.EXPENSE, "groceries", 0xFF2E7D32)

    @Test
    fun payeeSuggestionsFilterCaseInsensitivelyAndHideExactMatch() {
        val suggestions = suggestPayees(listOf("Fresh Mart", "Fresh & Easy", "Bakery"), "fr")

        assertEquals(listOf("Fresh Mart", "Fresh & Easy"), suggestions)
        assertEquals(emptyList<String>(), suggestPayees(suggestions, "fresh mart"))
    }

    @Test
    fun payeeSuggestionChipFillsTheField() {
        compose.setContent {
            var state by remember {
                mutableStateOf(
                    EditFormState(
                        loading = false,
                        accountId = 1,
                        allAccounts = listOf(cash),
                        allCategories = listOf(food),
                        payeeSuggestions = listOf("Fresh Mart"),
                    )
                )
            }
            AppTheme {
                EditTransactionForm(
                    state = state,
                    actions = EditFormActions(onPayee = { state = state.copy(payee = it) }),
                )
            }
        }

        // The form is taller than the Robolectric viewport, so bring each node into view first.
        // performScrollTo only scrolls the closest scrollable parent. For the chip that is the
        // horizontal suggestion row, so scroll that row into view in the form's vertical scroll first.
        val chip = compose.onNodeWithText("Fresh Mart")
        chip.onParent().assert(hasScrollAction()).performScrollTo()
        chip.performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("payee").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("payee").assertTextContains("Fresh Mart")
    }

    @Test
    fun saveWithEmptyFormShowsValidationErrorsAndValidInputSaves() {
        var savedMinor: Long? = null
        compose.setContent {
            var state by remember {
                mutableStateOf(EditFormState(loading = false, accountId = 1, currencySymbol = "₹", allAccounts = listOf(cash, bank), allCategories = listOf(food)))
            }
            AppTheme {
                EditTransactionForm(
                    state = state,
                    actions = EditFormActions(
                        onAmount = { state = state.copy(amountText = it) },
                        onCategory = { state = state.copy(categoryId = it) },
                        onSave = {
                            val r = TransactionValidator.validate(TransactionDraft(state.type, state.amountText, state.account, state.toAccount, state.category))
                            when (r) {
                                is ValidationResult.Invalid -> state = state.copy(errors = r.errors.associate { it.field to it.message })
                                is ValidationResult.Valid -> { state = state.copy(errors = emptyMap()); savedMinor = r.amountMinor }
                            }
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("save").performScrollTo().performClick()
        compose.onNodeWithText("Enter an amount").assertExists()
        compose.onNodeWithText("Choose a category").assertExists()
        assertEquals(null, savedMinor)

        compose.onNodeWithTag("amount").performTextInput("1.234")
        compose.onNodeWithTag("save").performScrollTo().performClick()
        compose.onNodeWithText("Use at most 2 decimal places").assertExists()

        compose.onNodeWithTag("category").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Choose category").assertExists()
        compose.onNodeWithText("Groceries", substring = true).performClick()
        compose.onNodeWithTag("amount").performTextClearance()
        compose.onNodeWithTag("amount").performTextInput("250.50")
        compose.onNodeWithTag("save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(25_050L, savedMinor) }
    }

    @Test
    fun groupIsPickedInsideItsMainGroupAndSaveLabelFollowsType() {
        val veg = Category(11, "Vegetables", CategoryKind.EXPENSE, "vegetables", 0xFF2E7D32, parentId = 10)
        val salary = Category(20, "Salary", CategoryKind.INCOME, "salary", 0xFF2E7D32)
        compose.setContent {
            var state by remember {
                mutableStateOf(EditFormState(loading = false, accountId = 1, allAccounts = listOf(cash), allCategories = listOf(food, veg, salary)))
            }
            AppTheme {
                EditTransactionForm(
                    state = state,
                    actions = EditFormActions(
                        onCategory = { state = state.copy(categoryId = it) },
                        onType = { state = state.copy(type = it, categoryId = null) },
                    ),
                )
            }
        }
        compose.onNodeWithText("Save expense").assertExists()

        compose.onNodeWithTag("category").performScrollTo().performClick()
        compose.onNodeWithText("Groceries ›").performClick() // main group with groups opens them
        compose.onNodeWithText("All Groceries").assertExists()
        compose.onNodeWithText("Vegetables").performClick()
        compose.onNodeWithText("Vegetables · Groceries").assertExists()

        compose.onNodeWithText("Income").performClick()
        compose.onNodeWithText("Save income").assertExists()
    }

    @Test
    fun transferToCreditCardExplainsItIsNotAnExpense() {
        compose.setContent {
            AppTheme {
                EditTransactionForm(
                    state = EditFormState(
                        loading = false, type = TransactionType.TRANSFER, accountId = 1, toAccountId = 2,
                        allAccounts = listOf(cash, bank), allCategories = listOf(food),
                    ),
                    actions = EditFormActions(),
                )
            }
        }
        compose.onNodeWithText("Credit-card payment", substring = true).assertIsDisplayed()
        compose.onNodeWithText("To account").assertIsDisplayed()
    }
}
