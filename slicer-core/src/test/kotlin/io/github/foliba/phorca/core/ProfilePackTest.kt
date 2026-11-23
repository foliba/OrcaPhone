package io.github.foliba.phorca.core

import io.github.foliba.phorca.core.orca.OrcaCatalog
import io.github.foliba.phorca.core.orca.OrcaKind
import io.github.foliba.phorca.core.orca.ProfilePack
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilePackTest {
    private val catalogBytes = OrcaCatalog::class.java.getResourceAsStream(OrcaCatalog.RESOURCE)!!.readBytes()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun manifest(v: String, format: Int = 1) = """{"format": $format, "orcaVersion": "$v", "generated": "2026-11-24"}""".toByteArray()

    private fun tmp() = File(createTempDirectory("pack").toFile(), "out")

    @Test
    fun versions() {
        assertEquals("2.5.3", ProfilePack.stableVersion("v2.5.3"))
        assertEquals("2.6.0", ProfilePack.stableVersion("2.6.0"))
        assertNull(ProfilePack.stableVersion("v2.6.0-beta"))
        assertNull(ProfilePack.stableVersion("nightly-builds"))
        assertTrue(ProfilePack.isNewer("2.10.0", "2.9.9"))
        assertTrue(ProfilePack.isNewer("2.5.3", ProfilePack.BUNDLED_VERSION))
        assertFalse(ProfilePack.isNewer("2.5.2", "2.5.2"))
        assertFalse(ProfilePack.isNewer("2.5", "2.5.0"))
    }

    @Test
    fun aGoodPackUnpacks() {
        val dest = tmp()
        val bed = "beds/Sovol/x.svg" to "<svg/>".toByteArray()
        val cat = ProfilePack.extract(
            zip(ProfilePack.MANIFEST to manifest("2.5.3"), ProfilePack.CATALOG to catalogBytes, bed).inputStream(), dest, "2.5.3",
        )
        assertTrue(cat.entries(OrcaKind.PRINTER).size > 1000)
        assertTrue(File(dest, "beds/Sovol/x.svg").exists())
        assertTrue(File(dest, ProfilePack.CATALOG).exists())
    }

    @Test
    fun badPacksAreRejectedAndRemoved() {
        val good = arrayOf(ProfilePack.MANIFEST to manifest("2.5.3"), ProfilePack.CATALOG to catalogBytes)
        val cases = mapOf(
            "wrong version" to zip(*good),
            "unknown file" to zip(*good, "evil.sh" to "x".toByteArray()),
            "path escape" to zip(*good, "beds/../../evil" to "x".toByteArray()),
            "no catalog" to zip(ProfilePack.MANIFEST to manifest("2.5.4")),
            "newer format" to zip(ProfilePack.MANIFEST to manifest("2.5.4", format = 2), ProfilePack.CATALOG to catalogBytes),
            "broken catalog" to zip(ProfilePack.MANIFEST to manifest("2.5.4"), ProfilePack.CATALOG to "not gzip".toByteArray()),
        )
        for ((what, bytes) in cases) {
            val dest = tmp()
            val expected = if (what == "wrong version") "2.6.0" else if (what == "unknown file" || what == "path escape") "2.5.3" else "2.5.4"
            assertFailsWith<Exception>(what) { ProfilePack.extract(bytes.inputStream(), dest, expected) }
            assertFalse(dest.exists(), "$what: left files behind")
            assertFalse(File(dest.parentFile.parentFile, "evil").exists(), "$what: wrote outside")
        }
    }

    /** Run by the profiles workflow on the pack it built (PHORCA_PACK, PHORCA_PACK_VERSION); skipped otherwise. */
    @Test
    fun packFromTheWorkflowIsValid() {
        val zip = System.getenv("PHORCA_PACK")?.let { File(it) } ?: return
        val version = System.getenv("PHORCA_PACK_VERSION")!!
        val dest = tmp()
        val cat = zip.inputStream().use { ProfilePack.extract(it, dest, version) }
        assertTrue(cat.entries(OrcaKind.PRINTER).size > 500, "only ${cat.entries(OrcaKind.PRINTER).size} printers")
        val beds = File(dest, ProfilePack.BEDS)
        val index = File(beds, "index.json").readText()
        val models = Regex(""""model": "([^"]+)"""").findAll(index).map { it.groupValues[1] }.toSet()
        assertTrue(models.size > 100, "only ${models.size} bed models")
        for (m in models) assertTrue(io.github.foliba.phorca.core.mesh.BedModelCodec.decode(File(beds, m).readBytes()).triangleCount > 0, m)
        for (t in Regex(""""texture": "([^"]+)"""").findAll(index).map { it.groupValues[1] }) assertTrue(File(beds, t).exists(), t)
        println("pack OrcaSlicer $version: ${cat.entries(OrcaKind.PRINTER).size} printers, ${models.size} bed models")
    }
}
