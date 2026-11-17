package com.orcaphone.core.gcode

import com.orcaphone.core.mesh.Mesh
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Small software renderer for the preview images embedded in G-code (shown by Mainsail, Fluidd,
 * OctoPrint and printer screens): the models seen from the front-right above, flat shaded in the
 * filament colour on a transparent background, encoded as PNG.
 */
object Thumbnail {
    /** Renders [meshes] (world coordinates) into a [width] x [height] RGBA PNG. */
    fun renderPng(meshes: List<Mesh>, width: Int, height: Int, rgb: Int): ByteArray =
        encodePng(render(meshes, width, height, rgb), width, height)

    /** G-code comment block in the PrusaSlicer/OrcaSlicer format ("; thumbnail begin WxH LEN" ... "; thumbnail end"). */
    fun gcodeBlock(png: ByteArray, width: Int, height: Int): String {
        val b64 = Base64.getEncoder().encodeToString(png)
        val sb = StringBuilder(b64.length + b64.length / 78 * 3 + 64)
        sb.append(";\n; thumbnail begin ").append(width).append('x').append(height).append(' ').append(b64.length).append('\n')
        var i = 0
        while (i < b64.length) {
            sb.append("; ").append(b64, i, min(b64.length, i + 78)).append('\n')
            i += 78
        }
        sb.append("; thumbnail end\n;\n")
        return sb.toString()
    }

    /** RGBA pixels, row-major from the top. 3x supersampled for smooth edges. */
    fun render(meshes: List<Mesh>, width: Int, height: Int, rgb: Int): ByteArray {
        val ss = 3
        val w = width * ss; val h = height * ss
        // View: looking from the front-right, 35° above the bed (world Z up).
        val yaw = Math.toRadians(-35.0); val pitch = Math.toRadians(35.0)
        val rx = doubleArrayOf(Math.cos(yaw), Math.sin(yaw), 0.0) // screen right
        val fwd = doubleArrayOf(-Math.sin(yaw) * Math.cos(pitch), Math.cos(yaw) * Math.cos(pitch), -Math.sin(pitch)) // into the screen
        val up = cross(rx, fwd) // screen up
        // Fit the projected bounds.
        var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE; var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
        for (m in meshes) {
            val v = m.vertices
            var i = 0
            while (i < v.size) {
                val pu = v[i] * rx[0] + v[i + 1] * rx[1] + v[i + 2] * rx[2]
                val pv = v[i] * up[0] + v[i + 1] * up[1] + v[i + 2] * up[2]
                if (pu < minU) minU = pu; if (pu > maxU) maxU = pu
                if (pv < minV) minV = pv; if (pv > maxV) maxV = pv
                i += 3
            }
        }
        val color = FloatArray(w * h * 4)
        if (minU > maxU) return downsample(color, w, h, ss)
        val margin = 0.06
        val scale = min(w * (1 - 2 * margin) / max(maxU - minU, 1e-6), h * (1 - 2 * margin) / max(maxV - minV, 1e-6))
        val offU = (w - (maxU - minU) * scale) / 2 - minU * scale
        val offV = (h - (maxV - minV) * scale) / 2 - minV * scale
        val depth = FloatArray(w * h) { Float.MAX_VALUE }
        val br = ((rgb shr 16) and 0xff) / 255f; val bg = ((rgb shr 8) and 0xff) / 255f; val bb = (rgb and 0xff) / 255f
        // Light from the upper left, slightly towards the viewer.
        val light = normalize(doubleArrayOf(-0.45 * rx[0] + 0.7 * up[0] - 0.55 * fwd[0], -0.45 * rx[1] + 0.7 * up[1] - 0.55 * fwd[1], -0.45 * rx[2] + 0.7 * up[2] - 0.55 * fwd[2]))
        val sx = DoubleArray(3); val sy = DoubleArray(3); val sz = DoubleArray(3)
        for (m in meshes) {
            val v = m.vertices; val idx = m.indices
            for (t in 0 until m.triangleCount) {
                for (k in 0 until 3) {
                    val o = idx[t * 3 + k] * 3
                    val x = v[o].toDouble(); val y = v[o + 1].toDouble(); val z = v[o + 2].toDouble()
                    sx[k] = (x * rx[0] + y * rx[1] + z * rx[2]) * scale + offU
                    sy[k] = h - ((x * up[0] + y * up[1] + z * up[2]) * scale + offV)
                    sz[k] = x * fwd[0] + y * fwd[1] + z * fwd[2]
                }
                val n = m.faceNormal(t)
                val len = n.length()
                if (len < 1e-12) continue
                var d = (n.x * light[0] + n.y * light[1] + n.z * light[2]) / len
                if ((n.x * fwd[0] + n.y * fwd[1] + n.z * fwd[2]) > 0) d = -d // back face seen from inside
                val shade = (0.35 + 0.65 * max(0.0, d)).toFloat()
                fill(sx, sy, sz, w, h, depth, color, br * shade, bg * shade, bb * shade)
            }
        }
        return downsample(color, w, h, ss)
    }

