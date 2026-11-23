package com.orcaphone.core.plate

import com.orcaphone.core.mesh.Bounds3
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Places instances (copies sharing one footprint) on a single bed without overlap.
 * Every footprint keeps [spacing] from the others and from [obstacles], and [edge] from the bed border.
 */
object Instances {
    /**
     * Centres of up to [count] instances of a [sx] x [sy] footprint, or as many as fit when [count] is null
     * ("fill bed"). The result size is the number that fits when it is smaller than [count].
     */
    fun place(
        sx: Double, sy: Double, bedW: Double, bedD: Double,
        spacing: Double, edge: Double = 0.0,
        obstacles: List<Bounds3> = emptyList(),
        count: Int? = null,
    ): List<Pair<Double, Double>> {
        val grid = grid(sx, sy, bedW, bedD, spacing, edge, obstacles)
        val want = count ?: Int.MAX_VALUE
        // A regular grid around the centre looks tidy; a greedy scan packs around other objects better.
        if (obstacles.isEmpty() || grid.size >= want) return pick(grid, want, bedW, bedD)
        val greedy = greedy(sx, sy, bedW, bedD, spacing, edge, obstacles)
        return if (greedy.size > grid.size) greedy.take(want) else pick(grid, want, bedW, bedD)
    }

    /** The most instances that fit. */
    fun max(sx: Double, sy: Double, bedW: Double, bedD: Double, spacing: Double, edge: Double = 0.0, obstacles: List<Bounds3> = emptyList()) =
        place(sx, sy, bedW, bedD, spacing, edge, obstacles).size

    private fun pick(cells: List<Pair<Double, Double>>, n: Int, bedW: Double, bedD: Double): List<Pair<Double, Double>> {
        if (n >= cells.size) return cells
        val cx = bedW / 2; val cy = bedD / 2
        // nearest to the centre, then in reading order so the layout is stable
        return cells.sortedBy { (x, y) -> hypot(x - cx, y - cy) }.take(n)
            .sortedWith(compareByDescending<Pair<Double, Double>> { it.second }.thenBy { it.first })
    }

    private fun free(x: Double, y: Double, sx: Double, sy: Double, spacing: Double, boxes: List<Bounds3>) = boxes.none { b ->
        x - sx / 2 < b.maxX + spacing - 1e-9 && x + sx / 2 > b.minX - spacing + 1e-9 &&
            y - sy / 2 < b.maxY + spacing - 1e-9 && y + sy / 2 > b.minY - spacing + 1e-9
    }

    private fun grid(
        sx: Double, sy: Double, bedW: Double, bedD: Double, spacing: Double, edge: Double, obstacles: List<Bounds3>,
    ): List<Pair<Double, Double>> {
        val roomW = bedW - 2 * edge; val roomD = bedD - 2 * edge
        if (sx > roomW + 1e-9 || sy > roomD + 1e-9) return emptyList()
        val cols = floor((roomW + spacing + 1e-9) / (sx + spacing)).toInt()
        val rows = floor((roomD + spacing + 1e-9) / (sy + spacing)).toInt()
        val x0 = (bedW - (cols * sx + (cols - 1) * spacing)) / 2 + sx / 2
        val y0 = (bedD - (rows * sy + (rows - 1) * spacing)) / 2 + sy / 2
        val out = ArrayList<Pair<Double, Double>>()
        for (r in rows - 1 downTo 0) for (c in 0 until cols) {
            val x = x0 + c * (sx + spacing); val y = y0 + r * (sy + spacing)
            if (free(x, y, sx, sy, spacing, obstacles)) out.add(x to y)
        }
        return out
    }

    /** Row-major scan from the back-left corner, taking every spot that is still free. */
    private fun greedy(
        sx: Double, sy: Double, bedW: Double, bedD: Double, spacing: Double, edge: Double, obstacles: List<Bounds3>,
    ): List<Pair<Double, Double>> {
        if (sx > bedW - 2 * edge + 1e-9 || sy > bedD - 2 * edge + 1e-9) return emptyList()
        val step = 2.0
        val placed = ArrayList<Pair<Double, Double>>()
        val boxes = ArrayList(obstacles)
        var y = bedD - edge - sy / 2
        while (y >= edge + sy / 2 - 1e-9) {
            var x = edge + sx / 2
            while (x <= bedW - edge - sx / 2 + 1e-9) {
                if (free(x, y, sx, sy, spacing, boxes)) {
                    placed.add(x to y)
                    boxes.add(Bounds3(x - sx / 2, y - sy / 2, 0.0, x + sx / 2, y + sy / 2, 0.0))
                    x += sx + spacing
                } else x += step
            }
            y -= step
        }
        return placed
    }
}
