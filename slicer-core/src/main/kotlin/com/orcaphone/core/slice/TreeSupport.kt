package com.orcaphone.core.slice

import com.orcaphone.core.config.ProcessProfile
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Location
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.distance.IndexedFacetDistance
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Tree supports, in the spirit of OrcaSlicer's tree support:
 *  - every overhang gets a thin roof of interface layers, held up by tips sampled on a grid
 *  - from the tips, branches grow downwards layer by layer: each may lean at most [ProcessProfile.treeBranchAngle],
 *    is pushed away from the model (keeping the XY distance), is drawn towards neighbours and merges with them
 *    into thicker trunks
 *  - branches end on the build plate, or on the model unless "on build plate only" is set (then they are
 *    removed together with the roof they would hold)
 * The result is per-layer areas (union of branch circles) that are printed with a wall and sparse lines.
 */
object TreeSupportGenerator {

    private class Node(var x: Double, var y: Double, val r: Double, val layer: Int, val roof: Int) {
        /** The node this one continues as on the layer below (shared when branches merge). */
        var child: Node? = null
        var endsOnModel = false
        var alive = false
    }

    private class Collider(region: Geometry) {
        private val empty = region.isEmpty
        private val locator = if (empty) null else IndexedPointInAreaLocator(region)
        private val facets = if (empty) null else IndexedFacetDistance(region.boundary)
        private val gf = Geo.factory

        /** Signed clearance: distance to the model outline, negative inside the model; nearest outline point in [out]. */
        fun clearance(x: Double, y: Double, out: DoubleArray): Double {
            if (empty) return Double.MAX_VALUE
            val pt = gf.createPoint(Coordinate(x, y))
            val near = facets!!.nearestPoints(pt)
            val q = near[0]
            out[0] = q.x; out[1] = q.y
            val d = hypot(x - q.x, y - q.y)
            return if (locator!!.locate(Coordinate(x, y)) == Location.EXTERIOR) d else -d
        }
    }

