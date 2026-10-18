package com.orcaphone.core.slice

import com.orcaphone.core.mesh.DoubleArrayBuilder
import com.orcaphone.core.mesh.LongIntHashMap
import com.orcaphone.core.mesh.Mesh
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.GeometryFixer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cuts a (world-space) mesh with horizontal planes and returns one polygonal region per plane.
 * Uses mesh topology (shared edges) to chain intersection segments into closed loops, and the
 * even-odd nesting of loops to decide which are holes.
 */
class MeshSlicer(private val mesh: Mesh) {

    private val v = mesh.vertices
    private val idx = mesh.indices

    /** Buckets triangles by the layers they cross (CSR layout). */
    private fun bucket(zs: DoubleArray): Pair<IntArray, IntArray> {
        val tCount = mesh.triangleCount
        val first = IntArray(tCount)
        val last = IntArray(tCount)
        val counts = IntArray(zs.size + 1)
        for (t in 0 until tCount) {
            val za = v[idx[t * 3] * 3 + 2].toDouble()
            val zb = v[idx[t * 3 + 1] * 3 + 2].toDouble()
            val zc = v[idx[t * 3 + 2] * 3 + 2].toDouble()
            val lo = min(za, min(zb, zc))
            val hi = max(za, max(zb, zc))
            // planes with lo < z <= hi ... a crossing needs one vertex strictly above and one not above.
            var f = lowerBound(zs, lo)
            while (f < zs.size && zs[f] < lo) f++
            var l = lowerBound(zs, hi) - 1
            if (l >= 0 && zs[l] >= hi) l--
            first[t] = f; last[t] = l
            if (f <= l) for (k in f..l) counts[k]++
        }
        val start = IntArray(zs.size + 1)
        for (k in 0 until zs.size) start[k + 1] = start[k] + counts[k]
        val fill = start.copyOf()
        val data = IntArray(start[zs.size])
        for (t in 0 until tCount) {
            if (first[t] <= last[t]) for (k in first[t]..last[t]) data[fill[k]++] = t
        }
        return start to data
    }

    private fun lowerBound(a: DoubleArray, x: Double): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun slice(zs: DoubleArray, executor: ParallelRunner, closingTolerance: Double = 0.0125): Array<Geometry> {
        val (start, data) = bucket(zs)
        val result = Array<Geometry>(zs.size) { Geo.EMPTY }
        executor.forEach(zs.size) { layer ->
            result[layer] = sliceLayer(zs[layer], data, start[layer], start[layer + 1], closingTolerance)
        }
        return result
    }

    private fun sliceLayer(z: Double, tris: IntArray, from: Int, to: Int, tol: Double): Geometry {
        val n = to - from
        if (n <= 0) return Geo.EMPTY
        val segPts = DoubleArray(n * 4)
        val segKeys = LongArray(n * 2)
        var segCount = 0
        for (k in from until to) {
            val t = tris[k]
            val ia = idx[t * 3]; val ib = idx[t * 3 + 1]; val ic = idx[t * 3 + 2]
            val za = v[ia * 3 + 2] > z; val zb = v[ib * 3 + 2] > z; val zc = v[ic * 3 + 2] > z
            if (za == zb && zb == zc) continue
            var found = 0
            val s = segCount
            fun edge(i: Int, j: Int) {
                val zi = v[i * 3 + 2].toDouble(); val zj = v[j * 3 + 2].toDouble()
                val f = (z - zi) / (zj - zi)
                val x = v[i * 3] + (v[j * 3] - v[i * 3]) * f
                val y = v[i * 3 + 1] + (v[j * 3 + 1] - v[i * 3 + 1]) * f
                segPts[s * 4 + found * 2] = x
                segPts[s * 4 + found * 2 + 1] = y
                val lo = min(i, j).toLong(); val hi = max(i, j).toLong()
                segKeys[s * 2 + found] = (lo shl 32) or hi
                found++
            }
            if (za != zb) edge(ia, ib)
            if (zb != zc) edge(ib, ic)
            if (zc != za) edge(ic, ia)
            if (found == 2) segCount++
        }
        if (segCount == 0) return Geo.EMPTY

        // Link segment ends sharing a mesh edge.
        val link = IntArray(segCount * 2) { -1 }
        val map = LongIntHashMap(segCount * 2)
        for (e in 0 until segCount * 2) {
            val key = segKeys[e]
            val other = map.get(key)
            if (other >= 0 && link[other] < 0) {
                link[other] = e; link[e] = other
            } else {
                map.put(key, e)
            }
        }

        val visited = BooleanArray(segCount)
        val loops = ArrayList<DoubleArray>()
        val open = ArrayList<DoubleArray>()
        val buf = DoubleArrayBuilder(64)
        for (s0 in 0 until segCount) {
            if (visited[s0]) continue
            buf.clear()
            // Walk forward from end 1 of s0.
            var s = s0
            var exitEnd = 1
            buf.add(segPts[s * 4], segPts[s * 4 + 1])
            var closed = false
            while (true) {
                visited[s] = true
                buf.add(segPts[s * 4 + exitEnd * 2], segPts[s * 4 + exitEnd * 2 + 1])
                val nxt = link[s * 2 + exitEnd]
                if (nxt < 0) break
                val ns = nxt / 2
                if (ns == s0) { closed = true; break }
                if (visited[ns]) break
                s = ns
                exitEnd = 1 - (nxt % 2)
            }
            if (closed) {
                // drop duplicated last point (equals first)
                val arr = buf.toArray()
                loops.add(arr.copyOf(arr.size - 2))
            } else {
                // Walk backwards from end 0 of s0 to complete the chain.
                val back = DoubleArrayBuilder(16)
                var bs = s0
                var bEnd = 0
                while (true) {
                    val nxt = link[bs * 2 + bEnd]
                    if (nxt < 0) break
                    val ns = nxt / 2
                    if (visited[ns]) break
                    visited[ns] = true
                    val ee = 1 - (nxt % 2)
                    back.add(segPts[ns * 4 + ee * 2], segPts[ns * 4 + ee * 2 + 1])
                    bs = ns; bEnd = ee
                }
                val b = back.toArray()
                val f = buf.toArray()
                val merged = DoubleArray(b.size + f.size)
                var o = 0
                var i = b.size - 2
                while (i >= 0) { merged[o++] = b[i]; merged[o++] = b[i + 1]; i -= 2 }
                System.arraycopy(f, 0, merged, o, f.size)
                open.add(merged)
            }
        }
        if (open.isNotEmpty()) loops.addAll(closeOpenChains(open))
        return buildRegion(loops, tol)
    }

