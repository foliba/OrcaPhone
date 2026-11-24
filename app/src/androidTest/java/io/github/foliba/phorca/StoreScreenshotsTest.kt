package io.github.foliba.phorca

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.foliba.phorca.core.math.Vec3
import io.github.foliba.phorca.ui.AppViewModel
import io.github.foliba.phorca.ui.SliceUiState
import io.github.foliba.phorca.ui.Tab
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Google Play screenshots: Prepare, Preview, Auto cut and Setup on a Sovol SV06 with its own bed. Runs only when
 * asked for (instrumentation argument screenshots=true, see .github/workflows/screenshots.yml); the PNGs go to
 * /sdcard/Download/phorca-screenshots, taken with the system's screencap so they survive the app's uninstall.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val dir = "/sdcard/Download/phorca-screenshots"

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1500) // the 3D view renders on its own thread
        shell("screencap -p $dir/$name.png")
        Thread.sleep(500)
    }

    @Test
    fun storeScreenshots() {
        // Only in the screenshots workflow. A plain return, because AGP 9 counts a skipped assumption as a failure.
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        shell("mkdir -p $dir")
        // a clean status bar: fixed time, full battery, no notifications
        shell("settings put global sysui_demo_allowed 1")
        shell("am broadcast -a com.android.systemui.demo -e command enter")
        shell("am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000")
        shell("am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false")
        shell("am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile hide")
        shell("am broadcast -a com.android.systemui.demo -e command notifications -e visible false")

        lateinit var vm: AppViewModel
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
        runBlocking { vm.cloud.addFromLibrary("Sovol SV06 0.4 nozzle") }
        rule.waitUntil(20_000) { vm.bedGraphics.value != null }

        // 1. Prepare: a few models, one selected
        rule.runOnUiThread {
            vm.addSample(AppViewModel.Sample.CUBE)
            vm.setInstances(4)
            vm.addSample(AppViewModel.Sample.CYLINDER)
            vm.addSample(AppViewModel.Sample.SPHERE)
            vm.setView(-115.0, 32.0)
            vm.frameContent()
        }
        shot("1_prepare")

        // 2. Preview of the sliced plate
        rule.runOnUiThread { vm.startSlice() }
        rule.waitUntil(180_000) { vm.slice.value is SliceUiState.Ready }
        rule.runOnUiThread { vm.setTab(Tab.PREVIEW) }
        Thread.sleep(1500)
        shot("2_preview")

        // 3. Auto cut: a part too big for the bed, its flat faces coloured to pick the one to lie on
        rule.runOnUiThread {
            vm.setTab(Tab.PREPARE)
            vm.clearPlate()
            vm.addSample(AppViewModel.Sample.CUBE)
            vm.setSelectedScale(Vec3(14.0, 6.0, 4.0))
        }
        rule.waitForIdle()
        rule.runOnUiThread { vm.startAutoCut() }
        rule.waitUntil(20_000) { vm.autoCut.value != null }
        rule.runOnUiThread { vm.setView(-120.0, 30.0); vm.frameContent() }
        shot("3_autocut")
        rule.runOnUiThread { vm.cancelAutoCut() }

        // 4. Setup
        rule.runOnUiThread { vm.setTab(Tab.DEVICE) }
        shot("4_setup")

        shell("am broadcast -a com.android.systemui.demo -e command exit")
    }
}
