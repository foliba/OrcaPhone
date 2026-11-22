package com.orcaphone

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orcaphone.core.plate.Arranger
import com.orcaphone.core.plate.PlateObject
import com.orcaphone.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Instances: fill the bed, ask for too many, edit one and all follow, one row with a count in the model list. */
@RunWith(AndroidJUnit4::class)
class InstancesUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var vm: AppViewModel

    private fun assertSpaced(objs: List<PlateObject>, spacing: Double) {
        val p = vm.printers.value.working
        for (o in objs) assertTrue("${o.id} off the bed", o.isInside(p.bedWidth, p.bedDepth, p.maxHeight))
        for (i in objs.indices) for (j in i + 1 until objs.size) {
            val a = objs[i].worldBounds; val b = objs[j].worldBounds
            val gap = maxOf(b.minX - a.maxX, a.minX - b.maxX, b.minY - a.maxY, a.minY - b.maxY)
            assertTrue("instances ${objs[i].id} and ${objs[j].id} are $gap mm apart", gap >= spacing - 1e-6)
        }
    }

    @Test
    fun fillBedCountAndGroupEdits() {
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
        rule.runOnUiThread { vm.addSample(AppViewModel.Sample.CUBE) }
        rule.waitForIdle()
        val spacing = Arranger.instanceSpacingFor(vm.processes.value.working)

        // Fill bed: as many as fit, on this bed only
        rule.onNodeWithText("Instances").performClick()
        val max = vm.maxInstances()
        assertTrue("only $max fit", max > 4)
        rule.onNodeWithText("Up to $max fit on bed 1", substring = true).assertIsDisplayed()
        rule.onNode(hasText("Fill bed ($max)")).performClick()
        rule.waitForIdle()
        assertEquals(1, vm.beds.value.size)
        assertEquals(max, vm.objects.value.size)
        assertEquals(1, vm.objects.value.map { it.group }.distinct().size)
        assertSpaced(vm.objects.value, spacing)

        // Asking for more than fits places the maximum and says so
        rule.onNodeWithText("Instances").performClick()
        rule.onNodeWithTag("instanceCount").performTextReplacement("999")
        rule.onNodeWithText("Only $max fit on bed 1.").assertIsDisplayed()
        rule.onNodeWithText("Place $max").performClick()
        rule.waitForIdle()
        assertEquals(1, vm.beds.value.size)
        assertEquals(max, vm.objects.value.size)

        // A smaller count
        rule.onNodeWithText("Instances").performClick()
        rule.onNodeWithTag("instanceCount").performTextReplacement("4")
        rule.onNodeWithText("Place 4").performClick()
        rule.waitForIdle()
        assertEquals(4, vm.objects.value.size)
        assertSpaced(vm.objects.value, spacing)

        // Scaling one instance scales all of them, and they are re-spaced
        rule.runOnUiThread { vm.setSelectedSize(0, 40.0); vm.rotateSelected('z', 30.0) }
        rule.waitForIdle()
        val objs = vm.objects.value
        val ref = objs.first { it.id == vm.selected.value }
        for (o in objs) {
            assertEquals(ref.size.x, o.size.x, 1e-6)
            assertEquals(ref.size.z, o.size.z, 1e-6)
        }
        assertTrue(ref.size.z > 39.0)
        assertSpaced(objs, 0.0)

        // The model list shows the model once, with its instance count
        rule.onNodeWithContentDescription("Models").performClick()
        rule.onNodeWithText("Models on bed 1").assertIsDisplayed()
        rule.onNodeWithTag("instances_Calibration cube", useUnmergedTree = true).assertIsDisplayed()
            .assert(hasText("×4"))
    }
}