    /** Greedily joins open chains end-to-start (handles small non-manifold defects). */
    private fun closeOpenChains(open: MutableList<DoubleArray>): List<DoubleArray> {
        val maxGap = 2.0
        val result = ArrayList<DoubleArray>()
        val pool = open.toMutableList()
        while (pool.isNotEmpty()) {
            var cur = pool.removeAt(pool.size - 1)
            while (true) {
                val ex = cur[cur.size - 2]; val ey = cur[cur.size - 1]
                val sx = cur[0]; val sy = cur[1]
                if (dist(ex, ey, sx, sy) < 1e-3 && cur.size >= 8) { result.add(cur.copyOf(cur.size - 2)); break }
                var best = -1; var bestD = dist(ex, ey, sx, sy); var bestRev = false
                for ((i, c) in pool.withIndex()) {
                    val d0 = dist(ex, ey, c[0], c[1])
                    if (d0 < bestD) { bestD = d0; best = i; bestRev = false }
                    val d1 = dist(ex, ey, c[c.size - 2], c[c.size - 1])
                    if (d1 < bestD) { bestD = d1; best = i; bestRev = true }
                }
                if (best < 0) {
                    if (bestD <= maxGap && cur.size >= 6) result.add(cur)
                    break
                }
                if (bestD > maxGap) break
                var nxt = pool.removeAt(best)
                if (bestRev) nxt = reversePts(nxt)
                cur = cur + nxt
            }
        }
        return result
    }

    private fun reversePts(a: DoubleArray): DoubleArray {
        val r = DoubleArray(a.size)
        val n = a.size / 2
        for (i in 0 until n) { r[i * 2] = a[(n - 1 - i) * 2]; r[i * 2 + 1] = a[(n - 1 - i) * 2 + 1] }
        return r
    }

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = ax - bx; val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private class Loop(val pts: DoubleArray, val area: Double) {
        val minX: Double; val minY: Double; val maxX: Double; val maxY: Double
        var depth = 0
        var parent = -1
        init {
            var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
            var i = 0
            while (i < pts.size) {
                a = min(a, pts[i]); c = max(c, pts[i]); b = min(b, pts[i + 1]); d = max(d, pts[i + 1]); i += 2
            }
            minX = a; minY = b; maxX = c; maxY = d
        }
    }

