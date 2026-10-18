package com.orcaphone.core.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * Printables.com via the GraphQL API its website uses. There is no official public API, so everything here
 * is parsed defensively and failures are reported as [ModelSourceException]s with a readable message.
 */
class PrintablesSource(private val http: HttpPost) : ModelSource {
    override val name = "Printables"

    override fun parseLink(text: String): String? {
        for (token in ModelSources.urlTokens(text)) {
            val (host, path) = ModelSources.hostAndPath(token)
            if (host != "printables.com" && !host.endsWith(".printables.com")) continue
            val i = path.indexOfFirst { it == "model" || it == "print" }
            if (i < 0 || i + 1 >= path.size) continue
            val id = path[i + 1].takeWhile { it.isDigit() }
            if (id.isNotEmpty()) return id
        }
        return null
    }

    override fun fetchModel(id: String): RemoteModel {
        val body = buildJsonObject {
            put("operationName", "PrintFiles")
            put("query", MODEL_QUERY)
            putJsonObject("variables") { put("id", id) }
        }.toString()
        val data = graphql(body)
        val print = data["print"] as? JsonObject
            ?: throw ModelSourceException("This Printables model doesn't exist or isn't public.", ModelSourceException.Reason.NOT_FOUND)
        return parseModel(print)
    }

    override fun downloadUrl(model: RemoteModel, file: RemoteFile): String {
        val body = buildJsonObject {
            put("operationName", "GetDownloadLink")
            put("query", DOWNLOAD_MUTATION)
            putJsonObject("variables") {
                put("id", file.id)
                put("modelId", model.id)
                put("fileType", "stl")
                put("source", "model_detail")
            }
        }.toString()
        val data = graphql(body)
        val res = data["getDownloadLink"] as? JsonObject
            ?: throw unexpected("no download link in response")
        val link = ((res["output"] as? JsonObject)?.get("link") as? JsonPrimitive)?.contentOrNull
        if (res.bool("ok") == true && !link.isNullOrBlank()) return link
        val messages = (res["errors"] as? JsonArray).orEmpty()
            .flatMap { ((it as? JsonObject)?.get("messages") as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        throw ModelSourceException(
            "Printables didn't allow downloading ${file.name}" +
                if (messages.isNotEmpty()) " (${messages.joinToString()})." else ".",
            ModelSourceException.Reason.DOWNLOAD_REFUSED,
        )
    }

    private fun graphql(body: String): JsonObject {
        val resp = try {
            http.postJson(API_URL, body, HEADERS)
        } catch (e: IOException) {
            throw ModelSourceException("Couldn't reach Printables. Check your internet connection.", ModelSourceException.Reason.NETWORK)
        }
        val root = runCatching { JSON.parseToJsonElement(resp.body) as? JsonObject }.getOrNull()
        if (root == null) {
            if (resp.code == 403 || resp.code == 429 || resp.body.contains("challenge", ignoreCase = true)) {
                throw ModelSourceException(
                    "Printables is blocking requests from the app right now. Try again later.",
                    ModelSourceException.Reason.BLOCKED,
                )
            }
            throw unexpected("HTTP ${resp.code}")
        }
        val errors = (root["errors"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull }
        val data = root["data"] as? JsonObject
        if (data == null || (errors.isNotEmpty() && data.values.all { it is JsonNull })) {
            throw unexpected(errors.firstOrNull() ?: "HTTP ${resp.code}")
        }
        return data
    }

    private fun unexpected(detail: String) = ModelSourceException(
        "Printables sent an unexpected answer ($detail). The app may need an update.",
        ModelSourceException.Reason.UNEXPECTED,
    )

    companion object {
        const val API_URL = "https://api.printables.com/graphql/"
        const val MEDIA_URL = "https://media.printables.com/"
        private val HEADERS = mapOf(
            "Accept" to "application/json",
            "Origin" to "https://www.printables.com",
            "Referer" to "https://www.printables.com/",
        )
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val MODEL_QUERY =
            "query PrintFiles(\$id: ID!) { print(id: \$id) { id name slug price " +
                "user { publicUsername handle } license { name abbreviation } " +
                "stls { id name fileSize filePreviewPath } gcodes { id name } slas { id name } otherFiles { id name } } }"

        private const val DOWNLOAD_MUTATION =
            "mutation GetDownloadLink(\$id: ID!, \$modelId: ID!, \$fileType: DownloadFileTypeEnum!, \$source: DownloadSourceEnum!) " +
                "{ getDownloadLink(id: \$id, printId: \$modelId, fileType: \$fileType, source: \$source) " +
                "{ ok errors { field messages } output { link } } }"

        /** Builds a [RemoteModel] from the `print` object of the GraphQL response. Public for tests. */
        fun parseModel(print: JsonObject): RemoteModel {
            val id = print.str("id") ?: throw ModelSourceException("Printables sent no model id.", ModelSourceException.Reason.UNEXPECTED)
            val price = (print["price"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
            if (price != null && price > 0) {
                throw ModelSourceException(
                    "This is a paid model. Buy it on Printables and download it from there.",
                    ModelSourceException.Reason.PAID,
                )
            }
            val user = print["user"] as? JsonObject
            val license = print["license"] as? JsonObject
            val slug = print.str("slug")
            val files = ArrayList<RemoteFile>()
            val ignored = ArrayList<String>()
            for (f in (print["stls"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
                val name = f.str("name") ?: continue
                val kind = RemoteFileKind.of(name)
                val fid = f.str("id")
                if (kind == null || fid == null) { ignored.add(name); continue }
                val preview = f.str("filePreviewPath")?.takeIf { it.isNotBlank() }?.let {
                    if (it.startsWith("http")) it else MEDIA_URL + it.removePrefix("/")
                }
                files.add(RemoteFile(fid, name, (f["fileSize"] as? JsonPrimitive)?.longOrNull ?: 0L, preview, kind))
            }
            for (key in listOf("gcodes", "slas", "otherFiles")) {
                for (f in (print[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
                    ignored.add(f.str("name") ?: key)
                }
            }
            return RemoteModel(
                sourceName = "Printables",
                id = id,
                title = print.str("name") ?: "Printables model $id",
                author = user?.str("publicUsername") ?: user?.str("handle"),
                license = license?.str("name") ?: license?.str("abbreviation"),
                pageUrl = "https://www.printables.com/model/$id" + (slug?.let { "-$it" } ?: ""),
                files = files,
                ignoredFiles = ignored,
            )
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
