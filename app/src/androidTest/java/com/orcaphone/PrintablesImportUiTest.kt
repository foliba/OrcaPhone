package com.orcaphone

import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.orcaphone.ui.AppViewModel
import com.orcaphone.ui.PendingPlacement
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Drives the real app: paste a Printables link, pick the files and import them.
 * Guards against the Import bar being laid out off-screen. Skipped when Printables can't be reached.
 */
@RunWith(AndroidJUnit4::class)
class PrintablesImportUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val link = "https://www.printables.com/model/450473-sovol-sv06-pi-cam-x-axis-mount"

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    /** Fails unless the node lies completely inside the part of the screen not covered by system bars. */
    private fun assertFullyOnScreen(node: SemanticsNodeInteraction, what: String) {
        val n = node.fetchSemanticsNode()
        val top = n.layoutInfo.coordinates.positionOnScreen().y
        val bottom = top + n.size.height
        val decor = rule.activity.window.decorView
        val loc = IntArray(2).also { decor.getLocationOnScreen(it) }
        val bars = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())
        val visibleTop = loc[1] + (bars?.top ?: 0)
        val visibleBottom = loc[1] + decor.height - (bars?.bottom ?: 0)
        assertTrue(
            "$what is off-screen: y=$top..$bottom, visible area y=$visibleTop..$visibleBottom",
            top >= visibleTop - 1 && bottom <= visibleBottom + 1,
        )
    }

    @Test
    fun importModelFromLink() {
        lateinit var vm: AppViewModel
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }

        rule.onNodeWithText("Printables link").performClick()
        rule.onNodeWithText("Model link").performTextInput(link)
        rule.onNodeWithText("Look up").performClick()

        rule.waitUntil(60_000) {
            exists("Import") || exists("Couldn't import this model") || exists("No STL files in this model")
        }
        if (exists("Couldn't import this model")) {
            val offline = exists("Couldn't reach Printables", substring = true) || exists("blocking requests", substring = true)
            assumeTrue("Printables not reachable from this device", !offline)
        }
        assertTrue("Model has no importable files", !exists("No STL files in this model"))
        assertTrue("Lookup failed", !exists("Couldn't import this model"))

        val import = rule.onNodeWithText("Import")
        import.assertIsDisplayed()
        assertFullyOnScreen(import, "Import button")

        if (exists("Select all", substring = true)) {
            import.assertIsNotEnabled()
            rule.onNodeWithText("Select all", substring = true).performClick()
        }
        import.assertIsEnabled()
        import.performClick()

        rule.waitUntil(180_000) { vm.remote.value == null && (vm.pending.value != null || vm.objects.value.isNotEmpty()) }
        when (vm.pending.value?.step) {
            PendingPlacement.Step.OVERSIZE -> rule.onNodeWithText("Scale to fit").performClick()
            PendingPlacement.Step.REPLACE_OR_ADD -> rule.onNodeWithText("Add to plate").performClick()
            null -> Unit
        }
        rule.waitUntil(10_000) { vm.objects.value.isNotEmpty() }
        val p = vm.printers.value.working
        val all = vm.beds.value.flatMap { it.objects }
        assertTrue("Nothing imported", all.isNotEmpty())
        assertTrue("Imported objects outside the bed", all.all { it.isInside(p.bedWidth, p.bedDepth, p.maxHeight) })
    }
}
