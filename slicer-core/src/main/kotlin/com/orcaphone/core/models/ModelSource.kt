package com.orcaphone.core.models

/** File types we can import from a model-sharing site. Everything else is ignored. */
enum class RemoteFileKind(val label: String) {
    STL("STL"),
    THREE_MF("3MF");

    companion object {
        fun of(fileName: String): RemoteFileKind? {
            val n = fileName.lowercase().trim()
            return when {
                n.endsWith(".stl") -> STL
                n.endsWith(".3mf") -> THREE_MF
                else -> null
            }
        }
    }
}

data class RemoteFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    /** Server-rendered 2D preview image, if the site has one. */
    val previewUrl: String?,
    val kind: RemoteFileKind,
)

data class RemoteModel(
    val sourceName: String,
    val id: String,
    val title: String,
    val author: String?,
    val license: String?,
    val pageUrl: String,
    /** Importable files (STL and 3MF). */
    val files: List<RemoteFile>,
    /** Names of files we skip (STEP, ZIP, G-code, …). */
    val ignoredFiles: List<String>,
)

class ModelSourceException(message: String, val reason: Reason) : Exception(message) {
    enum class Reason { BAD_LINK, NOT_FOUND, PAID, BLOCKED, NETWORK, DOWNLOAD_REFUSED, UNEXPECTED }
}

data class HttpResponse(val code: Int, val body: String)

/** Minimal HTTP abstraction so the core stays free of Android / OkHttp. Throws IOException on network errors. */
fun interface HttpPost {
    fun postJson(url: String, body: String, headers: Map<String, String>): HttpResponse
}

/** A website that hosts 3D models (Printables today; MakerWorld or Thingiverse could follow). */
interface ModelSource {
    val name: String

    /** Extracts the model id from a link (or from shared text containing one), or null if it isn't ours. */
    fun parseLink(text: String): String?

    /** Loads the model's title, author, licence and file list. Blocking. */
    fun fetchModel(id: String): RemoteModel

    /** Returns a direct download URL for [file]. Blocking. */
    fun downloadUrl(model: RemoteModel, file: RemoteFile): String
}

object ModelSources {
    /** Finds the source that understands [text] and the model id it contains. */
    fun resolve(text: String, sources: List<ModelSource>): Pair<ModelSource, String>? {
        for (s in sources) s.parseLink(text)?.let { return s to it }
        return null
    }

    /** Pulls URL-like tokens out of free text (e.g. "Check this out! https://…"). */
    internal fun urlTokens(text: String): List<String> =
        text.split(' ', '\n', '\t', '\r').map { it.trim().trim('<', '>', '"', '\'', '(', ')') }.filter { it.isNotEmpty() }

    /** Splits a URL into lower-case host and path segments, ignoring scheme, query and fragment. */
    internal fun hostAndPath(url: String): Pair<String, List<String>> {
        var u = url
        val scheme = u.indexOf("://")
        if (scheme >= 0) u = u.substring(scheme + 3)
        val cut = u.indexOfAny(charArrayOf('?', '#'))
        if (cut >= 0) u = u.substring(0, cut)
        val slash = u.indexOf('/')
        val host = (if (slash >= 0) u.substring(0, slash) else u).substringAfter('@').substringBefore(':').lowercase()
        val path = if (slash >= 0) u.substring(slash + 1).split('/').filter { it.isNotEmpty() } else emptyList()
        return host to path
    }
}
