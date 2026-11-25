package com.homebudget.app.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.recurring.RepeatOption
import com.homebudget.app.ui.edit.EditFormActions
import com.homebudget.app.ui.edit.EditFormState
import com.homebudget.app.ui.edit.EditTransactionForm
import com.homebudget.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Repeat field, its save label and the "added by a repeating transaction" chip. */
@RunWith(AndroidJUnit4::class)
class RepeatFieldTest {

    @get:Rule val compose = createComposeRule()

    private val cash = Account(1, "Cash", AccountType.CASH)
    private val food = Category(10, "Groceries", CategoryKind.EXPENSE, "groceries", 0xFF2E7D32)

    private fun show(state: EditFormState, actions: EditFormActions = EditFormActions()) {
        compose.setContent {
            AppTheme { EditTransactionForm(state = state, actions = actions) }
        }
    }

    private fun base(
        editingId: Long = 0, billId: Long? = null, recurringId: Long = 0, repeat: RepeatOption? = null,
        sourceRecurringId: Long? = null,
    ) = EditFormState(
        loading = false, editingId = editingId, billId = billId, recurringId = recurringId, repeat = repeat,
        sourceRecurringId = sourceRecurringId, accountId = 1, allAccounts = listOf(cash), allCategories = listOf(food),
    )

    @Test
    fun repeatFieldIsShownForANewTransaction() {
        show(base())
        compose.onNodeWithTag("repeat").performScrollTo().assertExists()
        compose.onNodeWithText("Does not repeat").assertExists()
        compose.onNodeWithText("Save expense").assertExists()
    }

    @Test
    fun repeatFieldIsHiddenWhenEditingARecordedTransaction() {
        show(base(editingId = 5))
        compose.onNodeWithTag("repeat").assertDoesNotExist()
        compose.onNodeWithText("Save changes").assertExists()
    }

    @Test
    fun repeatFieldIsHiddenForABillLine() {
        show(base(billId = 7))
        compose.onNodeWithTag("repeat").assertDoesNotExist()
    }

    @Test
    fun repeatFieldIsShownForARule() {
        show(base(recurringId = 3, repeat = RepeatOption.MONTHLY))
        compose.onNodeWithTag("repeat").performScrollTo().assertExists()
        compose.onNodeWithText("Every month").assertExists()
        compose.onNodeWithText("Next date").assertExists()
    }

    @Test
    fun saveButtonTextFollowsRepeatAndRule() {
        show(base(repeat = RepeatOption.WEEKLY))
        compose.onNodeWithText("Save and repeat").assertExists()
        compose.onNodeWithText("First date").assertExists()
    }

    @Test
    fun saveButtonTextForARule() {
        show(base(recurringId = 3, repeat = RepeatOption.MONTHLY))
        compose.onNodeWithText("Save repeating transaction").assertExists()
    }

    @Test
    fun pickingARepeatOptionReportsIt() {
        var picked: RepeatOption? = null
        show(base(), EditFormActions(onRepeat = { picked = it }))
        compose.onNodeWithTag("repeat").performScrollTo().performClick()
        compose.onNodeWithText("Every 3 months").performClick()
        compose.runOnIdle { assertEquals(RepeatOption.QUARTERLY, picked) }
    }

    @Test
    fun ruleRepeatPickerHasNoDoesNotRepeatOption() {
        show(base(recurringId = 3, repeat = RepeatOption.MONTHLY))
        compose.onNodeWithTag("repeat").performScrollTo().performClick()
        compose.onNodeWithText("Every year").assertExists()
        compose.onNodeWithText("Does not repeat").assertDoesNotExist()
    }

    @Test
    fun chipShownOnlyForTransactionsAddedByARule() {
        var opened: Long? = null
        show(base(editingId = 5, sourceRecurringId = 9), EditFormActions(onOpenRecurring = { opened = it }))
        compose.onNodeWithText("Added by a repeating transaction · manage").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(9L, opened) }
    }

    @Test
    fun chipIsAbsentWithoutASourceRule() {
        show(base(editingId = 5))
        compose.onNodeWithText("Added by a repeating transaction · manage").assertDoesNotExist()
    }
}
