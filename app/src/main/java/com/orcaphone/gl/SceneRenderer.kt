package com.orcaphone.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.orcaphone.core.mesh.Mesh
import com.orcaphone.core.plate.PlateObject
import com.orcaphone.core.preview.PreviewGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

class PrepareScene(
    val bedW: Double,
    val bedD: Double,
    val maxH: Double,
    val objects: List<PlateObject>,
    val selectedId: Long?,
    /** cos threshold for overhang highlighting, or -2 to disable. */
    val overhangCos: Float,
    val objectColor: Int,
)

class PreviewScene(
    val geometry: PreviewGeometry,
    val bedW: Double,
    val bedD: Double,
    val maxH: Double,
    /** Highest visible layer index (inclusive). */
    val maxLayer: Int,
    /** Number of segments of the top layer to draw, or -1 for all. */
    val movesInLayer: Int,
    val featureMask: Int,
    val colorMode: Int,
    val showTravel: Boolean,
    val palette: FloatArray,
)

/** Draws the build plate with either model meshes (Prepare) or extrusion paths (Preview). */
class SceneRenderer(private val camera: OrbitCamera) : GLSurfaceView.Renderer {
    @Volatile var prepare: PrepareScene? = null
    @Volatile var preview: PreviewScene? = null
    @Volatile var dark: Boolean = false

    var viewWidth = 1
        private set
    var viewHeight = 1
        private set

    private var meshProg = 0
    private var flatProg = 0
    private var pathProg = 0

    private class MeshGpu(val vbo: Int, val vertexCount: Int)
    private val meshCache = IdentityHashMap<Mesh, MeshGpu>()

    private var bedVbo = 0
    private var bedKey = ""
    private var bedQuadVerts = 0
    private var bedMinorStart = 0
    private var bedMinorCount = 0
    private var bedMajorStart = 0
    private var bedMajorCount = 0
    private var bedBoxStart = 0
    private var bedBoxCount = 0

    private var templateVbo = 0
    private var pathVbo = 0
    private var travelVbo = 0
    private var uploadedGeometry: PreviewGeometry? = null

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val vp = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        meshCache.clear()
        bedKey = ""
        uploadedGeometry = null
        meshProg = GlUtil.program(Shaders.MESH_VS, Shaders.MESH_FS)
        flatProg = GlUtil.program(Shaders.FLAT_VS, Shaders.FLAT_FS)
        pathProg = GlUtil.program(Shaders.PATH_VS, Shaders.PATH_FS)
        bedVbo = GlUtil.createBuffer()
        templateVbo = GlUtil.createBuffer()
        pathVbo = GlUtil.createBuffer()
        travelVbo = GlUtil.createBuffer()
        // (along, side) for the two faces of an extrusion ridge
        val tpl = floatArrayOf(
            0f, 1f, 0f, 0f, 1f, 0f,
            0f, 1f, 1f, 0f, 1f, 1f,
            0f, 0f, 0f, -1f, 1f, -1f,
            0f, 0f, 1f, -1f, 1f, 0f,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, templateVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tpl.size * 4, GlUtil.floatBuffer(tpl), GLES30.GL_STATIC_DRAW)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = max(1, width); viewHeight = max(1, height)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val isDark = dark
        if (isDark) GLES30.glClearColor(0.105f, 0.118f, 0.125f, 1f) else GLES30.glClearColor(0.925f, 0.937f, 0.945f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        camera.matrices(viewWidth.toFloat() / viewHeight, view, proj)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val pv = preview
        val pr = prepare
        val bedW = pv?.bedW ?: pr?.bedW ?: 220.0
        val bedD = pv?.bedD ?: pr?.bedD ?: 220.0
        val maxH = pv?.maxH ?: pr?.maxH ?: 250.0
        drawBed(bedW, bedD, maxH, isDark)
        if (pv != null) drawPreview(pv) else if (pr != null) drawObjects(pr)
        drawVolumeBox(isDark)
    }

    // ---------------------------------------------------------------- bed

