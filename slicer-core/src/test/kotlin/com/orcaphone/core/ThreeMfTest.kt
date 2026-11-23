package com.orcaphone.core

import com.orcaphone.core.mesh.MeshLoader
import com.orcaphone.core.mesh.VolumeKind
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFails

/** 3MF files as Bambu Studio / MakerWorld write them. */
class ThreeMfTest {
    /**
     * Writes a ZIP like Bambu Studio's: every entry has its sizes in a data descriptor after the data,
     * including STORED (uncompressed) ones, which ZipInputStream cannot read.
     */
    private fun bambuZip(entries: List<Pair<String, ByteArray>>, stored: Set<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        fun le(n: Int, v: Long) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply {
            if (n == 2) putShort(v.toShort()) else putInt(v.toInt())
        }.array()
        for ((name, data) in entries) {
            val method = if (name in stored) 0 else 8
            val body = if (method == 0) data else {
                val d = Deflater(Deflater.DEFAULT_COMPRESSION, true); d.setInput(data); d.finish()
                val buf = ByteArray(data.size + 1024); val n = d.deflate(buf); d.end(); buf.copyOf(n)
            }
            val crc = CRC32().apply { update(data) }.value
            val offset = out.size()
            val nb = name.toByteArray()
            out.write(le(4, 0x04034b50)); out.write(le(2, 20)); out.write(le(2, 0x08)); out.write(le(2, method.toLong()))
            out.write(le(4, 0)); out.write(le(4, 0)); out.write(le(4, 0)); out.write(le(4, 0))
            out.write(le(2, nb.size.toLong())); out.write(le(2, 0)); out.write(nb); out.write(body)
            out.write(le(4, 0x08074b50)); out.write(le(4, crc)); out.write(le(4, body.size.toLong())); out.write(le(4, data.size.toLong()))
            central.write(le(4, 0x02014b50)); central.write(le(2, 20)); central.write(le(2, 20)); central.write(le(2, 0x08))
            central.write(le(2, method.toLong())); central.write(le(4, 0)); central.write(le(4, crc))
            central.write(le(4, body.size.toLong())); central.write(le(4, data.size.toLong()))
            central.write(le(2, nb.size.toLong())); central.write(le(2, 0)); central.write(le(2, 0)); central.write(le(2, 0))
            central.write(le(2, 0)); central.write(le(4, 0)); central.write(le(4, offset.toLong())); central.write(nb)
        }
        val cdOffset = out.size()
        out.write(central.toByteArray())
        out.write(le(4, 0x06054b50)); out.write(le(2, 0)); out.write(le(2, 0))
        out.write(le(2, entries.size.toLong())); out.write(le(2, entries.size.toLong()))
        out.write(le(4, central.size().toLong())); out.write(le(4, cdOffset.toLong())); out.write(le(2, 0))
        return out.toByteArray()
    }

