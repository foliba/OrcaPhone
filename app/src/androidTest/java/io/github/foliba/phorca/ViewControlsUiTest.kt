package io.github.foliba.phorca

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.foliba.phorca.ui.AppViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The view buttons on the right stay between the top bar and the object panel, also in landscape. */
@RunWith(AndroidJUnit4::class)
class ViewControlsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun check(orientation: String) {
        val panelTop = rule.onNodeWithText("Calibration cube").getUnclippedBoundsInRoot().top
        val topBar = rule.onNodeWithText("Printer").getUnclippedBoundsInRoot().bottom
        for (name in listOf("3D view", "Top", "Front", "Fit", "Overhang")) {
            val b = rule.onNodeWithContentDescription(name).getUnclippedBoundsInRoot()
            assertTrue("$orientation: $name button ($b) reaches into the object panel (top $panelTop)", b.bottom <= panelTop)
            assertTrue("$orientation: $name button ($b) reaches into the top bar (bottom $topBar)", b.top >= topBar)
        }
    }

    private fun orientation(): Int {
        var o = 0
        rule.activityRule.scenario.onActivity { o = it.resources.configuration.orientation }
        return o
    }

    /** Checks the orientation the device starts in (portrait on phones, landscape on tablets), then the other one. */
    @Test
    fun viewButtonsStayClearOfThePanels() {
        rule.activityRule.scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].addSample(AppViewModel.Sample.CUBE) }
        rule.waitForIdle()
        val first = orientation()
        val landscapeFirst = first == Configuration.ORIENTATION_LANDSCAPE
        check(if (landscapeFirst) "landscape" else "portrait")
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscapeFirst) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        rule.waitUntil(10_000) { orientation() != first }
        rule.waitForIdle()
        check(if (landscapeFirst) "portrait" else "landscape")
    }
}
