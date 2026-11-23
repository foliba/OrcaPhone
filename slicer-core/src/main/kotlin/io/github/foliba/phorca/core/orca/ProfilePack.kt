package io.github.foliba.phorca.core.orca

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A downloadable set of OrcaSlicer profiles, converted by Phorca's profiles workflow (see
 * .github/workflows/profiles.yml): a zip with manifest.json, system_presets.json.gz and beds/.
 */
object ProfilePack {
    /** The OrcaSlicer version the app's built-in catalog and bed graphics come from. */
    const val BUNDLED_VERSION = "2.5.2"

    /** Pack format the app understands; the workflow writes it into manifest.json. */
    const val FORMAT = 1

    const val CATALOG = "system_presets.json.gz"
    const val MANIFEST = "manifest.json"
    const val BEDS = "beds"

    private val STABLE = Regex("""^\d+(\.\d+){1,3}$""")

    /** "v2.5.3" -> "2.5.3"; null for pre-releases and anything that isn't a plain version. */
    fun stableVersion(tag: String): String? = tag.trim().removePrefix("v").takeIf { STABLE.matches(it) }

    /** Compares dotted versions numerically ("2.10.0" > "2.9.1"). */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, than: String) = compare(candidate, than) > 0

    /** Release tag and file name of the pack for OrcaSlicer [version] in the Phorca repository. */
    fun releaseTag(version: String) = "profiles-$version"
    fun fileName(version: String) = "phorca-profiles-$version.zip"

    /**
     * Unpacks [zip] into [dest] (which must not exist yet) and checks it is a complete pack for OrcaSlicer
     * [version]: known entries only (no paths leaving [dest]), the manifest matches, the catalog loads and
     * has printers, filaments and processes. Deletes [dest] and throws when anything is wrong.
     */
    fun extract(zip: InputStream, dest: File, version: String): OrcaCatalog {
        if (dest.exists()) throw IOException("$dest exists")
        try {
            val root = dest.canonicalFile
            ZipInputStream(zip).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name
                    if (e.isDirectory) continue
                    val allowed = name == MANIFEST || name == CATALOG || (name.startsWith("$BEDS/") && !name.contains(".."))
                    if (!allowed) throw IOException("Unexpected file in profile pack: $name")
                    val out = File(root, name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw IOException("Bad path in profile pack: $name")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
            }
            val manifest = File(root, MANIFEST).takeIf { it.exists() }?.readText() ?: throw IOException("Profile pack has no manifest")
            val packVersion = Regex(""""orcaVersion"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
            val format = Regex(""""format"\s*:\s*(\d+)""").find(manifest)?.groupValues?.get(1)?.toIntOrNull()
            if (packVersion != version) throw IOException("Profile pack is for OrcaSlicer $packVersion, expected $version")
            if (format != FORMAT) throw IOException("Profile pack format $format is not supported (needs $FORMAT); update Phorca")
            val catalogFile = File(root, CATALOG).takeIf { it.exists() } ?: throw IOException("Profile pack has no catalog")
            val catalog = catalogFile.inputStream().use { OrcaCatalog.load(it) }
            for (kind in OrcaKind.entries) {
                if (catalog.entries(kind).size < 10) throw IOException("Profile pack has hardly any ${kind.name.lowercase()} presets")
            }
            return catalog
        } catch (t: Throwable) {
            dest.deleteRecursively()
            throw t
        }
    }
}
