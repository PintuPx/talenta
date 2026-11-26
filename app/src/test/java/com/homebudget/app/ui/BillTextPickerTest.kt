package com.homebudget.app.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.ui.bills.BillTextPickerSheet
import com.homebudget.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillTextPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tappingRecognisedLineSetsShopName() {
        var shopName: String? = null
        compose.setContent {
            AppTheme {
                BillTextPickerSheet(
                    text = "FRESH MART\nMilk 56.00",
                    selectedItemKey = 1,
                    fractionDigits = 2,
                    onUseShopName = { shopName = it },
                    onUseItemName = { _, _ -> },
                    onUseAmount = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("bill-text-line-0").performClick()
        compose.onNodeWithText("Use as shop name").performClick()
        assertEquals("FRESH MART", shopName)
    }
}