    private fun cube(id: Int, size: Double): String {
        val v = listOf(0, 1).flatMap { z -> listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1).map { (x, y) -> Triple(x * size, y * size, z * size) } }
        val f = listOf(0 to 2 to 1, 0 to 3 to 2, 4 to 5 to 6, 4 to 6 to 7, 0 to 1 to 5, 0 to 5 to 4, 1 to 2 to 6, 1 to 6 to 5, 2 to 3 to 7, 2 to 7 to 6, 3 to 0 to 4, 3 to 4 to 7)
        return """<object id="$id" type="model"><mesh><vertices>""" +
            v.joinToString("") { (x, y, z) -> """<vertex x="$x" y="$y" z="$z"/>""" } + "</vertices><triangles>" +
            f.joinToString("") { (ab, c) -> """<triangle v1="${ab.first}" v2="${ab.second}" v3="$c"/>""" } + "</triangles></mesh></object>"
    }

    private val ns = """xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06""""

    private fun bambuFile(): ByteArray {
        val parts = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" $ns><resources>${cube(1, 20.0)}${cube(2, 5.0)}${cube(4, 5.0)}${cube(5, 5.0)}</resources><build/></model>"""
        // object 3 = the head (part 1) plus a support blocker (part 2) stretched enormously in Z, as Bambu does
        val root = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" $ns><resources>
            <object id="3" type="model"><components>
              <component p:path="/3D/Objects/object_1.model" objectid="1" transform="1 0 0 0 1 0 0 0 1 0 0 0"/>
              <component p:path="/3D/Objects/object_1.model" objectid="2" transform="1 0 0 0 1 0 0 0 1e28 0 0 0"/>
              <component p:path="/3D/Objects/object_1.model" objectid="4" transform="1 0 0 0 1 0 0 0 1 5 5 5"/>
              <component p:path="/3D/Objects/object_1.model" objectid="5" transform="1 0 0 0 1 0 0 0 1 2 2 2"/>
            </components></object></resources>
            <build><item objectid="3" transform="1 0 0 0 1 0 0 0 1 100 100 0" printable="1"/></build></model>"""
        val settings = """<?xml version="1.0" encoding="UTF-8"?><config>
            <object id="3"><metadata key="name" value="Kopf.obj"/><metadata key="extruder" value="4"/>
              <part id="1" subtype="normal_part"><metadata key="name" value="Kopf.obj"/></part>
              <part id="2" subtype="support_blocker"><metadata key="name" value="Generisch-Zylinder"/></part>
              <part id="4" subtype="negative_part"><metadata key="name" value="Loch"/></part>
              <part id="5" subtype="modifier_part"><metadata key="name" value="Infill"/></part>
            </object></config>"""
        val png = ByteArray(300) { (it * 7).toByte() }
        return bambuZip(
            listOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "Metadata/plate_1.png" to png,
                "3D/3dmodel.model" to root.toByteArray(),
                "3D/Objects/object_1.model" to parts.toByteArray(),
                "Metadata/model_settings.config" to settings.toByteArray(),
            ),
            stored = setOf("Metadata/plate_1.png"),
        )
    }

    @Test
    fun bambuPackageWithStoredThumbnailsLoads() {
        val bytes = bambuFile()
        // the case that broke: a streaming reader can't get past the stored thumbnail
        assertFails { ZipInputStream(bytes.inputStream()).use { z -> while (z.nextEntry != null) z.readBytes() } }
        val models = MeshLoader.load("Brick+Men.3mf", bytes.inputStream())
        assertEquals(1, models.size)
        assertEquals("Kopf", models[0].name)
        // only the head: blocker, negative volume and modifier are not printed geometry
        assertEquals(12, models[0].mesh.triangleCount)
        val b = models[0].mesh.bounds()
        assertTrue(b.sizeZ < 20.0 + 1e-6, "support blocker merged into the part: height ${b.sizeZ}")
        // the blocker and the negative volume are kept as volumes, the modifier is counted as ignored
        val kinds = models[0].volumes.map { it.kind }.sorted()
        assertEquals(listOf(VolumeKind.NEGATIVE, VolumeKind.SUPPORT_BLOCKER), kinds)
        assertEquals(1, models[0].ignoredModifiers)
        // the "endless" blocker is cut to the part's height (plus a margin)
        val blocker = models[0].volumes.first { it.kind == VolumeKind.SUPPORT_BLOCKER }.mesh.bounds()
        assertTrue(blocker.maxZ <= b.maxZ + 1.0 + 1e-3, "blocker reaches z=${blocker.maxZ}")
        assertTrue(blocker.sizeX in 4.9..5.1, "blocker footprint ${blocker.sizeX}")
        val hole = models[0].volumes.first { it.kind == VolumeKind.NEGATIVE }.mesh.bounds()
        assertEquals(105.0, hole.minX, 1e-3) // item transform moves everything by 100
    }
}
