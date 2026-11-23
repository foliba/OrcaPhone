package io.github.foliba.phorca.core

import io.github.foliba.phorca.core.config.BuiltinProfiles
import io.github.foliba.phorca.core.config.SupportType
import io.github.foliba.phorca.core.math.Mat4
import io.github.foliba.phorca.core.mesh.Mesh
import io.github.foliba.phorca.core.mesh.PartVolume
import io.github.foliba.phorca.core.mesh.Primitives
import io.github.foliba.phorca.core.mesh.VolumeKind
import io.github.foliba.phorca.core.plate.PlateObject
import io.github.foliba.phorca.core.slice.Feature
import io.github.foliba.phorca.core.slice.LayerToolpaths
import io.github.foliba.phorca.core.slice.Slicer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Negative volumes, support blockers and enforcers from 3MF projects. */
class VolumesTest {
    private val printer = BuiltinProfiles.printers.first { it.id == "builtin-generic-klipper" }
    private val filament = BuiltinProfiles.filaments.first()
    private val base = BuiltinProfiles.processes.first { it.id == "builtin-0.20" }.copy(skirtLoops = 0)
    private val out = File("build/test-out").apply { mkdirs() }

    private fun slice(obj: PlateObject, process: io.github.foliba.phorca.core.config.ProcessProfile, name: String): List<LayerToolpaths> =
        Slicer(printer, filament, process).slice(listOf(obj.worldMesh()), File(out, "$name.gcode"), obj.worldVolumes()) { _, _ -> }.layers

    /** True when any extrusion of the given features passes a point (sampled every 0.25 mm) that passes [test]. */
    private fun LayerToolpaths.anyPoint(feature: (Feature) -> Boolean, test: (Double, Double) -> Boolean) =
        paths.filter { feature(it.feature) }.any { p ->
            val n = p.pointCount
            val segs = if (p.closed) n else n - 1
            (0 until maxOf(segs, 1)).any { k ->
                val ax = p.points[k * 2]; val ay = p.points[k * 2 + 1]
                val b = (k + 1) % n
                val bx = p.points[b * 2]; val by = p.points[b * 2 + 1]
                val steps = maxOf(1, (kotlin.math.hypot(bx - ax, by - ay) / 0.25).toInt())
                (0..steps).any { s -> val t = s.toDouble() / steps; test(ax + (bx - ax) * t, ay + (by - ay) * t) }
            }
        }

    @Test
    fun negativeVolumeCutsAHole() {
        val block = Primitives.box(30.0, 30.0, 10.0)
        val hole = Primitives.box(8.0, 8.0, 12.0).transformed(Mat4.translation(0.0, 0.0, -1.0))
        val plain = PlateObject.create(1, "block", block, x = 150.0, y = 150.0)
        val cut = plain.withVolumes(listOf(PartVolume(VolumeKind.NEGATIVE, hole)))
        val inHole = { x: Double, y: Double -> x in 147.0..153.0 && y in 147.0..153.0 }
        val solid = slice(plain, base, "neg-plain")
        assertTrue(solid.filter { it.z in 2.0..8.0 }.all { l -> l.anyPoint({ true }, inHole) }, "the plain block is solid in the middle")
        val layers = slice(cut, base, "neg-hole")
        for (l in layers) assertTrue(!l.anyPoint({ true }, inHole), "material inside the hole at z=${l.z}")
        // the hole is walled: there is a wall right around it
        assertTrue(layers.filter { it.z in 2.0..8.0 }.all { l -> l.anyPoint({ it == Feature.INNER_WALL || it == Feature.OUTER_WALL }) { x, y -> x in 145.5..146.5 } })
    }

    /** "T": a 10x10 post with a 60x10 slab on top, 20mm above the plate. */
    private fun tShape(): Mesh = Mesh.merge(listOf(
        Primitives.box(10.0, 10.0, 20.0),
        Primitives.box(60.0, 10.0, 4.0).transformed(Mat4.translation(0.0, 0.0, 20.0)),
    ))

    /** Covers the left arm (x < -6 relative to the post), the whole height. */
    private fun leftBox() = Primitives.box(26.0, 16.0, 30.0).transformed(Mat4.translation(-19.0, 0.0, -2.0))
    private fun rightBox() = Primitives.box(26.0, 16.0, 30.0).transformed(Mat4.translation(19.0, 0.0, -2.0))

    private fun supportLeftRight(layers: List<LayerToolpaths>): Pair<Boolean, Boolean> {
        val under = layers.filter { it.z in 10.0..19.0 }
        val sup = { f: Feature -> f == Feature.SUPPORT || f == Feature.SUPPORT_INTERFACE }
        return under.any { it.anyPoint(sup) { x, _ -> x < 140 } } to under.any { it.anyPoint(sup) { x, _ -> x > 160 } }
    }

    @Test
    fun blockerKeepsSupportAway() {
        for (type in listOf(SupportType.NORMAL, SupportType.TREE, SupportType.ORGANIC)) {
            val p = base.copy(enableSupport = true, supportType = type)
            val t = PlateObject.create(1, "t", tShape(), x = 150.0, y = 150.0)
            val (l0, r0) = supportLeftRight(slice(t, p, "blk-none-$type"))
            assertTrue(l0 && r0, "$type: both arms need support")
            val (l, r) = supportLeftRight(slice(t.withVolumes(listOf(PartVolume(VolumeKind.SUPPORT_BLOCKER, leftBox()))), p, "blk-$type"))
            assertTrue(!l, "$type: support under the blocked arm")
            assertTrue(r, "$type: the other arm lost its support")
        }
    }

    @Test
    fun enforcerAddsSupportWithSupportOff() {
        val p = base.copy(enableSupport = false, supportType = SupportType.NORMAL)
        val t = PlateObject.create(1, "t", tShape(), x = 150.0, y = 150.0)
        val (l0, r0) = supportLeftRight(slice(t, p, "enf-none"))
        assertTrue(!l0 && !r0, "support is off")
        val (l, r) = supportLeftRight(slice(t.withVolumes(listOf(PartVolume(VolumeKind.SUPPORT_ENFORCER, rightBox()))), p, "enf"))
        assertTrue(r, "no support in the enforcer")
        assertTrue(!l, "support outside the enforcer although support is off")
    }

    @Test
    fun volumesMoveWithTheObject() {
        val t = PlateObject.create(1, "t", tShape(), x = 150.0, y = 150.0).withVolumes(listOf(PartVolume(VolumeKind.SUPPORT_BLOCKER, leftBox())))
        val moved = t.movedTo(60.0, 80.0).rotated(Mat4.rotationZ(Math.PI))
        val b = moved.worldVolumes().single().mesh.bounds()
        // after a half turn the left arm's blocker is on the right of the object centre
        assertTrue(b.minX > 60.0 && b.maxX < 60.0 + 33.0, "blocker at ${b.minX}..${b.maxX}")
        val inst = moved.instance(2, 200.0, 80.0)
        assertTrue(inst.worldVolumes().single().mesh.bounds().minX > 200.0)
    }
}
