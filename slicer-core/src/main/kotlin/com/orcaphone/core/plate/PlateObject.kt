package com.orcaphone.core.plate

import com.orcaphone.core.math.Mat4
import com.orcaphone.core.math.Vec3
import com.orcaphone.core.mesh.Bounds3
import com.orcaphone.core.mesh.Mesh
import com.orcaphone.core.mesh.PartVolume

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
    /** Instances of one model share a group: orientation and scale changes apply to all of them. */
    val group: Long = id,
    /** Negative volumes and support blockers/enforcers, in mesh coordinates: they move with the object. */
    val volumes: List<PartVolume> = emptyList(),
) {
    val linear: Mat4 get() = rotation * Mat4.scale(scale.x, scale.y, scale.z)

    val worldMatrix: Mat4
        get() = Mat4.translation(x - rsBounds.centerX, y - rsBounds.centerY, -rsBounds.minZ) * linear

    val worldBounds: Bounds3
        get() = rsBounds.translated(x - rsBounds.centerX, y - rsBounds.centerY, -rsBounds.minZ)

    val size: Vec3 get() = Vec3(rsBounds.sizeX, rsBounds.sizeY, rsBounds.sizeZ)

    fun movedTo(nx: Double, ny: Double) = PlateObject(id, name, mesh, rotation, scale, nx, ny, rsBounds, group, volumes)

    fun withName(n: String) = PlateObject(id, n, mesh, rotation, scale, x, y, rsBounds, group, volumes)

    fun rotated(r: Mat4): PlateObject = create(id, name, mesh, r * rotation, scale, x, y, group, volumes)

    fun withRotation(r: Mat4): PlateObject = create(id, name, mesh, r, scale, x, y, group, volumes)

    fun scaled(s: Vec3): PlateObject = create(id, name, mesh, rotation, s, x, y, group, volumes)

    /** Takes over the orientation and scale of [other] (an instance of the same model), keeping the position. */
    fun withTransformOf(other: PlateObject) =
        if (other.mesh === mesh) PlateObject(id, name, mesh, other.rotation, other.scale, x, y, other.rsBounds, group, volumes)
        else create(id, name, mesh, other.rotation, other.scale, x, y, group, volumes)

    /** An independent copy: its own group. */
    fun withId(newId: Long, nx: Double, ny: Double) = PlateObject(newId, name, mesh, rotation, scale, nx, ny, rsBounds, newId, volumes)

    /** Another instance of the same model: shares mesh, orientation, scale and group. */
    fun instance(newId: Long, nx: Double, ny: Double) = PlateObject(newId, name, mesh, rotation, scale, nx, ny, rsBounds, group, volumes)

    fun layFlat(): PlateObject {
        val n = mesh.bestLayFlatNormal(linear) ?: return this
        return rotated(Mat4.rotationBetween(n, Vec3(0.0, 0.0, -1.0)))
    }

    fun worldMesh(): Mesh = mesh.transformed(worldMatrix)

    /** The volumes placed like the object. */
    fun worldVolumes(): List<PartVolume> = worldMatrix.let { m -> volumes.map { it.transformed(m) } }

    fun withVolumes(v: List<PartVolume>) = PlateObject(id, name, mesh, rotation, scale, x, y, rsBounds, group, v)

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
            group: Long = id,
            volumes: List<PartVolume> = emptyList(),
        ): PlateObject {
            val lin = rotation * Mat4.scale(scale.x, scale.y, scale.z)
            return PlateObject(id, name, mesh, rotation, scale, x, y, mesh.bounds(lin), group, volumes)
        }
    }
}

object Arranger {
    /**
     * Room the first-layer skirt and brim need around a part (mm), so arranged or cut parts don't push
     * them off the bed. Mirrors OrcaSlicer leaving room for the skirt when it arranges.
     */
    fun skirtBrimRoom(p: com.orcaphone.core.config.ProcessProfile): Double {
        val brim = if (p.brimType != com.orcaphone.core.config.BrimType.NONE) p.brimWidth + p.brimObjectGap else 0.0
        val skirt = if (p.skirtLoops > 0) p.skirtDistance + p.skirtLoops * p.initialLayerLineWidth else 0.0
        return brim + skirt
    }

    /**
     * Gap between instances of one model: two brims side by side, plus room for supports that reach past
     * the part's footprint (tree and organic branch feet).
     */
    fun instanceSpacingFor(p: com.orcaphone.core.config.ProcessProfile): Double {
        val support = if (p.enableSupport) 2 * (p.supportObjectXYDistance + 2.0) else 0.0
        val brim = if (p.brimType != com.orcaphone.core.config.BrimType.NONE) p.brimWidth + p.brimObjectGap else 0.0
        return maxOf(spacingFor(p), 2 * brim + support + 1.0)
    }

