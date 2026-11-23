package io.github.foliba.phorca

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.foliba.phorca.core.config.BrimType
import io.github.foliba.phorca.core.config.BuiltinProfiles
import io.github.foliba.phorca.core.config.InfillPattern
import io.github.foliba.phorca.core.mesh.Primitives
import io.github.foliba.phorca.core.plate.PlateObject
import io.github.foliba.phorca.core.preview.PreviewGeometry
import io.github.foliba.phorca.core.slice.Slicer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real slicing pipeline on Android (ART, ICU regex, Android file system). */
@RunWith(AndroidJUnit4::class)
class SlicingOnDeviceTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "test").apply { mkdirs() }

    @Test
    fun everyBuiltinPrinterSlicesASphere() {
        val mesh = Primitives.sphere(15.0)
        for (printer in BuiltinProfiles.printers) {
            val obj = PlateObject.create(1, "s", mesh, x = printer.bedWidth / 2, y = printer.bedDepth / 2)
            val out = File(dir, "${printer.id}.gcode")
            val process = BuiltinProfiles.processes.first { it.id == "builtin-0.20" }
                .copy(enableSupport = true, brimType = BrimType.OUTER, sparseInfillPattern = InfillPattern.GYROID)
            val res = Slicer(printer, BuiltinProfiles.filaments.first(), process).slice(listOf(obj.worldMesh()), out) { _, _ -> }
            val text = out.readText()
            assertTrue(printer.name, res.layers.size > 100)
            assertTrue(printer.name, text.contains(";LAYER_CHANGE") && !text.contains("[nozzle_temperature"))
            assertTrue(PreviewGeometry.build(res.layers) { tp, _ -> tp.speed }.instanceCount > 0)
        }
    }

    @Test
    fun treeSupports() {
        val printer = BuiltinProfiles.printers.first()
        val obj = PlateObject.create(1, "s", Primitives.sphere(20.0), x = 110.0, y = 110.0)
        for (type in listOf(io.github.foliba.phorca.core.config.SupportType.TREE, io.github.foliba.phorca.core.config.SupportType.ORGANIC)) {
            val process = BuiltinProfiles.processes.first { it.id == "builtin-0.20" }.copy(enableSupport = true, supportType = type)
            val res = Slicer(printer, BuiltinProfiles.filaments.first(), process).slice(listOf(obj.worldMesh()), File(dir, "tree-$type.gcode")) { _, _ -> }
            assertTrue(type.name, res.layers[0].paths.any { it.feature == io.github.foliba.phorca.core.slice.Feature.SUPPORT })
        }
    }

    @Test
    fun vaseMode() {
        val printer = BuiltinProfiles.printers.first()
        val obj = PlateObject.create(1, "c", Primitives.cylinder(20.0, 20.0), x = 110.0, y = 110.0)
        val res = Slicer(printer, BuiltinProfiles.filaments.first(), BuiltinProfiles.processes.first { it.spiralMode })
            .slice(listOf(obj.worldMesh()), File(dir, "vase.gcode")) { _, _ -> }
        assertTrue(res.layers.size > 50)
    }
}
