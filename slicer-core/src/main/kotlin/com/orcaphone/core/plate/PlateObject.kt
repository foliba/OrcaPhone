package com.orcaphone.core.plate

import com.orcaphone.core.math.Mat4
import com.orcaphone.core.math.Vec3
import com.orcaphone.core.mesh.Bounds3
import com.orcaphone.core.mesh.Mesh

/**
 * An object placed on the build plate. Immutable: every edit returns a new instance.
 * World transform = T(position, drop-to-bed) * R * S.
 */
class PlateObject private constructor(
    val id: Long,
    val name: String,
    val mesh: Mesh,
    val rotation: Mat4,
    val scale: Vec3,
    val x: Double,
    val y: Double,
    /** Bounds of the mesh after rotation and scale, before translation. */
    private val rsBounds: Bounds3,
) {
    val linear: Mat4 get() = rotation * Mat4.scale(scale.x, scale.y, scale.z)

    val worldMatrix: Mat4
        get() = Mat4.translation(x - rsBounds.centerX, y - rsBounds.centerY, -rsBounds.minZ) * linear

    val worldBounds: Bounds3
        get() = rsBounds.translated(x - rsBounds.centerX, y - rsBounds.centerY, -rsBounds.minZ)

    val size: Vec3 get() = Vec3(rsBounds.sizeX, rsBounds.sizeY, rsBounds.sizeZ)

    fun movedTo(nx: Double, ny: Double) = PlateObject(id, name, mesh, rotation, scale, nx, ny, rsBounds)

    fun withName(n: String) = PlateObject(id, n, mesh, rotation, scale, x, y, rsBounds)

    fun rotated(r: Mat4): PlateObject = create(id, name, mesh, r * rotation, scale, x, y)

    fun withRotation(r: Mat4): PlateObject = create(id, name, mesh, r, scale, x, y)

    fun scaled(s: Vec3): PlateObject = create(id, name, mesh, rotation, s, x, y)

    fun withId(newId: Long, nx: Double, ny: Double) = PlateObject(newId, name, mesh, rotation, scale, nx, ny, rsBounds)

    fun layFlat(): PlateObject {
        val n = mesh.bestLayFlatNormal(linear) ?: return this
        return rotated(Mat4.rotationBetween(n, Vec3(0.0, 0.0, -1.0)))
    }

    fun worldMesh(): Mesh = mesh.transformed(worldMatrix)

    fun isInside(bedW: Double, bedD: Double, maxH: Double): Boolean {
        val b = worldBounds
        val e = 1e-3
        return b.minX >= -e && b.minY >= -e && b.maxX <= bedW + e && b.maxY <= bedD + e && b.maxZ <= maxH + e
    }

    companion object {
        fun create(
            id: Long, name: String, mesh: Mesh,
            rotation: Mat4 = Mat4.identity(), scale: Vec3 = Vec3.ONE,
            x: Double = 0.0, y: Double = 0.0,
        ): PlateObject {
            val lin = rotation * Mat4.scale(scale.x, scale.y, scale.z)
            return PlateObject(id, name, mesh, rotation, scale, x, y, mesh.bounds(lin))
        }
    }
}

object Arranger {
    /**
     * Shelf-packs objects (by XY footprint) centred on the bed. Returns the moved objects in the same order.
     * Objects that do not fit keep being packed in rows past the bed edge so the user can see the overflow.
     */
    fun arrange(objects: List<PlateObject>, bedW: Double, bedD: Double, spacing: Double = 6.0): List<PlateObject> {
        if (objects.isEmpty()) return objects
        val order = objects.indices.sortedByDescending { objects[it].size.y * 1000 + objects[it].size.x }
        data class Placed(val idx: Int, var x: Double, var y: Double)
        val rows = ArrayList<MutableList<Placed>>()
        val rowHeights = ArrayList<Double>()
        val rowWidths = ArrayList<Double>()
        var cursorX = 0.0
        var current = ArrayList<Placed>()
        var rowH = 0.0
        for (i in order) {
            val s = objects[i].size
            if (current.isNotEmpty() && cursorX + s.x > bedW) {
                rows.add(current); rowHeights.add(rowH); rowWidths.add(cursorX - spacing)
                current = ArrayList(); cursorX = 0.0; rowH = 0.0
            }
            current.add(Placed(i, cursorX + s.x / 2, s.y / 2))
            cursorX += s.x + spacing
            rowH = maxOf(rowH, s.y)
        }
        rows.add(current); rowHeights.add(rowH); rowWidths.add(cursorX - spacing)
        val totalH = rowHeights.sum() + spacing * (rows.size - 1)
        var y = (bedD + totalH) / 2
        val result = objects.toMutableList()
        for ((r, row) in rows.withIndex()) {
            val h = rowHeights[r]
            val x0 = (bedW - rowWidths[r]) / 2
            for (p in row) {
                result[p.idx] = objects[p.idx].movedTo(x0 + p.x, y - h / 2)
            }
            y -= h + spacing
        }
        return result
    }
}
