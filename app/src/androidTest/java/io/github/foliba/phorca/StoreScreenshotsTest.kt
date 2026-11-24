package io.github.foliba.phorca

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.foliba.phorca.ui.AppViewModel
import io.github.foliba.phorca.ui.SliceUiState
import io.github.foliba.phorca.ui.Tab
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Google Play screenshots: Prepare, Preview, Fill bed and Setup on a Sovol SV06 with its own bed. Runs only when
 * asked for (instrumentation argument screenshots=true, see .github/workflows/screenshots.yml); the PNGs go to
 * /sdcard/Download/phorca-screenshots, taken with the system's screencap so they survive the app's uninstall.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val dir = "/sdcard/Download/phorca-screenshots"

    /** Runs a shell command and waits until it has finished (reading its output to the end). */
    private fun shell(cmd: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    /** A clean status bar: fixed time, full battery, full Wi-Fi, no notifications. Sent again before each
     *  shot, because System UI can drop demo mode when the app's window changes. */
    private fun demoMode() {
        shell("settings put global sysui_demo_allowed 1")
        shell("am broadcast -a com.android.systemui.demo -e command enter")
        shell("am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000")
        shell("am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false")
        shell("am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true -e mobile hide")
        shell("am broadcast -a com.android.systemui.demo -e command notifications -e visible false")
    }

    private fun shot(name: String, settleMs: Long = 2500) {
        rule.waitForIdle()
        demoMode()
        Thread.sleep(settleMs) // the 3D view renders on its own thread
        shell("screencap -p $dir/$name.png")
    }

    @Test
    fun storeScreenshots() {
        // Only in the screenshots workflow. A plain return, because AGP 9 counts a skipped assumption as a failure.
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        shell("mkdir -p $dir")
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
            vm.frameBed()
        }
        shot("1_prepare")

        // 2. Preview of the sliced plate
        rule.runOnUiThread { vm.startSlice() }
        rule.waitUntil(180_000) { vm.slice.value is SliceUiState.Ready }
        rule.runOnUiThread { vm.setTab(Tab.PREVIEW); vm.setView(-115.0, 32.0); vm.frameBed() }
        // the toolpaths are uploaded to the GPU after the result arrives; give the software renderer time
        shot("2_preview", settleMs = 8000)

        // 3. Fill bed: as many instances as fit, spaced for brim and skirt
        rule.runOnUiThread {
            vm.setTab(Tab.PREPARE)
            vm.clearPlate()
            vm.addSample(AppViewModel.Sample.CYLINDER)
            vm.setInstances(null)
            vm.setView(-100.0, 55.0)
            vm.frameBed()
        }
        shot("3_fill_bed")

        // 4. Setup
        rule.runOnUiThread { vm.setTab(Tab.DEVICE) }
        // long enough for the "Filled bed" message from scene 3 to go away
        shot("4_setup", settleMs = 7000)

        shell("am broadcast -a com.android.systemui.demo -e command exit")
    }
}
