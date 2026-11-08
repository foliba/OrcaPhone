package com.orcaphone.core

import com.orcaphone.core.cut.CutFrame
import com.orcaphone.core.cut.CutProfile
import com.orcaphone.core.cut.MeshCutter
import com.orcaphone.core.math.Mat4
import com.orcaphone.core.mesh.Mesh
import com.orcaphone.core.mesh.Primitives
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshCutterTest {
    companion object {
        /** Every edge used exactly once in each direction: closed and consistently oriented. */
        fun assertWatertight(m: Mesh, what: String) {
            val count = HashMap<Long, Int>()
            for (t in 0 until m.triangleCount) for (k in 0 until 3) {
                val a = m.indices[t * 3 + k].toLong(); val b = m.indices[t * 3 + (k + 1) % 3].toLong()
                val key = (a shl 32) or b
                count[key] = (count[key] ?: 0) + 1
            }
            var bad = 0
            for ((key, c) in count) {
                val rev = ((key and 0xffffffffL) shl 32) or (key ushr 32)
                if (c != 1 || count[rev] != 1) bad++
            }
            assertEquals(0, bad, "$what: $bad non-manifold or open edges (of ${count.size})")
        }

        fun signedVolume(m: Mesh): Double {
            var v = 0.0
            val p = m.vertices
            for (t in 0 until m.triangleCount) {
                val a = m.indices[t * 3] * 3; val b = m.indices[t * 3 + 1] * 3; val c = m.indices[t * 3 + 2] * 3
                v += p[a].toDouble() * (p[b + 1].toDouble() * p[c + 2] - p[b + 2].toDouble() * p[c + 1]) -
                    p[a + 1].toDouble() * (p[b].toDouble() * p[c + 2] - p[b + 2].toDouble() * p[c]) +
                    p[a + 2].toDouble() * (p[b].toDouble() * p[c + 1] - p[b + 1].toDouble() * p[c])
            }
            return v / 6
        }
    }

    private val box = Primitives.box(100.0, 60.0, 40.0).transformed(Mat4.translation(50.0, 30.0, 20.0)) // 0..100, 0..60, 0..40

    @Test
    fun flatCutOfBox() {
        val f = CutFrame.axis(0, 37.0)
        val a = MeshCutter.clip(box, f, CutProfile.flat(1000.0), keepBelow = true)
        val b = MeshCutter.clip(box, f, CutProfile.flat(1000.0), keepBelow = false)
        assertWatertight(a, "below"); assertWatertight(b, "above")
        assertEquals(37 * 60 * 40.0, signedVolume(a), 1e-2)
        assertEquals(63 * 60 * 40.0, signedVolume(b), 1e-2)
        assertEquals(37.0, a.bounds().maxX, 1e-4)
        assertEquals(37.0, b.bounds().minX, 1e-4)
    }

    @Test
    fun dovetailCutOfBox() {
        val f = CutFrame.axis(0, 50.0)
        val male = CutProfile.dovetail(1000.0, center = 30.0, neck = 8.0, width = 14.0, depth = 8.0)
        val female = CutProfile.dovetail(1000.0, center = 30.0, neck = 8.0, width = 14.0, depth = 8.0, clearance = 0.2)
        val a = MeshCutter.clip(box, f, male, keepBelow = true)
        val b = MeshCutter.clip(box, f, female, keepBelow = false)
        assertWatertight(a, "tail part"); assertWatertight(b, "slot part")
        val tail = (8.0 + 14.0) / 2 * 8.0 * 40.0 // trapezoid area x height
        assertEquals(50 * 60 * 40.0 + tail, signedVolume(a), 0.5)
        val slot = (8.4 + 14.4) / 2 * 8.2 * 40.0
        assertEquals(50 * 60 * 40.0 - slot, signedVolume(b), 0.5)
        assertEquals(58.0, a.bounds().maxX, 1e-4) // tail sticks out by its depth
        assertEquals(50.0, b.bounds().minX, 1e-4)
    }

    @Test
    fun dovetailCutOfRoundShapes() {
        val cyl = Primitives.cylinder(30.0, 50.0)
        val sphere = Primitives.sphere(25.0)
        for ((name, m) in listOf("cylinder" to cyl, "sphere" to sphere)) {
            val v = signedVolume(m)
            for (axis in 0..2) {
                val c = m.bounds()
                val at = when (axis) { 0 -> c.centerX + 3.1; 1 -> c.centerY - 2.3; else -> c.centerZ + 1.7 }
                val f = CutFrame.axis(axis, at)
                val a = MeshCutter.clip(m, f, CutProfile.dovetail(1000.0, 1.3, 6.0, 10.0, 6.0), keepBelow = true)
                val b = MeshCutter.clip(m, f, CutProfile.dovetail(1000.0, 1.3, 6.0, 10.0, 6.0), keepBelow = false)
                assertWatertight(a, "$name axis $axis below"); assertWatertight(b, "$name axis $axis above")
                // Same profile on both sides: volumes add up exactly.
                assertTrue(abs(signedVolume(a) + signedVolume(b) - v) < v * 1e-6, "$name axis $axis volume")
            }
        }
    }

    @Test
    fun sectionOfBox() {
        val g = MeshCutter.section(box, CutFrame.axis(0, 20.0))
        assertEquals(60.0 * 40.0, g.area, 1e-6)
    }
}
