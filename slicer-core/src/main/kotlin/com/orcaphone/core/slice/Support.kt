package com.orcaphone.core.slice

import com.orcaphone.core.config.ProcessProfile
import org.locationtech.jts.geom.Geometry
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Support areas for one layer: [base] gets sparse lines, [interfaceArea] dense lines right under the model.
 * Tree supports print up to [walls] outline loops per branch; organic branches are hollow ([infill] = false).
 */
class SupportLayer(val base: Geometry, val interfaceArea: Geometry, val walls: Int = 0, val infill: Boolean = true)

/**
 * "Normal" (grid-style) support: overhangs are detected by comparing each layer with the
 * one below (offset by the threshold-angle allowance) and projected down to the plate.
 */
object SupportGenerator {

    /**
     * Areas of each layer that need support: detected with the threshold-angle allowance, then the
     * whole overhanging strip (R[i] - R[i-1]) near the detection so gentle slopes form a continuous area.
     */
    fun detectOverhangs(regions: Array<Geometry>, h: Double, p: ProcessProfile, lineWidth: Double, runner: ParallelRunner): Array<Geometry> {
        val n = regions.size
        val angle = p.supportThresholdAngle.coerceIn(1.0, 89.0) * PI / 180
        val allowance = h / tan(angle)
        val overhang = Array<Geometry>(n) { Geo.EMPTY }
        if (n < 2) return overhang
        runner.forEach(n - 1) { k ->
            val i = k + 1
            val below = Geo.offset(regions[i - 1], allowance)
            val detected = Geo.open(Geo.difference(regions[i], below), 0.02)
            if (Geo.removeSmall(detected, lineWidth * lineWidth * 0.25).isEmpty) return@forEach
            val strip = Geo.difference(regions[i], regions[i - 1])
            val o = Geo.intersection(strip, Geo.offset(detected, allowance + lineWidth))
            overhang[i] = Geo.simplify(Geo.removeSmall(o, lineWidth * lineWidth * 0.5), 0.03)
        }
        return overhang
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
        val xy = p.supportObjectXYDistance + lineWidth / 2

        // 1. overhangs per layer
        val overhang = detectOverhangs(regions, h, p, lineWidth, runner)
        progress(0.3f)
        // 2. grown model for clearance
        val clearance = Array<Geometry>(n) { Geo.EMPTY }
        runner.forEach(n) { i -> clearance[i] = Geo.simplify(Geo.offset(regions[i], xy, roundJoins = true), 0.05) }
        progress(0.5f)

        // 3. project down
        val support = Array<Geometry>(n) { Geo.EMPTY }
        val ifaceLayers = p.supportInterfaceLayers.coerceAtLeast(0)
        var carried: Geometry = Geo.EMPTY
        for (j in n - 1 downTo 0) {
            cancel.check()
            val src = j + 1 + gap
            if (src < n && !overhang[src].isEmpty) carried = Geo.union(carried, overhang[src])
            if (carried.isEmpty) continue
            // only the model itself blocks the projection; the XY clearance just trims what is printed
            carried = Geo.simplify(Geo.difference(carried, regions[j]), 0.03)
            var s = Geo.difference(carried, clearance[j])
            for (k in j + 1..minOf(n - 1, j + gap)) s = Geo.difference(s, clearance[k])
            support[j] = Geo.simplify(Geo.removeSmall(Geo.open(s, lineWidth * 0.3), lineWidth * lineWidth * 4), 0.05)
            if (j % 8 == 0) progress(0.5f + 0.4f * (n - j) / n)
        }

        // 4. build plate only: drop anything standing on the model
        if (p.supportOnBuildPlateOnly) {
            var below: Geometry = Geo.EMPTY
            for (j in 0 until n) {
                below = Geo.simplify(Geo.union(below, clearance[j]), 0.05)
                support[j] = Geo.difference(support[j], below)
            }
            // a column interrupted by the model must not restart above it
            for (j in 1 until n) support[j] = Geo.intersection(support[j], Geo.offset(support[j - 1], lineWidth))
        }

        // 5. interface layers
        val result = Array(n) { empty }
        runner.forEach(n) { j ->
            val s = support[j]
            if (s.isEmpty) return@forEach
            if (ifaceLayers == 0) { result[j] = SupportLayer(s, Geo.EMPTY); return@forEach }
            val tops = ArrayList<Geometry>()
            for (k in 1..ifaceLayers) {
                val src = j + gap + k
                if (src < n) tops.add(overhang[src])
            }
            val iface = Geo.removeSmall(Geo.intersection(s, Geo.union(tops)), lineWidth * lineWidth)
            result[j] = SupportLayer(Geo.difference(s, iface), iface)
        }
        progress(1f)
        return result
    }
}
