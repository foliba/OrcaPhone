package io.github.foliba.phorca

import android.app.UiAutomation
import android.view.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.foliba.phorca.ui.AppViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Tablets in landscape: Prepare / Preview / Device sit in the middle of each screen's bottom bar, no second bar.
 * Everywhere else (phones, tablets in portrait) the navigation bar stays.
 */
@RunWith(AndroidJUnit4::class)
class DockedTabsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @After
    fun unfreeze() { automation.setRotation(UiAutomation.ROTATION_UNFREEZE) }

    private fun rotate(freeze: Int, surfaceRotation: Int) {
        automation.setRotation(freeze)
        rule.waitUntil(10_000) {
            var r = -1
            @Suppress("DEPRECATION")
            rule.activityRule.scenario.onActivity { r = it.windowManager.defaultDisplay.rotation }
            r == surfaceRotation
        }
        rule.waitForIdle()
    }

    private fun isTablet(): Boolean {
        var sw = 0
        rule.activityRule.scenario.onActivity { sw = it.resources.configuration.smallestScreenWidthDp }
        return sw >= 600
    }

    private fun landscape(): Boolean {
        var l = false
        rule.activityRule.scenario.onActivity { l = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        return l
    }

    private fun DpRect.contains(o: DpRect) = o.left >= left && o.right <= right && o.top >= top && o.bottom <= bottom
    private fun DpRect.centerX() = (left + right) / 2

    /** The switcher is in the bar tagged [bar] (or the screen's bottom bar), centred, and there is no navigation bar. */
    private fun assertDocked(where: String, bar: DpRect?) {
        rule.onAllNodesWithTag("dockedTabs").assertCountEquals(1)
        val tabs = rule.onNodeWithTag("dockedTabs").getUnclippedBoundsInRoot()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        // centred in its bar (Prepare's bar leaves a strip for the view buttons) or on the screen
        val frame = bar ?: root
        if (bar != null) assertTrue("$where: switcher $tabs is not in the bottom bar $bar", bar.contains(tabs))
        assertTrue("$where: switcher $tabs isn't centred in $frame", abs((tabs.centerX() - frame.centerX()).value) < 8)
        // one "Preview" label on screen: the switcher's, no navigation bar below it
        rule.onAllNodesWithText("Preview").assertCountEquals(1)
    }

    @Test
    fun tabsJoinTheBottomBarOnTabletsInLandscape() {
        rule.activityRule.scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].addSample(AppViewModel.Sample.CUBE) }
        rotate(UiAutomation.ROTATION_FREEZE_0, Surface.ROTATION_0)
        val tablet = isTablet()
        for ((freeze, rotation) in listOf(UiAutomation.ROTATION_FREEZE_0 to Surface.ROTATION_0, UiAutomation.ROTATION_FREEZE_90 to Surface.ROTATION_90)) {
            rotate(freeze, rotation)
            rule.onNodeWithText("Prepare").performClick()
            rule.waitForIdle()
            if (tablet && landscape()) {
                assertDocked("Prepare", rule.onNodeWithTag("actionBar").getUnclippedBoundsInRoot())
                rule.onNodeWithText("Preview").performClick()
                rule.waitForIdle()
                assertDocked("Preview", null)
                rule.onNodeWithText("Device").performClick()
                rule.waitForIdle()
                assertDocked("Device", null)
                // Add printer shares the bar with the switcher
                val tabs = rule.onNodeWithTag("dockedTabs").getUnclippedBoundsInRoot()
                val adds = rule.onAllNodesWithText("Add printer")
                val n = adds.fetchSemanticsNodes().size
                val bounds = (0 until n).map { adds[it].getUnclippedBoundsInRoot() }
                assertTrue(
                    "no Add printer button in the switcher's bar $tabs: $bounds",
                    bounds.any { it.top < tabs.bottom && it.bottom > tabs.top && it.left > tabs.right },
                )
            } else {
                rule.onAllNodes(hasTestTag("dockedTabs")).assertCountEquals(0)
                rule.onNodeWithText("Device").assertExists() // the navigation bar
            }
        }
    }
}
