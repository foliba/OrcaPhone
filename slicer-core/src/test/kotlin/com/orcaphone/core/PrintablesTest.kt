package com.orcaphone.core

import com.orcaphone.core.mesh.MeshLoader
import com.orcaphone.core.models.HttpPost
import com.orcaphone.core.models.HttpResponse
import com.orcaphone.core.models.ModelSourceException
import com.orcaphone.core.models.ModelSources
import com.orcaphone.core.models.PrintablesSource
import com.orcaphone.core.models.RemoteFileKind
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintablesTest {
    private fun fake(vararg bodies: String): HttpPost {
        var i = 0
        return HttpPost { _, _, _ -> HttpResponse(200, bodies[minOf(i++, bodies.size - 1)]) }
    }

    @Test
    fun parsesLinks() {
        val s = PrintablesSource(fake("{}"))
        assertEquals("3161", s.parseLink("https://www.printables.com/model/3161-3d-benchy"))
        assertEquals("3161", s.parseLink("printables.com/de/model/3161-3d-benchy/files"))
        assertEquals("1400123", s.parseLink("Look at this! https://www.printables.com/model/1400123-balay?lang=en#preview"))
        assertEquals("42", s.parseLink("  https://printables.com/model/42 \n"))
        assertNull(s.parseLink("https://www.thingiverse.com/thing:3161"))
        assertNull(s.parseLink("https://www.printables.com/@Prusa3D"))
        assertNull(s.parseLink("not a link"))
        val r = ModelSources.resolve("https://www.printables.com/model/7-x", listOf(s))
        assertEquals("7", r?.second)
    }

    @Test
    fun parsesModelResponse() {
        val body = """{"data":{"print":{"id":"1400123","name":"Balay shock absorber","slug":"balay","price":null,
            "user":{"publicUsername":"Jane","handle":"jane"},"license":{"name":"Creative Commons — Public Domain","abbreviation":"CC0"},
            "stls":[{"id":"1","name":"BandejaBalay.3mf","fileSize":33623,"filePreviewPath":"media/prints/x/p.png"},
                    {"id":"2","name":"BandejaBalay v4.step","fileSize":22292,"filePreviewPath":""},
                    {"id":"3","name":"Part.STL","fileSize":100,"filePreviewPath":null}],
            "gcodes":[{"id":"9","name":"a.gcode"}],"slas":[],"otherFiles":[{"id":"8","name":"all.zip"}]}}}"""
        val m = PrintablesSource(fake(body)).fetchModel("1400123")
        assertEquals("Balay shock absorber", m.title)
        assertEquals("Jane", m.author)
        assertEquals("https://www.printables.com/model/1400123-balay", m.pageUrl)
        assertEquals(listOf("BandejaBalay.3mf", "Part.STL"), m.files.map { it.name })
        assertEquals(RemoteFileKind.THREE_MF, m.files[0].kind)
        assertEquals("https://media.printables.com/media/prints/x/p.png", m.files[0].previewUrl)
        assertNull(m.files[1].previewUrl)
        assertEquals(listOf("BandejaBalay v4.step", "a.gcode", "all.zip"), m.ignoredFiles)
    }

    @Test
    fun reportsErrors() {
        val missing = assertFailsWith<ModelSourceException> { PrintablesSource(fake("""{"data":{"print":null}}""")).fetchModel("1") }
        assertEquals(ModelSourceException.Reason.NOT_FOUND, missing.reason)
        val paid = assertFailsWith<ModelSourceException> {
            PrintablesSource(fake("""{"data":{"print":{"id":"1","name":"x","price":4.99,"stls":[]}}}""")).fetchModel("1")
        }
        assertEquals(ModelSourceException.Reason.PAID, paid.reason)
        val blocked = assertFailsWith<ModelSourceException> {
            PrintablesSource { _, _, _ -> HttpResponse(403, "<html>Just a moment... challenge</html>") }.fetchModel("1")
        }
        assertEquals(ModelSourceException.Reason.BLOCKED, blocked.reason)
        val offline = assertFailsWith<ModelSourceException> { PrintablesSource { _, _, _ -> throw IOException("no route") }.fetchModel("1") }
        assertEquals(ModelSourceException.Reason.NETWORK, offline.reason)
        val schema = assertFailsWith<ModelSourceException> {
            PrintablesSource(fake("""{"errors":[{"message":"Cannot query field 'x'"}]}""")).fetchModel("1")
        }
        assertEquals(ModelSourceException.Reason.UNEXPECTED, schema.reason)
        val src = PrintablesSource(
            fake(
                """{"data":{"print":{"id":"1","name":"x","stls":[{"id":"5","name":"a.stl","fileSize":1}]}}}""",
                """{"data":{"getDownloadLink":{"ok":false,"errors":[{"field":"non_field_error","messages":["files_cannot_be_downloaded"]}],"output":null}}}""",
            ),
        )
        val m = src.fetchModel("1")
        val refused = assertFailsWith<ModelSourceException> { src.downloadUrl(m, m.files[0]) }
        assertEquals(ModelSourceException.Reason.DOWNLOAD_REFUSED, refused.reason)
    }

    /** Talks to the real Printables API. Runs only when PRINTABLES_LIVE=1 (set in CI, which has internet access). */
    @Test
    fun liveBenchy() {
        if (System.getenv("PRINTABLES_LIVE") != "1") return
        val http = HttpPost { url, body, headers ->
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "OrcaPhone-test")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
            HttpResponse(c.responseCode, stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        }
        val src = PrintablesSource(http)
        val m = src.fetchModel(src.parseLink("https://www.printables.com/model/3161-3d-benchy")!!)
        println("Live model: ${m.title} by ${m.author}, ${m.license}, files=${m.files}, ignored=${m.ignoredFiles}")
        val stl = m.files.first { it.kind == RemoteFileKind.STL }
        assertTrue(stl.sizeBytes > 0 && stl.previewUrl != null)
        val link = src.downloadUrl(m, stl)
        val bytes = URL(link).openStream().use { it.readBytes() }
        assertEquals(stl.sizeBytes, bytes.size.toLong())
        val loaded = MeshLoader.load(stl.name, bytes.inputStream())
        assertTrue(loaded.isNotEmpty() && loaded[0].mesh.triangleCount > 1000)
    }
}
