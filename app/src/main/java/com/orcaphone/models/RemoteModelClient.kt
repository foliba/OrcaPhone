package com.orcaphone.models

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.orcaphone.core.models.HttpPost
import com.orcaphone.core.models.HttpResponse
import com.orcaphone.core.models.ModelSource
import com.orcaphone.core.models.PrintablesSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Network access for importing models from websites: metadata, file downloads and preview images. */
class RemoteModelClient(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent = "OrcaPhone (Android)"

    private val post = HttpPost { url, body, headers ->
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { HttpResponse(it.code, it.body?.string() ?: "") }
    }

    val sources: List<ModelSource> = listOf(PrintablesSource(post))

    /** True when the active network is metered (usually mobile data). */
    fun isMetered(): Boolean = runCatching {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(false)

    /**
     * Downloads [url] to [dest]. [onBytes] receives the bytes read so far; [isActive] is polled so the
     * download stops promptly when the user cancels. Blocking.
     */
    fun download(url: String, dest: File, isActive: () -> Boolean, onBytes: (Long) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val call = http.newCall(req)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code})")
            val body = resp.body ?: throw IOException("Empty download")
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        if (!isActive()) { call.cancel(); throw IOException("Cancelled") }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
        }
    }

    private val previews = LruCache<String, ImageBitmap>(48)

    /** Loads a small preview image, cached in memory. Returns null if it can't be loaded. */
    suspend fun preview(url: String): ImageBitmap? {
        previews.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
                val bytes = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()?.also { previews.put(url, it) }
        }
    }
}
