package com.orcaphone.core.cut

import com.orcaphone.core.math.Vec3
import com.orcaphone.core.mesh.DoubleArrayBuilder
import com.orcaphone.core.mesh.FloatArrayBuilder
import com.orcaphone.core.mesh.IntArrayBuilder
import com.orcaphone.core.mesh.Mesh
import com.orcaphone.core.slice.Geo
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.triangulate.polygon.ConstrainedDelaunayTriangulator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Right-handed cut frame: [n] is the cut normal, [u] runs across the cut profile and [s] = u × n is the
 * direction the cut surface is extruded along (the direction dovetail parts slide together).
 */
class CutFrame(val origin: Vec3, val u: Vec3, val n: Vec3) {
    val s: Vec3 = u cross n

    companion object {
        /** Cut perpendicular to world axis 0 = X, 1 = Y, 2 = Z at coordinate [at]. */
        fun axis(axis: Int, at: Double): CutFrame = when (axis) {
            0 -> CutFrame(Vec3(at, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0))
            1 -> CutFrame(Vec3(0.0, at, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
            else -> CutFrame(Vec3(0.0, 0.0, at), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0))
        }
    }
}

/**
 * The cut surface in the frame's (u, n) plane, extruded along s: a polyline running from u = -big to
 * u = +big. Material below it (smaller n) is the "below" part.
 */
class CutProfile(val points: List<DoubleArray>) {
    init { require(points.size >= 2) }

    /** Cumulative arc length at each point (the unfolded cut surface coordinate). */
    val arc: DoubleArray = DoubleArray(points.size).also {
        for (i in 1 until points.size) it[i] = it[i - 1] + dist(points[i - 1], points[i])
    }

    companion object {
        fun flat(big: Double) = CutProfile(listOf(doubleArrayOf(-big, 0.0), doubleArrayOf(big, 0.0)))

        /**
         * A dovetail tail centred at [center]: [neck] wide at the cut plane, [width] wide at height [depth].
         * [clearance] grows the shape for the matching slot.
         */
        fun dovetail(big: Double, center: Double, neck: Double, width: Double, depth: Double, clearance: Double = 0.0): CutProfile {
            val n2 = neck / 2 + clearance
            val w2 = width / 2 + clearance
            val h = depth + clearance
            return CutProfile(
                listOf(
                    doubleArrayOf(-big, 0.0), doubleArrayOf(center - n2, 0.0), doubleArrayOf(center - w2, h),
                    doubleArrayOf(center + w2, h), doubleArrayOf(center + n2, 0.0), doubleArrayOf(big, 0.0),
                ),
            )
        }

        private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]))
    }
}

/**
 * Splits closed triangle meshes along a [CutProfile] and closes the opening with flat caps, so each
 * result is again a closed (watertight) mesh.
 *
 * The mesh surface is split along the supporting lines of every profile segment, with intersection
 * points computed canonically per edge so neighbouring triangles agree exactly. The cap outline is taken
 * from the boundary of the kept surface itself, unfolded onto the profile (arc length × s), filled with
 * JTS and triangulated per profile segment, then the result is welded.
 */
object MeshCutter {
    /** Points closer than this to a profile line count as on it (for splitting and for cap outlines). */
    private const val EPS = 1e-7

