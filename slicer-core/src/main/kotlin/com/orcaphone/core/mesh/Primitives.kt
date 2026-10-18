package com.orcaphone.core.mesh

import kotlin.math.cos
import kotlin.math.sin

/** Simple built-in shapes so the app can be tried without importing a file. */
object Primitives {

    fun box(sx: Double, sy: Double, sz: Double): Mesh {
        val x = (sx / 2).toFloat(); val y = (sy / 2).toFloat(); val z = sz.toFloat()
        val v = floatArrayOf(
            -x, -y, 0f, x, -y, 0f, x, y, 0f, -x, y, 0f,
            -x, -y, z, x, -y, z, x, y, z, -x, y, z,
        )
        val i = intArrayOf(
            0, 2, 1, 0, 3, 2, // bottom
            4, 5, 6, 4, 6, 7, // top
            0, 1, 5, 0, 5, 4, // front
            1, 2, 6, 1, 6, 5, // right
            2, 3, 7, 2, 7, 6, // back
            3, 0, 4, 3, 4, 7, // left
        )
        return Mesh(v, i)
    }

    fun cylinder(radius: Double, height: Double, segments: Int = 96): Mesh {
        val v = FloatArrayBuilder()
        val idx = IntArrayBuilder()
        v.add(0f); v.add(0f); v.add(0f)
        v.add(0f); v.add(0f); v.add(height.toFloat())
        for (s in 0 until segments) {
            val a = 2 * Math.PI * s / segments
            val x = (radius * cos(a)).toFloat(); val y = (radius * sin(a)).toFloat()
            v.add(x); v.add(y); v.add(0f)
            v.add(x); v.add(y); v.add(height.toFloat())
        }
        for (s in 0 until segments) {
            val b0 = 2 + s * 2; val t0 = b0 + 1
            val b1 = 2 + ((s + 1) % segments) * 2; val t1 = b1 + 1
            idx.add(0); idx.add(b1); idx.add(b0)
            idx.add(1); idx.add(t0); idx.add(t1)
            idx.add(b0); idx.add(b1); idx.add(t1)
            idx.add(b0); idx.add(t1); idx.add(t0)
        }
        return Mesh(v.toArray(), idx.toArray())
    }

    fun sphere(radius: Double, rings: Int = 48, segments: Int = 96): Mesh {
        val v = FloatArrayBuilder()
        val idx = IntArrayBuilder()
        for (r in 0..rings) {
            val phi = Math.PI * r / rings
            for (s in 0 until segments) {
                val th = 2 * Math.PI * s / segments
                v.add((radius * sin(phi) * cos(th)).toFloat())
                v.add((radius * sin(phi) * sin(th)).toFloat())
                v.add((radius - radius * cos(phi)).toFloat())
            }
        }
        for (r in 0 until rings) {
            for (s in 0 until segments) {
                val a = r * segments + s
                val b = r * segments + (s + 1) % segments
                val c = (r + 1) * segments + s
                val d = (r + 1) * segments + (s + 1) % segments
                if (r != 0) { idx.add(a); idx.add(b); idx.add(c) }
                if (r != rings - 1) { idx.add(b); idx.add(d); idx.add(c) }
            }
        }
        // Weld the degenerate pole vertices.
        val soup = FloatArray(idx.size * 3)
        val vv = v.toArray()
        for (k in 0 until idx.size) {
            soup[k * 3] = vv[idx[k] * 3]; soup[k * 3 + 1] = vv[idx[k] * 3 + 1]; soup[k * 3 + 2] = vv[idx[k] * 3 + 2]
        }
        return Mesh.fromTriangleSoup(soup)
    }
}
