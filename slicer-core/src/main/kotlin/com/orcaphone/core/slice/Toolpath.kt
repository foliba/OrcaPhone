package com.orcaphone.core.slice

/** Extrusion roles, matching OrcaSlicer's feature types shown in the preview legend. */
enum class Feature(val label: String, val colorArgb: Int, val gcodeName: String) {
    OUTER_WALL("Outer wall", 0xFFFF7D38.toInt(), "Outer wall"),
    INNER_WALL("Inner wall", 0xFFFFE64D.toInt(), "Inner wall"),
    SPARSE_INFILL("Sparse infill", 0xFFB03029.toInt(), "Sparse infill"),
    SOLID_INFILL("Internal solid infill", 0xFF9654CC.toInt(), "Internal solid infill"),
    TOP_SURFACE("Top surface", 0xFFF04040.toInt(), "Top surface"),
    BOTTOM_SURFACE("Bottom surface", 0xFF6680B3.toInt(), "Bottom surface"),
    IRONING("Ironing", 0xFFFFA07A.toInt(), "Ironing"),
    SUPPORT("Support", 0xFF4CC24C.toInt(), "Support"),
    SUPPORT_INTERFACE("Support interface", 0xFF26A69A.toInt(), "Support interface"),
    SKIRT("Skirt", 0xFF00C2B2.toInt(), "Skirt"),
    BRIM("Brim", 0xFF00897B.toInt(), "Brim"),
    TRAVEL("Travel", 0xFF3D6FE0.toInt(), "Travel"),
}

/**
 * One continuous extrusion. [points] holds x,y pairs; closed paths return to the first point.
 * [speed] is the requested feed rate in mm/s before volumetric / cooling limits.
 */
class ToolPath(
    val feature: Feature,
    val points: DoubleArray,
    val closed: Boolean,
    val width: Double,
    val speed: Double,
    val flow: Double = 1.0,
    /** Spiral-vase paths ramp Z from the previous layer top to this layer top. */
    val spiral: Boolean = false,
) {
    val pointCount get() = points.size / 2
    val startX get() = points[0]
    val startY get() = points[1]
    val endX get() = if (closed) points[0] else points[points.size - 2]
    val endY get() = if (closed) points[1] else points[points.size - 1]

    fun length(): Double {
        var l = 0.0
        val n = pointCount
        for (i in 1 until n) l += hyp(points[i * 2] - points[i * 2 - 2], points[i * 2 + 1] - points[i * 2 - 1])
        if (closed && n > 1) l += hyp(points[0] - points[n * 2 - 2], points[1] - points[n * 2 - 1])
        return l
    }

    fun reversed(): ToolPath {
        val n = pointCount
        val r = DoubleArray(points.size)
        for (i in 0 until n) { r[i * 2] = points[(n - 1 - i) * 2]; r[i * 2 + 1] = points[(n - 1 - i) * 2 + 1] }
        return ToolPath(feature, r, closed, width, speed, flow, spiral)
    }

    private fun hyp(a: Double, b: Double) = kotlin.math.sqrt(a * a + b * b)
}

/** Ordered tool paths of one printed layer. */
class LayerToolpaths(
    val index: Int,
    /** Top of the layer (nozzle height). */
    val z: Double,
    val height: Double,
    val paths: List<ToolPath>,
    /** Area inside which travel moves need no retraction (the printed part), if known. */
    val travelArea: org.locationtech.jts.geom.Geometry? = null,
)
