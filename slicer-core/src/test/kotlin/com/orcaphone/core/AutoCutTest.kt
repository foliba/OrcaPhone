package com.orcaphone.core

import com.orcaphone.core.MeshCutterTest.Companion.assertWatertight
import com.orcaphone.core.MeshCutterTest.Companion.signedVolume
import com.orcaphone.core.config.BuiltinProfiles
import com.orcaphone.core.cut.AutoCut
import com.orcaphone.core.cut.FaceGroups
import com.orcaphone.core.math.Mat4
import com.orcaphone.core.math.Vec3
import com.orcaphone.core.mesh.Primitives
import com.orcaphone.core.plate.PlateObject
import com.orcaphone.core.slice.Slicer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoCutTest {
    @Test
    fun planCounts() {
        // 220 x 220 x 250 bed -> 200 x 200 usable, 190 per piece with the joint allowance
        assertEquals(listOf(1, 1, 1), AutoCut.plan(Vec3(150.0, 200.0, 100.0), 220.0, 220.0, 250.0)!!.counts)
        assertEquals(listOf(2, 1, 1), AutoCut.plan(Vec3(300.0, 120.0, 30.0), 220.0, 220.0, 250.0)!!.counts)
        assertEquals(listOf(3, 2, 1), AutoCut.plan(Vec3(500.0, 260.0, 30.0), 220.0, 220.0, 250.0)!!.counts)
        assertEquals(listOf(1, 1, 2), AutoCut.plan(Vec3(100.0, 100.0, 400.0), 220.0, 220.0, 250.0)!!.counts)
        assertNull(AutoCut.plan(Vec3(100.0, 100.0, 40.0), 30.0, 30.0, 250.0))
    }

    @Test
    fun cutsLargeBoxIntoFittingWatertightParts() {
        val bedW = 220.0; val bedD = 220.0; val maxH = 250.0
        val mesh = Primitives.box(480.0, 250.0, 40.0)
        val plan = AutoCut.plan(Vec3(480.0, 250.0, 40.0), bedW, bedD, maxH)!!
        assertEquals(listOf(3, 2, 1), plan.counts)
        val parts = AutoCut.cut(mesh, plan)
        assertEquals(6, parts.size)
        val usable = AutoCut.usable(bedW, bedD, maxH)
        var vol = 0.0
        for ((i, p) in parts.withIndex()) {
            assertWatertight(p, "part $i")
            val b = p.bounds()
            assertTrue(b.sizeX <= usable.x && b.sizeY <= usable.y && b.sizeZ <= usable.z, "part $i is ${b.sizeX} x ${b.sizeY} x ${b.sizeZ}")
            // placed in the middle of the bed it keeps the margin on every side
            val obj = PlateObject.create(i.toLong(), "p", p).movedTo(bedW / 2, bedD / 2)
            val w = obj.worldBounds
            assertTrue(w.minX >= AutoCut.MARGIN - 1e-6 && w.maxX <= bedW - AutoCut.MARGIN + 1e-6)
            assertTrue(w.minY >= AutoCut.MARGIN - 1e-6 && w.maxY <= bedD - AutoCut.MARGIN + 1e-6)
            vol += signedVolume(p)
        }
        // Only the clearance gaps around the dovetails are lost.
        val full = 480.0 * 250 * 40
        assertTrue(vol < full && vol > full * 0.99, "volume $vol of $full")
        // Every cut got a dovetail: some parts stick out past the regular grid.
        assertTrue(parts.any { it.bounds().sizeX > 480.0 / 3 + 1 })
    }

    @Test
    fun cutPartsSlice() {
        val mesh = Primitives.cylinder(30.0, 330.0) // too tall for 250
        val plan = AutoCut.plan(Vec3(60.0, 60.0, 330.0), 220.0, 220.0, 250.0)!!
        val parts = AutoCut.cut(mesh, plan)
        assertEquals(2, parts.size)
        val printer = BuiltinProfiles.printers.first()
        val process = BuiltinProfiles.processes.first { it.id == "builtin-0.20" }
        for ((i, p) in parts.withIndex()) {
            assertWatertight(p, "part $i")
            val obj = PlateObject.create(1, "p", p).movedTo(printer.bedWidth / 2, printer.bedDepth / 2)
            val out = File.createTempFile("cut", ".gcode")
            val res = Slicer(printer, BuiltinProfiles.filaments.first(), process).slice(listOf(obj.worldMesh()), out) { _, _ -> }
            assertTrue(res.layers.size > 100, "part $i: ${res.layers.size} layers")
            out.delete()
        }
    }

    @Test
    fun faceGroupsOfBox() {
        val g = FaceGroups.compute(Primitives.box(40.0, 30.0, 20.0))
        assertEquals(6, g.count)
        assertEquals(40.0 * 30.0, g.areas[0], 1e-6)
        assertTrue(g.groupOf.all { it >= 0 })
        // a rotated box still groups by its own faces
        val r = FaceGroups.compute(Primitives.box(40.0, 30.0, 20.0).transformed(Mat4.rotationAxisAngle(Vec3(1.0, 1.0, 0.3).normalized(), 0.7)))
        assertEquals(6, r.count)
        // a sphere has no flat faces worth laying on
        assertNotNull(FaceGroups.compute(Primitives.sphere(20.0)).also { assertEquals(0, it.count) })
    }
}
