package com.orcaphone.core.mesh

import com.orcaphone.core.math.Mat4
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class LoadedModel(val name: String, val mesh: Mesh)

/** Loads STL (binary/ASCII), OBJ and 3MF files into meshes. */
object MeshLoader {

    fun load(fileName: String, input: InputStream): List<LoadedModel> {
        val lower = fileName.lowercase()
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Model" }
        val bytes = input.readBytes()
        val models = when {
            lower.endsWith(".3mf") || isZip(bytes) -> load3mf(bytes, base)
            lower.endsWith(".obj") -> listOf(LoadedModel(base, loadObj(bytes)))
            else -> listOf(LoadedModel(base, loadStl(bytes)))
        }
        val valid = models.filter { it.mesh.triangleCount > 0 }
        if (valid.isEmpty()) throw IOException("No printable geometry found in $fileName")
        return valid
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    // ---------------------------------------------------------------- STL

    fun loadStl(bytes: ByteArray): Mesh {
        if (bytes.size >= 84) {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.getInt(80).toLong() and 0xffffffffL
            if (84 + n * 50 == bytes.size.toLong()) return parseBinaryStl(bb, n.toInt())
        }
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.US_ASCII).trimStart()
        if (head.startsWith("solid") && head.contains("facet")) return parseAsciiStl(bytes)
        if (bytes.size >= 84) {
            // Binary STL with a wrong triangle count in the header: trust the file length.
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return parseBinaryStl(bb, (bytes.size - 84) / 50)
        }
        throw IOException("Unrecognised STL file")
    }

    private fun parseBinaryStl(bb: ByteBuffer, n: Int): Mesh {
        val soup = FloatArray(n * 9)
        var o = 0
        for (t in 0 until n) {
            val base = 84 + t * 50 + 12
            for (k in 0 until 9) soup[o++] = bb.getFloat(base + k * 4)
        }
        return Mesh.fromTriangleSoup(soup)
    }

    private fun parseAsciiStl(bytes: ByteArray): Mesh {
        val soup = FloatArrayBuilder(bytes.size / 20)
        val tok = Tokenizer(bytes)
        while (true) {
            val t = tok.next() ?: break
            if (t == "vertex") {
                for (k in 0 until 3) soup.add((tok.next() ?: "0").toFloat())
            }
        }
        val arr = soup.toArray()
        return Mesh.fromTriangleSoup(arr.copyOf(arr.size - arr.size % 9))
    }

    private class Tokenizer(val b: ByteArray) {
        var pos = 0
        fun next(): String? {
            while (pos < b.size && b[pos] <= 32) pos++
            if (pos >= b.size) return null
            val start = pos
            while (pos < b.size && b[pos] > 32) pos++
            return String(b, start, pos - start, Charsets.US_ASCII)
        }
    }

    // ---------------------------------------------------------------- OBJ

