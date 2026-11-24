package io.github.foliba.phorca.core

import io.github.foliba.phorca.core.config.BuiltinProfiles
import io.github.foliba.phorca.core.config.GCodeFlavor
import io.github.foliba.phorca.core.gcode.Placeholders
import io.github.foliba.phorca.core.math.Mat4
import io.github.foliba.phorca.core.mesh.Primitives
import io.github.foliba.phorca.core.slice.Slicer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceholdersTest {
    private val v = mapOf(
        "layer_num" to "4", "layer_z" to "1.2", "max_layer_z" to "20", "printable_height" to "250",
        "nozzle_temperature" to "215", "bed_temperature" to "60,65", "filament_type" to "PETG",
        "first_layer_print_min" to "80,90", "first_layer_print_max" to "140,130",
    )

    private fun a(s: String) = Placeholders.apply(s, v)

    @Test
    fun simpleValues() {
        assertEquals("M104 S215", a("M104 S[nozzle_temperature]"))
        assertEquals("M104 S215", a("M104 S{nozzle_temperature}"))
        assertEquals("M140 S65", a("M140 S{bed_temperature[1]}"))
        assertEquals("M140 S60", a("M140 S[bed_temperature[0]]"))
        assertEquals("; PETG", a("; {filament_type}"))
    }

    @Test
    fun expressions() {
        // the line from OrcaSlicer's Klipper profiles that reached the printer unevaluated
        assertEquals("SET_PRINT_STATS_INFO CURRENT_LAYER=5", a("SET_PRINT_STATS_INFO CURRENT_LAYER={layer_num + 1}"))
        assertEquals("5", a("{layer_num+1}"))
        assertEquals("1.6", a("{layer_z + 0.4}"))
        assertEquals("G1 Z70", a("G1 Z{min(max_layer_z+50, printable_height)}"))
        assertEquals("G1 Z250", a("G1 Z{min(max_layer_z+500, printable_height)}"))
        assertEquals("95", a("{first_layer_print_min[0] + (first_layer_print_max[0] - first_layer_print_min[0]) / 4}"))
        assertEquals("210", a("{nozzle_temperature - 5}"))
        assertEquals("1", a("{layer_num % 3}"))
        assertEquals("-4", a("{-layer_num}"))
    }

    @Test
    fun leavesWhatItCannotEvaluate() {
        for (s in listOf(
            "{if layer_num == 3}M106 S255{endif}", "{unknown_var + 1}", "{filament_type + 1}",
            "{(layer_num % 2 == 0 ? 100.0 : 220.0)}", "{e_retracted[1] = 1.5}", "{layer_num / 0}",
        )) assertEquals(s, a(s), s)
        assertEquals(listOf("{if layer_num == 3}", "{endif}"), Placeholders.unresolved(a("{if layer_num == 3}M106 S255{endif}")))
        assertTrue(Placeholders.isSupported("layer_num + 1"))
        assertFalse(Placeholders.isSupported("if layer_z < 10"))
    }

    @Test
    fun layerChangeGcodeIsEvaluatedPerLayer() {
        val printer = BuiltinProfiles.printers.first().copy(
            flavor = GCodeFlavor.KLIPPER, layerChangeGcode = "SET_PRINT_STATS_INFO CURRENT_LAYER={layer_num + 1}",
        )
        val mesh = Primitives.box(10.0, 10.0, 1.0).transformed(Mat4.translation(100.0, 100.0, 0.0))
        val out = File.createTempFile("placeholders", ".gcode")
        val r = Slicer(printer, BuiltinProfiles.filaments.first(), BuiltinProfiles.processes.first { it.id == "builtin-0.20" })
            .slice(listOf(mesh), out) { _, _ -> }
        val g = out.readText().also { out.delete() }
        assertFalse(g.contains("{layer_num"), "placeholder left in the G-code")
        assertTrue(g.contains("SET_PRINT_STATS_INFO CURRENT_LAYER=5\n"))
        assertTrue(r.warnings.none { "scripting" in it }, r.warnings.toString())
    }
}