    /** Returns the part of [mesh] below ([keepBelow]) or above the profile, capped. */
    fun clip(mesh: Mesh, frame: CutFrame, profile: CutProfile, keepBelow: Boolean): Mesh {
        val local = toLocal(mesh, frame)
        val lines = supportingLines(profile)
        val region = regionPolygon(profile)
        val soup = DoubleArrayBuilder(mesh.triangleCount * 9 + 64)
        val boundary = DoubleArrayBuilder(256) // a0, s0, a1, s1 per segment
        var sMin = Double.MAX_VALUE; var sMax = -Double.MAX_VALUE
        val idx = mesh.indices
        val cells = ArrayList<List<DoubleArray>>(4)
        val next = ArrayList<List<DoubleArray>>(4)
        for (t in 0 until mesh.triangleCount) {
            val tri = List(3) { k -> local[idx[t * 3 + k]] }
            for (p in tri) { if (p[2] < sMin) sMin = p[2]; if (p[2] > sMax) sMax = p[2] }
            cells.clear(); cells.add(tri)
            for (line in lines) {
                next.clear()
                for (c in cells) splitConvex(c, line, next)
                cells.clear(); cells.addAll(next)
            }
            for (c in cells) {
                if (c.size < 3) continue
                var cu = 0.0; var cn = 0.0
                for (p in c) { cu += p[0]; cn += p[1] }
                cu /= c.size; cn /= c.size
                if (insideRegion(region, cu, cn) != keepBelow) continue
                for (k in 1 until c.size - 1) emit(soup, c[0], c[k], c[k + 1])
                for (k in c.indices) {
                    val p = c[k]; val q = c[(k + 1) % c.size]
                    if (!onProfile(profile, p) || !onProfile(profile, q)) continue
                    val mid = doubleArrayOf((p[0] + q[0]) / 2, (p[1] + q[1]) / 2, 0.0)
                    if (!onProfile(profile, mid)) continue
                    boundary.add(arcOf(profile, p), p[2]); boundary.add(arcOf(profile, q), q[2])
                }
            }
        }
        if (soup.size == 0) return Mesh(FloatArray(0), IntArray(0))
        val capRegion = buildRegion(boundary.toArray())
        if (!capRegion.isEmpty) addCaps(soup, capRegion, profile, keepBelow, sMin - 1, sMax + 1)
        return removeSlivers(weld(toWorld(soup.toArray(), frame), 1e-4))
    }

    /** The cross-section of [mesh] in the frame's plane n = 0, in (u, s) coordinates. */
    fun section(mesh: Mesh, frame: CutFrame): Geometry {
        val local = toLocal(mesh, frame)
        val idx = mesh.indices
        val seg = DoubleArrayBuilder(256)
        for (t in 0 until mesh.triangleCount) {
            val a = local[idx[t * 3]]; val b = local[idx[t * 3 + 1]]; val c = local[idx[t * 3 + 2]]
            val pts = ArrayList<DoubleArray>(2)
            for ((p, q) in listOf(a to b, b to c, c to a)) {
                if ((p[1] > 0) != (q[1] > 0)) pts.add(intersect(p, q, p[1], q[1]))
            }
            if (pts.size == 2) { seg.add(pts[0][0], pts[0][2]); seg.add(pts[1][0], pts[1][2]) }
        }
        return buildRegion(seg.toArray())
    }

    // ------------------------------------------------------------------ geometry helpers

    private fun toLocal(mesh: Mesh, f: CutFrame): Array<DoubleArray> {
        val v = mesh.vertices
        return Array(mesh.vertexCount) { i ->
            val x = v[i * 3] - f.origin.x; val y = v[i * 3 + 1] - f.origin.y; val z = v[i * 3 + 2] - f.origin.z
            doubleArrayOf(
                x * f.u.x + y * f.u.y + z * f.u.z,
                x * f.n.x + y * f.n.y + z * f.n.z,
                x * f.s.x + y * f.s.y + z * f.s.z,
            )
        }
    }

    private fun toWorld(soup: DoubleArray, f: CutFrame): DoubleArray {
        val out = DoubleArray(soup.size)
        var i = 0
        while (i < soup.size) {
            val a = soup[i]; val b = soup[i + 1]; val c = soup[i + 2]
            out[i] = f.origin.x + a * f.u.x + b * f.n.x + c * f.s.x
            out[i + 1] = f.origin.y + a * f.u.y + b * f.n.y + c * f.s.y
            out[i + 2] = f.origin.z + a * f.u.z + b * f.n.z + c * f.s.z
            i += 3
        }
        return out
    }

