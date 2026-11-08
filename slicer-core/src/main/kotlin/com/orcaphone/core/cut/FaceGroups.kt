package com.orcaphone.core.cut

import com.orcaphone.core.math.Vec3
import com.orcaphone.core.mesh.Mesh
import kotlin.math.abs
import kotlin.math.max

/**
 * Flat areas of a mesh that a part can be laid on: neighbouring triangles with (nearly) the same plane,
 * flood-filled over shared edges. The largest [maxGroups] get an index for colouring; every other
 * triangle has group -1.
 */
class FaceGroups(
    /** Group index per triangle, or -1. */
    val groupOf: IntArray,
    /** Unit outward normal of each group (area weighted). */
    val normals: List<Vec3>,
    val areas: List<Double>,
) {
    val count: Int get() = normals.size

    companion object {
        fun compute(mesh: Mesh, maxGroups: Int = 8, angleCos: Double = 0.9994): FaceGroups {
            val tc = mesh.triangleCount
            val normal = Array(tc) { t -> mesh.faceNormal(t) }
            val area = DoubleArray(tc) { normal[it].length() / 2 }
            val unit = Array(tc) { if (area[it] > 0) normal[it] * (1 / (area[it] * 2)) else Vec3.ZERO }
            val b = mesh.bounds()
            val planeTol = max(0.02, 0.0005 * max(b.sizeX, max(b.sizeY, b.sizeZ)))
            // Edge adjacency.
            val edgeTris = HashMap<Long, IntArray>(tc * 2)
            for (t in 0 until tc) for (k in 0 until 3) {
                val a = mesh.indices[t * 3 + k]; val c = mesh.indices[t * 3 + (k + 1) % 3]
                val key = (minOf(a, c).toLong() shl 32) or maxOf(a, c).toLong()
                val cur = edgeTris[key]
                edgeTris[key] = if (cur == null) intArrayOf(t) else cur + t
            }
            val raw = IntArray(tc) { -1 }
            val rawArea = ArrayList<Double>()
            val rawNormal = ArrayList<Vec3>()
            val stack = ArrayDeque<Int>()
            val v = mesh.vertices
            for (seed in 0 until tc) {
                if (raw[seed] >= 0 || area[seed] <= 0) continue
                val g = rawArea.size
                val n0 = unit[seed]
                val i0 = mesh.indices[seed * 3] * 3
                val d0 = n0.x * v[i0] + n0.y * v[i0 + 1] + n0.z * v[i0 + 2]
                var sum = 0.0; var nx = 0.0; var ny = 0.0; var nz = 0.0
                raw[seed] = g; stack.addLast(seed)
                while (stack.isNotEmpty()) {
                    val t = stack.removeLast()
                    sum += area[t]; nx += unit[t].x * area[t]; ny += unit[t].y * area[t]; nz += unit[t].z * area[t]
                    for (k in 0 until 3) {
                        val a = mesh.indices[t * 3 + k]; val c = mesh.indices[t * 3 + (k + 1) % 3]
                        val key = (minOf(a, c).toLong() shl 32) or maxOf(a, c).toLong()
                        for (o in edgeTris[key] ?: continue) {
                            if (raw[o] >= 0 || area[o] <= 0) continue
                            if ((unit[o] dot n0) < angleCos) continue
                            var flat = true
                            for (j in 0 until 3) {
                                val vi = mesh.indices[o * 3 + j] * 3
                                if (abs(n0.x * v[vi] + n0.y * v[vi + 1] + n0.z * v[vi + 2] - d0) > planeTol) { flat = false; break }
                            }
                            if (!flat) continue
                            raw[o] = g; stack.addLast(o)
                        }
                    }
                }
                rawArea.add(sum); rawNormal.add(Vec3(nx, ny, nz).normalized())
            }
            val total = area.sum()
            val minArea = max(4.0, total * 0.005)
            val keep = rawArea.indices.filter { rawArea[it] >= minArea }.sortedByDescending { rawArea[it] }.take(maxGroups)
            val remap = IntArray(rawArea.size) { -1 }
            keep.forEachIndexed { i, g -> remap[g] = i }
            return FaceGroups(
                IntArray(tc) { if (raw[it] >= 0) remap[raw[it]] else -1 },
                keep.map { rawNormal[it] },
                keep.map { rawArea[it] },
            )
        }
    }
}
