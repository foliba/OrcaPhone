package com.orcaphone.core.slice

import com.orcaphone.core.config.InfillPattern
import com.orcaphone.core.mesh.DoubleArrayBuilder
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Infill line generation: scanline clipping of parallel lines, gyroid curves and concentric loops. */
object Infill {

    /**
     * Parallel lines at [spacing] and [angleDeg] clipped to [area] using an even-odd scanline.
     * Lines are aligned to a global grid (plus [shift]) so they stack across layers.
     * Returns segments as [x0,y0,x1,y1] in scanline order (alternating direction).
     */
    fun lines(area: Geometry, spacing: Double, angleDeg: Double, shift: Double = 0.0, minLength: Double = 0.0): List<DoubleArray> {
        if (area.isEmpty || spacing <= 0) return emptyList()
        val a = angleDeg * PI / 180
        val c = cos(a); val s = sin(a)
        // rotate area by -a so lines become horizontal
        val edges = DoubleArrayBuilder(256)
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (loop in Geo.loops(area)) {
            val n = loop.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                val x1 = loop[i * 2] * c + loop[i * 2 + 1] * s
                val y1 = -loop[i * 2] * s + loop[i * 2 + 1] * c
                val x2 = loop[j * 2] * c + loop[j * 2 + 1] * s
                val y2 = -loop[j * 2] * s + loop[j * 2 + 1] * c
                if (y1 == y2) continue
                edges.add(x1); edges.add(y1); edges.add(x2); edges.add(y2)
                if (y1 < minY) minY = y1; if (y1 > maxY) maxY = y1
                if (y2 < minY) minY = y2; if (y2 > maxY) maxY = y2
            }
        }
        val e = edges.toArray()
        val ne = e.size / 4
        if (ne == 0) return emptyList()
        // sort edges by min y for a sweep
        val order = (0 until ne).sortedBy { minOf(e[it * 4 + 1], e[it * 4 + 3]) }.toIntArray()
        val active = ArrayList<Int>()
        var next = 0
        val kStart = ceil((minY - shift) / spacing).toLong()
        val kEnd = floor((maxY - shift) / spacing).toLong()
        val out = ArrayList<DoubleArray>()
        val xs = DoubleArrayBuilder(32)
        var flip = false
        for (k in kStart..kEnd) {
            val y = k * spacing + shift
            while (next < ne && minOf(e[order[next] * 4 + 1], e[order[next] * 4 + 3]) <= y) active.add(order[next++])
            xs.clear()
            val it = active.iterator()
            while (it.hasNext()) {
                val id = it.next()
                val y1 = e[id * 4 + 1]; val y2 = e[id * 4 + 3]
                if (maxOf(y1, y2) < y) { it.remove(); continue }
                if ((y1 <= y) != (y2 <= y)) {
                    val x1 = e[id * 4]; val x2 = e[id * 4 + 2]
                    xs.add(x1 + (y - y1) * (x2 - x1) / (y2 - y1))
                }
            }
            if (xs.size < 2) continue
            val arr = xs.toArray()
            arr.sort()
            val row = ArrayList<DoubleArray>()
            var i = 0
            while (i + 1 < arr.size) {
                val xa = arr[i]; val xb = arr[i + 1]
                if (xb - xa >= minLength) {
                    row.add(doubleArrayOf(xa * c - y * s, xa * s + y * c, xb * c - y * s, xb * s + y * c))
                }
                i += 2
            }
            if (flip) {
                row.reverse()
                for (r in row) { var t = r[0]; r[0] = r[2]; r[2] = t; t = r[1]; r[1] = r[3]; r[3] = t }
            }
            flip = !flip
            out.addAll(row)
        }
        return out
    }

    /** Sparse infill polylines for [pattern] at [density] (0..1). */
    fun sparse(
        area: Geometry, pattern: InfillPattern, density: Double, width: Double,
        layerIndex: Int, z: Double, baseAngle: Double,
    ): List<DoubleArray> {
        if (density <= 0.0 || area.isEmpty) return emptyList()
        val d = density.coerceAtMost(1.0)
        val minLen = width * 0.8
        return when (pattern) {
            InfillPattern.GRID -> {
                val sp = 2 * width / d
                lines(area, sp, baseAngle, 0.0, minLen) + lines(area, sp, baseAngle + 90, 0.0, minLen)
            }
            InfillPattern.RECTILINEAR -> {
                val sp = width / d
                lines(area, sp, baseAngle + if (layerIndex % 2 == 0) 0.0 else 90.0, 0.0, minLen)
            }
            InfillPattern.TRIANGLES -> {
                val sp = 3 * width / d
                lines(area, sp, baseAngle, 0.0, minLen) + lines(area, sp, baseAngle + 60, 0.0, minLen) +
                    lines(area, sp, baseAngle + 120, 0.0, minLen)
            }
            InfillPattern.CUBIC -> {
                val sp = 3 * width / d
                val shift = (z / sqrt(2.0)) % sp
                lines(area, sp, baseAngle, shift, minLen) + lines(area, sp, baseAngle + 60, shift, minLen) +
                    lines(area, sp, baseAngle + 120, shift, minLen)
            }
            // same density scaling as Slic3r/Orca's FillGyroid (period = 2π · spacing / (2.44 · density))
            InfillPattern.GYROID -> gyroid(area, 2 * PI * width / (2.44 * d), z, minLen)
            InfillPattern.CONCENTRIC -> concentric(area, width / d)
        }
    }

    /**
     * Gyroid: sin(X)cos(Y) + sin(Y)cos(Z) + sin(Z)cos(X) = 0 with X = x*2π/period.
     * Solved analytically for Y(X) (or X(Y)) at the layer's Z, then clipped to the area.
     */
    fun gyroid(area: Geometry, period: Double, z: Double, minLen: Double): List<DoubleArray> {
        val env = area.envelopeInternal
        val f = 2 * PI / period
        val zz = z * f
        val sinZ = sin(zz); val cosZ = cos(zz)
        val alongX = abs(cosZ) >= abs(sinZ)
        val step = period / 16
        val polys = ArrayList<DoubleArray>()
        val (uMin, uMax, vMin, vMax) = if (alongX) arrayOf(env.minX, env.maxX, env.minY, env.maxY) else arrayOf(env.minY, env.maxY, env.minX, env.maxX)
        val kMin = floor(vMin * f / (2 * PI)).toInt() - 1
        val kMax = ceil(vMax * f / (2 * PI)).toInt() + 1
        for (k in kMin..kMax) {
            for (branch in 0..1) {
                val b = DoubleArrayBuilder(256)
                var u = floor(uMin / step) * step - step
                var prevV = Double.NaN
                while (u <= uMax + step) {
                    val U = u * f
                    // pa*sin(V) + pb*cos(V) = rhs  ->  R cos(V - phi) = rhs
                    // along X (V = Y): sinX cosY + cosZ sinY = -sinZ cosX
                    // along Y (V = X): cosY sinX + sinZ cosX = -sinY cosZ
                    val pa: Double; val pb: Double; val rhs: Double
                    if (alongX) { pa = cosZ; pb = sin(U); rhs = -sinZ * cos(U) } else { pa = cos(U); pb = sinZ; rhs = -sin(U) * cosZ }
                    val r = sqrt(pa * pa + pb * pb)
                    val phi = atan2(pa, pb)
                    val acosv = acos((rhs / r).coerceIn(-1.0, 1.0))
                    var V = phi + (if (branch == 0) acosv else -acosv) + 2 * PI * k
                    // atan2 wraps at +-PI: keep the curve continuous
                    if (!prevV.isNaN()) V -= 2 * PI * Math.round((V - prevV) / (2 * PI))
                    prevV = V
                    val vv = V / f
                    if (alongX) b.add(u, vv) else b.add(vv, u)
                    u += step
                }
                polys.add(b.toArray())
            }
        }
        return clipPolylines(area, polys, minLen)
    }

    fun concentric(area: Geometry, spacing: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var g = Geo.offset(area, -spacing / 2)
        var guard = 0
        while (!g.isEmpty && guard++ < 500) {
            for (l in Geo.loops(g)) out.add(closeLoop(l))
            g = Geo.offset(g, -spacing)
        }
        return out
    }

    fun closeLoop(l: DoubleArray): DoubleArray {
        val r = l.copyOf(l.size + 2)
        r[l.size] = l[0]; r[l.size + 1] = l[1]
        return r
    }

    fun clipPolylines(area: Geometry, polys: List<DoubleArray>, minLen: Double): List<DoubleArray> {
        if (polys.isEmpty()) return emptyList()
        val lines = polys.filter { it.size >= 4 }.map { p ->
            Geo.factory.createLineString(Array(p.size / 2) { Coordinate(p[it * 2], p[it * 2 + 1]) })
        }
        val ml = Geo.factory.createMultiLineString(lines.toTypedArray())
        val clipped = try { ml.intersection(area) } catch (_: RuntimeException) { return emptyList() }
        val out = ArrayList<DoubleArray>()
        for (i in 0 until clipped.numGeometries) {
            val g = clipped.getGeometryN(i)
            if (g is LineString && g.length >= minLen) {
                val cs = g.coordinates
                out.add(DoubleArray(cs.size * 2).also { a -> cs.forEachIndexed { j, c -> a[j * 2] = c.x; a[j * 2 + 1] = c.y } })
            }
        }
        return out
    }

    /**
     * Orders open polylines nearest-neighbour from (sx, sy), reversing when shorter, and joins consecutive
     * lines with a short connector when the connector stays inside [area] (zig-zag connection).
     */
    fun chain(
        polys: List<DoubleArray>, sx: Double, sy: Double, area: Geometry?, joinDist: Double,
        keepOrder: Boolean = false,
    ): List<DoubleArray> {
        if (polys.isEmpty()) return emptyList()
        val n = polys.size
        val used = BooleanArray(n)
        val ordered = ArrayList<DoubleArray>(n)
        var cx = sx; var cy = sy
        if (keepOrder) {
            for (p in polys) ordered.add(p)
        } else {
            // spatial hashing would be faster; plain NN is fine for a few thousand lines
            for (step in 0 until n) {
                var best = -1; var bestD = Double.MAX_VALUE; var rev = false
                for (i in 0 until n) {
                    if (used[i]) continue
                    val p = polys[i]
                    val d0 = d2(cx, cy, p[0], p[1])
                    if (d0 < bestD) { bestD = d0; best = i; rev = false }
                    val d1 = d2(cx, cy, p[p.size - 2], p[p.size - 1])
                    if (d1 < bestD) { bestD = d1; best = i; rev = true }
                }
                used[best] = true
                val p = if (rev) reverse(polys[best]) else polys[best]
                ordered.add(p)
                cx = p[p.size - 2]; cy = p[p.size - 1]
            }
        }
        if (area == null || joinDist <= 0) return ordered
        val prepared = PreparedGeometryFactory.prepare(Geo.offset(area, 0.05))
        val result = ArrayList<DoubleArray>()
        var cur = DoubleArrayBuilder(64)
        fun flush() { if (cur.size >= 4) result.add(cur.toArray()); cur = DoubleArrayBuilder(64) }
        for (p in ordered) {
            if (cur.size >= 2) {
                val ex = cur[cur.size - 2]; val ey = cur[cur.size - 1]
                val dd = d2(ex, ey, p[0], p[1])
                val ok = dd <= joinDist * joinDist && dd > 1e-12 &&
                    prepared.covers(Geo.factory.createLineString(arrayOf(Coordinate(ex, ey), Coordinate(p[0], p[1]))))
                if (!ok) flush()
            }
            var i = 0
            while (i < p.size) {
                if (cur.size >= 2 && i == 0 && d2(cur[cur.size - 2], cur[cur.size - 1], p[0], p[1]) < 1e-12) { i += 2; continue }
                cur.add(p[i], p[i + 1]); i += 2
            }
        }
        flush()
        return result
    }

    fun reverse(p: DoubleArray): DoubleArray {
        val n = p.size / 2
        val r = DoubleArray(p.size)
        for (i in 0 until n) { r[i * 2] = p[(n - 1 - i) * 2]; r[i * 2 + 1] = p[(n - 1 - i) * 2 + 1] }
        return r
    }

    private fun d2(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = ax - bx; val dy = ay - by
        return dx * dx + dy * dy
    }
}