    /** Lines a*u + b*n = c (unit (a,b)) supporting the profile segments, without duplicates. */
    private fun supportingLines(p: CutProfile): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (i in 0 until p.points.size - 1) {
            val s = p.points[i]; val e = p.points[i + 1]
            var a = -(e[1] - s[1]); var b = e[0] - s[0]
            val l = sqrt(a * a + b * b)
            if (l < 1e-12) continue
            a /= l; b /= l
            val c = a * s[0] + b * s[1]
            if (out.none { abs(it[0] * a + it[1] * b) > 1 - 1e-12 && abs(it[2] - c * (it[0] * a + it[1] * b)) < 1e-9 }) {
                out.add(doubleArrayOf(a, b, c))
            }
        }
        return out
    }

    /** The "below" region as a closed polygon in (u, n). */
    private fun regionPolygon(p: CutProfile): DoubleArray {
        val pts = ArrayList<Double>()
        for (q in p.points) { pts.add(q[0]); pts.add(q[1]) }
        val last = p.points.last(); val first = p.points.first()
        val big = max(abs(first[0]), abs(last[0])) * 2 + 1
        pts.add(last[0]); pts.add(-big); pts.add(first[0]); pts.add(-big)
        return pts.toDoubleArray()
    }

    private fun insideRegion(poly: DoubleArray, x: Double, y: Double): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]; val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Splits a convex polygon by a line into its parts on either side (both kept). */
    private fun splitConvex(poly: List<DoubleArray>, line: DoubleArray, out: MutableList<List<DoubleArray>>) {
        val d = DoubleArray(poly.size) { line[0] * poly[it][0] + line[1] * poly[it][1] - line[2] }
        var neg = false; var pos = false
        for (x in d) { if (x < -EPS) neg = true; if (x > EPS) pos = true }
        if (!neg || !pos) { out.add(poly); return }
        val a = ArrayList<DoubleArray>(poly.size + 1)
        val b = ArrayList<DoubleArray>(poly.size + 1)
        for (i in poly.indices) {
            val j = (i + 1) % poly.size
            val p = poly[i]; val dp = d[i]; val dq = d[j]
            if (dp <= EPS) a.add(p)
            if (dp >= -EPS) b.add(p)
            if ((dp < -EPS && dq > EPS) || (dp > EPS && dq < -EPS)) {
                val x = intersect(p, poly[j], dp, dq)
                a.add(x); b.add(x)
            }
        }
        if (a.size >= 3) out.add(a)
        if (b.size >= 3) out.add(b)
    }

    /** Edge/line intersection computed from the lexicographically ordered endpoints, so it is identical for both triangles sharing the edge. */
    private fun intersect(p: DoubleArray, q: DoubleArray, dp: Double, dq: Double): DoubleArray {
        val swap = compare(p, q) > 0
        val a = if (swap) q else p; val b = if (swap) p else q
        val da = if (swap) dq else dp; val db = if (swap) dp else dq
        val t = da / (da - db)
        return doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
    }

    private fun compare(p: DoubleArray, q: DoubleArray): Int {
        for (k in 0 until 3) { val c = p[k].compareTo(q[k]); if (c != 0) return c }
        return 0
    }

    private fun emit(soup: DoubleArrayBuilder, a: DoubleArray, b: DoubleArray, c: DoubleArray) {
        for (p in arrayOf(a, b, c)) { soup.add(p[0]); soup.add(p[1]); soup.add(p[2]) }
    }

    private fun segDist(p: CutProfile, i: Int, u: Double, n: Double): Pair<Double, Double> {
        val s = p.points[i]; val e = p.points[i + 1]
        val dx = e[0] - s[0]; val dy = e[1] - s[1]
        val l2 = dx * dx + dy * dy
        val t = if (l2 <= 0) 0.0 else (((u - s[0]) * dx + (n - s[1]) * dy) / l2).coerceIn(0.0, 1.0)
        val px = s[0] + dx * t - u; val py = s[1] + dy * t - n
        return sqrt(px * px + py * py) to t * sqrt(l2)
    }

    private fun onProfile(p: CutProfile, q: DoubleArray): Boolean {
        val tol = 2 * EPS + 1e-12 * (abs(q[0]) + abs(q[1]))
        for (i in 0 until p.points.size - 1) if (segDist(p, i, q[0], q[1]).first <= tol) return true
        return false
    }

    /** Arc-length coordinate of a point on the profile. */
    private fun arcOf(p: CutProfile, q: DoubleArray): Double {
        var best = Double.MAX_VALUE; var arc = 0.0
        for (i in 0 until p.points.size - 1) {
            val (d, along) = segDist(p, i, q[0], q[1])
            if (d < best) { best = d; arc = p.arc[i] + along }
        }
        return arc
    }

    // ------------------------------------------------------------------ caps

    /** Chains (undirected) boundary segments into closed loops and fills them even-odd. */
    private fun buildRegion(seg: DoubleArray): Geometry {
        val count = seg.size / 4
        if (count < 3) return Geo.EMPTY
        // Exact endpoint matching: points come from shared, canonically computed vertices.
        val ends = HashMap<Pair<Double, Double>, MutableList<Int>>() // value = segment * 2 + end
        for (i in 0 until count) {
            ends.getOrPut(seg[i * 4] to seg[i * 4 + 1]) { ArrayList(2) }.add(i * 2)
            ends.getOrPut(seg[i * 4 + 2] to seg[i * 4 + 3]) { ArrayList(2) }.add(i * 2 + 1)
        }
        val used = BooleanArray(count)
        val rings = ArrayList<LinearRing>()
        for (start in 0 until count) {
            if (used[start]) continue
            used[start] = true
            val sx = seg[start * 4]; val sy = seg[start * 4 + 1]
            val coords = arrayListOf(Coordinate(sx, sy))
            var x = seg[start * 4 + 2]; var y = seg[start * 4 + 3]
            var closed = false
            while (true) {
                if (x == sx && y == sy) { closed = true; break }
                coords.add(Coordinate(x, y))
                val hit = ends[x to y]?.firstOrNull { !used[it / 2] } ?: nearestEnd(seg, used, x, y)
                if (hit == null) break
                val j = hit / 2
                used[j] = true
                val other = 1 - hit % 2
                x = seg[j * 4 + other * 2]; y = seg[j * 4 + other * 2 + 1]
            }
            if (!closed && coords.last().distance(coords[0]) > 0.05) continue
            if (coords.size < 3) continue
            coords.add(Coordinate(coords[0]))
            rings.add(Geo.factory.createLinearRing(coords.toTypedArray()))
        }
        return evenOdd(rings)
    }

    /** Fallback for tiny numeric gaps: the closest unused segment end within 1 µm. */
    private fun nearestEnd(seg: DoubleArray, used: BooleanArray, x: Double, y: Double): Int? {
        var best: Int? = null; var bestD = 1e-3
        for (i in used.indices) {
            if (used[i]) continue
            for (e in 0..1) {
                val d = abs(seg[i * 4 + e * 2] - x) + abs(seg[i * 4 + e * 2 + 1] - y)
                if (d < bestD) { bestD = d; best = i * 2 + e }
            }
        }
        return best
    }

    private fun evenOdd(rings: List<LinearRing>): Geometry {
        if (rings.isEmpty()) return Geo.EMPTY
        val polys = rings.map { Geo.factory.createPolygon(it) }.filter { it.area > 1e-9 }.sortedByDescending { it.area }
        val prepared = polys.map { PreparedGeometryFactory.prepare(it) }
        val depth = IntArray(polys.size)
        for (i in polys.indices) {
            val pt = polys[i].interiorPoint
            for (j in 0 until i) if (prepared[j].contains(pt)) depth[i]++
        }
        val result = ArrayList<Polygon>()
        for (i in polys.indices) {
            if (depth[i] % 2 != 0) continue
            val holes = ArrayList<LinearRing>()
            for (j in i + 1 until polys.size) {
                if (depth[j] == depth[i] + 1 && prepared[i].contains(polys[j].interiorPoint)) holes.add(polys[j].exteriorRing)
            }
            var p: Geometry = Geo.factory.createPolygon(polys[i].exteriorRing, holes.toTypedArray())
            if (!p.isValid) p = GeometryFixer.fix(p)
            for (k in 0 until p.numGeometries) (p.getGeometryN(k) as? Polygon)?.let { result.add(it) }
        }
        return if (result.size == 1) result[0] else Geo.factory.createMultiPolygon(result.toTypedArray())
    }

    private fun addCaps(soup: DoubleArrayBuilder, region: Geometry, p: CutProfile, keepBelow: Boolean, s0: Double, s1: Double) {
        for (i in 0 until p.points.size - 1) {
            val a0 = p.arc[i]; val a1 = p.arc[i + 1]
            if (a1 - a0 < 1e-12) continue
            val env = Envelope(a0, a1, s0, s1)
            if (!env.intersects(region.envelopeInternal)) continue
            val part = Geo.intersection(region, Geo.factory.toGeometry(env))
            if (part.isEmpty) continue
            val tris = try { ConstrainedDelaunayTriangulator.triangulate(part) } catch (e: RuntimeException) { continue }
            val st = p.points[i]; val en = p.points[i + 1]
            val du = (en[0] - st[0]) / (a1 - a0); val dn = (en[1] - st[1]) / (a1 - a0)
            // Left normal of the segment points to the "above" side.
            val want = if (keepBelow) doubleArrayOf(-dn, du, 0.0) else doubleArrayOf(dn, -du, 0.0)
            fun map(c: Coordinate) = doubleArrayOf(st[0] + (c.x - a0) * du, st[1] + (c.x - a0) * dn, c.y)
            for (k in 0 until tris.numGeometries) {
                val cs = tris.getGeometryN(k).coordinates
                if (cs.size < 4) continue
                val a = map(cs[0]); var b = map(cs[1]); var c = map(cs[2])
                val nx = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1])
                val ny = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2])
                val nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
                if (nx * want[0] + ny * want[1] + nz * want[2] < 0) { val tmp = b; b = c; c = tmp }
                emit(soup, a, b, c)
            }
        }
    }

    /**
     * Drops tiny triangles none of whose edges are shared with the rest of the mesh: numeric slivers
     * that would otherwise leave the part not quite closed.
     */
    private fun removeSlivers(m: Mesh): Mesh {
        val edges = HashSet<Long>(m.indices.size * 2)
        for (t in 0 until m.triangleCount) for (k in 0 until 3) {
            edges.add((m.indices[t * 3 + k].toLong() shl 32) or m.indices[t * 3 + (k + 1) % 3].toLong())
        }
        val keep = IntArrayBuilder(m.indices.size)
        var dropped = false
        for (t in 0 until m.triangleCount) {
            var shared = 0
            for (k in 0 until 3) {
                val a = m.indices[t * 3 + k].toLong(); val b = m.indices[t * 3 + (k + 1) % 3].toLong()
                if (edges.contains((b shl 32) or a)) shared++
            }
            if (shared == 0 && m.faceNormal(t).length() < 0.02) { dropped = true; continue }
            for (k in 0 until 3) keep.add(m.indices[t * 3 + k])
        }
        return if (dropped) Mesh(m.vertices, keep.toArray()) else m
    }

    // ------------------------------------------------------------------ welding

    /**
     * Merges vertices closer than [tol] and drops degenerate triangles. Identical coordinates always
     * become one vertex; distinct points within [tol] are then joined with union-find, so the result does
     * not depend on the order points arrive in.
     */
    fun weld(soup: DoubleArray, tol: Double): Mesh {
        val exact = HashMap<Triple<Double, Double, Double>, Int>(soup.size / 6)
        val pts = DoubleArrayBuilder(soup.size / 2)
        val corner = IntArray(soup.size / 3)
        for (c in corner.indices) {
            val k = Triple(soup[c * 3], soup[c * 3 + 1], soup[c * 3 + 2])
            corner[c] = exact.getOrPut(k) {
                pts.add(k.first); pts.add(k.second); pts.add(k.third)
                pts.size / 3 - 1
            }
        }
        val n = pts.size / 3
        val parent = IntArray(n) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var x = i
            while (parent[x] != r) { val nx = parent[x]; parent[x] = r; x = nx }
            return r
        }
        val inv = 1.0 / tol
        fun key(x: Long, y: Long, z: Long) = ((x and 0x1FFFFF) shl 42) or ((y and 0x1FFFFF) shl 21) or (z and 0x1FFFFF)
        val grid = HashMap<Long, IntArrayBuilder>(n)
        for (i in 0 until n) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]; val z = pts[i * 3 + 2]
            val gx = floor(x * inv).toLong(); val gy = floor(y * inv).toLong(); val gz = floor(z * inv).toLong()
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val cell = grid[key(gx + dx, gy + dy, gz + dz)] ?: continue
                for (k in 0 until cell.size) {
                    val j = cell[k]
                    val ex = pts[j * 3] - x; val ey = pts[j * 3 + 1] - y; val ez = pts[j * 3 + 2] - z
                    if (ex * ex + ey * ey + ez * ez <= tol * tol) {
                        val ri = root(i); val rj = root(j)
                        if (ri != rj) parent[maxOf(ri, rj)] = minOf(ri, rj)
                    }
                }
            }
            grid.getOrPut(key(gx, gy, gz)) { IntArrayBuilder(2) }.add(i)
        }
        val id = IntArray(n) { -1 }
        val verts = FloatArrayBuilder(n * 3)
        var next = 0
        for (i in 0 until n) {
            val r = root(i)
            if (id[r] < 0) {
                id[r] = next++
                verts.add(pts[r * 3].toFloat()); verts.add(pts[r * 3 + 1].toFloat()); verts.add(pts[r * 3 + 2].toFloat())
            }
        }
        val idx = IntArrayBuilder(soup.size / 3)
        var c = 0
        while (c + 2 < corner.size) {
            val a = id[root(corner[c])]; val b = id[root(corner[c + 1])]; val d = id[root(corner[c + 2])]
            if (a != b && b != d && a != d) { idx.add(a); idx.add(b); idx.add(d) }
            c += 3
        }
        return Mesh(verts.toArray(), idx.toArray())
    }
}
