package com.orcaphone.gl

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Turntable camera orbiting a target point, Z up (like OrcaSlicer). Thread-safe via synchronized. */
class OrbitCamera {
    var yaw = Math.toRadians(-90.0 - 30.0)
        private set
    var pitch = Math.toRadians(35.0)
        private set
    var distance = 400.0
        private set
    var targetX = 110.0
    var targetY = 110.0
    var targetZ = 0.0
    private val fovY = 35.0

    /** Bumped on every change so observers can redraw. */
    @Volatile
    var version = 0
        private set

    @Synchronized
    fun orbit(dxPx: Float, dyPx: Float) {
        yaw -= dxPx * 0.008
        pitch = (pitch + dyPx * 0.008).coerceIn(Math.toRadians(-89.0), Math.toRadians(89.0))
        version++
    }

    @Synchronized
    fun pan(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        val worldPerPx = 2 * distance * tan(Math.toRadians(fovY / 2)) / max(1, viewHeightPx)
        // camera right and up vectors on the ground-aligned frame
        val rx = -sin(yaw); val ry = cos(yaw)
        val ux = -cos(yaw) * sin(pitch); val uy = -sin(yaw) * sin(pitch); val uz = cos(pitch)
        targetX += (-dxPx * rx + dyPx * ux) * worldPerPx
        targetY += (-dxPx * ry + dyPx * uy) * worldPerPx
        targetZ += (dyPx * uz) * worldPerPx
        version++
    }

    @Synchronized
    fun zoom(factor: Float) {
        distance = (distance * factor).coerceIn(10.0, 5000.0)
        version++
    }

    @Synchronized
    fun setView(yawDeg: Double, pitchDeg: Double) {
        yaw = Math.toRadians(yawDeg); pitch = Math.toRadians(pitchDeg)
        version++
    }

    /** Frames a box of the given size centred at (cx, cy, cz). */
    @Synchronized
    fun frame(cx: Double, cy: Double, cz: Double, radius: Double) {
        targetX = cx; targetY = cy; targetZ = cz
        distance = (radius / sin(Math.toRadians(fovY / 2)) * 1.05).coerceIn(20.0, 5000.0)
        version++
    }

    @Synchronized
    fun eye(): DoubleArray = doubleArrayOf(
        targetX + distance * cos(pitch) * cos(yaw),
        targetY + distance * cos(pitch) * sin(yaw),
        targetZ + distance * sin(pitch),
    )

    /** Writes view and projection matrices. */
    @Synchronized
    fun matrices(aspect: Float, view: FloatArray, proj: FloatArray) {
        val e = eye()
        val upZ = if (cos(pitch) >= 0) 1f else -1f
        Matrix.setLookAtM(
            view, 0, e[0].toFloat(), e[1].toFloat(), e[2].toFloat(),
            targetX.toFloat(), targetY.toFloat(), targetZ.toFloat(), 0f, 0f, upZ,
        )
        val near = (distance * 0.02).toFloat().coerceAtLeast(0.5f)
        val far = (distance * 20 + 2000).toFloat()
        Matrix.perspectiveM(proj, 0, fovY.toFloat(), aspect, near, far)
    }

    /** World-space ray through a screen point: returns [ox, oy, oz, dx, dy, dz]. */
    fun ray(sx: Float, sy: Float, width: Int, height: Int): DoubleArray {
        val view = FloatArray(16); val proj = FloatArray(16); val vp = FloatArray(16); val inv = FloatArray(16)
        matrices(width.toFloat() / max(1, height), view, proj)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.invertM(inv, 0, vp, 0)
        val nx = 2f * sx / width - 1f
        val ny = 1f - 2f * sy / height
        val near = FloatArray(4); val far = FloatArray(4)
        Matrix.multiplyMV(near, 0, inv, 0, floatArrayOf(nx, ny, -1f, 1f), 0)
        Matrix.multiplyMV(far, 0, inv, 0, floatArrayOf(nx, ny, 1f, 1f), 0)
        val ox = near[0] / near[3]; val oy = near[1] / near[3]; val oz = near[2] / near[3]
        var dx = far[0] / far[3] - ox; var dy = far[1] / far[3] - oy; var dz = far[2] / far[3] - oz
        val l = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        dx /= l; dy /= l; dz /= l
        return doubleArrayOf(ox.toDouble(), oy.toDouble(), oz.toDouble(), dx.toDouble(), dy.toDouble(), dz.toDouble())
    }
}
