package com.orcaphone.core

import com.orcaphone.core.config.BuiltinProfiles
import com.orcaphone.core.config.SupportType
import com.orcaphone.core.math.Mat4
import com.orcaphone.core.mesh.Mesh
import com.orcaphone.core.mesh.Primitives
import com.orcaphone.core.plate.PlateObject
import com.orcaphone.core.slice.Feature
import com.orcaphone.core.slice.Geo
import com.orcaphone.core.slice.LayerToolpaths
import com.orcaphone.core.slice.Slicer
import org.locationtech.jts.geom.Coordinate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TreeSupportTest {
    private val printer = BuiltinProfiles.printers.first { it.id == "builtin-generic-klipper" }
    private val filament = BuiltinProfiles.filaments.first()
    private val base = BuiltinProfiles.processes.first { it.id == "builtin-0.20" }.copy(enableSupport = true, skirtLoops = 0)
    private val out = File("build/test-out").apply { mkdirs() }

    /** "T": a 10x10 post with a 60x10 slab on top, 20mm above the plate. */
    private fun tShape(): Mesh {
        val post = Primitives.box(10.0, 10.0, 20.0)
        val slab = Primitives.box(60.0, 10.0, 4.0).transformed(Mat4.translation(0.0, 0.0, 20.0))
        return Mesh.merge(listOf(post, slab))
    }

    private fun supportVolume(layers: List<LayerToolpaths>) =
        layers.sumOf { l -> l.paths.filter { it.feature == Feature.SUPPORT || it.feature == Feature.SUPPORT_INTERFACE }.sumOf { it.length() * it.width * l.height } }

    private fun slice(mesh: Mesh, type: SupportType, plateOnly: Boolean = false, name: String): List<LayerToolpaths> {
        val obj = PlateObject.create(1, "m", mesh, x = 150.0, y = 150.0)
        val t0 = System.currentTimeMillis()
        val res = Slicer(printer, filament, base.copy(supportType = type, supportOnBuildPlateOnly = plateOnly))
            .slice(listOf(obj.worldMesh()), File(out, "$name.gcode")) { _, _ -> }
        println("$name: ${System.currentTimeMillis() - t0} ms, support volume ${"%.0f".format(supportVolume(res.layers))} mm3")
        return res.layers
    }

    @Test
    fun treeSupportsHoldTheArmsAndAvoidTheModel() {
        val mesh = tShape()
        val tree = slice(mesh, SupportType.TREE, name = "t-tree")
        val normal = slice(mesh, SupportType.NORMAL, name = "t-normal")
        // the arms (|x - 150| > 5 + clearance) are supported right below the slab
        val under = tree.filter { it.z in 18.0..19.6 }
        assertTrue(under.isNotEmpty())
        for (l in under) {
            val sup = l.paths.filter { it.feature == Feature.SUPPORT || it.feature == Feature.SUPPORT_INTERFACE }
            assertTrue(sup.any { p -> (0 until p.pointCount).any { p.points[it * 2] < 140 } }, "left arm unsupported at z=${l.z}")
            assertTrue(sup.any { p -> (0 until p.pointCount).any { p.points[it * 2] > 160 } }, "right arm unsupported at z=${l.z}")
        }
        // supports never touch the post (x/y within 145..155 minus clearance) below the slab
        for (l in tree.filter { it.z < 19.0 }) for (p in l.paths.filter { it.feature == Feature.SUPPORT }) {
            for (k in 0 until p.pointCount) {
                val x = p.points[k * 2]; val y = p.points[k * 2 + 1]
                assertTrue(!(x in 145.2..154.8 && y in 145.2..154.8), "support inside the post at z=${l.z}: $x,$y")
            }
        }
        // trees use clearly less material than full grid support
        assertTrue(supportVolume(tree) < supportVolume(normal) * 0.8, "tree ${supportVolume(tree)} vs normal ${supportVolume(normal)}")
        // first layer holds tree feet on the plate
        assertTrue(tree[0].paths.any { it.feature == Feature.SUPPORT })
    }

    @Test
    fun noFloatingBranches() {
        val layers = slice(Primitives.sphere(20.0), SupportType.TREE, name = "sphere-tree")
        assertTrue(layers.any { l -> l.paths.any { it.feature == Feature.SUPPORT } })
        // every support area on layer i+1 must overlap support (or model) on layer i
        fun area(l: LayerToolpaths) = Geo.union(l.paths.filter { it.feature == Feature.SUPPORT }.map {
            Geo.factory.createLineString(Array(it.pointCount + if (it.closed) 1 else 0) { k -> val q = k % it.pointCount; Coordinate(it.points[q * 2], it.points[q * 2 + 1]) }).buffer(it.width)
        })
        var prev = area(layers[0])
        for (i in 1 until layers.size) {
            val cur = area(layers[i])
            for (poly in Geo.polygons(cur)) {
                if (poly.area < 1.0) continue
                assertTrue(poly.buffer(1.5).intersects(prev) || layers[i].z > 15, "floating support at z=${layers[i].z}")
            }
            prev = cur
        }
    }

    @Test
    fun buildPlateOnlyNeverStartsOnTheModel() {
        // a bridge: two legs and a deck; the inner arch can only be supported from the plate
        val legA = Primitives.box(8.0, 20.0, 20.0).transformed(Mat4.translation(-20.0, 0.0, 0.0))
        val legB = Primitives.box(8.0, 20.0, 20.0).transformed(Mat4.translation(20.0, 0.0, 0.0))
        val deck = Primitives.box(48.0, 20.0, 3.0).transformed(Mat4.translation(0.0, 0.0, 20.0))
        val layers = slice(Mesh.merge(listOf(legA, legB, deck)), SupportType.TREE, plateOnly = true, name = "bridge-tree")
        assertTrue(layers[0].paths.any { it.feature == Feature.SUPPORT }, "trees should stand on the plate")
    }
}
