package com.orcaphone.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import com.orcaphone.core.plate.PlateObject
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.abs
import kotlin.math.hypot

interface ViewportListener {
    fun onTapObject(id: Long?) {}
    fun onDragObject(id: Long, dx: Double, dy: Double) {}
    fun onDragEnd() {}
    fun onDoubleTap() {}
}

/**
 * GLSurfaceView with touch controls tuned for phones:
 * one finger orbits (or drags the selected object), two fingers pan and pinch-zoom,
 * tap selects, double tap re-frames.
 */
@SuppressLint("ViewConstructor")
class GlViewport(context: Context, val camera: OrbitCamera) : GLSurfaceView(context) {
    val renderer = SceneRenderer(camera)
    var listener: ViewportListener? = null
    /** When false, objects cannot be picked or dragged (preview mode). */
    var editable = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var multiTouch = false
    private var dragId: Long? = null
    private var dragPlaneZ = 0.0
    private var dragLast: DoubleArray? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera.zoom(1f / detector.scaleFactor.coerceIn(0.5f, 2f))
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    private val objects: List<PlateObject> get() = renderer.prepare?.objects ?: emptyList()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                downTime = e.eventTime
                moved = false; multiTouch = false
                dragId = null
                if (editable) {
                    val sel = renderer.prepare?.selectedId
                    val hit = pick(e.x, e.y)
                    if (sel != null && hit != null && hit.first == sel) {
                        dragId = sel
                        dragPlaneZ = hit.second[2]
                        dragLast = planePoint(e.x, e.y, dragPlaneZ)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                if (dragId != null) { dragId = null; listener?.onDragEnd() }
                lastX = centroidX(e); lastY = centroidY(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // continue with the remaining finger(s) without a jump
                val skip = e.actionIndex
                var sx = 0f; var sy = 0f; var n = 0
                for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
                if (n > 0) { lastX = sx / n; lastY = sy / n }
            }
            MotionEvent.ACTION_MOVE -> {
                val cx = centroidX(e); val cy = centroidY(e)
                val dx = cx - lastX; val dy = cy - lastY
                if (!moved && hypot(cx - downX, cy - downY) > touchSlop) moved = true
                if (moved || multiTouch) {
                    if (e.pointerCount >= 2) {
                        camera.pan(dx, dy, height)
                    } else if (!multiTouch) {
                        val id = dragId
                        if (id != null) {
                            val p = planePoint(e.x, e.y, dragPlaneZ)
                            val last = dragLast
                            if (p != null && last != null) {
                                listener?.onDragObject(id, p[0] - last[0], p[1] - last[1])
                                dragLast = p
                            }
                        } else {
                            camera.orbit(dx, dy)
                        }
                    }
                    lastX = cx; lastY = cy
                    requestRender()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragId != null) { listener?.onDragEnd(); dragId = null }
                if (!moved && !multiTouch && e.eventTime - downTime < 400) {
                    val now = e.eventTime
                    if (now - lastTapTime < 300 && hypot(e.x - lastTapX, e.y - lastTapY) < touchSlop * 3) {
                        listener?.onDoubleTap()
                        lastTapTime = 0
                    } else {
                        lastTapTime = now; lastTapX = e.x; lastTapY = e.y
                        if (editable) listener?.onTapObject(pick(e.x, e.y)?.first)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragId != null) { listener?.onDragEnd(); dragId = null }
            }
        }
        return true
    }

    private fun centroidX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun centroidY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    private fun planePoint(sx: Float, sy: Float, z: Double): DoubleArray? {
        val r = camera.ray(sx, sy, width, height)
        if (abs(r[5]) < 1e-6) return null
        val t = (z - r[2]) / r[5]
        if (t < 0) return null
        return doubleArrayOf(r[0] + r[3] * t, r[1] + r[4] * t, z)
    }

    /** Returns the hit object id and world hit point. */
    private fun pick(sx: Float, sy: Float): Pair<Long, DoubleArray>? {
        if (width == 0 || height == 0) return null
        val r = camera.ray(sx, sy, width, height)
        var bestT = Double.MAX_VALUE
        var best: Long? = null
        for (o in objects) {
            val b = o.worldBounds
            if (!rayHitsBox(r, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) continue
            val t = Picking.rayMesh(r, o) ?: continue
            if (t < bestT) { bestT = t; best = o.id }
        }
        val id = best ?: return null
        return id to doubleArrayOf(r[0] + r[3] * bestT, r[1] + r[4] * bestT, r[2] + r[5] * bestT)
    }

    private fun rayHitsBox(r: DoubleArray, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): Boolean {
        var tmin = -Double.MAX_VALUE; var tmax = Double.MAX_VALUE
        val lo = doubleArrayOf(x0, y0, z0); val hi = doubleArrayOf(x1, y1, z1)
        for (a in 0 until 3) {
            val o = r[a]; val d = r[a + 3]
            if (abs(d) < 1e-12) { if (o < lo[a] || o > hi[a]) return false; continue }
            var t1 = (lo[a] - o) / d; var t2 = (hi[a] - o) / d
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tmin = maxOf(tmin, t1); tmax = minOf(tmax, t2)
            if (tmin > tmax) return false
        }
        return tmax >= 0
    }

    /** Prefers 4x MSAA, falls back to a plain RGB888 + depth config. */
    private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val es3 = 0x40 // EGL_OPENGL_ES3_BIT_KHR
            val attempts = listOf(
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE),
            )
            for (attrs in attempts) {
                val num = IntArray(1)
                val configs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, attrs, configs, 1, num) && num[0] > 0 && configs[0] != null) return configs[0]!!
            }
            throw IllegalStateException("No suitable EGL config")
        }
    }
}

object Picking {
    /** Möller–Trumbore against the object's mesh in local space. Returns ray parameter t or null. */
    fun rayMesh(ray: DoubleArray, obj: PlateObject): Double? {
        val inv = obj.worldMatrix.inverseAffine()
        val o = DoubleArray(3); inv.transformPoint(ray[0], ray[1], ray[2], o)
        val e = DoubleArray(3); inv.transformPoint(ray[0] + ray[3], ray[1] + ray[4], ray[2] + ray[5], e)
        val dx = e[0] - o[0]; val dy = e[1] - o[1]; val dz = e[2] - o[2]
        val v = obj.mesh.vertices
        val idx = obj.mesh.indices
        var best = Double.MAX_VALUE
        for (t in 0 until obj.mesh.triangleCount) {
            val a = idx[t * 3] * 3; val b = idx[t * 3 + 1] * 3; val c = idx[t * 3 + 2] * 3
            val e1x = (v[b] - v[a]).toDouble(); val e1y = (v[b + 1] - v[a + 1]).toDouble(); val e1z = (v[b + 2] - v[a + 2]).toDouble()
            val e2x = (v[c] - v[a]).toDouble(); val e2y = (v[c + 1] - v[a + 1]).toDouble(); val e2z = (v[c + 2] - v[a + 2]).toDouble()
            val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12) continue
            val inv2 = 1.0 / det
            val tx = o[0] - v[a]; val ty = o[1] - v[a + 1]; val tz = o[2] - v[a + 2]
            val u = (tx * px + ty * py + tz * pz) * inv2
            if (u < 0 || u > 1) continue
            val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
            val w = (dx * qx + dy * qy + dz * qz) * inv2
            if (w < 0 || u + w > 1) continue
            val tt = (e2x * qx + e2y * qy + e2z * qz) * inv2
            if (tt > 0 && tt < best) best = tt
        }
        return if (best == Double.MAX_VALUE) null else best
    }
}
