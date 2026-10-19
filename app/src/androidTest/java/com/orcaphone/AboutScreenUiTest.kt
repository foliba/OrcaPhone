package com.orcaphone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The AGPL notices, the generated library list and the licence texts are reachable from the app. */
@RunWith(AndroidJUnit4::class)
class AboutScreenUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsLicencesAndTexts() {
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("About & licences").performClick()
        rule.onNodeWithText("ABSOLUTELY NO WARRANTY", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Licence").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTextSafe("GNU AFFERO GENERAL PUBLIC LICENSE")
        }
        rule.onNodeWithContentDescription("Back").performClick()
        // The list is lazy: rows below the fold only exist once scrolled into view.
        // The generated library list must contain the real dependencies, e.g. JTS and OkHttp.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("org.locationtech.jts:jts-core", substring = true))
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("com.squareup.okhttp3:okhttp ", substring = true))
        rule.onNodeWithText("com.squareup.okhttp3:okhttp ", substring = true).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextSafe("Apache License") }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextSafe(text: String) =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
}