    private fun fill(
        sx: DoubleArray, sy: DoubleArray, sz: DoubleArray, w: Int, h: Int,
        depth: FloatArray, color: FloatArray, r: Float, g: Float, b: Float,
    ) {
        val area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0])
        if (kotlin.math.abs(area) < 1e-12) return
        val x0 = max(0, kotlin.math.floor(min(sx[0], min(sx[1], sx[2]))).toInt())
        val x1 = min(w - 1, kotlin.math.ceil(max(sx[0], max(sx[1], sx[2]))).toInt())
        val y0 = max(0, kotlin.math.floor(min(sy[0], min(sy[1], sy[2]))).toInt())
        val y1 = min(h - 1, kotlin.math.ceil(max(sy[0], max(sy[1], sy[2]))).toInt())
        for (py in y0..y1) {
            val cy = py + 0.5
            for (px in x0..x1) {
                val cx = px + 0.5
                val w0 = ((sx[1] - cx) * (sy[2] - cy) - (sx[2] - cx) * (sy[1] - cy)) / area
                val w1 = ((sx[2] - cx) * (sy[0] - cy) - (sx[0] - cx) * (sy[2] - cy)) / area
                val w2 = 1 - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val z = (w0 * sz[0] + w1 * sz[1] + w2 * sz[2]).toFloat()
                val i = py * w + px
                if (z >= depth[i]) continue
                depth[i] = z
                color[i * 4] = r; color[i * 4 + 1] = g; color[i * 4 + 2] = b; color[i * 4 + 3] = 1f
            }
        }
    }

    private fun downsample(c: FloatArray, w: Int, h: Int, ss: Int): ByteArray {
        val ow = w / ss; val oh = h / ss
        val out = ByteArray(ow * oh * 4)
        val n = (ss * ss).toFloat()
        for (y in 0 until oh) for (x in 0 until ow) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (dy in 0 until ss) for (dx in 0 until ss) {
                val i = ((y * ss + dy) * w + x * ss + dx) * 4
                val al = c[i + 3]
                r += c[i] * al; g += c[i + 1] * al; b += c[i + 2] * al; a += al
            }
            val o = (y * ow + x) * 4
            if (a > 0) {
                out[o] = (r / a * 255 + 0.5f).toInt().coerceIn(0, 255).toByte()
                out[o + 1] = (g / a * 255 + 0.5f).toInt().coerceIn(0, 255).toByte()
                out[o + 2] = (b / a * 255 + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
            out[o + 3] = (a / n * 255 + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /** Minimal RGBA8 PNG encoder. */
    fun encodePng(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val raw = ByteArray(height * (width * 4 + 1))
        for (y in 0 until height) {
            raw[y * (width * 4 + 1)] = 0 // filter: none
            System.arraycopy(rgba, y * width * 4, raw, y * (width * 4 + 1) + 1, width * 4)
        }
        val deflated = ByteArrayOutputStream()
        DeflaterOutputStream(deflated, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(raw) }
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply { writeInt(width); writeInt(height); writeByte(8); writeByte(6); writeByte(0); writeByte(0); writeByte(0) }
        chunk(data, "IHDR", ihdr.toByteArray())
        chunk(data, "IDAT", deflated.toByteArray())
        chunk(data, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: DataOutputStream, type: String, body: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(body.size)
        out.write(t); out.write(body)
        val crc = CRC32()
        crc.update(t); crc.update(body)
        out.writeInt(crc.value.toInt())
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun normalize(a: DoubleArray): DoubleArray {
        val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        return doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
    }
}
