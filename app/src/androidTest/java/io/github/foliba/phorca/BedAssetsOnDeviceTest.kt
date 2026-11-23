package io.github.foliba.phorca

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.foliba.phorca.core.config.PrinterProfile
import io.github.foliba.phorca.core.orca.OrcaCatalog
import io.github.foliba.phorca.gl.BedAssets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The baked-in OrcaSlicer bed graphics load on a device, SVG texture included. */
@RunWith(AndroidJUnit4::class)
class BedAssetsOnDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sovolSv06BedLoads() {
        val bed = BedAssets.load(context, "Sovol SV06", 220.0, 220.0)
        assertNotNull(bed)
        val mesh = bed!!.model!!
        assertTrue(mesh.triangleCount > 10)
        val tex = bed.texture!!
        assertEquals(tex.width, tex.height) // square bed, square texture
        // the SVG drew something: some pixels are not transparent
        var drawn = 0
        for (y in 0 until tex.height step 8) for (x in 0 until tex.width step 8) if ((tex.getPixel(x, y) ushr 24) != 0) drawn++
        assertTrue("texture is empty", drawn > 10)
    }

    @Test
    fun printersFindTheirModel() = runBlocking {
        val catalog by lazy { OrcaCatalog.load() }
        // stored on the profile (new), or found through the system preset of the same name (older profiles)
        assertEquals("Sovol SV06", BedAssets.modelName(PrinterProfile("a", "x", printerModel = "Sovol SV06")) { catalog })
        assertEquals("Sovol SV06", BedAssets.modelName(PrinterProfile("b", "Sovol SV06 0.4 nozzle")) { catalog })
        assertNull(BedAssets.modelName(PrinterProfile("c", "My own printer")) { catalog })
        assertNull(BedAssets.load(context, "No such printer", 220.0, 220.0))
    }
}
