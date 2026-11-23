package io.github.foliba.phorca.core.mesh

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the compact bed models (.pbed) made by tools/gen_bed_assets.py from OrcaSlicer's bed STLs:
 * shared vertices on a grid of [step] mm as 16-bit integers, triangles as 16- or 32-bit indices.
 */
object BedModelCodec {
    fun decode(bytes: ByteArray): Mesh {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 30 || b.get(0) != 'P'.code.toByte() || b.get(1) != 'B'.code.toByte() ||
            b.get(2) != 'E'.code.toByte() || b.get(3) != 'D'.code.toByte()
        ) throw IOException("Not a bed model")
        b.position(4)
        val version = b.get().toInt()
        if (version != 1) throw IOException("Unsupported bed model version $version")
        val wide = (b.get().toInt() and 1) != 0
        val ox = b.getFloat(); val oy = b.getFloat(); val oz = b.getFloat(); val step = b.getFloat()
        val nv = b.getInt(); val nt = b.getInt()
        val need = 30L + nv * 6L + nt * 3L * (if (wide) 4 else 2)
        if (nv < 0 || nt < 0 || need > bytes.size) throw IOException("Truncated bed model")
        val v = FloatArray(nv * 3)
        for (i in 0 until nv) {
            v[i * 3] = ox + (b.getShort().toInt() and 0xFFFF) * step
            v[i * 3 + 1] = oy + (b.getShort().toInt() and 0xFFFF) * step
            v[i * 3 + 2] = oz + (b.getShort().toInt() and 0xFFFF) * step
        }
        val idx = IntArray(nt * 3)
        for (i in idx.indices) {
            val k = if (wide) b.getInt() else b.getShort().toInt() and 0xFFFF
            if (k !in 0 until nv) throw IOException("Bad bed model index")
            idx[i] = k
        }
        return Mesh(v, idx)
    }
}