    fun generate(
        regions: Array<Geometry>, heights: DoubleArray, p: ProcessProfile, lineWidth: Double,
        runner: ParallelRunner, cancel: CancelToken, progress: (Float) -> Unit,
    ): Array<SupportLayer> {
        val n = regions.size
        val empty = SupportLayer(Geo.EMPTY, Geo.EMPTY)
        if (n < 2) return Array(n) { empty }
        val h = heights.average()
        val gap = max(1, (p.supportTopZDistance / h).roundToInt())
        val roofLayers = p.supportInterfaceLayers.coerceIn(0, 10)
        val xy = p.supportObjectXYDistance + lineWidth / 2
        val maxMove = h * tan(p.treeBranchAngle.coerceIn(5.0, 75.0) * PI / 180)
        val tipR = max(0.8, lineWidth * 1.5)
        val branchR = max(tipR, p.treeBranchDiameter / 2)
        val maxR = branchR * 2
        val growth = h * tan(5.0 * PI / 180) // branches thicken slightly towards the bottom
        val spacing = if (roofLayers > 0) max(3.0, p.treeBranchDiameter * 0.8) else max(2.0, tipR * 3)
        val plateOnly = p.supportOnBuildPlateOnly

        // 1. overhangs and the layers holding their tips
        val overhang = SupportGenerator.detectOverhangs(regions, h, p, lineWidth, runner)
        progress(0.15f)
        val tipLayer = IntArray(n) { i -> i - 1 - gap - roofLayers }
        val tips = arrayOfNulls<List<DoubleArray>>(n)
        runner.forEach(n) { i ->
            if (overhang[i].isEmpty || tipLayer[i] < 0) return@forEach
            tips[i] = samplePoints(overhang[i], spacing)
        }
        progress(0.3f)

        // 2. grow branches from the top down
        val colliders = arrayOfNulls<Collider>(n)
        fun collider(j: Int) = colliders[j] ?: Collider(regions[j]).also { colliders[j] = it }
        val nodesByLayer = Array(n) { ArrayList<Node>() }
        var active = ArrayList<Node>()
        val tmp = DoubleArray(2)

        /** Moves (x, y) out of the model at layer [j] by at least r + xy. Returns null when it cannot. */
        fun avoid(j: Int, x: Double, y: Double, r: Double, fromX: Double, fromY: Double, limit: Double): DoubleArray? {
            val c = collider(j)
            val need = r + xy
            var px = x; var py = y
            for (iter in 0 until 3) {
                val d = c.clearance(px, py, tmp)
                if (d >= need) break
                val qx = tmp[0]; val qy = tmp[1]
                var dx = px - qx; var dy = py - qy
                var len = hypot(dx, dy)
                if (len < 1e-9) { dx = px - fromX; dy = py - fromY; len = hypot(dx, dy) }
                if (len < 1e-9) return null
                if (d < 0) { dx = -dx; dy = -dy } // inside the model: the outline is the way out
                px = qx + dx / len * (need + 0.01)
                py = qy + dy / len * (need + 0.01)
            }
            if (c.clearance(px, py, tmp) < need - 1e-6) return null
            if (hypot(px - fromX, py - fromY) > limit) return null
            return doubleArrayOf(px, py)
        }

        for (j in n - 1 downTo 0) {
            cancel.check()
            val next = ArrayList<Node>(active.size + 16)

            // a) continue the branches from the layer above
            if (active.isNotEmpty()) {
                val merged = mergeAndAttract(active, maxMove, maxR)
                for ((parents, pos, r0) in merged) {
                    val r = min(maxR, r0 + growth)
                    val fromX = parents.sumOf { it.x } / parents.size
                    val fromY = parents.sumOf { it.y } / parents.size
                    val moved = avoid(j, pos[0], pos[1], r, fromX, fromY, maxMove * 2.5)
                    if (moved == null) {
                        // trapped: the branch rests on the model below (or dies if it must reach the plate)
                        for (pn in parents) pn.endsOnModel = !plateOnly
                        continue
                    }
                    val node = Node(moved[0], moved[1], r, j, parents.minOf { it.roof })
                    for (pn in parents) pn.child = node
                    next.add(node)
                }
            }

            // b) new tips right below the roofs of overhangs above
            for (i in j + 1 until n) {
                if (tipLayer[i] != j) continue
                val pts = tips[i] ?: continue
                for (pt in pts) {
                    if (next.any { hypot(it.x - pt[0], it.y - pt[1]) < it.r + spacing * 0.4 }) continue
                    val at = avoid(j, pt[0], pt[1], tipR, pt[0], pt[1], spacing) ?: continue
                    next.add(Node(at[0], at[1], tipR, j, i))
                }
            }
            nodesByLayer[j] = next
            active = next
            if (j % 8 == 0) progress(0.3f + 0.4f * (n - j) / n)
        }

        // 3. which branches reach the plate (or may rest on the model)
        for (j in 0 until n) {
            for (node in nodesByLayer[j]) {
                val c = node.child
                node.alive = when {
                    c != null -> c.alive
                    j == 0 -> true
                    else -> node.endsOnModel
                }
            }
        }

        // 4. roofs: only above live tips
        val liveTipsPerRoof = HashMap<Int, MutableList<Node>>()
        for (j in 0 until n) for (node in nodesByLayer[j]) {
            if (node.alive && node.layer == tipLayer.getOrElse(node.roof) { -1 }) liveTipsPerRoof.getOrPut(node.roof) { ArrayList() }.add(node)
        }
        val ifaceParts = Array(n) { ArrayList<Geometry>() }
        for ((i, live) in liveTipsPerRoof) {
            if (roofLayers == 0) break
            val held = Geo.polygons(overhang[i]).filter { poly ->
                val env = poly.envelopeInternal
                live.any { t -> env.minX - spacing <= t.x && t.x <= env.maxX + spacing && env.minY - spacing <= t.y && t.y <= env.maxY + spacing }
            }
            if (held.isEmpty()) continue
            val roof = Geo.multi(held)
            for (j in tipLayer[i] + 1..i - 1 - gap) if (j in 0 until n) ifaceParts[j].add(roof)
        }

        // 5. per-layer areas
        val circleParams = BufferParameters().apply { quadrantSegments = 4 }
        val result = Array(n) { empty }
        runner.forEach(n) { j ->
            val circles = nodesByLayer[j].filter { it.alive }.map {
                org.locationtech.jts.operation.buffer.BufferOp.bufferOp(Geo.factory.createPoint(Coordinate(it.x, it.y)), it.r, circleParams)
            }
            if (circles.isEmpty() && ifaceParts[j].isEmpty()) return@forEach
            val clearance = Geo.offset(regions[j], xy, roundJoins = true)
            var iface = Geo.union(ifaceParts[j])
            if (!iface.isEmpty) {
                var cut = Geo.difference(iface, clearance)
                for (k in j + 1..min(n - 1, j + gap)) cut = Geo.difference(cut, regions[k])
                iface = Geo.removeSmall(cut, lineWidth * lineWidth)
            }
            val base = Geo.removeSmall(Geo.difference(Geo.difference(Geo.union(circles), clearance), iface), lineWidth * lineWidth)
            result[j] = SupportLayer(Geo.simplify(base, 0.02), iface, walls = true)
        }
        progress(1f)
        return result
    }

