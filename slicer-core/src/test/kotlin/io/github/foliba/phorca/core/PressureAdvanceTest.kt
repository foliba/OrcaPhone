package io.github.foliba.phorca.core

import io.github.foliba.phorca.core.config.BuiltinProfiles
import io.github.foliba.phorca.core.config.GCodeFlavor
import io.github.foliba.phorca.core.gcode.PHORCA_VERSION
import io.github.foliba.phorca.core.gcode.PaTower
import io.github.foliba.phorca.core.gcode.PressureAdvance
import io.github.foliba.phorca.core.math.Mat4
import io.github.foliba.phorca.core.mesh.Primitives
import io.github.foliba.phorca.core.orca.OrcaCatalog
import io.github.foliba.phorca.core.orca.OrcaKind
import io.github.foliba.phorca.core.orca.OrcaPresetMapper
import io.github.foliba.phorca.core.slice.Slicer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PressureAdvanceTest {
    private fun slice(
        flavor: GCodeFlavor, enable: Boolean, pa: Double = 0.035, tower: PaTower? = null, height: Double = 10.0,
    ): String {
        val printer = BuiltinProfiles.printers.first().copy(flavor = flavor)
        val filament = BuiltinProfiles.filaments.first().copy(enablePressureAdvance = enable, pressureAdvance = pa)
        val process = BuiltinProfiles.processes.first { it.id == "builtin-0.20" }
        val mesh = Primitives.box(20.0, 20.0, height).transformed(Mat4.translation(printer.bedWidth / 2 - 10, printer.bedDepth / 2 - 10, 0.0))
        val out = File.createTempFile("pressure", ".gcode")
        Slicer(printer, filament, process, paTower = tower).slice(listOf(mesh), out) { _, _ -> }
        return out.readText().also { out.delete() }
    }

    @Test
    fun commandPerFirmware() {
        assertEquals("SET_PRESSURE_ADVANCE ADVANCE=0.035", PressureAdvance.command(GCodeFlavor.KLIPPER, 0.035))
        assertEquals("M900 K0.035", PressureAdvance.command(GCodeFlavor.MARLIN, 0.035))
        assertEquals("M572 D0 S0.035", PressureAdvance.command(GCodeFlavor.REPRAP, 0.035))
    }

    @Test
    fun setOnlyWhenEnabled() {
        val on = slice(GCodeFlavor.KLIPPER, enable = true)
        val body = on.substringAfter("; EXECUTABLE_BLOCK_START")
        assertEquals(1, Regex("^SET_PRESSURE_ADVANCE ADVANCE=0.035$", RegexOption.MULTILINE).findAll(body).count())
        // after the start G-code (which may home and heat), before the first layer
        assertTrue(body.indexOf("SET_PRESSURE_ADVANCE") < body.indexOf(";LAYER_CHANGE"))
        assertTrue(on.contains("; enable_pressure_advance = 1"))
        val off = slice(GCodeFlavor.KLIPPER, enable = false)
        assertFalse(off.substringAfter("; EXECUTABLE_BLOCK_START").contains("SET_PRESSURE_ADVANCE"), "off: the firmware's own value applies")
        assertTrue(slice(GCodeFlavor.MARLIN, enable = true).contains("\nM900 K0.035\n"))
    }

    @Test
    fun towerRaisesTheValueWithHeight() {
        val tower = PaTower(0.0, 0.1, 0.002)
        assertEquals(50.0, tower.height(250.0))
        assertEquals(0.02, tower.valueAt(10.0), 1e-9)
        assertEquals(0.1, tower.valueAt(80.0)) // capped at the end value
        val g = slice(GCodeFlavor.KLIPPER, enable = true, tower = tower, height = 10.0)
        val values = Regex("^SET_PRESSURE_ADVANCE ADVANCE=([0-9.]+)", RegexOption.MULTILINE).findAll(g).map { it.groupValues[1].toDouble() }.toList()
        // the tower's start value replaces the filament's, then one value per layer above the first
        assertEquals(0.0, values.first())
        assertFalse(values.contains(0.035))
        assertTrue(values.size >= 45, "one command per layer: ${values.size}")
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a }, "values rise with the height")
        assertEquals(0.02, values.last(), 1e-3)
        // walls only: no sparse infill, no top surface
        assertFalse(g.contains(";TYPE:Sparse infill"))
        assertFalse(g.contains(";TYPE:Top surface"))
    }

    @Test
    fun readFromOrcaSlicerPresets() {
        val c = OrcaCatalog.load()
        val f = OrcaPresetMapper.toFilament(c.get(OrcaKind.FILAMENT, "AliZ PLA @P1-X1")!!, "f", "AliZ PLA")
        assertTrue(f.enablePressureAdvance)
        assertEquals(0.015, f.pressureAdvance)
        val generic = OrcaPresetMapper.toFilament(c.get(OrcaKind.FILAMENT, "Generic PLA @System")!!, "g", "Generic PLA")
        assertFalse(generic.enablePressureAdvance)
    }

    @Test
    fun headerVersionIsTheAppVersion() {
        val gradle = File("../app/build.gradle.kts").readText()
        val versionName = Regex("versionName = \"([^\"]+)\"").find(gradle)!!.groupValues[1]
        assertEquals(versionName, PHORCA_VERSION, "update PHORCA_VERSION in GCodeGenerator.kt")
    }
}
