package com.orcaphone.core.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Column-major 4x4 matrix, same memory layout as OpenGL / android.opengl.Matrix. */
class Mat4(val m: DoubleArray = identityArray()) {

    operator fun get(row: Int, col: Int): Double = m[col * 4 + row]

    operator fun times(o: Mat4): Mat4 {
        val r = DoubleArray(16)
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += m[k * 4 + row] * o.m[c * 4 + k]
                r[c * 4 + row] = s
            }
        }
        return Mat4(r)
    }

    fun transformPoint(x: Double, y: Double, z: Double, out: DoubleArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }

    fun transformPoint(p: Vec3): Vec3 = Vec3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14],
    )

    fun transformDir(p: Vec3): Vec3 = Vec3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z,
        m[1] * p.x + m[5] * p.y + m[9] * p.z,
        m[2] * p.x + m[6] * p.y + m[10] * p.z,
    )

    fun determinant3(): Double =
        m[0] * (m[5] * m[10] - m[9] * m[6]) -
            m[4] * (m[1] * m[10] - m[9] * m[2]) +
            m[8] * (m[1] * m[6] - m[5] * m[2])

    /** Inverse of an affine matrix (3x3 linear part + translation). */
    fun inverseAffine(): Mat4 {
        val a = m
        val det = determinant3()
        require(abs(det) > 1e-18) { "Singular matrix" }
        val inv = 1.0 / det
        val r = DoubleArray(16)
        r[0] = (a[5] * a[10] - a[9] * a[6]) * inv
        r[4] = -(a[4] * a[10] - a[8] * a[6]) * inv
        r[8] = (a[4] * a[9] - a[8] * a[5]) * inv
        r[1] = -(a[1] * a[10] - a[9] * a[2]) * inv
        r[5] = (a[0] * a[10] - a[8] * a[2]) * inv
        r[9] = -(a[0] * a[9] - a[8] * a[1]) * inv
        r[2] = (a[1] * a[6] - a[5] * a[2]) * inv
        r[6] = -(a[0] * a[6] - a[4] * a[2]) * inv
        r[10] = (a[0] * a[5] - a[4] * a[1]) * inv
        val tx = a[12]
        val ty = a[13]
        val tz = a[14]
        r[12] = -(r[0] * tx + r[4] * ty + r[8] * tz)
        r[13] = -(r[1] * tx + r[5] * ty + r[9] * tz)
        r[14] = -(r[2] * tx + r[6] * ty + r[10] * tz)
        r[15] = 1.0
        return Mat4(r)
    }

    fun toFloatArray(): FloatArray = FloatArray(16) { m[it].toFloat() }

    companion object {
        fun identityArray(): DoubleArray = DoubleArray(16).also {
            it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0
        }

        fun identity() = Mat4()

        fun translation(x: Double, y: Double, z: Double) = Mat4().also {
            it.m[12] = x; it.m[13] = y; it.m[14] = z
        }

        fun scale(x: Double, y: Double, z: Double) = Mat4().also {
            it.m[0] = x; it.m[5] = y; it.m[10] = z
        }

        fun rotationX(rad: Double) = rotationAxisAngle(Vec3(1.0, 0.0, 0.0), rad)
        fun rotationY(rad: Double) = rotationAxisAngle(Vec3(0.0, 1.0, 0.0), rad)
        fun rotationZ(rad: Double) = rotationAxisAngle(Vec3(0.0, 0.0, 1.0), rad)

        fun rotationAxisAngle(axis: Vec3, rad: Double): Mat4 {
            val a = axis.normalized()
            val c = cos(rad)
            val s = sin(rad)
            val t = 1 - c
            val r = Mat4()
            r.m[0] = t * a.x * a.x + c
            r.m[1] = t * a.x * a.y + s * a.z
            r.m[2] = t * a.x * a.z - s * a.y
            r.m[4] = t * a.x * a.y - s * a.z
            r.m[5] = t * a.y * a.y + c
            r.m[6] = t * a.y * a.z + s * a.x
            r.m[8] = t * a.x * a.z + s * a.y
            r.m[9] = t * a.y * a.z - s * a.x
            r.m[10] = t * a.z * a.z + c
            // Snap values that are numerically zero/one so 90° rotations stay exact.
            for (i in 0 until 11) {
                if (abs(r.m[i]) < 1e-12) r.m[i] = 0.0
            }
            return r
        }

        /** Rotation that maps direction [from] onto direction [to]. */
        fun rotationBetween(from: Vec3, to: Vec3): Mat4 {
            val f = from.normalized()
            val t = to.normalized()
            val d = (f dot t).coerceIn(-1.0, 1.0)
            if (d > 1 - 1e-12) return identity()
            if (d < -1 + 1e-12) {
                // 180°: pick any perpendicular axis
                var axis = f cross Vec3(1.0, 0.0, 0.0)
                if (axis.length() < 1e-6) axis = f cross Vec3(0.0, 1.0, 0.0)
                return rotationAxisAngle(axis, Math.PI)
            }
            return rotationAxisAngle(f cross t, acos(d))
        }
    }
}