    private data class Merge(val parents: List<Node>, val pos: DoubleArray, val r: Double)

    /**
     * Merges nodes that touch into one (area-preserving radius) and draws the others towards their nearest
     * neighbour so branches join into trunks. Uses a spatial hash; movement is limited to [maxMove].
     */
    private fun mergeAndAttract(nodes: List<Node>, maxMove: Double, maxR: Double): List<Merge> {
        val cell = max(maxR * 4, 8.0)
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        nodes.forEachIndexed { i, nd -> grid.getOrPut(key(floor(nd.x / cell).toInt(), floor(nd.y / cell).toInt())) { ArrayList() }.add(i) }
        fun neighbours(i: Int): List<Int> {
            val nd = nodes[i]
            val cx = floor(nd.x / cell).toInt(); val cy = floor(nd.y / cell).toInt()
            val out = ArrayList<Int>()
            for (dx in -1..1) for (dy in -1..1) grid[key(cx + dx, cy + dy)]?.let { out.addAll(it) }
            return out
        }
        // union-find for touching nodes
        val parent = IntArray(nodes.size) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in nodes.indices) {
            for (k in neighbours(i)) {
                if (k <= i) continue
                val a = nodes[i]; val b = nodes[k]
                if (hypot(a.x - b.x, a.y - b.y) < max(a.r, b.r) * 0.9) parent[find(i)] = find(k)
            }
        }
        val groups = nodes.indices.groupBy { find(it) }.values
        val result = ArrayList<Merge>(groups.size)
        val centres = groups.map { g ->
            var wx = 0.0; var wy = 0.0; var w = 0.0; var area = 0.0
            for (i in g) { val nd = nodes[i]; val a = nd.r * nd.r; wx += nd.x * a; wy += nd.y * a; w += a; area += a }
            Triple(doubleArrayOf(wx / w, wy / w), min(maxR, sqrt(area)), g)
        }
        // attraction: move each merged node towards the nearest other one within reach
        val reach = max(maxR * 3, maxMove * 12)
        for ((idx, c) in centres.withIndex()) {
            val (pos, r, g) = c
            var best = -1; var bestD = Double.MAX_VALUE
            for ((k, o) in centres.withIndex()) {
                if (k == idx) continue
                val d = hypot(o.first[0] - pos[0], o.first[1] - pos[1])
                if (d < bestD) { bestD = d; best = k }
            }
            val newPos = if (best >= 0 && bestD < reach) {
                val o = centres[best].first
                val step = min(maxMove, max(0.0, (bestD - 0.01) / 2))
                doubleArrayOf(pos[0] + (o[0] - pos[0]) / bestD * step, pos[1] + (o[1] - pos[1]) / bestD * step)
            } else pos
            result.add(Merge(g.map { nodes[it] }, newPos, r))
        }
        return result
    }

    /** Grid points inside the area plus points along its outline, at least ~[spacing] apart. */
    internal fun samplePoints(area: Geometry, spacing: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val seen = HashSet<Long>()
        val half = spacing * 0.5
        fun add(x: Double, y: Double) {
            val k = (floor(x / half).toLong() shl 32) xor (floor(y / half).toLong() and 0xffffffffL)
            if (!seen.add(k)) return
            if (out.any { hypot(it[0] - x, it[1] - y) < half }) return
            out.add(doubleArrayOf(x, y))
        }
        for (poly in Geo.polygons(area)) {
            val env = poly.envelopeInternal
            val locator = IndexedPointInAreaLocator(poly)
            var gx = floor(env.minX / spacing) * spacing
            while (gx <= env.maxX) {
                var gy = floor(env.minY / spacing) * spacing
                while (gy <= env.maxY) {
                    if (locator.locate(Coordinate(gx, gy)) == Location.INTERIOR) add(gx, gy)
                    gy += spacing
                }
                gx += spacing
            }
            // thin areas (gentle slopes) have few grid points: also follow the outline
            val ring = (poly as Polygon).exteriorRing.coordinates
            var acc = spacing
            for (k in 1 until ring.size) {
                val a = ring[k - 1]; val b = ring[k]
                val len = a.distance(b)
                var t = 0.0
                while (acc + (len - t) >= spacing) {
                    val step = spacing - acc
                    t += step
                    val f = t / len
                    add(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
                    acc = 0.0
                }
                acc += len - t
            }
            if (out.isEmpty()) poly.interiorPoint.coordinate.let { add(it.x, it.y) }
        }
        return out
    }
}
