package io.github.foliba.phorca.data

import android.content.Context
import io.github.foliba.phorca.core.orca.OrcaCatalog
import io.github.foliba.phorca.core.orca.ProfilePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A newer set of OrcaSlicer profiles that can be downloaded. */
data class ProfileUpdate(val version: String, val url: String, val sizeBytes: Long)

/**
 * Newer OrcaSlicer printer, filament and process profiles (and bed graphics). OrcaSlicer's latest stable
 * release is looked up on GitHub; Phorca's profiles workflow publishes the converted pack for it as a release
 * of the Phorca repository. A downloaded pack replaces the built-in one when it is newer.
 */
class ProfileUpdates(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "orca_profiles")
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private val _checkOnStartup = MutableStateFlow(prefs.getBoolean("check_on_startup", true))
    /** Ask about new profiles when the app starts. */
    val checkOnStartup: StateFlow<Boolean> = _checkOnStartup.asStateFlow()

    fun setCheckOnStartup(on: Boolean) {
        prefs.edit().putBoolean("check_on_startup", on).apply()
        _checkOnStartup.value = on
    }

    private val _version = MutableStateFlow(activeVersion())
    /** The OrcaSlicer version of the profiles in use. */
    val version: StateFlow<String> = _version.asStateFlow()

    /** The downloaded pack's folder when it is newer than the built-in profiles, else null. */
    fun activeDir(): File? {
        val v = File(dir, "current").takeIf { it.exists() }?.readText()?.trim() ?: return null
        val d = File(dir, v)
        return d.takeIf { ProfilePack.isNewer(v, ProfilePack.BUNDLED_VERSION) && File(it, ProfilePack.CATALOG).exists() }
    }

    private fun activeVersion(): String = activeDir()?.name ?: ProfilePack.BUNDLED_VERSION

    /** The catalog in use: the downloaded one if newer, else the built-in one. */
    fun loadCatalog(): OrcaCatalog =
        activeDir()?.let { d -> runCatching { File(d, ProfilePack.CATALOG).inputStream().use { OrcaCatalog.load(it) } }.getOrNull() }
            ?: OrcaCatalog.load()

    private fun get(url: String): String = http.newCall(
        Request.Builder().url(url).header("User-Agent", "Phorca (Android)").header("Accept", "application/vnd.github+json").build(),
    ).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
        r.body?.string() ?: ""
    }

    /**
     * Looks for newer profiles: OrcaSlicer's latest stable release, if it is newer than the profiles in use and
     * Phorca has published the converted pack for it. Null when there is nothing new (or no network).
     */
    suspend fun check(): ProfileUpdate? = withContext(Dispatchers.IO) {
        val tag = JSONObject(get("https://api.github.com/repos/OrcaSlicer/OrcaSlicer/releases/latest")).optString("tag_name")
        val latest = ProfilePack.stableVersion(tag) ?: return@withContext null
        if (!ProfilePack.isNewer(latest, activeVersion())) return@withContext null
        // the pack is there once Phorca's workflow converted this OrcaSlicer release
        val release = runCatching {
            JSONObject(get("https://api.github.com/repos/$PHORCA_REPO/releases/tags/${ProfilePack.releaseTag(latest)}"))
        }.getOrNull() ?: return@withContext null
        val assets = release.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == ProfilePack.fileName(latest)) {
                return@withContext ProfileUpdate(latest, a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        null
    }

    /** Downloads and installs [u]; the new profiles are used from then on. Older downloads are removed. */
    suspend fun install(u: ProfileUpdate, progress: (Float) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmpZip = File(dir, "download.zip")
        http.newCall(Request.Builder().url(u.url).header("User-Agent", "Phorca (Android)").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed: HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: u.sizeBytes
            body.byteStream().use { input ->
                tmpZip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        val dest = File(dir, u.version)
        val staging = File(dir, "${u.version}.new")
        staging.deleteRecursively()
        try {
            tmpZip.inputStream().use { ProfilePack.extract(it, staging, u.version) }
        } finally {
            tmpZip.delete()
        }
        dest.deleteRecursively()
        if (!staging.renameTo(dest)) throw IOException("Could not install the profiles")
        File(dir, "current").writeText(u.version)
        dir.listFiles()?.filter { it.isDirectory && it.name != u.version }?.forEach { it.deleteRecursively() }
        _version.value = u.version
    }

    companion object {
        const val PHORCA_REPO = "foliba/Phorca"
    }
}
