package io.github.foliba.phorca.core

import io.github.foliba.phorca.core.mesh.BedModelCodec
import io.github.foliba.phorca.core.orca.OrcaCatalog
import io.github.foliba.phorca.core.orca.OrcaKind
import io.github.foliba.phorca.core.orca.OrcaPresetMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The bed models baked into the app (app/src/main/assets/beds, made by tools/gen_bed_assets.py). */
class BedModelTest {
    private val beds = File("../app/src/main/assets/beds")

    @Test
    fun sovolSv06BedDecodes() {
        val mesh = BedModelCodec.decode(File(beds, "Sovol/sovol_sv06_buildplate_model.pbed").readBytes())
        assertTrue(mesh.triangleCount > 10)
        val b = mesh.bounds()
        // a plate somewhat larger than the 220 x 220 mm printable area, a few mm thick
        assertTrue(b.sizeX in 220.0..300.0 && b.sizeY in 220.0..300.0, "plate ${b.sizeX} x ${b.sizeY}")
        assertTrue(b.sizeZ < 20.0, "plate thickness ${b.sizeZ}")
    }

    @Test
    fun everyIndexedBedModelDecodes() {
        val index = File(beds, "index.json").readText()
        val files = Regex(""""model": "([^"]+)"""").findAll(index).map { it.groupValues[1] }.toSet()
        assertTrue(files.size > 200, "only ${files.size} bed models")
        for (f in files) {
            val mesh = BedModelCodec.decode(File(beds, f).readBytes())
            assertTrue(mesh.triangleCount > 0, f)
        }
        // every texture the index names is there
        for (t in Regex(""""texture": "([^"]+)"""").findAll(index).map { it.groupValues[1] }) assertTrue(File(beds, t).exists(), t)
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<java.io.IOException> { BedModelCodec.decode(ByteArray(40)) }
    }

    @Test
    fun printersKnowTheirModel() {
        val cat = OrcaCatalog.load()
        val raw = cat.get(OrcaKind.PRINTER, "Sovol SV06 0.4 nozzle")!!
        assertEquals("Sovol SV06", OrcaPresetMapper.toPrinter(raw, "x", "Sovol SV06 0.4 nozzle").printerModel)
    }
}
