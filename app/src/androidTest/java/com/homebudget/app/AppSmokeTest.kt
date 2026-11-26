package com.homebudget.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end smoke test on a real device/emulator: launches the app and navigates the main tabs. */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Waits until exactly one node shows [text] (as text or content description), scrolls it into view
     * when it sits in a scrollable container, then asserts it is displayed.
     *
     * The first wait is long on purpose: a cold debug build on a busy emulator can take 20+ seconds
     * before Room/DataStore data replaces the loading spinner.
     */
    private fun awaitDisplayed(text: String, timeoutMillis: Long = 60_000) {
        val matcher = hasText(text) or hasContentDescription(text)
        try {
            compose.waitUntil(timeoutMillis) {
                compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1
            }
            val node = compose.onNode(matcher)
            if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
            node.assertIsDisplayed()
        } catch (e: Throwable) {
            val count = runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().size }.getOrNull()
            throw AssertionError(
                "'$text' not displayed (matching nodes: $count). Tree:\n" +
                    runCatching { compose.onRoot(useUnmergedTree = false).printToString() }.getOrElse { "<no tree: $it>" },
                e,
            )
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