    /** Gap between arranged parts: enough for two brims side by side. */
    fun spacingFor(p: com.orcaphone.core.config.ProcessProfile): Double {
        val brim = if (p.brimType != com.orcaphone.core.config.BrimType.NONE) p.brimWidth + p.brimObjectGap else 0.0
        return maxOf(6.0, 2 * brim + 1.0)
    }

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

    /**
     * Shelf-packs objects onto as many beds as needed. Every returned bed holds objects that fit inside it,
     * except an object that is larger than the bed on its own, which gets a bed to itself.
     * Each bed's layout is centred. Objects keep their relative order within a bed.
     */
    fun arrangeOnBeds(
        objects: List<PlateObject>, bedW: Double, bedD: Double, spacing: Double = 6.0,
        /** Free border kept along every bed edge (room for skirt and brim). */
        edge: Double = 0.0,
    ): List<List<PlateObject>> {
        val roomW = bedW - 2 * edge; val roomD = bedD - 2 * edge
        if (objects.isEmpty()) return emptyList()
        val eps = 1e-6
        class Row(val y0: Double, val h: Double) {
            var cursor = 0.0
            val items = ArrayList<Pair<Int, Double>>() // object index, left x within row
        }
        class Bin(val closed: Boolean = false) { val rows = ArrayList<Row>(); val used get() = rows.lastOrNull()?.let { it.y0 + it.h } ?: 0.0 }
        val order = objects.indices.sortedWith(compareByDescending<Int> { objects[it].size.y }.thenByDescending { objects[it].size.x })
        val bins = ArrayList<Bin>()
        for (i in order) {
            val s = objects[i].size
            var placed = false
            for (bin in bins) {
                if (bin.closed) continue
                val row = bin.rows.firstOrNull { s.y <= it.h + eps && it.cursor + s.x <= roomW + eps }
                    ?: run {
                        val y0 = bin.used + spacing
                        if (s.x <= roomW + eps && y0 + s.y <= roomD + eps) Row(y0, s.y).also { bin.rows.add(it) } else null
                    }
                if (row != null) {
                    row.items.add(i to row.cursor); row.cursor += s.x + spacing
                    placed = true; break
                }
            }
            if (!placed) {
                val bin = Bin(closed = s.x > roomW + eps || s.y > roomD + eps); val row = Row(0.0, s.y)
                row.items.add(i to 0.0); row.cursor = s.x + spacing
                bin.rows.add(row); bins.add(bin)
            }
        }
        return bins.map { bin ->
            val top = (bedD + bin.used) / 2
            val placed = HashMap<Int, PlateObject>()
            for (row in bin.rows) {
                val x0 = (bedW - (row.cursor - spacing)) / 2
                for ((idx, left) in row.items) {
                    val o = objects[idx]
                    placed[idx] = o.movedTo(x0 + left + o.size.x / 2, top - row.y0 - row.h / 2)
                }
            }
            placed.keys.sorted().map { placed.getValue(it) }
        }
    }

    /**
     * Finds the free spot closest to the bed centre for an object of footprint [sx] x [sy] that keeps [spacing]
     * from [existing] objects, or null if there is none.
     */
    fun findFreeSpot(
        existing: List<PlateObject>, sx: Double, sy: Double, bedW: Double, bedD: Double, spacing: Double = 6.0, edge: Double = 0.0,
    ): Pair<Double, Double>? {
        if (sx > bedW - 2 * edge || sy > bedD - 2 * edge) return null
        val boxes = existing.map { it.worldBounds }
        val step = 4.0
        val candidates = ArrayList<Pair<Double, Double>>()
        var y = edge + sy / 2
        while (y <= bedD - edge - sy / 2 + 1e-9) {
            var x = edge + sx / 2
            while (x <= bedW - edge - sx / 2 + 1e-9) { candidates.add(x to y); x += step }
            y += step
        }
        candidates.add(bedW / 2 to bedD / 2)
        val cx = bedW / 2; val cy = bedD / 2
        candidates.sortBy { (x, y) -> (x - cx) * (x - cx) + (y - cy) * (y - cy) }
        return candidates.firstOrNull { (x, y) ->
            boxes.none { b ->
                x - sx / 2 < b.maxX + spacing && x + sx / 2 > b.minX - spacing &&
                    y - sy / 2 < b.maxY + spacing && y + sy / 2 > b.minY - spacing
            }
        }
    }
}
