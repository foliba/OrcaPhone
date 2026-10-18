package com.orcaphone.data

import com.orcaphone.core.config.HostType
import com.orcaphone.core.config.PrinterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HostStatus(
    val online: Boolean,
    val state: String,
    val printing: Boolean = false,
    val paused: Boolean = false,
    /** 0..1, or null when idle. */
    val progress: Double? = null,
    val fileName: String? = null,
    val nozzleTemp: Double? = null,
    val nozzleTarget: Double? = null,
    val bedTemp: Double? = null,
    val bedTarget: Double? = null,
    val jobId: String? = null,
)

enum class JobCommand { PAUSE, RESUME, CANCEL }

/** Minimal clients for OctoPrint, Moonraker (Klipper) and PrusaLink. */
class PrinterHostClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun base(c: PrinterConnection): String {
        var u = c.url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u.trimEnd('/')
    }

    private fun Request.Builder.auth(c: PrinterConnection): Request.Builder =
        if (c.apiKey.isNotBlank()) header("X-Api-Key", c.apiKey.trim()) else this

    private fun get(c: PrinterConnection, path: String): JsonElement {
        val req = Request.Builder().url(base(c) + path).auth(c).get().build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IOException("Unauthorized - check the API key")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $path")
            return json.parseToJsonElement(r.body?.string().orEmpty().ifBlank { "{}" })
        }
    }

    private fun send(c: PrinterConnection, method: String, path: String, body: RequestBody?) {
        val req = Request.Builder().url(base(c) + path).auth(c).method(method, body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${r.body?.string()?.take(200)}")
        }
    }

    suspend fun status(c: PrinterConnection): HostStatus = withContext(Dispatchers.IO) {
        when (c.type) {
            HostType.OCTOPRINT -> octoStatus(c)
            HostType.MOONRAKER -> moonrakerStatus(c)
            HostType.PRUSALINK -> prusaStatus(c)
        }
    }

    private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.num(key: String): Double? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull
    private fun JsonElement?.str(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun octoStatus(c: PrinterConnection): HostStatus {
        val job = get(c, "/api/job")
        val state = job.str("state") ?: "Unknown"
        val printer = runCatching { get(c, "/api/printer") }.getOrNull()
        val temps = printer.obj("temperature")
        val tool = temps.obj("tool0"); val bed = temps.obj("bed")
        val completion = job.obj("progress").num("completion")
        val printing = state.startsWith("Printing", true) || state.startsWith("Pausing", true)
        return HostStatus(
            online = true, state = state, printing = printing || state.startsWith("Paused", true),
            paused = state.startsWith("Paused", true),
            progress = completion?.div(100.0), fileName = job.obj("job").obj("file").str("name"),
            nozzleTemp = tool.num("actual"), nozzleTarget = tool.num("target"),
            bedTemp = bed.num("actual"), bedTarget = bed.num("target"),
        )
    }

    private fun moonrakerStatus(c: PrinterConnection): HostStatus {
        val r = get(c, "/printer/objects/query?print_stats&virtual_sdcard&extruder&heater_bed&webhooks")
        val st = r.obj("result").obj("status")
        val ps = st.obj("print_stats")
        val state = ps.str("state") ?: st.obj("webhooks").str("state") ?: "unknown"
        val printing = state == "printing" || state == "paused"
        return HostStatus(
            online = true, state = state.replaceFirstChar { it.uppercase() }, printing = printing, paused = state == "paused",
            progress = if (printing) st.obj("virtual_sdcard").num("progress") else null,
            fileName = ps.str("filename")?.takeIf { it.isNotBlank() },
            nozzleTemp = st.obj("extruder").num("temperature"), nozzleTarget = st.obj("extruder").num("target"),
            bedTemp = st.obj("heater_bed").num("temperature"), bedTarget = st.obj("heater_bed").num("target"),
        )
    }

    private fun prusaStatus(c: PrinterConnection): HostStatus {
        val r = get(c, "/api/v1/status")
        val pr = r.obj("printer")
        val job = r.obj("job")
        val state = pr.str("state") ?: "UNKNOWN"
        val printing = state == "PRINTING" || state == "PAUSED"
        return HostStatus(
            online = true, state = state.lowercase().replaceFirstChar { it.uppercase() }, printing = printing, paused = state == "PAUSED",
            progress = job.num("progress")?.div(100.0),
            nozzleTemp = pr.num("temp_nozzle"), nozzleTarget = pr.num("target_nozzle"),
            bedTemp = pr.num("temp_bed"), bedTarget = pr.num("target_bed"),
            jobId = job?.get("id")?.jsonPrimitive?.content,
        )
    }

    suspend fun upload(
        c: PrinterConnection, file: File, remoteName: String, startPrint: Boolean,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val body = ProgressBody(file, "application/octet-stream".toMediaType(), onProgress)
        when (c.type) {
            HostType.OCTOPRINT -> {
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", remoteName, body)
                    .addFormDataPart("select", if (startPrint) "true" else "false")
                    .addFormDataPart("print", if (startPrint) "true" else "false")
                    .build()
                send(c, "POST", "/api/files/local", form)
            }
            HostType.MOONRAKER -> {
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", remoteName, body)
                    .addFormDataPart("root", "gcodes")
                    .addFormDataPart("print", if (startPrint) "true" else "false")
                    .build()
                send(c, "POST", "/server/files/upload", form)
            }
            HostType.PRUSALINK -> {
                val name = URLEncoder.encode(remoteName, "UTF-8").replace("+", "%20")
                val req = Request.Builder().url(base(c) + "/api/v1/files/usb/$name").auth(c)
                    .header("Print-After-Upload", if (startPrint) "?1" else "?0")
                    .header("Overwrite", "?1")
                    .put(body).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${r.body?.string()?.take(200)}")
                }
            }
        }
    }

    suspend fun command(c: PrinterConnection, cmd: JobCommand, status: HostStatus?) = withContext(Dispatchers.IO) {
        val empty = ByteArray(0).toRequestBody(null)
        when (c.type) {
            HostType.OCTOPRINT -> {
                val payload = when (cmd) {
                    JobCommand.PAUSE -> """{"command":"pause","action":"pause"}"""
                    JobCommand.RESUME -> """{"command":"pause","action":"resume"}"""
                    JobCommand.CANCEL -> """{"command":"cancel"}"""
                }
                send(c, "POST", "/api/job", payload.toRequestBody("application/json".toMediaType()))
            }
            HostType.MOONRAKER -> send(
                c, "POST",
                when (cmd) {
                    JobCommand.PAUSE -> "/printer/print/pause"
                    JobCommand.RESUME -> "/printer/print/resume"
                    JobCommand.CANCEL -> "/printer/print/cancel"
                },
                empty,
            )
            HostType.PRUSALINK -> {
                val id = status?.jobId ?: runCatching { prusaStatus(c).jobId }.getOrNull()
                    ?: throw IOException("No active job")
                when (cmd) {
                    JobCommand.PAUSE -> send(c, "PUT", "/api/v1/job/$id/pause", empty)
                    JobCommand.RESUME -> send(c, "PUT", "/api/v1/job/$id/resume", empty)
                    JobCommand.CANCEL -> send(c, "DELETE", "/api/v1/job/$id", null)
                }
            }
        }
    }

    private class ProgressBody(
        private val file: File, private val type: MediaType, private val onProgress: (Float) -> Unit,
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1)
            var sent = 0L
            file.source().use { src ->
                val buf = okio.Buffer()
                while (true) {
                    val n = src.read(buf, 64 * 1024)
                    if (n < 0) break
                    sink.write(buf, n)
                    sent += n
                    onProgress(sent.toFloat() / total)
                }
            }
        }
    }
}