    private fun ensureBed(w: Double, d: Double, h: Double) {
        val key = "$w/$d/$h"
        if (key == bedKey) return
        bedKey = key
        val data = ArrayList<Float>()
        fun v(x: Double, y: Double, z: Double) { data.add(x.toFloat()); data.add(y.toFloat()); data.add(z.toFloat()) }
        val z0 = -0.15
        v(0.0, 0.0, z0); v(w, 0.0, z0); v(w, d, z0); v(0.0, 0.0, z0); v(w, d, z0); v(0.0, d, z0)
        bedQuadVerts = 6
        bedMinorStart = data.size / 3
        var x = 10.0
        while (x < w - 1e-6) { if ((x % 50.0) > 1e-6) { v(x, 0.0, 0.0); v(x, d, 0.0) }; x += 10.0 }
        var y = 10.0
        while (y < d - 1e-6) { if ((y % 50.0) > 1e-6) { v(0.0, y, 0.0); v(w, y, 0.0) }; y += 10.0 }
        bedMinorCount = data.size / 3 - bedMinorStart
        bedMajorStart = data.size / 3
        x = 50.0
        while (x < w - 1e-6) { v(x, 0.0, 0.0); v(x, d, 0.0); x += 50.0 }
        y = 50.0
        while (y < d - 1e-6) { v(0.0, y, 0.0); v(w, y, 0.0); y += 50.0 }
        v(0.0, 0.0, 0.0); v(w, 0.0, 0.0); v(w, 0.0, 0.0); v(w, d, 0.0)
        v(w, d, 0.0); v(0.0, d, 0.0); v(0.0, d, 0.0); v(0.0, 0.0, 0.0)
        bedMajorCount = data.size / 3 - bedMajorStart
        bedBoxStart = data.size / 3
        for ((ax, ay) in listOf(0.0 to 0.0, w to 0.0, w to d, 0.0 to d)) { v(ax, ay, 0.0); v(ax, ay, h) }
        v(0.0, 0.0, h); v(w, 0.0, h); v(w, 0.0, h); v(w, d, h); v(w, d, h); v(0.0, d, h); v(0.0, d, h); v(0.0, 0.0, h)
        bedBoxCount = data.size / 3 - bedBoxStart
        val arr = data.toFloatArray()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bedVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, arr.size * 4, GlUtil.floatBuffer(arr), GLES30.GL_STATIC_DRAW)
    }

    private fun bindFlat() {
        GLES30.glUseProgram(flatProg)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(flatProg, "u_vp"), 1, false, vp, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bedVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
    }

    private fun drawBed(w: Double, d: Double, h: Double, isDark: Boolean) {
        ensureBed(w, d, h)
        bindFlat()
        val colorLoc = GLES30.glGetUniformLocation(flatProg, "u_color")
        if (isDark) GLES30.glUniform4f(colorLoc, 0.17f, 0.19f, 0.2f, 1f) else GLES30.glUniform4f(colorLoc, 0.80f, 0.83f, 0.85f, 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, bedQuadVerts)
        if (isDark) GLES30.glUniform4f(colorLoc, 0.24f, 0.27f, 0.29f, 1f) else GLES30.glUniform4f(colorLoc, 0.70f, 0.74f, 0.76f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, bedMinorStart, bedMinorCount)
        if (isDark) GLES30.glUniform4f(colorLoc, 0.34f, 0.38f, 0.40f, 1f) else GLES30.glUniform4f(colorLoc, 0.55f, 0.60f, 0.63f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, bedMajorStart, bedMajorCount)
        GLES30.glDisableVertexAttribArray(0)
    }

    private fun drawVolumeBox(isDark: Boolean) {
        bindFlat()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        val colorLoc = GLES30.glGetUniformLocation(flatProg, "u_color")
        if (isDark) GLES30.glUniform4f(colorLoc, 0.6f, 0.65f, 0.7f, 0.25f) else GLES30.glUniform4f(colorLoc, 0.3f, 0.35f, 0.4f, 0.25f)
        GLES30.glDrawArrays(GLES30.GL_LINES, bedBoxStart, bedBoxCount)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisableVertexAttribArray(0)
    }

    // ---------------------------------------------------------------- objects

    private fun upload(mesh: Mesh): MeshGpu {
        val tc = mesh.triangleCount
        val buf = ByteBuffer.allocateDirect(tc * 3 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val v = mesh.vertices
        val idx = mesh.indices
        for (t in 0 until tc) {
            val a = idx[t * 3] * 3; val b = idx[t * 3 + 1] * 3; val c = idx[t * 3 + 2] * 3
            val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
            val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 0) { nx /= l; ny /= l; nz /= l }
            for (k in intArrayOf(a, b, c)) {
                buf.put(v[k]); buf.put(v[k + 1]); buf.put(v[k + 2]); buf.put(nx); buf.put(ny); buf.put(nz)
            }
        }
        buf.position(0)
        val vbo = GlUtil.createBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tc * 3 * 24, buf, GLES30.GL_STATIC_DRAW)
        return MeshGpu(vbo, tc * 3)
    }

    private fun drawObjects(scene: PrepareScene) {
        // free meshes that left the plate
        val live = scene.objects.map { it.mesh }.toSet()
        val it = meshCache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in live) { GlUtil.deleteBuffer(e.value.vbo); it.remove() }
        }
        GLES30.glUseProgram(meshProg)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(meshProg, "u_vp"), 1, false, vp, 0)
        val eye = camera.eye()
        GLES30.glUniform3f(GLES30.glGetUniformLocation(meshProg, "u_eye"), eye[0].toFloat(), eye[1].toFloat(), eye[2].toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProg, "u_overhangCos"), scene.overhangCos)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProg, "u_alpha"), 1f)
        val modelLoc = GLES30.glGetUniformLocation(meshProg, "u_model")
        val nrmLoc = GLES30.glGetUniformLocation(meshProg, "u_nrm")
        val colorLoc = GLES30.glGetUniformLocation(meshProg, "u_color")
        val base = scene.objectColor
        for (obj in scene.objects) {
            val gpu = meshCache.getOrPut(obj.mesh) { upload(obj.mesh) }
            val m = obj.worldMatrix
            GLES30.glUniformMatrix4fv(modelLoc, 1, false, m.toFloatArray(), 0)
            val inv = m.inverseAffine()
            val n = FloatArray(9)
            for (c in 0 until 3) for (r in 0 until 3) n[c * 3 + r] = inv.m[r * 4 + c].toFloat()
            GLES30.glUniformMatrix3fv(nrmLoc, 1, false, n, 0)
            val inside = obj.isInside(scene.bedW, scene.bedD, scene.maxH)
            val (cr, cg, cb) = when {
                !inside -> Triple(0.85f, 0.35f, 0.25f)
                obj.id == scene.selectedId -> Triple(1.0f, 0.62f, 0.2f)
                else -> Triple(((base shr 16) and 0xff) / 255f, ((base shr 8) and 0xff) / 255f, (base and 0xff) / 255f)
            }
            GLES30.glUniform3f(colorLoc, cr, cg, cb)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gpu.vbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, gpu.vertexCount)
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    // ---------------------------------------------------------------- preview

    private fun drawPreview(scene: PreviewScene) {
        val g = scene.geometry
        if (g !== uploadedGeometry) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pathVbo)
            g.instances.position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, max(1, g.instances.limit()), g.instances, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, travelVbo)
            g.travel.position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, max(1, g.travel.limit()), g.travel, GLES30.GL_STATIC_DRAW)
            uploadedGeometry = g
        }
        if (g.layerCount == 0) return
        val layer = scene.maxLayer.coerceIn(0, g.layerCount - 1)
        val layerStart = g.layerInstanceStart[layer]
        val layerEnd = g.layerInstanceStart[layer + 1]
        val count = if (scene.movesInLayer < 0) layerEnd else min(layerEnd, layerStart + scene.movesInLayer)

        GLES30.glUseProgram(pathProg)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pathProg, "u_vp"), 1, false, vp, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(pathProg, "u_mask"), scene.featureMask)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(pathProg, "u_colorMode"), scene.colorMode)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(pathProg, "u_palette"), scene.palette.size / 3, scene.palette, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pathProg, "u_dimBelow"), if (scene.movesInLayer >= 0) layerStart.toFloat() else 0f)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, templateVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glVertexAttribDivisor(0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pathVbo)
        val s = PreviewGeometry.INSTANCE_STRIDE
        for (loc in 1..4) { GLES30.glEnableVertexAttribArray(loc); GLES30.glVertexAttribDivisor(loc, 1) }
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, s, 0)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, s, 12)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, s, 24)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_UNSIGNED_BYTE, true, s, 32)
        if (count > 0) GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 12, count)
        for (loc in 1..4) { GLES30.glVertexAttribDivisor(loc, 0); GLES30.glDisableVertexAttribArray(loc) }
        GLES30.glDisableVertexAttribArray(0)

        if (scene.showTravel) {
            val tEnd = g.layerTravelStart[layer + 1]
            if (tEnd > 0) {
                GLES30.glUseProgram(flatProg)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(flatProg, "u_vp"), 1, false, vp, 0)
                GLES30.glUniform4f(GLES30.glGetUniformLocation(flatProg, "u_color"), 0.24f, 0.44f, 0.88f, 1f)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, travelVbo)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, PreviewGeometry.TRAVEL_STRIDE, 0)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, tEnd)
                GLES30.glDisableVertexAttribArray(0)
            }
        }
    }
}
