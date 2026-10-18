package com.orcaphone.core.slice

import com.orcaphone.core.config.BrimType
import com.orcaphone.core.config.FilamentProfile
import com.orcaphone.core.config.PrinterProfile
import com.orcaphone.core.config.ProcessProfile
import com.orcaphone.core.config.SeamPosition
import com.orcaphone.core.config.SurfacePattern
import com.orcaphone.core.config.WallOrder
import com.orcaphone.core.gcode.GCodeGenerator
import com.orcaphone.core.gcode.PrintStats
import com.orcaphone.core.mesh.Mesh
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class SliceResult(
    val layers: List<LayerToolpaths>,
    val stats: PrintStats,
    val gcodeFile: File,
    val warnings: List<String>,
)

fun interface SliceProgress {
    fun update(stage: String, fraction: Float)
}

/**
 * The slicing pipeline:
 *  slice mesh -> walls -> top/bottom shells -> sparse infill -> supports -> tool paths -> G-code.
 */
class Slicer(
    private val printer: PrinterProfile,
    private val filament: FilamentProfile,
    process: ProcessProfile,
    private val cancel: CancelToken = CancelToken(),
) {
    private val p: ProcessProfile = if (process.spiralMode) process.copy(
        wallLoops = 1, topShellLayers = 0, sparseInfillDensity = 0.0, enableSupport = false, ironing = false,
    ) else process

    private class Island(
        val polygon: Polygon,
        val walls: List<List<DoubleArray>>, // walls[depth] = loops
        val infillArea: Geometry,
    )

    private class LayerData(
        val index: Int, val bottom: Double, val top: Double,
        val region: Geometry,
        val islands: List<Island>,
    ) {
        val height get() = top - bottom
        var infillArea: Geometry = Geo.EMPTY
        var sparse: Geometry = Geo.EMPTY
        var solid: Geometry = Geo.EMPTY
        var topSurface: Geometry = Geo.EMPTY
        var bottomSurface: Geometry = Geo.EMPTY
        var support: SupportLayer? = null
    }

    fun slice(meshes: List<Mesh>, outFile: File, progress: SliceProgress): SliceResult {
        require(meshes.isNotEmpty()) { "Nothing to slice" }
        val warnings = ArrayList<String>()
        ParallelRunner(cancel = cancel).use { runner ->
            progress.update("Preparing layers", 0f)
            val maxZ = meshes.maxOf { it.bounds().maxZ }
            val minZ = meshes.minOf { it.bounds().minZ }
            if (maxZ - minZ < 0.05) throw IllegalArgumentException("Model is too flat to print")
            val bottoms = ArrayList<Double>()
            val tops = ArrayList<Double>()
            var z = 0.0
            var first = true
            while (z < maxZ - 1e-4) {
                val h = if (first) p.initialLayerHeight else p.layerHeight
                bottoms.add(z); tops.add(z + h)
                z += h; first = false
            }
            val n = bottoms.size
            val sliceZ = DoubleArray(n) { min((bottoms[it] + tops[it]) / 2, maxZ - 1e-4) }

            // 1. slice every object and merge
            progress.update("Slicing mesh", 0.02f)
            val regions = Array<Geometry>(n) { Geo.EMPTY }
            val perObject = meshes.map { MeshSlicer(it).slice(sliceZ, runner) }
            runner.forEach(n) { i ->
                regions[i] = Geo.union(perObject.map { it[i] })
            }
            cancel.check()
            progress.update("Generating walls", 0.15f)

            // 2. walls + infill areas
            val layers = arrayOfNulls<LayerData>(n)
            val done = java.util.concurrent.atomic.AtomicInteger()
            runner.forEach(n) { i ->
                val h = tops[i] - bottoms[i]
                var region = regions[i]
                if (i == 0 && p.elephantFootCompensation > 0) {
                    val shrunk = Geo.offset(region, -p.elephantFootCompensation)
                    if (!shrunk.isEmpty) region = shrunk
                }
                val (extW, w) = widths(i)
                val islands = Geo.polygons(region).map { makeIsland(it, extW, w, h) }
                layers[i] = LayerData(i, bottoms[i], tops[i], regions[i], islands).also { ld ->
                    ld.infillArea = Geo.multi(islands.flatMap { Geo.polygons(it.infillArea) })
                }
                progress.update("Generating walls", 0.15f + 0.15f * done.incrementAndGet() / n)
            }
            val L = layers.map { it!! }

            // 3. top / bottom shells
            progress.update("Detecting top and bottom surfaces", 0.3f)
            done.set(0)
            runner.forEach(n) { i ->
                classifySurfaces(L, regions, i)
                progress.update("Detecting top and bottom surfaces", 0.3f + 0.15f * done.incrementAndGet() / n)
            }

            // 4. supports
            if (p.enableSupport) {
                progress.update("Generating supports", 0.45f)
                val heights = DoubleArray(n) { tops[it] - bottoms[it] }
                val sup = SupportGenerator.generate(regions, heights, p, p.lineWidth, runner, cancel) {
                    progress.update("Generating supports", 0.45f + 0.15f * it)
                }
                for (i in 0 until n) L[i].support = sup[i]
            }

            // 5. tool paths
            progress.update("Generating tool paths", 0.6f)
            val toolpaths = arrayOfNulls<LayerToolpaths>(n)
            done.set(0)
            val skirtBrim = buildSkirtAndBrim(L[0], regions[0])
            runner.forEach(n) { i ->
                toolpaths[i] = buildLayer(L, i, if (i < p.skirtHeight.coerceAtLeast(1)) skirtBrim else Pair(emptyList(), emptyList()))
                progress.update("Generating tool paths", 0.6f + 0.15f * done.incrementAndGet() / n)
            }
            val result = toolpaths.map { it!! }.filter { it.paths.isNotEmpty() }
            if (result.isEmpty()) throw IllegalArgumentException("Nothing printable: the model may be too small or thin for the nozzle")

            // 6. G-code
            progress.update("Writing G-code", 0.75f)
            val gen = GCodeGenerator(printer, filament, p, cancel)
            val stats = gen.generate(result, outFile) { f -> progress.update("Writing G-code", 0.75f + 0.25f * f) }
            progress.update("Done", 1f)
            return SliceResult(result, stats, outFile, warnings)
        }
    }

    private fun spacing(w: Double, h: Double) = w - h * (1 - PI / 4)

    /** (outer wall width, other line width) for a layer. */
    private fun widths(layer: Int): Pair<Double, Double> =
        if (layer == 0) p.initialLayerLineWidth to p.initialLayerLineWidth else p.outerWallLineWidth to p.lineWidth

    private fun makeIsland(poly: Polygon, extW: Double, w: Double, h: Double): Island {
        val loopsCount = p.wallLoops.coerceAtLeast(1)
        val extSp = spacing(extW, h)
        val sp = spacing(w, h)
        val walls = ArrayList<List<DoubleArray>>()
        var g = Geo.offset(poly, -extW / 2)
        if (g.isEmpty) {
            // Too thin for a full wall: print a single thin loop on the centre line if it is at least half a line wide.
            val thin = Geo.offset(poly, -extW / 4)
            if (!thin.isEmpty) walls.add(Geo.loops(thin))
            return Island(poly, walls, Geo.EMPTY)
        }
        walls.add(Geo.loops(g))
        var lastSp = extSp
        for (k in 1 until loopsCount) {
            val d = if (k == 1) (extSp + sp) / 2 else sp
            val g2 = Geo.offset(g, -d)
            if (g2.isEmpty) break
            walls.add(Geo.loops(g2))
            g = g2
            lastSp = sp
        }
        val overlap = p.infillWallOverlap / 100.0 * sp
        val infill = Geo.offset(g, -(lastSp / 2) + overlap)
        return Island(poly, walls, infill)
    }

    private fun classifySurfaces(L: List<LayerData>, regions: Array<Geometry>, i: Int) {
        val ld = L[i]
        val area = ld.infillArea
        if (area.isEmpty) return
        val n = L.size
        val (_, w) = widths(i)
        val top = p.topShellLayers
        val bottom = p.bottomShellLayers
        if (p.spiralMode && i >= bottom) {
            ld.solid = Geo.EMPTY; ld.sparse = Geo.EMPTY
            return
        }
        // interior = region present in all of the shell layers above and below
        var interior: Geometry? = if (i - bottom < 0 || i + top >= n) Geo.EMPTY else null
        if (interior == null) {
            var acc: Geometry = regions[i]
            for (k in 1..top) { acc = Geo.intersection(acc, regions[i + k]); if (acc.isEmpty) break }
            if (!acc.isEmpty) for (k in 1..bottom) { acc = Geo.intersection(acc, regions[i - k]); if (acc.isEmpty) break }
            interior = acc
        }
        var sparse = Geo.intersection(area, interior)
        sparse = Geo.intersection(Geo.open(sparse, w), area)
        val solid = Geo.removeSmall(Geo.difference(area, sparse), w * w * 0.5)
        ld.sparse = sparse
        if (solid.isEmpty) { ld.solid = Geo.EMPTY; return }
        val above = if (i + 1 < n) regions[i + 1] else Geo.EMPTY
        val topS = Geo.removeSmall(Geo.intersection(solid, Geo.difference(area, above)), w * w)
        val bottomS = if (i == 0) solid else {
            Geo.removeSmall(Geo.difference(Geo.intersection(solid, Geo.difference(area, regions[i - 1])), topS), w * w)
        }
        ld.topSurface = if (i == 0) Geo.EMPTY else topS
        ld.bottomSurface = bottomS
        ld.solid = Geo.difference(Geo.difference(solid, ld.topSurface), bottomS)
    }

    private fun buildSkirtAndBrim(first: LayerData, region0: Geometry): Pair<List<ToolPath>, List<ToolPath>> {
        val w = p.initialLayerLineWidth
        val speed = p.initialLayerSpeed
        val brim = ArrayList<ToolPath>()
        var outline = region0
        if (p.brimType == BrimType.OUTER && p.brimWidth > 0) {
            val count = max(1, (p.brimWidth / w).toInt())
            val base = Geo.multi(Geo.polygons(region0).map { Geo.factory.createPolygon(it.exteriorRing) })
            val rings = ArrayList<DoubleArray>()
            for (k in count - 1 downTo 0) {
                val g = Geo.offset(base, p.brimObjectGap + w / 2 + k * spacing(w, first.height), roundJoins = true)
                rings.addAll(Geo.exteriorLoops(g))
            }
            for (r in rings) brim.add(ToolPath(Feature.BRIM, r, true, w, speed))
            outline = Geo.offset(base, p.brimObjectGap + count * w, roundJoins = true)
        }
        val skirt = ArrayList<ToolPath>()
        if (p.skirtLoops > 0) {
            val hull = Geo.union(outline, first.support?.let { Geo.union(it.base, it.interfaceArea) } ?: Geo.EMPTY).convexHull()
            for (k in p.skirtLoops - 1 downTo 0) {
                val g = Geo.offset(hull, p.skirtDistance + w / 2 + k * spacing(w, first.height), roundJoins = true)
                for (r in Geo.exteriorLoops(g)) skirt.add(ToolPath(Feature.SKIRT, r, true, w, speed))
            }
        }
        return skirt to brim
    }

    private fun buildLayer(L: List<LayerData>, i: Int, skirtBrim: Pair<List<ToolPath>, List<ToolPath>>): LayerToolpaths {
        cancel.check()
        val ld = L[i]
        val h = ld.height
        val (extW, w) = widths(i)
        val sp = spacing(w, h)
        val first = i == 0
        val out = ArrayList<ToolPath>()
        var cx = 0.0; var cy = 0.0

        fun add(tp: ToolPath) { out.add(tp); cx = tp.endX; cy = tp.endY }

        // skirt and brim (first layers)
        for (s in skirtBrim.first) add(seamed(s, cx, cy, SeamPosition.NEAREST))
        for (b in skirtBrim.second) add(seamed(b, cx, cy, SeamPosition.NEAREST))

        // supports
        ld.support?.let { s ->
            val supSpeed = if (first) p.initialLayerSpeed else p.supportSpeed
            val ifSpeed = if (first) p.initialLayerSpeed else p.supportInterfaceSpeed
            if (!s.base.isEmpty) {
                val lines = Infill.lines(s.base, max(p.supportBaseSpacing, w), 0.0, 0.0, w)
                for (pl in Infill.chain(lines, cx, cy, s.base, p.supportBaseSpacing * 1.6)) {
                    add(ToolPath(Feature.SUPPORT, pl, false, w, supSpeed))
                }
            }
            if (!s.interfaceArea.isEmpty) {
                val lines = Infill.lines(s.interfaceArea, max(p.supportInterfaceSpacing + w, w), if (i % 2 == 0) 90.0 else 0.0, 0.0, w)
                for (pl in Infill.chain(lines, cx, cy, s.interfaceArea, (p.supportInterfaceSpacing + w) * 1.6)) {
                    add(ToolPath(Feature.SUPPORT_INTERFACE, pl, false, w, ifSpeed))
                }
            }
        }

        // spiral vase: a single outer loop per layer above the bottom
        if (p.spiralMode && i >= p.bottomShellLayers) {
            val biggest = ld.islands.maxByOrNull { it.polygon.area }
            val loop = biggest?.walls?.firstOrNull()?.maxByOrNull { abs(MeshSlicer.signedArea(it)) }
            if (loop != null) {
                val tp = ToolPath(Feature.OUTER_WALL, loop, true, extW, p.outerWallSpeed, spiral = i > p.bottomShellLayers)
                add(seamed(tp, cx, cy, SeamPosition.BACK))
            }
            return LayerToolpaths(i, ld.top, h, out, ld.region)
        }

        val wallSpeedOuter = if (first) p.initialLayerSpeed else p.outerWallSpeed
        val wallSpeedInner = if (first) p.initialLayerSpeed else p.innerWallSpeed
        val infillSpeed = if (first) p.initialLayerInfillSpeed else p.sparseInfillSpeed
        val solidSpeed = if (first) p.initialLayerInfillSpeed else p.internalSolidInfillSpeed
        val topSpeed = if (first) p.initialLayerInfillSpeed else p.topSurfaceSpeed

        // islands in nearest-neighbour order
        val remaining = ld.islands.toMutableList()
        val single = ld.islands.size == 1
        while (remaining.isNotEmpty()) {
            val isl = remaining.minByOrNull {
                val c = it.polygon.envelopeInternal
                val dx = (c.minX + c.maxX) / 2 - cx; val dy = (c.minY + c.maxY) / 2 - cy
                dx * dx + dy * dy
            }!!
            remaining.remove(isl)

            // walls
            val depths = if (p.wallOrder == WallOrder.INNER_OUTER) isl.walls.indices.reversed() else isl.walls.indices
            for (d in depths) {
                val feature = if (d == 0) Feature.OUTER_WALL else Feature.INNER_WALL
                val width = if (d == 0) extW else w
                val speed = if (d == 0) wallSpeedOuter else wallSpeedInner
                val loops = isl.walls[d].toMutableList()
                while (loops.isNotEmpty()) {
                    val l = loops.minByOrNull { nearestDist(it, cx, cy) }!!
                    loops.remove(l)
                    val mode = if (d == 0) p.seamPosition else SeamPosition.NEAREST
                    add(seamed(ToolPath(feature, l, true, width, speed), cx, cy, mode, i))
                }
            }

            // infill parts of this island
            fun part(g: Geometry): Geometry = if (single) g else Geo.intersection(g, isl.infillArea)

            val bottomG = part(ld.bottomSurface)
            if (!bottomG.isEmpty) {
                for (pl in surfaceLines(bottomG, p.bottomSurfacePattern, sp, i, cx, cy)) {
                    add(ToolPath(Feature.BOTTOM_SURFACE, pl, false, w, solidSpeed))
                }
            }
            val sparseG = part(ld.sparse)
            if (!sparseG.isEmpty && p.sparseInfillDensity > 0) {
                val density = p.sparseInfillDensity / 100.0
                if (density >= 0.999) {
                    for (pl in surfaceLines(sparseG, SurfacePattern.RECTILINEAR, sp, i, cx, cy)) add(ToolPath(Feature.SOLID_INFILL, pl, false, w, solidSpeed))
                } else {
                    val raw = Infill.sparse(sparseG, p.sparseInfillPattern, density, sp, i, ld.top, p.sparseInfillDirection)
                    for (pl in Infill.chain(raw, cx, cy, sparseG, sp / density * 2.5)) add(ToolPath(Feature.SPARSE_INFILL, pl, false, w, infillSpeed))
                }
            }
            val solidG = part(ld.solid)
            if (!solidG.isEmpty) {
                for (pl in surfaceLines(solidG, SurfacePattern.RECTILINEAR, sp, i, cx, cy)) add(ToolPath(Feature.SOLID_INFILL, pl, false, w, solidSpeed))
            }
            val topG = part(ld.topSurface)
            if (!topG.isEmpty) {
                for (pl in surfaceLines(topG, p.topSurfacePattern, sp, i, cx, cy)) add(ToolPath(Feature.TOP_SURFACE, pl, false, w, topSpeed))
                if (p.ironing) {
                    val ia = Geo.offset(topG, -w / 2)
                    val lines = Infill.lines(ia, p.ironingSpacing, p.solidInfillDirection + 90 + if (i % 2 == 0) 0.0 else 90.0, 0.0, w)
                    for (pl in Infill.chain(lines, cx, cy, ia, p.ironingSpacing * 3, keepOrder = true)) {
                        add(ToolPath(Feature.IRONING, pl, false, w, p.ironingSpeed, flow = p.ironingFlow / 100.0))
                    }
                }
            }
        }
        return LayerToolpaths(i, ld.top, h, out, ld.region)
    }

    private fun surfaceLines(area: Geometry, pattern: SurfacePattern, sp: Double, layer: Int, cx: Double, cy: Double): List<DoubleArray> {
        if (pattern == SurfacePattern.CONCENTRIC) {
            return Infill.chain(Infill.concentric(area, sp), cx, cy, null, 0.0)
        }
        val angle = p.solidInfillDirection + if (layer % 2 == 0) 0.0 else 90.0
        val lines = Infill.lines(area, sp, angle, 0.0, sp * 0.3)
        return Infill.chain(lines, cx, cy, area, sp * 2.2, keepOrder = pattern == SurfacePattern.MONOTONIC)
    }

    private fun nearestDist(loop: DoubleArray, x: Double, y: Double): Double {
        var best = Double.MAX_VALUE
        var i = 0
        while (i < loop.size) {
            val dx = loop[i] - x; val dy = loop[i + 1] - y
            val d = dx * dx + dy * dy
            if (d < best) best = d
            i += 2
        }
        return best
    }

    /** Rotates a closed loop so it starts at the seam point. */
    private fun seamed(tp: ToolPath, cx: Double, cy: Double, mode: SeamPosition, layer: Int = 0): ToolPath {
        if (!tp.closed) return tp
        val pts = tp.points
        val n = pts.size / 2
        if (n < 3) return tp
        val start = when (mode) {
            SeamPosition.NEAREST -> {
                var best = 0; var bd = Double.MAX_VALUE
                for (k in 0 until n) {
                    val dx = pts[k * 2] - cx; val dy = pts[k * 2 + 1] - cy
                    val d = dx * dx + dy * dy
                    if (d < bd) { bd = d; best = k }
                }
                best
            }
            SeamPosition.BACK -> backMost(pts, n)
            SeamPosition.RANDOM -> Random(layer * 7919 + n).nextInt(n)
            SeamPosition.ALIGNED -> aligned(pts, n)
        }
        if (start == 0) return tp
        val r = DoubleArray(pts.size)
        for (k in 0 until n) {
            val s = (start + k) % n
            r[k * 2] = pts[s * 2]; r[k * 2 + 1] = pts[s * 2 + 1]
        }
        return ToolPath(tp.feature, r, true, tp.width, tp.speed, tp.flow, tp.spiral)
    }

    private fun backMost(pts: DoubleArray, n: Int): Int {
        var best = 0
        for (k in 1 until n) {
            val y = pts[k * 2 + 1]; val by = pts[best * 2 + 1]
            if (y > by + 1e-6 || (abs(y - by) <= 1e-6 && pts[k * 2] < pts[best * 2])) best = k
        }
        return best
    }

    /** Prefers sharp (especially concave) corners, falling back to the back of the part. */
    private fun aligned(pts: DoubleArray, n: Int): Int {
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (k in 0 until n) { minY = min(minY, pts[k * 2 + 1]); maxY = max(maxY, pts[k * 2 + 1]) }
        val span = max(1.0, maxY - minY)
        var best = 0; var bestScore = -Double.MAX_VALUE
        for (k in 0 until n) {
            val pk = (k + n - 1) % n; val nk = (k + 1) % n
            val ax = pts[k * 2] - pts[pk * 2]; val ay = pts[k * 2 + 1] - pts[pk * 2 + 1]
            val bx = pts[nk * 2] - pts[k * 2]; val by = pts[nk * 2 + 1] - pts[k * 2 + 1]
            val turn = atan2(ax * by - ay * bx, ax * bx + ay * by) // >0 convex (left turn) for CCW loops
            val sharp = abs(turn)
            val cornerBonus = if (sharp > 0.6) (if (turn < 0) 1.5 else 1.0) * sharp else 0.0
            val score = (pts[k * 2 + 1] - minY) / span + cornerBonus * 0.8
            if (score > bestScore + 1e-9) { bestScore = score; best = k }
        }
        return best
    }
}
