package com.orcaphone.core.slice

import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

/** Thin, exception-safe wrappers around JTS operations used by the slicer. */
object Geo {
    val factory = GeometryFactory(PrecisionModel(), 0)
    val EMPTY: Geometry = factory.createPolygon()

    private val mitre = BufferParameters().apply {
        joinStyle = BufferParameters.JOIN_MITRE
        mitreLimit = 3.0
        quadrantSegments = 4
    }
    private val round = BufferParameters().apply {
        joinStyle = BufferParameters.JOIN_ROUND
        quadrantSegments = 6
    }

    fun offset(g: Geometry, d: Double, roundJoins: Boolean = false): Geometry {
        if (g.isEmpty) return EMPTY
        if (d == 0.0) return g
        return try {
            polygonal(BufferOp.bufferOp(g, d, if (roundJoins) round else mitre))
        } catch (e: RuntimeException) {
            try { polygonal(BufferOp.bufferOp(GeometryFixer.fix(g), d, if (roundJoins) round else mitre)) } catch (_: RuntimeException) { EMPTY }
        }
    }

    /** Morphological opening: removes features thinner than 2*r. */
    fun open(g: Geometry, r: Double): Geometry = offset(offset(g, -r), r)

    private fun op(a: Geometry, b: Geometry, code: Int): Geometry = try {
        polygonal(OverlayNGRobust.overlay(a, b, code))
    } catch (e: RuntimeException) {
        try { polygonal(OverlayNGRobust.overlay(GeometryFixer.fix(a), GeometryFixer.fix(b), code)) } catch (_: RuntimeException) { a }
    }

    fun intersection(a: Geometry, b: Geometry): Geometry =
        if (a.isEmpty || b.isEmpty) EMPTY
        else if (!a.envelopeInternal.intersects(b.envelopeInternal)) EMPTY
        else op(a, b, OverlayNG.INTERSECTION)

    fun difference(a: Geometry, b: Geometry): Geometry =
        if (a.isEmpty) EMPTY
        else if (b.isEmpty || !a.envelopeInternal.intersects(b.envelopeInternal)) a
        else op(a, b, OverlayNG.DIFFERENCE)

    fun union(a: Geometry, b: Geometry): Geometry =
        if (a.isEmpty) b else if (b.isEmpty) a else op(a, b, OverlayNG.UNION)

    fun union(list: Collection<Geometry>): Geometry {
        val parts = list.filter { !it.isEmpty }
        if (parts.isEmpty()) return EMPTY
        if (parts.size == 1) return parts[0]
        return try {
            polygonal(OverlayNGRobust.union(parts))
        } catch (e: RuntimeException) {
            parts.reduce { acc, g -> union(acc, g) }
        }
    }

    fun simplify(g: Geometry, tol: Double): Geometry =
        if (g.isEmpty) g else try { polygonal(DouglasPeuckerSimplifier.simplify(g, tol)) } catch (_: RuntimeException) { g }

    /** Keeps only the polygonal part of a geometry (drops stray lines/points from overlays). */
    fun polygonal(g: Geometry): Geometry {
        if (g is Polygon) return if (g.isEmpty) EMPTY else g
        val polys = polygons(g)
        return when (polys.size) {
            0 -> EMPTY
            1 -> polys[0]
            else -> factory.createMultiPolygon(polys.toTypedArray())
        }
    }

    fun polygons(g: Geometry): List<Polygon> {
        val out = ArrayList<Polygon>()
        collect(g, out)
        return out
    }

    private fun collect(g: Geometry, out: MutableList<Polygon>) {
        if (g.isEmpty) return
        if (g is Polygon) { out.add(g); return }
        for (i in 0 until g.numGeometries) {
            val c = g.getGeometryN(i)
            if (c !== g) collect(c, out)
        }
    }

    fun removeSmall(g: Geometry, minArea: Double): Geometry {
        val polys = polygons(g).filter { it.area >= minArea }
        return when (polys.size) {
            0 -> EMPTY
            1 -> polys[0]
            else -> factory.createMultiPolygon(polys.toTypedArray())
        }
    }

    fun multi(polys: List<Polygon>): Geometry = when (polys.size) {
        0 -> EMPTY
        1 -> polys[0]
        else -> factory.createMultiPolygon(polys.toTypedArray())
    }

    /**
     * Returns closed loops (x,y pairs without repeated end point): exteriors counter-clockwise,
     * holes clockwise, so material is always on the left.
     */
    fun loops(g: Geometry): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (p in polygons(g)) {
            out.add(ringToArray(p.exteriorRing, ccw = true))
            for (h in 0 until p.numInteriorRing) out.add(ringToArray(p.getInteriorRingN(h), ccw = false))
        }
        return out.filter { it.size >= 6 }
    }

    fun exteriorLoops(g: Geometry): List<DoubleArray> =
        polygons(g).map { ringToArray(it.exteriorRing, ccw = true) }.filter { it.size >= 6 }

    fun ringToArray(r: LinearRing, ccw: Boolean): DoubleArray {
        val cs = r.coordinates
        val n = cs.size - 1 // drop closing point
        if (n < 3) return DoubleArray(0)
        val isCcw = Orientation.isCCW(cs)
        val out = DoubleArray(n * 2)
        for (i in 0 until n) {
            val c = if (isCcw == ccw) cs[i] else cs[n - 1 - i]
            out[i * 2] = c.x; out[i * 2 + 1] = c.y
        }
        return out
    }

    fun ring(xy: DoubleArray): LinearRing? {
        val n = xy.size / 2
        if (n < 3) return null
        val cs = Array(n + 1) { i -> val k = i % n; Coordinate(xy[k * 2], xy[k * 2 + 1]) }
        return try { factory.createLinearRing(cs) } catch (_: IllegalArgumentException) { null }
    }

    fun polygon(shell: DoubleArray, holes: List<DoubleArray> = emptyList()): Polygon? {
        val s = ring(shell) ?: return null
        return factory.createPolygon(s, holes.mapNotNull { ring(it) }.toTypedArray())
    }
}
