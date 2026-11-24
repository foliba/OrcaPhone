package io.github.foliba.phorca

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextContains
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.foliba.phorca.ui.AppViewModel
import io.github.foliba.phorca.ui.SliceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** + → Calibration → Pressure advance tower: the tower is added and its G-code raises pressure advance layer by layer. */
@RunWith(AndroidJUnit4::class)
class PressureAdvanceUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** A hang fails with a stack trace instead of blocking the whole run until the CI job's time limit. */
    @get:Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(180)

    @Test
    fun towerFromTheAddMenu() {
        lateinit var vm: AppViewModel
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
        rule.runOnUiThread { vm.addSample(AppViewModel.Sample.CUBE) } // shows the bottom bar with its + button

        rule.onNodeWithContentDescription("Add model").performClick()
        rule.onNodeWithText("3DBenchy").performScrollTo()
        rule.onNodeWithText("Pressure advance tower").performScrollTo().performClick()
        // the Bowden preset (no typing: replacing text in a dialog's field hung the test tool on the Android 11 emulator)
        rule.onNodeWithText("Bowden").performClick()
        rule.onNodeWithTag("paEnd").assertTextContains("1")
        rule.onNodeWithText("Add tower").performClick()

        rule.waitUntil(10_000) { vm.objects.value.any { it.name.startsWith("PA tower") } }
        val tower = vm.objects.value.first { it.name.startsWith("PA tower") }
        assertEquals("PA tower 0–1 (+0.02/mm)", tower.name)
        assertEquals(50.0, tower.size.z, 1e-6) // (1.0 - 0) / 0.02 = 50 mm

        rule.runOnUiThread { vm.clearPlate() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Add model").assertExists()
    }

    @Test
    fun towerGcodeRaisesPressureAdvance() {
        lateinit var vm: AppViewModel
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
        rule.runOnUiThread { vm.addPaTower(io.github.foliba.phorca.core.gcode.PaTower(0.0, 0.01, 0.002)) }
        rule.waitUntil(10_000) { vm.objects.value.isNotEmpty() }
        rule.runOnUiThread { vm.startSlice() }
        rule.waitUntil(180_000) { vm.slice.value is SliceUiState.Ready || vm.slice.value is SliceUiState.Failed }
        val ready = vm.slice.value as SliceUiState.Ready
        val g = ready.result.gcodeFile.readText()
        val values = Regex("^(?:SET_PRESSURE_ADVANCE ADVANCE=|M900 K|M572 D0 S)([0-9.]+)", RegexOption.MULTILINE)
            .findAll(g).map { it.groupValues[1].toDouble() }.toList()
        assertTrue("one value per layer: ${values.size}", values.size >= 20)
        assertEquals(0.0, values.first(), 0.0)
        assertEquals(0.01, values.last(), 1e-9)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
    }
}