    fun loadObj(bytes: ByteArray): Mesh {
        val verts = FloatArrayBuilder()
        val soup = FloatArrayBuilder()
        val reader = BufferedReader(InputStreamReader(bytes.inputStream(), Charsets.UTF_8))
        val face = ArrayList<Int>(8)
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.startsWith("v ")) {
                val p = line.split(WS)
                if (p.size >= 4) {
                    verts.add(p[1].toFloat()); verts.add(p[2].toFloat()); verts.add(p[3].toFloat())
                }
            } else if (line.startsWith("f ")) {
                val p = line.split(WS)
                face.clear()
                val vc = verts.size / 3
                for (i in 1 until p.size) {
                    val s = p[i].substringBefore('/')
                    if (s.isEmpty()) continue
                    var idx = s.toInt()
                    idx = if (idx < 0) vc + idx else idx - 1
                    if (idx in 0 until vc) face.add(idx)
                }
                val arr = verts.raw()
                for (i in 1 until face.size - 1) {
                    for (v in intArrayOf(face[0], face[i], face[i + 1])) {
                        soup.add(arr[v * 3]); soup.add(arr[v * 3 + 1]); soup.add(arr[v * 3 + 2])
                    }
                }
            }
        }
        return Mesh.fromTriangleSoup(soup.toArray())
    }

    private val WS = Regex("\\s+")

    // ---------------------------------------------------------------- 3MF

    private class Component(val path: String?, val objectId: String, val transform: Mat4)

    private class ObjectDef(val id: String, val name: String?) {
        val verts = FloatArrayBuilder(64)
        val tris = IntArrayBuilder(64)
        val components = ArrayList<Component>()
    }

    private class ModelFile {
        val objects = HashMap<String, ObjectDef>()
        val items = ArrayList<Component>()
        var unitScale = 1.0
    }

    fun load3mf(bytes: ByteArray, baseName: String): List<LoadedModel> {
        val files = HashMap<String, ModelFile>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && e.name.lowercase().endsWith(".model")) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    files["/" + e.name.trimStart('/')] = parseModel(text)
                }
            }
        }
        val rootKey = files.keys.firstOrNull { it.equals("/3D/3dmodel.model", ignoreCase = true) }
            ?: files.keys.firstOrNull { files[it]!!.items.isNotEmpty() }
            ?: throw IOException("3MF has no model")
        val root = files[rootKey]!!
        val result = ArrayList<LoadedModel>()
        val items = root.items.ifEmpty {
            root.objects.values.filter { it.tris.size > 0 || it.components.isNotEmpty() }
                .map { Component(null, it.id, Mat4.identity()) }
        }
        val unit = Mat4.scale(root.unitScale, root.unitScale, root.unitScale)
        for ((n, item) in items.withIndex()) {
            val soup = FloatArrayBuilder(1024)
            appendObject(files, rootKey, item.objectId, unit * item.transform, soup, 0)
            if (soup.size == 0) continue
            val name = root.objects[item.objectId]?.name?.takeIf { it.isNotBlank() }
                ?: if (items.size > 1) "$baseName ${n + 1}" else baseName
            result.add(LoadedModel(name, Mesh.fromTriangleSoup(soup.toArray())))
        }
        return result
    }

    private fun appendObject(
        files: Map<String, ModelFile>, path: String, id: String, t: Mat4,
        soup: FloatArrayBuilder, depth: Int,
    ) {
        if (depth > 16) return
        val file = files[path] ?: return
        val obj = file.objects[id] ?: return
        if (obj.tris.size > 0) {
            val v = obj.verts.raw()
            val p = DoubleArray(3)
            for (i in 0 until obj.tris.size) {
                val vi = obj.tris[i] * 3
                if (vi + 2 >= obj.verts.size) { soup.add(0f); soup.add(0f); soup.add(0f); continue }
                t.transformPoint(v[vi].toDouble(), v[vi + 1].toDouble(), v[vi + 2].toDouble(), p)
                soup.add(p[0].toFloat()); soup.add(p[1].toFloat()); soup.add(p[2].toFloat())
            }
        }
        for (c in obj.components) {
            val cp = c.path?.let { "/" + it.trimStart('/') } ?: path
            appendObject(files, cp, c.objectId, t * c.transform, soup, depth + 1)
        }
    }

    private fun parseModel(text: String): ModelFile {
        val mf = ModelFile()
        var current: ObjectDef? = null
        var i = 0
        val n = text.length
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            val gt = text.indexOf('>', lt)
            if (gt < 0) break
            i = gt + 1
            if (lt + 1 >= n) break
            val c = text[lt + 1]
            if (c == '?' || c == '!') continue
            if (c == '/') {
                if (text.startsWith("object", lt + 2)) current = null
                continue
            }
            val nameEnd = findNameEnd(text, lt + 1, gt)
            val tag = text.substring(lt + 1, nameEnd).substringAfter(':')
            when (tag) {
                "vertex" -> current?.let { o ->
                    o.verts.add(attrFloat(text, nameEnd, gt, "x"))
                    o.verts.add(attrFloat(text, nameEnd, gt, "y"))
                    o.verts.add(attrFloat(text, nameEnd, gt, "z"))
                }
                "triangle" -> current?.let { o ->
                    o.tris.add(attrInt(text, nameEnd, gt, "v1"))
                    o.tris.add(attrInt(text, nameEnd, gt, "v2"))
                    o.tris.add(attrInt(text, nameEnd, gt, "v3"))
                }
                "object" -> {
                    val id = attr(text, nameEnd, gt, "id") ?: continue
                    val o = ObjectDef(id, attr(text, nameEnd, gt, "name"))
                    mf.objects[id] = o
                    current = if (text[gt - 1] == '/') null else o
                }
                "component" -> current?.let { o ->
                    val oid = attr(text, nameEnd, gt, "objectid") ?: return@let
                    o.components.add(Component(attr(text, nameEnd, gt, "path"), oid, parseTransform(attr(text, nameEnd, gt, "transform"))))
                }
                "item" -> {
                    val oid = attr(text, nameEnd, gt, "objectid")
                    val printable = attr(text, nameEnd, gt, "printable")
                    if (oid != null && printable != "0") {
                        mf.items.add(Component(attr(text, nameEnd, gt, "path"), oid, parseTransform(attr(text, nameEnd, gt, "transform"))))
                    }
                }
                "model" -> {
                    mf.unitScale = when (attr(text, nameEnd, gt, "unit")) {
                        "micron" -> 0.001
                        "centimeter" -> 10.0
                        "inch" -> 25.4
                        "foot" -> 304.8
                        "meter" -> 1000.0
                        else -> 1.0
                    }
                }
            }
        }
        return mf
    }

    private fun findNameEnd(s: String, from: Int, to: Int): Int {
        var i = from
        while (i < to && !s[i].isWhitespace() && s[i] != '/' && s[i] != '>') i++
        return i
    }

    /** Finds attribute [name] (optionally namespace-prefixed) inside s[from, to). */
    private fun attr(s: String, from: Int, to: Int, name: String): String? {
        var i = from
        while (i < to) {
            while (i < to && (s[i].isWhitespace() || s[i] == '/')) i++
            val ks = i
            while (i < to && s[i] != '=' && !s[i].isWhitespace()) i++
            val key = s.substring(ks, i)
            while (i < to && s[i] != '"' && s[i] != '\'') i++
            if (i >= to) return null
            val q = s[i]
            val vs = i + 1
            val ve = s.indexOf(q, vs)
            if (ve < 0 || ve > to) return null
            if (key == name || key.substringAfter(':') == name) return s.substring(vs, ve)
            i = ve + 1
        }
        return null
    }

    private fun attrFloat(s: String, from: Int, to: Int, name: String): Float =
        attr(s, from, to, name)?.toFloatOrNull() ?: 0f

    private fun attrInt(s: String, from: Int, to: Int, name: String): Int =
        attr(s, from, to, name)?.toIntOrNull() ?: 0

    /** 3MF uses row-vector 4x3 matrices: "m00 m01 m02 m10 m11 m12 m20 m21 m22 m30 m31 m32". */
    private fun parseTransform(s: String?): Mat4 {
        if (s.isNullOrBlank()) return Mat4.identity()
        val v = s.trim().split(WS).mapNotNull { it.toDoubleOrNull() }
        if (v.size != 12) return Mat4.identity()
        val m = Mat4()
        // column-major: m[col*4+row]; x' = x*m00 + y*m10 + z*m20 + m30
        m.m[0] = v[0]; m.m[4] = v[3]; m.m[8] = v[6]; m.m[12] = v[9]
        m.m[1] = v[1]; m.m[5] = v[4]; m.m[9] = v[7]; m.m[13] = v[10]
        m.m[2] = v[2]; m.m[6] = v[5]; m.m[10] = v[8]; m.m[14] = v[11]
        return m
    }
}

