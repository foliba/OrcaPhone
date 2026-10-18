package com.orcaphone.core.mesh

import com.orcaphone.core.math.Mat4
import com.orcaphone.core.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Indexed triangle mesh in millimetres. */
class Mesh(val vertices: FloatArray, val indices: IntArray) {
    val vertexCount: Int get() = vertices.size / 3
    val triangleCount: Int get() = indices.size / 3

    fun bounds(): Bounds3 {
        if (vertices.isEmpty()) return Bounds3.EMPTY
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        var i = 0
        while (i < vertices.size) {
            val x = vertices[i].toDouble(); val y = vertices[i + 1].toDouble(); val z = vertices[i + 2].toDouble()
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 3
        }
        return Bounds3(minX, minY, minZ, maxX, maxY, maxZ)
    }

    fun bounds(m: Mat4): Bounds3 {
        if (vertices.isEmpty()) return Bounds3.EMPTY
        val p = DoubleArray(3)
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        var i = 0
        while (i < vertices.size) {
            m.transformPoint(vertices[i].toDouble(), vertices[i + 1].toDouble(), vertices[i + 2].toDouble(), p)
            if (p[0] < minX) minX = p[0]; if (p[0] > maxX) maxX = p[0]
            if (p[1] < minY) minY = p[1]; if (p[1] > maxY) maxY = p[1]
            if (p[2] < minZ) minZ = p[2]; if (p[2] > maxZ) maxZ = p[2]
            i += 3
        }
        return Bounds3(minX, minY, minZ, maxX, maxY, maxZ)
    }

    fun transformed(m: Mat4): Mesh {
        val out = FloatArray(vertices.size)
        val p = DoubleArray(3)
        var i = 0
        while (i < vertices.size) {
            m.transformPoint(vertices[i].toDouble(), vertices[i + 1].toDouble(), vertices[i + 2].toDouble(), p)
            out[i] = p[0].toFloat(); out[i + 1] = p[1].toFloat(); out[i + 2] = p[2].toFloat()
            i += 3
        }
        val idx = if (m.determinant3() < 0) {
            IntArray(indices.size).also {
                var t = 0
                while (t < indices.size) {
                    it[t] = indices[t]; it[t + 1] = indices[t + 2]; it[t + 2] = indices[t + 1]
                    t += 3
                }
            }
        } else indices
        return Mesh(out, idx)
    }

    /** Unnormalised face normal (length = 2 * area). */
    fun faceNormal(t: Int): Vec3 {
        val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
        val ux = (vertices[b] - vertices[a]).toDouble()
        val uy = (vertices[b + 1] - vertices[a + 1]).toDouble()
        val uz = (vertices[b + 2] - vertices[a + 2]).toDouble()
        val vx = (vertices[c] - vertices[a]).toDouble()
        val vy = (vertices[c + 1] - vertices[a + 1]).toDouble()
        val vz = (vertices[c + 2] - vertices[a + 2]).toDouble()
        return Vec3(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    }

    fun volume(): Double {
        var v = 0.0
        for (t in 0 until triangleCount) {
            val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
            val ax = vertices[a].toDouble(); val ay = vertices[a + 1].toDouble(); val az = vertices[a + 2].toDouble()
            val bx = vertices[b].toDouble(); val by = vertices[b + 1].toDouble(); val bz = vertices[b + 2].toDouble()
            val cx = vertices[c].toDouble(); val cy = vertices[c + 1].toDouble(); val cz = vertices[c + 2].toDouble()
            v += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)
        }
        return abs(v / 6.0)
    }