    private fun buildRegion(raw: List<DoubleArray>, tol: Double): Geometry {
        val loops = raw.map { simplifyRing(it, tol) }
            .filter { it.size >= 6 }
            .map { Loop(it, abs(signedArea(it))) }
            .filter { it.area > 1e-4 }
            .sortedByDescending { it.area }
        if (loops.isEmpty()) return Geo.EMPTY
        for (i in loops.indices) {
            val li = loops[i]
            for (j in i - 1 downTo 0) {
                val lj = loops[j]
                if (li.minX < lj.minX || li.maxX > lj.maxX || li.minY < lj.minY || li.maxY > lj.maxY) continue
                if (pointInRing(lj.pts, li.pts[0], li.pts[1])) {
                    // innermost containing loop is the smallest one, i.e. the one found first scanning downwards
                    li.parent = j; li.depth = lj.depth + 1
                    break
                }
            }
        }
        val polys = ArrayList<Polygon>()
        for ((i, l) in loops.withIndex()) {
            if (l.depth % 2 != 0) continue
            val holes = loops.filterIndexed { k, h -> h.parent == i && h.depth == l.depth + 1 && k != i }.map { it.pts }
            val p = Geo.polygon(l.pts, holes) ?: continue
            if (p.isValid) polys.add(p) else polys.addAll(Geo.polygons(GeometryFixer.fix(p)))
        }
        return if (polys.size == 1) polys[0] else Geo.union(polys)
    }

    companion object {
        fun signedArea(p: DoubleArray): Double {
            var a = 0.0
            val n = p.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                a += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
            }
            return a / 2
        }

        fun pointInRing(p: DoubleArray, x: Double, y: Double): Boolean {
            var inside = false
            val n = p.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = p[i * 2]; val yi = p[i * 2 + 1]; val xj = p[j * 2]; val yj = p[j * 2 + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        /** Radial-distance + Douglas-Peucker simplification of a closed ring. */
        fun simplifyRing(p: DoubleArray, tol: Double): DoubleArray {
            val n = p.size / 2
            if (n < 4 || tol <= 0) return p
            // remove near-duplicate consecutive points first
            val tmp = DoubleArrayBuilder(p.size)
            tmp.add(p[0], p[1])
            for (i in 1 until n) {
                val lx = tmp[tmp.size - 2]; val ly = tmp[tmp.size - 1]
                val dx = p[i * 2] - lx; val dy = p[i * 2 + 1] - ly
                if (dx * dx + dy * dy > tol * tol * 0.25) tmp.add(p[i * 2], p[i * 2 + 1])
            }
            val q = tmp.toArray()
            val m = q.size / 2
            if (m < 4) return q
            val keep = BooleanArray(m)
            // split the ring at the point farthest from point 0
            var far = 0; var farD = -1.0
            for (i in 1 until m) {
                val dx = q[i * 2] - q[0]; val dy = q[i * 2 + 1] - q[1]
                val d = dx * dx + dy * dy
                if (d > farD) { farD = d; far = i }
            }
            keep[0] = true; keep[far] = true
            dp(q, 0, far, tol * tol, keep)
            dpWrap(q, far, m, tol * tol, keep)
            val out = DoubleArrayBuilder(m * 2)
            for (i in 0 until m) if (keep[i]) out.add(q[i * 2], q[i * 2 + 1])
            return out.toArray()
        }

        private fun dpWrap(q: DoubleArray, from: Int, m: Int, tol2: Double, keep: BooleanArray) {
            // segment from 'from' to index m (== 0 wrapped)
            val idx = IntArray(m - from + 1) { if (from + it == m) 0 else from + it }
            dpIdx(q, idx, 0, idx.size - 1, tol2, keep)
        }

        private fun dp(q: DoubleArray, a: Int, b: Int, tol2: Double, keep: BooleanArray) {
            val idx = IntArray(b - a + 1) { a + it }
            dpIdx(q, idx, 0, idx.size - 1, tol2, keep)
        }

        private fun dpIdx(q: DoubleArray, idx: IntArray, a: Int, b: Int, tol2: Double, keep: BooleanArray) {
            if (b <= a + 1) return
            val ax = q[idx[a] * 2]; val ay = q[idx[a] * 2 + 1]
            val bx = q[idx[b] * 2]; val by = q[idx[b] * 2 + 1]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var best = -1; var bestD = tol2
            for (k in a + 1 until b) {
                val px = q[idx[k] * 2]; val py = q[idx[k] * 2 + 1]
                val d = if (len2 < 1e-18) (px - ax) * (px - ax) + (py - ay) * (py - ay) else {
                    val c = (px - ax) * dy - (py - ay) * dx
                    c * c / len2
                }
                if (d > bestD) { bestD = d; best = k }
            }
            if (best >= 0) {
                keep[idx[best]] = true
                dpIdx(q, idx, a, best, tol2, keep)
                dpIdx(q, idx, best, b, tol2, keep)
            }
        }
    }
}

internal fun ceilDiv(a: Double, b: Double) = ceil(a / b)
