package com.orcaphone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orcaphone.core.cut.AutoCut
import com.orcaphone.core.math.Vec3
import com.orcaphone.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Auto cut end to end: chip on a too-big part, pick the face to lie on, cut, one part per bed. */
@RunWith(AndroidJUnit4::class)
class AutoCutUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun cutsTooBigPartOntoBeds() {
        lateinit var vm: AppViewModel
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
        val printer = vm.printers.value.working
        val usable = AutoCut.usable(printer.bedWidth, printer.bedDepth, printer.maxHeight)
        // A 20 mm cube stretched to about 1.6 x 1.3 usable bed widths, 30 mm high.
        rule.runOnUiThread {
            vm.addSample(AppViewModel.Sample.CUBE)
            vm.setSelectedScale(Vec3(usable.x * 1.6 / 20, usable.y * 1.3 / 20, 1.5))
        }
        rule.waitForIdle()

        rule.onNodeWithText("Too big for the bed · Auto cut").assertIsDisplayed().performClick()
        rule.waitUntil(20_000) { vm.autoCut.value != null }
        rule.onNodeWithText("Which side should lie on the bed?").assertIsDisplayed()

        // Choose the bottom face (what a user does by tapping it).
        val st = vm.autoCut.value!!
        val obj = vm.objects.value.first { it.id == st.objectId }
        val bottom = (0 until obj.mesh.triangleCount).first { t ->
            obj.worldMatrix.transformDir(obj.mesh.faceNormal(t)).normalized().z < -0.9
        }
        rule.runOnUiThread { vm.autoCutPick(bottom) }
        rule.waitForIdle()
        val plan = vm.autoCut.value!!.plan!!
        assertEquals(listOf(2, 2, 1), plan.counts)
        rule.onNodeWithText("Lies on this face").assertIsDisplayed()

        rule.onNode(hasText("Cut into ${plan.parts}")).assertIsEnabled().performClick()
        rule.waitUntil(120_000) { vm.autoCut.value == null }

        val beds = vm.beds.value
        assertEquals(plan.parts, beds.size)
        for (bed in beds) {
            assertEquals(1, bed.objects.size)
            val b = bed.objects[0].worldBounds
            assertTrue("part ${bed.objects[0].name} doesn't keep the margin: $b",
                b.minX >= AutoCut.MARGIN - 0.01 && b.maxX <= printer.bedWidth - AutoCut.MARGIN + 0.01 &&
                    b.minY >= AutoCut.MARGIN - 0.01 && b.maxY <= printer.bedDepth - AutoCut.MARGIN + 0.01)
        }
    }
}