    /**
     * Finds the downward rotation that lays the largest flat face group on the bed.
     * Faces are grouped by (quantised) normal direction and their areas summed.
     */
    fun bestLayFlatNormal(current: Mat4): Vec3? {
        val groups = HashMap<Long, DoubleArray>() // key -> [area, nx, ny, nz]
        for (t in 0 until triangleCount) {
            val n = current.transformDir(faceNormal(t))
            val len = n.length()
            if (len < 1e-9) continue
            val nx = n.x / len; val ny = n.y / len; val nz = n.z / len
            val key = (((nx * 50).roundToLong() + 64) shl 16) or
                (((ny * 50).roundToLong() + 64) shl 8) or ((nz * 50).roundToLong() + 64)
            val g = groups.getOrPut(key) { DoubleArray(4) }
            val area = len / 2
            g[0] += area; g[1] += nx * area; g[2] += ny * area; g[3] += nz * area
        }
        val best = groups.values.maxByOrNull { it[0] } ?: return null
        return Vec3(best[1], best[2], best[3]).normalized()
    }

    companion object {
        /** Builds an indexed mesh from a flat triangle soup (9 floats per triangle), welding coincident vertices. */
        fun fromTriangleSoup(soup: FloatArray): Mesh {
            var maxAbs = 1.0
            for (f in soup) { val a = abs(f.toDouble()); if (a > maxAbs) maxAbs = a }
            // 21 bits per axis (signed range +-2^20 quanta).
            val tol = max(1e-4, maxAbs / (1 shl 20).toDouble() * 1.01)
            val inv = 1.0 / tol
            val map = LongIntHashMap(soup.size / 3)
            val verts = FloatArrayBuilder(soup.size / 2)
            val idx = IntArrayBuilder(soup.size / 3)
            val tri = IntArray(3)
            var i = 0
            while (i + 8 < soup.size) {
                for (k in 0 until 3) {
                    val x = soup[i + k * 3]; val y = soup[i + k * 3 + 1]; val z = soup[i + k * 3 + 2]
                    val qx = (x * inv).roundToLong() + (1L shl 20)
                    val qy = (y * inv).roundToLong() + (1L shl 20)
                    val qz = (z * inv).roundToLong() + (1L shl 20)
                    val key = (qx shl 42) or (qy shl 21) or qz
                    var id = map.get(key)
                    if (id < 0) {
                        id = verts.size / 3
                        verts.add(x); verts.add(y); verts.add(z)
                        map.put(key, id)
                    }
                    tri[k] = id
                }
                if (tri[0] != tri[1] && tri[1] != tri[2] && tri[0] != tri[2]) {
                    idx.add(tri[0]); idx.add(tri[1]); idx.add(tri[2])
                }
                i += 9
            }
            return Mesh(verts.toArray(), idx.toArray())
        }

        fun merge(meshes: List<Mesh>): Mesh {
            val vCount = meshes.sumOf { it.vertices.size }
            val iCount = meshes.sumOf { it.indices.size }
            val v = FloatArray(vCount)
            val idx = IntArray(iCount)
            var vo = 0; var io = 0
            for (m in meshes) {
                System.arraycopy(m.vertices, 0, v, vo, m.vertices.size)
                val base = vo / 3
                for (k in m.indices.indices) idx[io + k] = m.indices[k] + base
                vo += m.vertices.size; io += m.indices.size
            }
            return Mesh(v, idx)
        }
    }
}

class FloatArrayBuilder(initial: Int = 1024) {
    private var data = FloatArray(max(16, initial))
    var size = 0
        private set

    fun add(f: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = f
    }

    fun toArray(): FloatArray = data.copyOf(size)

    /** Backing array (valid up to [size]); invalidated by further adds. */
    fun raw(): FloatArray = data
}

class IntArrayBuilder(initial: Int = 1024) {
    private var data = IntArray(max(16, initial))
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
    fun toArray(): IntArray = data.copyOf(size)
}

class DoubleArrayBuilder(initial: Int = 64) {
    private var data = DoubleArray(max(8, initial))
    var size = 0
        private set

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun add(x: Double, y: Double) { add(x); add(y) }
    operator fun get(i: Int) = data[i]
    fun clear() { size = 0 }
    fun toArray(): DoubleArray = data.copyOf(size)
}

internal fun sqr(x: Double) = x * x
internal fun hypot2(x: Double, y: Double) = sqrt(x * x + y * y)
