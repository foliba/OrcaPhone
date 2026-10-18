package com.orcaphone.core

import com.orcaphone.core.config.BrimType
import com.orcaphone.core.config.GCodeFlavor
import com.orcaphone.core.config.InfillPattern
import com.orcaphone.core.orca.OrcaCatalog
import com.orcaphone.core.orca.OrcaKind
import com.orcaphone.core.orca.OrcaPresetMapper as M
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrcaMapperTest {
    private val catalog by lazy { OrcaCatalog.load() }

    @Test
    fun catalogLoadsAndResolvesSystemPresets() {
        val t0 = System.currentTimeMillis()
        val c = OrcaCatalog.load()
        println("catalog load ${System.currentTimeMillis() - t0} ms: ${c.names(OrcaKind.PRINTER).size} printers, " +
            "${c.names(OrcaKind.FILAMENT).size} filaments, ${c.names(OrcaKind.PROCESS).size} processes")
        val x1c = M.toPrinter(c.get(OrcaKind.PRINTER, "Bambu Lab X1 Carbon 0.4 nozzle")!!, "x", "X1C")
        assertEquals(256.0, x1c.bedWidth); assertEquals(256.0, x1c.bedDepth); assertEquals(250.0, x1c.maxHeight)
        assertTrue(x1c.notes.isNotEmpty(), "Bambu start G-code uses Orca scripting and should be flagged")
        val voron = M.toPrinter(c.get(OrcaKind.PRINTER, "Voron 2.4 350 0.4 nozzle")!!, "v", "Voron")
        assertEquals(350.0, voron.bedWidth); assertEquals(GCodeFlavor.KLIPPER, voron.flavor)
        val pla = M.toFilament(c.get(OrcaKind.FILAMENT, "Bambu PLA Basic @BBL X1C")!!, "f", "PLA")
        assertEquals(220, pla.nozzleTemp); assertEquals(55, pla.bedTemp); assertEquals(21.0, pla.maxVolumetricSpeed)
        assertEquals(0.0, pla.retractionLengthOverride) // "nil"
        val std = M.toProcess(c.get(OrcaKind.PROCESS, "0.20mm Standard @BBL X1C")!!, "p", "Std")
        assertEquals(0.2, std.layerHeight); assertEquals(15.0, std.sparseInfillDensity); assertEquals(200.0, std.outerWallSpeed)
        // every mapped printer/process converts without throwing
        for ((name, v) in c.entries(OrcaKind.PRINTER)) M.toPrinter(v, name, name).also { assertTrue(it.bedWidth > 0, name) }
        for ((name, v) in c.entries(OrcaKind.PROCESS)) M.toProcess(v, name, name).also { assertTrue(it.layerHeight > 0, name) }
        for ((name, v) in c.entries(OrcaKind.FILAMENT)) M.toFilament(v, name, name).also { assertTrue(it.diameter > 0, name) }
    }

    @Test
    fun cloudContentRoundTrip() {
        // A user process preset as OrcaSlicer syncs it: only differences + inherits, serialized values.
        val content = mapOf(
            "name" to "My fast PETG", "type" to "print", "inherits" to "0.20mm Standard @BBL X1C",
            "sparse_infill_density" to "25%", "sparse_infill_pattern" to "gyroid", "brim_type" to "outer_only",
            "wall_loops" to "3", "some_unmapped_key" to "keep me", "version" to "2.3.0.0",
        )
        val kind = M.kindOf(content)
        assertEquals(OrcaKind.PRINTER, M.kindOf(mapOf("inherits" to "Bambu Lab X1 Carbon 0.4 nozzle"), catalog))
        assertEquals(OrcaKind.FILAMENT, M.kindOf(mapOf("inherits" to "Bambu PLA Basic @BBL X1C"), catalog))
        assertEquals(OrcaKind.PRINTER, M.kindOf(mapOf("type" to "machine")))
        assertEquals(OrcaKind.PROCESS, M.kindOf(mapOf("name" to "x")))
        assertEquals(OrcaKind.PROCESS, kind)
        val raw = M.resolve(catalog, OrcaKind.PROCESS, content)
        val p = M.toProcess(raw, "id", "My fast PETG")
        assertEquals(25.0, p.sparseInfillDensity); assertEquals(InfillPattern.GYROID, p.sparseInfillPattern)
        assertEquals(BrimType.OUTER, p.brimType); assertEquals(3, p.wallLoops)
        assertEquals(200.0, p.outerWallSpeed) // inherited from the system parent

        // unchanged -> content identical apart from metadata
        val same = M.buildContent(OrcaKind.PROCESS, p.name, p, p, content)
        assertEquals(content - "updated_time", same)
        // change one setting -> only that key is touched, unmapped keys survive
        val edited = p.copy(layerHeight = 0.16)
        val out = M.buildContent(OrcaKind.PROCESS, p.name, edited, p, content)
        assertEquals("0.16", out["layer_height"])
        assertEquals("keep me", out["some_unmapped_key"])
        assertEquals("25%", out["sparse_infill_density"])
        assertFalse(out.containsKey("outer_wall_speed"))
    }

    @Test
    fun gcodeEscaping() {
        val gcode = "G28 ; home\nM104 S[nozzle_temperature_initial_layer]\nM117 \"hi\"\\done"
        val content = mapOf("type" to "printer", "machine_start_gcode" to M.escape(gcode), "printable_area" to "0x0,300x0,300x300,0x300")
        val printer = M.toPrinter(M.resolve(null, OrcaKind.PRINTER, content), "p", "P")
        assertEquals(gcode, printer.startGcode)
        assertEquals(300.0, printer.bedWidth)
        val back = M.buildContent(OrcaKind.PRINTER, "P", printer.copy(startGcode = gcode + "\nG1 Z5"), printer, content)
        assertEquals(gcode + "\nG1 Z5", M.unescape(back["machine_start_gcode"]!!))
        assertEquals("Bambu Lab", M.firstString("\"Bambu Lab\""))
        assertEquals("PLA", M.firstString("PLA;PETG"))
        assertFalse(M.usesUnsupportedScripting("M190 S[bed_temperature_initial_layer_single]\nM104 S{nozzle_temperature[0]}"))
        assertTrue(M.usesUnsupportedScripting("{if layer_z < 10}G1 Z10{endif}"))
        assertTrue(M.usesUnsupportedScripting("G1 Z{max_layer_z+10}"))
    }

    @Test
    fun settingIdMatchesOrcaSlicer() {
        // UUID v5 like boost::uuids::name_generator_sha1; deterministic and version 5
        val a = M.settingId("My preset", "user-1")
        assertEquals(a, M.settingId("My preset", "user-1"))
        assertTrue(a[14] == '5', a)
        assertNotNull(java.util.UUID.fromString(a))
        assertTrue(a != M.settingId("My preset", "user-2"))
    }
}
