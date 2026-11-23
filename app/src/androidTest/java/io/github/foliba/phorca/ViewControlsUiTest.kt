package io.github.foliba.phorca

import android.app.UiAutomation
import android.content.res.Configuration
import android.view.Surface
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.foliba.phorca.ui.AppViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The view buttons on the right have room and stay clear of the top bar, the object panel and the action bar,
 * in both orientations, on phones and tablets.
 */
@RunWith(AndroidJUnit4::class)
class ViewControlsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @After
    fun unfreeze() { automation.setRotation(UiAutomation.ROTATION_UNFREEZE) }

    private fun DpRect.overlaps(o: DpRect) = left < o.right && right > o.left && top < o.bottom && bottom > o.top

    private fun check() {
        val orientation = if (orientation() == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("objectPanel").getUnclippedBoundsInRoot()
        val bar = rule.onNodeWithTag("actionBar").getUnclippedBoundsInRoot()
        val topBar = rule.onNodeWithText("Printer").getUnclippedBoundsInRoot()
        for (name in listOf("3D view", "Top", "Front", "Fit", "Overhang")) {
            val b = rule.onNodeWithContentDescription(name).getUnclippedBoundsInRoot()
            val where = "$orientation ${root.right}x${root.bottom}: $name button $b"
            assertTrue("$where is squashed", b.bottom - b.top >= 32.dp && b.right - b.left >= 32.dp)
            assertTrue("$where overlaps the object panel $panel", !b.overlaps(panel))
            assertTrue("$where overlaps the action bar $bar", !b.overlaps(bar))
            assertTrue("$where overlaps the printer chip $topBar", !b.overlaps(topBar))
            assertTrue("$where is off screen", b.bottom <= root.bottom && b.right <= root.right)
        }
    }

    private fun orientation(): Int {
        var o = 0
        rule.activityRule.scenario.onActivity { o = it.resources.configuration.orientation }
        return o
    }

    /** Turns the device itself (Android 16+ ignores an app's orientation request on large screens). */
    private fun rotate(freeze: Int, surfaceRotation: Int) {
        automation.setRotation(freeze)
        rule.waitUntil(10_000) {
            var r = -1
            @Suppress("DEPRECATION")
            rule.activityRule.scenario.onActivity { r = it.windowManager.defaultDisplay.rotation }
            r == surfaceRotation
        }
        rule.waitForIdle()
        settle()
    }

    /**
     * Waits until the layout has stopped moving after a rotation: the screen has the new orientation's shape
     * and the panels and buttons are where they were on the previous look (slow emulators lag a few frames).
     */
    private fun settle() {
        var last: List<DpRect>? = null
        rule.waitUntil(10_000) {
            val now = runCatching {
                val root = rule.onRoot().getUnclippedBoundsInRoot()
                val landscape = orientation() == Configuration.ORIENTATION_LANDSCAPE
                if ((root.right > root.bottom) != landscape) return@runCatching null
                listOf(root, rule.onNodeWithTag("objectPanel").getUnclippedBoundsInRoot()) +
                    listOf("3D view", "Front", "Overhang").map { rule.onNodeWithContentDescription(it).getUnclippedBoundsInRoot() }
            }.getOrNull()
            val same = now != null && now == last
            last = now
            if (!same) Thread.sleep(100)
            same
        }
    }

    @Test
    fun viewButtonsStayClearOfThePanels() {
        rule.activityRule.scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].addSample(AppViewModel.Sample.CUBE) }
        // the natural orientation (portrait phone, landscape tablet), then turned by 90 degrees
        rotate(UiAutomation.ROTATION_FREEZE_0, Surface.ROTATION_0)
        check()
        rotate(UiAutomation.ROTATION_FREEZE_90, Surface.ROTATION_90)
        check()
    }
}
