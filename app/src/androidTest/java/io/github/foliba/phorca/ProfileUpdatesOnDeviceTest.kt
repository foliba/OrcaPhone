package io.github.foliba.phorca

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.foliba.phorca.core.orca.OrcaCatalog
import io.github.foliba.phorca.core.orca.OrcaKind
import io.github.foliba.phorca.core.orca.ProfilePack
import io.github.foliba.phorca.data.ProfileUpdate
import io.github.foliba.phorca.data.ProfileUpdates
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Downloading and installing an OrcaSlicer profile pack; the Setup card. */
@RunWith(AndroidJUnit4::class)
class ProfileUpdatesOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir get() = File(context.filesDir, "orca_profiles")

    @After
    fun cleanUp() { dir.deleteRecursively() }

    private fun pack(version: String): ByteArray {
        val out = ByteArrayOutputStream()
        val catalog = OrcaCatalog::class.java.getResourceAsStream(OrcaCatalog.RESOURCE)!!.readBytes()
        ZipOutputStream(out).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put(ProfilePack.MANIFEST, """{"format": 1, "orcaVersion": "$version"}""".toByteArray())
            put(ProfilePack.CATALOG, catalog)
            put("beds/index.json", context.assets.open("beds/index.json").readBytes())
        }
        return out.toByteArray()
    }

    @Test
    fun aDownloadedPackReplacesTheBuiltInProfiles() = runBlocking {
        dir.deleteRecursively()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(pack("9.9.9"))))
        server.start()
        try {
            val updates = ProfileUpdates(context)
            assertEquals(ProfilePack.BUNDLED_VERSION, updates.version.value)
            assertNull(updates.activeDir())
            var last = 0f
            updates.install(ProfileUpdate("9.9.9", server.url("/pack.zip").toString(), 0)) { last = it }
            assertEquals("9.9.9", updates.version.value)
            assertEquals("9.9.9", updates.activeDir()!!.name)
            assertTrue(updates.loadCatalog().entries(OrcaKind.PRINTER).size > 1000)
            assertTrue(last > 0.99f)
            // a fresh start (new instance) keeps using it
            assertEquals("9.9.9", ProfileUpdates(context).version.value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aBrokenDownloadKeepsTheProfilesInUse() = runBlocking {
        dir.deleteRecursively()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not a zip"))
        server.start()
        try {
            val updates = ProfileUpdates(context)
            val failed = runCatching { updates.install(ProfileUpdate("9.9.9", server.url("/pack.zip").toString(), 0)) }.isFailure
            assertTrue(failed)
            assertEquals(ProfilePack.BUNDLED_VERSION, updates.version.value)
            assertNull(updates.activeDir())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun setupCardShowsTheVersionAndTheSwitch() {
        rule.onNodeWithText("Setup").performClick()
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("OrcaSlicer profiles"))
        rule.onNodeWithText("OrcaSlicer profiles").assertIsDisplayed()
        rule.onNodeWithText("OrcaSlicer ${ProfilePack.BUNDLED_VERSION} (built in)", substring = true).assertIsDisplayed()
        val prefs = context.getSharedPreferences("profiles", android.content.Context.MODE_PRIVATE)
        val before = prefs.getBoolean("check_on_startup", true)
        rule.onNodeWithTag("profileCheckSwitch").performClick()
        rule.waitForIdle()
        assertEquals(!before, prefs.getBoolean("check_on_startup", true))
        rule.onNodeWithTag("profileCheckSwitch").performClick() // back as it was
    }
}
