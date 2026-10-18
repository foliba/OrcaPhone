package com.orcaphone.core.preview

import com.orcaphone.core.slice.Feature
import com.orcaphone.core.slice.LayerToolpaths
import com.orcaphone.core.slice.ToolPath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * GPU-ready preview data. Every extrusion segment is one instance (36 bytes):
 *   float px, py, pz, qx, qy, qz, width, height; ubyte feature, speedNorm, 0, 0
 * Travel moves are plain line vertices (12 bytes: float x, y, z).
 */
class PreviewGeometry(
    val instances: ByteBuffer,
    val instanceCount: Int,
    /** layerInstanceStart[i] = first instance of layer i; size = layers + 1. */
    val layerInstanceStart: IntArray,
    val travel: ByteBuffer,
    val travelVertexCount: Int,
    val layerTravelStart: IntArray,
    val layerZ: DoubleArray,
    val minSpeed: Double,
    val maxSpeed: Double,
    val featuresPresent: Set<Feature>,
) {
    val layerCount get() = layerZ.size

    companion object {
        const val INSTANCE_STRIDE = 36
        const val TRAVEL_STRIDE = 12

        fun build(layers: List<LayerToolpaths>, effectiveSpeed: (ToolPath, Double) -> Double): PreviewGeometry {
            var segs = 0
            var travels = 0
            for (l in layers) for (p in l.paths) { segs += if (p.closed) p.pointCount else p.pointCount - 1; travels++ }
            var minS = Double.MAX_VALUE; var maxS = 0.0
            for (l in layers) for (p in l.paths) {
                val s = effectiveSpeed(p, l.height)
                minS = min(minS, s); maxS = max(maxS, s)
            }
            if (minS > maxS) { minS = 0.0; maxS = 1.0 }
            val inst = ByteBuffer.allocateDirect(max(1, segs) * INSTANCE_STRIDE).order(ByteOrder.nativeOrder())
            val trav = ByteBuffer.allocateDirect(max(1, travels) * 2 * TRAVEL_STRIDE).order(ByteOrder.nativeOrder())
            val layerStart = IntArray(layers.size + 1)
            val travelStart = IntArray(layers.size + 1)
            val present = HashSet<Feature>()
            var count = 0
            var tCount = 0
            var lx = Float.NaN; var ly = 0f; var lz = 0f
            for ((li, l) in layers.withIndex()) {
                layerStart[li] = count
                travelStart[li] = tCount
                val zTop = l.z.toFloat()
                for (p in l.paths) {
                    present.add(p.feature)
                    val speedNorm = ((effectiveSpeed(p, l.height) - minS) / max(1e-6, maxS - minS) * 255).toInt().coerceIn(0, 255)
                    val pts = p.points
                    val n = p.pointCount
                    if (!lx.isNaN()) {
                        trav.putFloat(lx); trav.putFloat(ly); trav.putFloat(lz)
                        trav.putFloat(pts[0].toFloat()); trav.putFloat(pts[1].toFloat()); trav.putFloat(zTop)
                        tCount += 2
                    }
                    val total = if (p.spiral) p.length() else 0.0
                    val z0 = l.z - l.height
                    var acc = 0.0
                    var pz = if (p.spiral) z0.toFloat() else zTop
                    val last = if (p.closed) n else n - 1
                    for (k in 1..last) {
                        val a = (k - 1) * 2
                        val b = (k % n) * 2
                        val ax = pts[a]; val ay = pts[a + 1]; val bx = pts[b]; val by = pts[b + 1]
                        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                        acc += len
                        val qz = if (p.spiral && total > 0) (z0 + l.height * acc / total).toFloat() else zTop
                        inst.putFloat(ax.toFloat()); inst.putFloat(ay.toFloat()); inst.putFloat(pz)
                        inst.putFloat(bx.toFloat()); inst.putFloat(by.toFloat()); inst.putFloat(qz)
                        inst.putFloat(p.width.toFloat()); inst.putFloat(l.height.toFloat())
                        inst.put(p.feature.ordinal.toByte()); inst.put(speedNorm.toByte()); inst.put(0); inst.put(0)
                        pz = qz
                        count++
                    }
                    lx = p.endX.toFloat(); ly = p.endY.toFloat(); lz = zTop
                }
            }
            layerStart[layers.size] = count
            travelStart[layers.size] = tCount
            inst.flip(); trav.flip()
            return PreviewGeometry(
                inst, count, layerStart, trav, tCount, travelStart,
                DoubleArray(layers.size) { layers[it].z }, minS, maxS, present,
            )
        }
    }
}
