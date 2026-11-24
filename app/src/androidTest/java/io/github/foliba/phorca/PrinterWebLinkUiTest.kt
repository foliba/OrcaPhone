package io.github.foliba.phorca

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.foliba.phorca.core.config.HostType
import io.github.foliba.phorca.core.config.PrinterConnection
import io.github.foliba.phorca.ui.AppViewModel
import io.github.foliba.phorca.ui.Tab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Setup: a configured printer's name opens its web interface (Mainsail on the web port, not Moonraker's 7125). */
@RunWith(AndroidJUnit4::class)
class PrinterWebLinkUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    @Test
    fun nameOpensTheWebInterface() {
        lateinit var vm: AppViewModel
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
        val c = PrinterConnection("weblink-test", "Web link test", HostType.MOONRAKER, "http://192.0.2.10:7125")
        vm.saveConnection(c)
        try {
            rule.runOnUiThread { vm.setTab(Tab.DEVICE) }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("printerWebLink").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Web link test").assertExists()
            // catch the browser intent instead of leaving the app
            val monitor = InstrumentationRegistry.getInstrumentation().addMonitor(
                android.content.IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("http") }, null, true,
            )
            rule.onNodeWithTag("printerWebLink").performClick()
            // a blocking monitor intercepts the start (counted in hits) without opening a browser
            rule.waitUntil(5_000) { monitor.hits > 0 }
            InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
            assertEquals(1, monitor.hits)
        } finally {
            vm.deleteConnection(c.id)
        }
    }
}
