package com.orcaphone.core

import com.orcaphone.core.config.BrimType
import com.orcaphone.core.config.ProcessProfile
import com.orcaphone.core.mesh.Bounds3
import com.orcaphone.core.mesh.Primitives
import com.orcaphone.core.plate.Arranger
import com.orcaphone.core.plate.Instances
import com.orcaphone.core.plate.PlateObject
import com.orcaphone.core.math.Mat4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstancesTest {
    private fun box(x: Double, y: Double, sx: Double, sy: Double) = Bounds3(x - sx / 2, y - sy / 2, 0.0, x + sx / 2, y + sy / 2, 0.0)

    private fun assertValid(pos: List<Pair<Double, Double>>, sx: Double, sy: Double, bed: Double, spacing: Double, edge: Double, obstacles: List<Bounds3> = emptyList()) {
        val boxes = pos.map { (x, y) -> box(x, y, sx, sy) }
        for (b in boxes) {
            assertTrue(b.minX >= edge - 1e-6 && b.minY >= edge - 1e-6 && b.maxX <= bed - edge + 1e-6 && b.maxY <= bed - edge + 1e-6, "off the bed: $b")
            for (o in obstacles) assertTrue(b.minX >= o.maxX + spacing - 1e-6 || b.maxX <= o.minX - spacing + 1e-6 || b.minY >= o.maxY + spacing - 1e-6 || b.maxY <= o.minY - spacing + 1e-6, "touches obstacle $o: $b")
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            val gap = maxOf(b.minX - a.maxX, a.minX - b.maxX, b.minY - a.maxY, a.minY - b.maxY)
            assertTrue(gap >= spacing - 1e-6, "instances $i and $j are only $gap mm apart")
        }
    }

    @Test
    fun fillBedUsesAFullGrid() {
        // 20 mm cubes, 6 mm gap, 200 mm bed with 3 mm edge: floor((194 + 6) / 26) = 7 per side
        val pos = Instances.place(20.0, 20.0, 200.0, 200.0, 6.0, 3.0)
        assertEquals(49, pos.size)
        assertValid(pos, 20.0, 20.0, 200.0, 6.0, 3.0)
        assertEquals(49, Instances.max(20.0, 20.0, 200.0, 200.0, 6.0, 3.0))
    }

    @Test
    fun countIsClampedToWhatFits() {
        val pos = Instances.place(50.0, 50.0, 220.0, 220.0, 6.0, 0.0, count = 100)
        assertEquals(16, pos.size) // floor((220 + 6) / 56) = 4 per side
        assertValid(pos, 50.0, 50.0, 220.0, 6.0, 0.0)
    }

    @Test
    fun aSmallCountClustersAroundTheCentre() {
        val pos = Instances.place(20.0, 20.0, 200.0, 200.0, 6.0, 0.0, count = 4)
        assertEquals(4, pos.size)
        assertValid(pos, 20.0, 20.0, 200.0, 6.0, 0.0)
        for ((x, y) in pos) assertTrue(kotlin.math.hypot(x - 100, y - 100) < 40, "far from centre: $x,$y")
    }

    @Test
    fun otherObjectsAreAvoided() {
        val obstacles = listOf(box(100.0, 100.0, 60.0, 60.0), box(30.0, 170.0, 40.0, 20.0))
        val pos = Instances.place(20.0, 20.0, 200.0, 200.0, 6.0, 2.0, obstacles)
        assertTrue(pos.size > 20, "only ${pos.size}")
        assertValid(pos, 20.0, 20.0, 200.0, 6.0, 2.0, obstacles)
    }

    @Test
    fun tooBigGivesNone() {
        assertEquals(0, Instances.max(210.0, 20.0, 200.0, 200.0, 6.0))
    }

    @Test
    fun spacingLeavesRoomForBrimAndSupports() {
        val plain = ProcessProfile(id = "x", name = "x")
        val brim = plain.copy(brimType = BrimType.OUTER, brimWidth = 5.0, brimObjectGap = 0.1)
        val support = brim.copy(enableSupport = true)
        assertTrue(Arranger.instanceSpacingFor(brim) >= 2 * 5.1)
        assertTrue(Arranger.instanceSpacingFor(support) > Arranger.instanceSpacingFor(brim))
        assertTrue(Arranger.instanceSpacingFor(plain) >= Arranger.spacingFor(plain))
    }

    @Test
    fun instancesShareTheGroupAndTransform() {
        val a = PlateObject.create(1, "cube", Primitives.box(10.0, 20.0, 5.0), x = 50.0, y = 50.0)
        val b = a.instance(2, 80.0, 50.0)
        assertEquals(a.group, b.group)
        val turned = a.rotated(Mat4.rotationZ(Math.PI / 2))
        assertEquals(a.group, turned.group)
        val b2 = b.withTransformOf(turned)
        assertEquals(80.0, b2.x); assertEquals(turned.size.x, b2.size.x, 1e-9)
        assertEquals(2L, b2.id); assertEquals(a.group, b2.group)
        // an independent copy is a model of its own
        assertEquals(3L, a.withId(3, 0.0, 0.0).group)
    }
}
