package com.homebudget.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end smoke test on a real device/emulator: launches the app and navigates the main tabs. */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Waits (data loads asynchronously from Room/DataStore) until [text] is on screen. */
    private fun awaitDisplayed(text: String) {
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                runCatching { compose.onNode(hasText(text) or hasContentDescription(text)).assertIsDisplayed() }.isSuccess
            }
        } catch (e: Throwable) {
            throw AssertionError("'$text' not displayed. Tree:\n" + compose.onRoot(useUnmergedTree = false).printToString(), e)
        }
    }

    @Test
    fun launchesAndNavigatesTopLevelScreens() {
        awaitDisplayed("Total balance")
        compose.onNodeWithText("Accounts").performClick()
        awaitDisplayed("Add account")
        compose.onNodeWithText("Settings").performClick()
        awaitDisplayed("Export backup")
    }
}
