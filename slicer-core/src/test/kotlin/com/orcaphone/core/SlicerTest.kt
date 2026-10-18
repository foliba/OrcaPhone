package com.orcaphone.core

import com.orcaphone.core.config.BrimType
import com.orcaphone.core.config.BuiltinProfiles
import com.orcaphone.core.config.InfillPattern
import com.orcaphone.core.math.Mat4
import com.orcaphone.core.mesh.Mesh
import com.orcaphone.core.mesh.MeshLoader
import com.orcaphone.core.mesh.Primitives
import com.orcaphone.core.plate.Arranger
import com.orcaphone.core.plate.PlateObject
import com.orcaphone.core.preview.PreviewGeometry
import com.orcaphone.core.slice.Feature
import com.orcaphone.core.slice.Slicer
import com.orcaphone.core.gcode.NumFmt
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlicerTest {
    private val printer = BuiltinProfiles.printers.first { it.id == "builtin-ender3v3se" }
    private val filament = BuiltinProfiles.filaments.first()
    private val process = BuiltinProfiles.processes.first { it.id == "builtin-0.20" }
    private val outDir = File("build/test-out").apply { mkdirs() }

    private fun place(mesh: Mesh, x: Double = 110.0, y: Double = 110.0) =
        PlateObject.create(1, "m", mesh, x = x, y = y)

    private fun gcodeChecks(gcode: String) {
        // every coordinate must use '.' decimals and stay on the bed
        val xs = Regex(" X(-?[0-9.]+)").findAll(gcode).map { it.groupValues[1].toDouble() }.toList()
        val ys = Regex(" Y(-?[0-9.]+)").findAll(gcode).map { it.groupValues[1].toDouble() }.toList()
        assertTrue(xs.isNotEmpty())
        assertTrue(xs.all { it >= -3 && it <= 221 }, "X out of range: ${xs.minOrNull()}..${xs.maxOrNull()}")
        assertTrue(ys.all { it >= -3 && it <= 221 }, "Y out of range")
        assertTrue(!gcode.contains(","), "Locale comma found")
        assertTrue(!gcode.contains("NaN") && !gcode.contains("Infinity"))
    }

    @Test
    fun numberFormatting() {
        assertEquals("0.05", NumFmt.format(0.05, 3))
        assertEquals("-1.2", NumFmt.format(-1.2, 3))
        assertEquals("10", NumFmt.format(10.0004, 3))
        assertEquals("0", NumFmt.format(-0.0001, 3))
        assertEquals("123.457", NumFmt.format(123.4567, 3))
        assertEquals("1h 1m 5s", NumFmt.duration(3665.0))
    }

    @Test
    fun calibrationCube() {
        val obj = place(Primitives.box(20.0, 20.0, 20.0))
        val out = File(outDir, "cube.gcode")
        val t0 = System.currentTimeMillis()
        val res = Slicer(printer, filament, process).slice(listOf(obj.worldMesh()), out) { _, _ -> }
        println("cube: ${System.currentTimeMillis() - t0} ms, layers=${res.layers.size}, time=${NumFmt.duration(res.stats.timeSeconds)}, filament=${res.stats.filamentGrams} g")
        assertEquals(100, res.layers.size)
        val gcode = out.readText()
        gcodeChecks(gcode)
        // 20mm cube with 15% infill ~ 5-8 g of PLA
        assertTrue(res.stats.filamentGrams in 3.0..10.0, "grams=${res.stats.filamentGrams}")
        assertTrue(res.stats.timeSeconds in 300.0..3600.0, "time=${res.stats.timeSeconds}")
        val mid = res.layers[50].paths.map { it.feature }.toSet()
        assertTrue(Feature.OUTER_WALL in mid && Feature.INNER_WALL in mid && Feature.SPARSE_INFILL in mid, "mid=$mid")
        assertTrue(res.layers[0].paths.any { it.feature == Feature.BOTTOM_SURFACE })
        assertTrue(res.layers[99].paths.any { it.feature == Feature.TOP_SURFACE })
        assertTrue(res.layers[0].paths.any { it.feature == Feature.SKIRT })
        // outer wall stays inside the cube footprint (100..120)
        for (p in res.layers[50].paths.filter { it.feature == Feature.OUTER_WALL }) {
            for (i in 0 until p.pointCount) {
                assertTrue(p.points[i * 2] in 100.0..120.0 && p.points[i * 2 + 1] in 100.0..120.0)
            }
        }
        val preview = PreviewGeometry.build(res.layers) { tp, _ -> tp.speed }
        assertTrue(preview.instanceCount > 1000)
        assertEquals(preview.instanceCount * PreviewGeometry.INSTANCE_STRIDE, preview.instances.limit())
    }

    @Test
    fun allInfillPatterns() {
        val obj = place(Primitives.cylinder(15.0, 6.0))
        for (pat in InfillPattern.entries) {
            val out = File(outDir, "cyl-$pat.gcode")
            val res = Slicer(printer, filament, process.copy(sparseInfillPattern = pat, sparseInfillDensity = 20.0, topShellLayers = 2, bottomShellLayers = 2))
                .slice(listOf(obj.worldMesh()), out) { _, _ -> }
            val sparse = res.layers[15].paths.filter { it.feature == Feature.SPARSE_INFILL }
            println("$pat: sparse paths=${sparse.size}, len=${sparse.sumOf { it.length() }.toInt()} mm")
            assertTrue(sparse.isNotEmpty(), "$pat produced no infill")
            gcodeChecks(out.readText())
        }
    }

    @Test
    fun sphereWithSupportAndBrim() {
        val obj = place(Primitives.sphere(15.0))
        val out = File(outDir, "sphere.gcode")
        val t0 = System.currentTimeMillis()
        val res = Slicer(printer, filament, process.copy(enableSupport = true, brimType = BrimType.OUTER, ironing = true))
            .slice(listOf(obj.worldMesh()), out) { _, _ -> }
        println("sphere: ${System.currentTimeMillis() - t0} ms, ${NumFmt.duration(res.stats.timeSeconds)} ${res.stats.filamentGrams} g")
        assertTrue(res.layers.any { l -> l.paths.any { it.feature == Feature.SUPPORT } }, "no support")
        assertTrue(res.layers[0].paths.any { it.feature == Feature.BRIM })
        gcodeChecks(out.readText())
    }

    @Test
    fun hollowTubeHasHoleWalls() {
        val outer = Primitives.cylinder(10.0, 10.0)
        val inner = Primitives.cylinder(5.0, 10.0).transformed(Mat4.scale(1.0, 1.0, 1.0))
        // inner cylinder with flipped winding acts as a hole
        val flipped = Mesh(inner.vertices, IntArray(inner.indices.size) { i -> inner.indices[i - i % 3 + (2 - i % 3)] })
        val tube = Mesh.merge(listOf(outer, flipped))
        val res = Slicer(printer, filament, process).slice(listOf(place(tube).worldMesh()), File(outDir, "tube.gcode")) { _, _ -> }
        val walls = res.layers[20].paths.filter { it.feature == Feature.OUTER_WALL }
        assertEquals(2, walls.size, "expected outer + hole outer walls")
    }

    @Test
    fun vaseMode() {
        val obj = place(Primitives.cylinder(20.0, 30.0))
        val vase = BuiltinProfiles.processes.first { it.spiralMode }
        val out = File(outDir, "vase.gcode")
        val res = Slicer(printer, filament, vase).slice(listOf(obj.worldMesh()), out) { _, _ -> }
        assertTrue(res.layers[50].paths.single().spiral)
        gcodeChecks(out.readText())
    }

    @Test
    fun loadersRoundTrip() {
        val box = Primitives.box(10.0, 20.0, 30.0)
        // binary STL
        val bb = ByteBuffer.allocate(84 + box.triangleCount * 50).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(80); bb.putInt(box.triangleCount)
        for (t in 0 until box.triangleCount) {
            repeat(3) { bb.putFloat(0f) }
            for (k in 0 until 3) { val v = box.indices[t * 3 + k] * 3; bb.putFloat(box.vertices[v]); bb.putFloat(box.vertices[v + 1]); bb.putFloat(box.vertices[v + 2]) }
            bb.putShort(0)
        }
        val stl = MeshLoader.load("box.stl", bb.array().inputStream()).single()
        assertEquals(12, stl.mesh.triangleCount)
        assertEquals(8, stl.mesh.vertexCount)
        assertTrue(abs(stl.mesh.volume() - 6000.0) < 1e-3)
        // ASCII STL
        val ascii = buildString {
            append("solid t\n")
            for (t in 0 until box.triangleCount) {
                append("facet normal 0 0 0\nouter loop\n")
                for (k in 0 until 3) { val v = box.indices[t * 3 + k] * 3; append("vertex ${box.vertices[v]} ${box.vertices[v + 1]} ${box.vertices[v + 2]}\n") }
                append("endloop\nendfacet\n")
            }
            append("endsolid t\n")
        }
        assertEquals(12, MeshLoader.load("a.stl", ascii.byteInputStream()).single().mesh.triangleCount)
        // OBJ with quads
        val obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nv 0 0 1\nf 1 2 3 4\nf 1/1/1 2/2/2 5/5/5\n"
        assertEquals(3, MeshLoader.load("q.obj", obj.byteInputStream()).single().mesh.triangleCount)
        // 3MF with component + transform
        val model = buildString {
            append("<?xml version=\"1.0\"?><model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources>")
            append("<object id=\"1\" type=\"model\"><mesh><vertices>")
            for (v in 0 until box.vertexCount) append("<vertex x=\"${box.vertices[v * 3]}\" y=\"${box.vertices[v * 3 + 1]}\" z=\"${box.vertices[v * 3 + 2]}\"/>")
            append("</vertices><triangles>")
            for (t in 0 until box.triangleCount) append("<triangle v1=\"${box.indices[t * 3]}\" v2=\"${box.indices[t * 3 + 1]}\" v3=\"${box.indices[t * 3 + 2]}\"/>")
            append("</triangles></mesh></object>")
            append("<object id=\"2\" name=\"Wrapper\"><components><component objectid=\"1\" transform=\"2 0 0 0 2 0 0 0 2 0 0 0\"/></components></object>")
            append("</resources><build><item objectid=\"2\" transform=\"1 0 0 0 1 0 0 0 1 50 50 0\"/></build></model>")
        }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("3D/3dmodel.model")); z.write(model.toByteArray()); z.closeEntry() }
        val m3 = MeshLoader.load("x.3mf", bos.toByteArray().inputStream()).single()
        assertEquals("Wrapper", m3.name)
        assertTrue(abs(m3.mesh.volume() - 48000.0) < 1e-2)
        assertTrue(abs(m3.mesh.bounds().sizeZ - 60.0) < 1e-4)
    }

    @Test
    fun plateTransforms() {
        val o = PlateObject.create(1, "b", Primitives.box(10.0, 20.0, 30.0), x = 50.0, y = 60.0)
        val wb = o.worldBounds
        assertTrue(abs(wb.centerX - 50) < 1e-9 && abs(wb.centerY - 60) < 1e-9 && abs(wb.minZ) < 1e-9)
        val flat = o.layFlat() // largest faces are the 20x30 sides -> object becomes 10 tall
        assertTrue(abs(flat.size.z - 10.0) < 1e-6, "lay flat z=${flat.size.z}")
        val arranged = Arranger.arrange(List(6) { o.withId(it.toLong(), 0.0, 0.0) }, 220.0, 220.0)
        assertTrue(arranged.all { it.isInside(220.0, 220.0, 250.0) })
        val wm = flat.worldMesh().bounds()
        assertTrue(abs(wm.minZ) < 1e-4)
    }
}
