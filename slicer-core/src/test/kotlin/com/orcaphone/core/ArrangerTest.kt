package com.orcaphone.core

import com.orcaphone.core.mesh.Primitives
import com.orcaphone.core.plate.Arranger
import com.orcaphone.core.plate.PlateObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrangerTest {
    private fun box(id: Long, x: Double, y: Double) = PlateObject.create(id, "b$id", Primitives.box(x, y, 10.0))

    private fun overlaps(a: PlateObject, b: PlateObject): Boolean {
        val p = a.worldBounds; val q = b.worldBounds
        return p.minX < q.maxX - 1e-6 && p.maxX > q.minX + 1e-6 && p.minY < q.maxY - 1e-6 && p.maxY > q.minY + 1e-6
    }

    @Test
    fun fitsOnOneBed() {
        val beds = Arranger.arrangeOnBeds(List(6) { box(it.toLong(), 40.0, 30.0) }, 220.0, 220.0)
        assertEquals(1, beds.size)
        assertEquals(6, beds[0].size)
        assertTrue(beds[0].all { it.isInside(220.0, 220.0, 250.0) })
    }

    @Test
    fun overflowGoesToMoreBeds() {
        // 100x100 boxes: two per row, two rows per 220 bed -> 4 per bed, 9 objects -> 3 beds
        val objs = List(9) { box(it.toLong(), 100.0, 100.0) }
        val beds = Arranger.arrangeOnBeds(objs, 220.0, 220.0)
        assertEquals(3, beds.size)
        assertEquals(9, beds.sumOf { it.size })
        assertEquals(objs.map { it.id }.toSet(), beds.flatten().map { it.id }.toSet())
        for (bed in beds) {
            assertTrue(bed.all { it.isInside(220.0, 220.0, 250.0) })
            for (i in bed.indices) for (j in i + 1 until bed.size) assertTrue(!overlaps(bed[i], bed[j]))
        }
    }

    @Test
    fun mixedSizesFillGaps() {
        val objs = listOf(box(1, 200.0, 150.0), box(2, 30.0, 30.0), box(3, 30.0, 30.0), box(4, 150.0, 150.0))
        val beds = Arranger.arrangeOnBeds(objs, 220.0, 220.0)
        assertEquals(2, beds.size)
        assertTrue(beds.flatten().all { it.isInside(220.0, 220.0, 250.0) })
    }

    @Test
    fun oversizeObjectGetsOwnBed() {
        val beds = Arranger.arrangeOnBeds(listOf(box(1, 300.0, 50.0), box(2, 20.0, 20.0)), 220.0, 220.0)
        assertEquals(2, beds.size)
        assertTrue(beds.any { b -> b.size == 1 && b[0].id == 1L })
    }

    @Test
    fun freeSpot() {
        val bedObj = box(1, 50.0, 50.0).movedTo(110.0, 110.0)
        val spot = assertNotNull(Arranger.findFreeSpot(listOf(bedObj), 40.0, 40.0, 220.0, 220.0))
        val placed = box(2, 40.0, 40.0).movedTo(spot.first, spot.second)
        assertTrue(!overlaps(bedObj, placed))
        assertTrue(placed.isInside(220.0, 220.0, 250.0))
        assertNull(Arranger.findFreeSpot(emptyList(), 300.0, 40.0, 220.0, 220.0))
    }
}
