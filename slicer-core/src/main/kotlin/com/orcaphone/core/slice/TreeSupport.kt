package com.orcaphone.core.slice

import com.orcaphone.core.config.ProcessProfile
import com.orcaphone.core.config.SupportType
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Location
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
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
 * Tree and organic tree supports, in the spirit of OrcaSlicer's:
 *  - every overhang gets a thin roof of interface layers, held up by tips sampled on a grid
 *  - from the tips, branches grow downwards layer by layer: each may lean at most the branch angle,
 *    is pushed away from the model (keeping the XY distance), is drawn towards neighbours and merges with
 *    them (area-preserving) into thicker trunks
 *  - branches end on the build plate, or on the model unless "on build plate only" is set (then they are
 *    removed together with the roof they would hold)
 *
 * Organic mode (OrcaSlicer's default tree style) additionally: starts from thin 0.8 mm tips that widen to the
 * branch diameter, prefers a gentler lean when merging, smooths every branch into a curve, uses round tapered
 * cross-sections and wider feet on the plate, and is printed hollow (walls only).
 */
object TreeSupportGenerator {

    private class Node(var x: Double, var y: Double, val r: Double, val layer: Int, val roof: Int, val age: Int) {
        /** The node this one continues as on the layer below (shared when branches merge). */
        var child: Node? = null
        var parents: MutableList<Node>? = null
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
            val q = facets!!.nearestPoints(gf.createPoint(Coordinate(x, y)))[0]
            out[0] = q.x; out[1] = q.y
            val d = hypot(x - q.x, y - q.y)
            return if (locator!!.locate(Coordinate(x, y)) == Location.EXTERIOR) d else -d
        }
    }

    /** Geometry/behaviour parameters derived from the process settings. */
    private class Params(p: ProcessProfile, lineWidth: Double, h: Double) {
        val organic = p.supportType == SupportType.ORGANIC
        val maxMove = h * tan((if (organic) p.organicBranchAngle else p.treeBranchAngle).coerceIn(5.0, 75.0) * PI / 180)
        /** Lean used for merging when no avoidance is needed. */
        val preferredMove = if (organic) h * tan(p.organicPreferredAngle.coerceIn(5.0, 75.0) * PI / 180) else maxMove
        val tipR = if (organic) max(p.organicTipDiameter / 2, lineWidth * 0.75) else max(0.8, lineWidth * 1.5)
        val branchR = max(tipR, (if (organic) p.organicBranchDiameter else p.treeBranchDiameter) / 2)
        val maxR = if (organic) 12.0 else branchR * 2
        /** Radius growth per layer towards the bottom (branch diameter angle). */
        val growth = h * tan((if (organic) p.organicDiameterAngle else 5.0).coerceIn(0.0, 15.0) * PI / 180)
        /** Layers over which an organic tip widens to the branch diameter. */
        val rampLayers = if (organic) max(1, (3.0 / h).roundToInt()) else 0
        val spacing: Double = if (organic) {
            // OrcaSlicer's "branch density": share of the roof area covered by tips
            val density = (p.organicBranchDensity / 100.0).coerceIn(0.05, 0.6)
            // with an interface roof above, tips only need to hold the roof: space them wider
            (p.organicTipDiameter / sqrt(density) * if (p.supportInterfaceLayers > 0) 2.2 else 1.6).coerceIn(1.5, 6.0)
        } else if (p.supportInterfaceLayers > 0) max(3.0, p.treeBranchDiameter * 0.8) else max(2.0, tipR * 3)
        val circleSegments = if (organic) 8 else 4
    }

    fun generate(
        regions: Array<Geometry>, heights: DoubleArray, p: ProcessProfile, lineWidth: Double,
        runner: ParallelRunner, cancel: CancelToken, progress: (Float) -> Unit,
    ): Array<SupportLayer> {
        val n = regions.size
        val empty = SupportLayer(Geo.EMPTY, Geo.EMPTY)
        if (n < 2) return Array(n) { empty }
        val h = heights.average()
        val prm = Params(p, lineWidth, h)
        val gap = max(1, (p.supportTopZDistance / h).roundToInt())
        val roofLayers = p.supportInterfaceLayers.coerceIn(0, 10)
        val xy = p.supportObjectXYDistance + lineWidth / 2
        val plateOnly = p.supportOnBuildPlateOnly
        val spacing = prm.spacing

        // 1. overhangs and the layers holding their tips
        val overhang = SupportGenerator.detectOverhangs(regions, h, p, lineWidth, runner)
        progress(0.1f)
        val tipLayer = IntArray(n) { i -> i - 1 - gap - roofLayers }
        val tips = arrayOfNulls<List<DoubleArray>>(n)
        runner.forEach(n) { i ->
            if (overhang[i].isEmpty || tipLayer[i] < 0) return@forEach
            tips[i] = samplePoints(overhang[i], spacing)
        }
        progress(0.2f)

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

        fun radiusFor(mergedR: Double, age: Int): Double {
            val ramp = if (prm.rampLayers > 0) prm.tipR + (prm.branchR - prm.tipR) * min(1.0, age.toDouble() / prm.rampLayers) else prm.tipR
            return min(prm.maxR, max(mergedR, ramp) + prm.growth)
        }

        for (j in n - 1 downTo 0) {
            cancel.check()
            val next = ArrayList<Node>(active.size + 16)

            // a) continue the branches from the layer above
            if (active.isNotEmpty()) {
                for ((parents, pos, r0) in mergeAndAttract(active, prm.preferredMove, prm.maxR, areaPreserving = !prm.organic)) {
                    val age = parents.maxOf { it.age } + 1
                    val r = radiusFor(r0, age)
                    val fromX = parents.sumOf { it.x } / parents.size
                    val fromY = parents.sumOf { it.y } / parents.size
                    val moved = avoid(j, pos[0], pos[1], r, fromX, fromY, prm.maxMove * 2.5)
                    if (moved == null) {
                        // trapped: the branch rests on the model below (or dies if it must reach the plate)
                        for (pn in parents) pn.endsOnModel = !plateOnly
                        continue
                    }
                    val node = Node(moved[0], moved[1], r, j, parents.minOf { it.roof }, age)
                    node.parents = parents.toMutableList()
                    for (pn in parents) pn.child = node
                    next.add(node)
                }
            }

            // b) new tips right below the roofs of overhangs above (skip where a branch already is)
            val occupied = SpatialHash(max(spacing, prm.maxR * 2))
            for (nd in next) occupied.add(nd.x, nd.y, nd.r)
            for (i in j + 1 until n) {
                if (tipLayer[i] != j) continue
                val pts = tips[i] ?: continue
                for (pt in pts) {
                    if (occupied.anyWithin(pt[0], pt[1], spacing * 0.4)) continue
                    val at = avoid(j, pt[0], pt[1], prm.tipR, pt[0], pt[1], spacing) ?: continue
                    next.add(Node(at[0], at[1], prm.tipR, j, i, 0))
                    occupied.add(at[0], at[1], prm.tipR)
                }
            }
            nodesByLayer[j] = next
            active = next
            if (j % 8 == 0) progress(0.2f + 0.45f * (n - j) / n)
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

        // 4. organic: smooth every branch into a curve (tips and feet stay put), then re-check clearance
        if (prm.organic) {
            val all = nodesByLayer.flatMap { l -> l.filter { it.alive } }
            val movable = all.filter { it.child != null && !it.parents.isNullOrEmpty() }
            val nx = DoubleArray(movable.size); val ny = DoubleArray(movable.size)
            repeat(25) {
                for ((k, nd) in movable.withIndex()) {
                    val ps = nd.parents!!.filter { it.alive }
                    val c = nd.child!!
                    if (ps.isEmpty()) { nx[k] = nd.x; ny[k] = nd.y; continue }
                    val px = ps.sumOf { it.x } / ps.size; val py = ps.sumOf { it.y } / ps.size
                    nx[k] = (nd.x * 2 + px + c.x) / 4
                    ny[k] = (nd.y * 2 + py + c.y) / 4
                }
                for ((k, nd) in movable.withIndex()) {
                    val ok = avoid(nd.layer, nx[k], ny[k], nd.r, nd.x, nd.y, prm.maxMove * 4)
                    if (ok != null) { nd.x = ok[0]; nd.y = ok[1] }
                }
            }
        }
        progress(0.75f)

        // 5. roofs: only above live tips
        val liveTipsPerRoof = HashMap<Int, MutableList<Node>>()
        for (j in 0 until n) for (node in nodesByLayer[j]) {
            if (node.alive && node.age == 0) liveTipsPerRoof.getOrPut(node.roof) { ArrayList() }.add(node)
        }
        val ifaceParts = Array(n) { ArrayList<Geometry>() }
        if (roofLayers > 0) for ((i, live) in liveTipsPerRoof) {
            val held = Geo.polygons(overhang[i]).filter { poly ->
                val env = poly.envelopeInternal
                live.any { t -> env.minX - spacing <= t.x && t.x <= env.maxX + spacing && env.minY - spacing <= t.y && t.y <= env.maxY + spacing }
            }
            if (held.isEmpty()) continue
            val roof = Geo.multi(held)
            for (j in tipLayer[i] + 1..i - 1 - gap) if (j in 0 until n) ifaceParts[j].add(roof)
        }

        // 6. per-layer areas
        val circleParams = BufferParameters().apply { quadrantSegments = prm.circleSegments }
        val result = Array(n) { empty }
        runner.forEach(n) { j ->
            // organic feet flare out over the first layers for better adhesion
            val flare = if (prm.organic && j < 3) (3 - j) * 0.6 else 0.0
            val circles = nodesByLayer[j].filter { it.alive }.map {
                BufferOp.bufferOp(Geo.factory.createPoint(Coordinate(it.x, it.y)), it.r + flare, circleParams)
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
            result[j] = SupportLayer(
                Geo.simplify(base, 0.02), iface,
                walls = if (prm.organic) 2 else 1,
                infill = !prm.organic,
            )
        }
        progress(1f)
        return result
    }

    /** Points with radii in a uniform grid for neighbour queries. */
    private class SpatialHash(private val cell: Double) {
        private val map = HashMap<Long, MutableList<DoubleArray>>()
        private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        fun add(x: Double, y: Double, r: Double) {
            map.getOrPut(key(floor(x / cell).toInt(), floor(y / cell).toInt())) { ArrayList() }.add(doubleArrayOf(x, y, r))
        }
        /** True if a stored point lies within its radius + [extra] of (x, y). */
        fun anyWithin(x: Double, y: Double, extra: Double): Boolean {
            val cx = floor(x / cell).toInt(); val cy = floor(y / cell).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                val l = map[key(cx + dx, cy + dy)] ?: continue
                for (q in l) if (hypot(q[0] - x, q[1] - y) < q[2] + extra) return true
            }
            return false
        }
    }

    private data class Merge(val parents: List<Node>, val pos: DoubleArray, val r: Double)

    /**
     * Merges nodes that touch into one and draws the others towards their nearest neighbour so branches join
     * into trunks. Merged radius: area-preserving for plain trees, the largest member for organic trees
     * (whose thickness comes from the distance to the tips, like OrcaSlicer's). Spatial hash keeps it
     * near-linear; movement is limited to [maxMove].
     */
    private fun mergeAndAttract(nodes: List<Node>, maxMove: Double, maxR: Double, areaPreserving: Boolean): List<Merge> {
        val reach = max(maxR * 3, maxMove * 12)
        val cell = max(max(maxR * 2, 4.0), reach / 2)
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        val grid = HashMap<Long, MutableList<Int>>()
        nodes.forEachIndexed { i, nd -> grid.getOrPut(key(floor(nd.x / cell).toInt(), floor(nd.y / cell).toInt())) { ArrayList() }.add(i) }
        // union-find for touching nodes
        val parent = IntArray(nodes.size) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in nodes.indices) {
            val a = nodes[i]
            val cx = floor(a.x / cell).toInt(); val cy = floor(a.y / cell).toInt()
            for (dx in -1..1) for (dy in -1..1) for (k in grid[key(cx + dx, cy + dy)] ?: continue) {
                if (k <= i) continue
                val b = nodes[k]
                // merge once the branches overlap substantially
                if (hypot(a.x - b.x, a.y - b.y) < (a.r + b.r) * 0.6) parent[find(i)] = find(k)
            }
        }
        val groups = nodes.indices.groupBy { find(it) }.values.toList()
        val centres = groups.map { g ->
            var wx = 0.0; var wy = 0.0; var area = 0.0; var largest = 0.0
            for (i in g) { val nd = nodes[i]; val a = nd.r * nd.r; wx += nd.x * a; wy += nd.y * a; area += a; largest = max(largest, nd.r) }
            Triple(doubleArrayOf(wx / area, wy / area), min(maxR, if (areaPreserving) sqrt(area) else largest), g)
        }
        // attraction: move each merged node towards the nearest other one within reach
        val cgrid = HashMap<Long, MutableList<Int>>()
        centres.forEachIndexed { k, c -> cgrid.getOrPut(key(floor(c.first[0] / cell).toInt(), floor(c.first[1] / cell).toInt())) { ArrayList() }.add(k) }
        val result = ArrayList<Merge>(centres.size)
        for ((idx, c) in centres.withIndex()) {
            val (pos, r, g) = c
            var best = -1; var bestD = Double.MAX_VALUE
            val cx = floor(pos[0] / cell).toInt(); val cy = floor(pos[1] / cell).toInt()
            for (dx in -2..2) for (dy in -2..2) for (k in cgrid[key(cx + dx, cy + dy)] ?: continue) {
                if (k == idx) continue
                val o = centres[k].first
                val d = hypot(o[0] - pos[0], o[1] - pos[1])
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
        val half = spacing * 0.5
        val taken = SpatialHash(spacing)
        fun add(x: Double, y: Double) {
            if (taken.anyWithin(x, y, half)) return
            out.add(doubleArrayOf(x, y))
            taken.add(x, y, 0.0)
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
                    t += spacing - acc
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
