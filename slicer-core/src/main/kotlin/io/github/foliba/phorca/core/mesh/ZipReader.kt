package io.github.foliba.phorca.core.mesh

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Reads a ZIP archive held in memory through its central directory.
 *
 * `ZipInputStream` reads entry by entry and cannot handle STORED entries whose sizes follow the data in a
 * data descriptor, which Bambu Studio writes for the PNG thumbnails in its 3MF files (Android's version
 * silently stops there, so the model files are never reached). The central directory lists every entry's
 * real sizes up front, as regular unzip tools use it.
 */
internal object ZipReader {
    /** Calls [onEntry] with each file whose name passes [wanted], with its uncompressed bytes. */
    fun read(bytes: ByteArray, wanted: (String) -> Boolean, onEntry: (String, ByteArray) -> Unit) {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = findEndOfCentralDirectory(bb)
        if (eocd < 0) { readStreaming(bytes, wanted, onEntry); return }
        val count = bb.getShort(eocd + 10).toInt() and 0xFFFF
        var p = (bb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL).toInt()
        repeat(count) {
            if (p + 46 > bytes.size || bb.getInt(p) != 0x02014b50) throw IOException("Damaged ZIP directory")
            val method = bb.getShort(p + 10).toInt() and 0xFFFF
            val compSize = (bb.getInt(p + 20).toLong() and 0xFFFFFFFFL).toInt()
            val size = (bb.getInt(p + 24).toLong() and 0xFFFFFFFFL).toInt()
            val nameLen = bb.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = bb.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = bb.getShort(p + 32).toInt() and 0xFFFF
            val local = (bb.getInt(p + 42).toLong() and 0xFFFFFFFFL).toInt()
            val name = String(bytes, p + 46, nameLen, Charsets.UTF_8)
            p += 46 + nameLen + extraLen + commentLen
            if (name.endsWith("/") || !wanted(name)) return@repeat
            if (local + 30 > bytes.size || bb.getInt(local) != 0x04034b50) throw IOException("Damaged ZIP entry $name")
            val start = local + 30 + (bb.getShort(local + 26).toInt() and 0xFFFF) + (bb.getShort(local + 28).toInt() and 0xFFFF)
            if (start + compSize > bytes.size) throw IOException("Truncated ZIP entry $name")
            val data = when (method) {
                0 -> bytes.copyOfRange(start, start + compSize)
                8 -> inflate(bytes, start, compSize, size)
                else -> throw IOException("Unsupported compression in $name")
            }
            onEntry(name, data)
        }
    }

    private fun findEndOfCentralDirectory(bb: ByteBuffer): Int {
        val n = bb.capacity()
        var i = n - 22
        val stop = maxOf(0, n - 22 - 65535)
        while (i >= stop) {
            if (bb.getInt(i) == 0x06054b50) return i
            i--
        }
        return -1
    }

    private fun inflate(src: ByteArray, off: Int, len: Int, size: Int): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(src, off, len)
            val out = ByteArray(size)
            var n = 0
            while (n < size) {
                val r = inf.inflate(out, n, size - n)
                if (r == 0 && (inf.finished() || inf.needsInput())) break
                n += r
            }
            if (n != size) throw IOException("Damaged compressed ZIP entry")
            return out
        } finally {
            inf.end()
        }
    }

    /** Fallback for archives without a readable central directory. */
    private fun readStreaming(bytes: ByteArray, wanted: (String) -> Boolean, onEntry: (String, ByteArray) -> Unit) {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && wanted(e.name)) onEntry(e.name, zip.readBytes())
            }
        }
    }
}
