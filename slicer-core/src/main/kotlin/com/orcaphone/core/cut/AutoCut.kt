package com.orcaphone.core.cut

import com.orcaphone.core.math.Vec3
import com.orcaphone.core.mesh.Mesh
import com.orcaphone.core.slice.Geo
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import kotlin.math.ceil
import kotlin.math.max

/**
 * Cuts a part that is too big for the printer into pieces that fit, joined by dovetails.
 *
 * Pieces are cut on a regular grid along X, Y and (if needed) Z. Each cut gets one dovetail tail on the
 * lower piece and a matching slot (with [CLEARANCE]) in the upper piece; tails on X/Y cuts run vertically
 * so the pieces slide together from above and print without supports.
 */
object AutoCut {
    /** Free bed border left around every piece, mm. */
    const val MARGIN = 10.0
    /** Room reserved per piece for a dovetail tail sticking out of it, mm. */
    const val JOINT = 10.0
    /** Gap between tail and slot, mm. */
    const val CLEARANCE = 0.2

    /** Number of pieces along each world axis. */
    data class Plan(val counts: List<Int>, val pieceSize: Vec3) {
        val parts: Int get() = counts[0] * counts[1] * counts[2]
        val needsCut: Boolean get() = parts > 1
    }

    /** Usable build volume after the [MARGIN] on every side of the bed. */
    fun usable(bedW: Double, bedD: Double, maxH: Double) = Vec3(bedW - 2 * MARGIN, bedD - 2 * MARGIN, maxH)

    /** How many pieces a part of [size] needs, or null if the bed is too small to cut for. */
    fun plan(size: Vec3, bedW: Double, bedD: Double, maxH: Double): Plan? {
        val u = usable(bedW, bedD, maxH)
        val dims = listOf(size.x to u.x, size.y to u.y, size.z to u.z)
        val counts = dims.map { (s, room) ->
            when {
                s <= room + 1e-6 -> 1
                room - JOINT < 5 -> return null
                else -> ceil(s / (room - JOINT)).toInt()
            }
        }
        val piece = Vec3(
            size.x / counts[0] + if (counts[0] > 1) JOINT else 0.0,
            size.y / counts[1] + if (counts[1] > 1) JOINT else 0.0,
            size.z / counts[2] + if (counts[2] > 1) JOINT else 0.0,
        )
        return Plan(counts, piece)
    }

    /**
     * Cuts [mesh] (world coordinates, already in its print orientation) according to [plan].
     * [progress] receives (cuts done, cuts total).
     */
    fun cut(mesh: Mesh, plan: Plan, progress: (Int, Int) -> Unit = { _, _ -> }): List<Mesh> {
        val b = mesh.bounds()
        val mins = doubleArrayOf(b.minX, b.minY, b.minZ)
        val sizes = doubleArrayOf(b.sizeX, b.sizeY, b.sizeZ)
        val big = 10 * max(b.sizeX, max(b.sizeY, b.sizeZ)) + 1000
        var pieces = listOf(mesh)
        var total = 0
        var mult = 1
        for (a in 0..2) { total += (plan.counts[a] - 1) * mult; mult *= plan.counts[a] }
        var done = 0
        for (axis in 0..2) {
            val n = plan.counts[axis]
            if (n <= 1) continue
            val next = ArrayList<Mesh>()
            for (piece in pieces) {
                var rest = piece
                for (i in 1 until n) {
                    val at = mins[axis] + sizes[axis] * i / n
                    val frame = CutFrame.axis(axis, at)
                    val (male, female) = joint(rest, frame, big)
                    val below = MeshCutter.clip(rest, frame, male, keepBelow = true)
                    rest = MeshCutter.clip(rest, frame, female, keepBelow = false)
                    if (below.triangleCount > 0) next.add(below)
                    progress(++done, total)
                }
                if (rest.triangleCount > 0) next.add(rest)
            }
            pieces = next
        }
        return pieces.filter { it.triangleCount >= 4 && it.volume() > 1.0 }
    }

    /** Dovetail profiles (tail, slot) for a cut, or flat ones if the cross-section is too small or thin. */
    private fun joint(mesh: Mesh, frame: CutFrame, big: Double): Pair<CutProfile, CutProfile> {
        val flat = CutProfile.flat(big) to CutProfile.flat(big)
        val at = MeshCutter.section(mesh, frame)
        if (at.isEmpty) return flat
        val env = at.envelopeInternal
        val uExt = env.width
        if (uExt < 12 || at.area < 60) return flat
        val width = (0.22 * uExt).coerceIn(5.0, 16.0)
        val neck = 0.6 * width
        val depth = (0.7 * width).coerceIn(3.0, 8.0)
        // The tail sits between the cut plane and `depth` above it: it needs material in both sections.
        val n = frame.n
        val deeper = MeshCutter.section(
            mesh, CutFrame(Vec3(frame.origin.x + n.x * depth, frame.origin.y + n.y * depth, frame.origin.z + n.z * depth), frame.u, frame.n),
        )
        val band = width + 3
        var best = Double.NaN; var bestScore = -1.0
        val lo = env.minX + band / 2; val hi = env.maxX - band / 2
        val steps = 24
        for (k in 0..steps) {
            val c = if (hi <= lo) (env.minX + env.maxX) / 2 else lo + (hi - lo) * k / steps
            val rect = Geo.factory.toGeometry(Envelope(c - band / 2, c + band / 2, env.minY - 1, env.maxY + 1))
            val score = minOf(area(at, rect), area(deeper, rect))
            if (score > bestScore) { bestScore = score; best = c }
            if (hi <= lo) break
        }
        // Require a solid run of material for the tail (not just a thin shell).
        if (best.isNaN() || bestScore < band * 6.0) return flat
        return CutProfile.dovetail(big, best, neck, width, depth) to
            CutProfile.dovetail(big, best, neck, width, depth, CLEARANCE)
    }

    private fun area(g: Geometry, rect: Geometry) = if (g.isEmpty) 0.0 else Geo.intersection(g, rect).area
}
