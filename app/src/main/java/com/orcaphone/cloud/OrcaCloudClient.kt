package com.orcaphone.cloud

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Signed-in Orca Cloud user. Tokens are Supabase (GoTrue) JWTs. */
@Serializable
data class OrcaSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    /** Access token expiry (epoch seconds). */
    val expiresAt: Long = 0,
) {
    val label: String get() = displayName.ifBlank { username.ifBlank { "Orca Cloud user" } }
}

class OrcaCloudException(message: String, val httpCode: Int = 0) : IOException(message)

class CloudProfile(val id: String, val name: String, val content: Map<String, String>, val updatedTime: Long)

class PullResult(
    val upserts: List<CloudProfile>,
    val deletes: List<String>,
    val nextCursor: Long,
    val httpCode: Int = 200,
    /** Start of the raw response, for the sync diagnostics screen. */
    val rawSample: String = "",
)

class PushResult(val ok: Boolean, val httpCode: Int, val updatedTime: Long = 0, val conflictCode: Int = 0, val serverProfile: CloudProfile? = null, val message: String = "")

class CloudPrinter(val id: String, val name: String, val model: String, val online: Boolean, val jobState: String?, val progress: Double?)

/**
 * Client for OrcaSlicer's cloud (api/auth/cloud.orcaslicer.com), mirroring OrcaSlicer's
 * OrcaCloudServiceAgent: PKCE sign-in through the hosted login page, token refresh, preset sync
 * and the printer list.
 */
class OrcaCloudClient(
    private val apiUrl: String = API_URL,
    private val onSessionChanged: (OrcaSession?) -> Unit,
) {
    companion object {
        const val API_URL = "https://api.orcaslicer.com"
        const val AUTH_URL = "https://auth.orcaslicer.com"
        const val CLOUD_URL = "https://cloud.orcaslicer.com"
        /** Public client key (not a secret), same as OrcaSlicer desktop. */
        const val PUB_KEY = "sb_publishable_lvVe_whOi80SU9BPSxM1kA_tbt9AbR_"
        const val LOGIN_PATH = "/orcaslicer-login"
        const val LOOPBACK_PORT = 41172
        private const val TOKEN_PATH = "/auth/v1/token"
        private const val REFRESH_SKEW_S = 900L
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    @Volatile
    var session: OrcaSession? = null
        private set

    fun restore(s: OrcaSession?) { session = s }

    // ------------------------------------------------------------------ PKCE

    class Pkce(val verifier: String, val challenge: String, val state: String, var port: Int) {
        val redirectUri get() = "http://localhost:$port/callback"
    }

    fun newPkce(port: Int): Pkce {
        val rnd = SecureRandom()
        fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val verifier = b64(ByteArray(32).also { rnd.nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = b64(ByteArray(16).also { rnd.nextBytes(it) })
        return Pkce(verifier, challenge, state, port)
    }

    /** Message the hosted login page expects in reply to "get_login_cmd". */
    fun loginConfig(p: Pkce): String = buildJsonObject {
        put("action", "login_config")
        put("backend_url", AUTH_URL)
        put("apikey", PUB_KEY)
        put("pkce", buildJsonObject {
            put("code_challenge", p.challenge)
            put("code_challenge_method", "S256")
            put("state", p.state)
            put("redirect_uri", p.redirectUri)
            put("code_verifier", p.verifier)
            put("loopback_port", p.port)
        })
    }.toString()

    /** Exchanges a PKCE auth code for a session. */
    fun exchangeCode(code: String, pkce: Pkce): OrcaSession {
        val body = buildJsonObject { put("auth_code", code); put("code_verifier", pkce.verifier) }.toString()
        val resp = postToken("$AUTH_URL$TOKEN_PATH?grant_type=pkce", body)
        return sessionFrom(resp).also { setSession(it) }
    }

    /** Accepts a session handed over by the login page (tokens already obtained in the page). */
    fun acceptSession(payload: JsonObject): OrcaSession = sessionFrom(payload).also { setSession(it) }

    private fun setSession(s: OrcaSession?) {
        session = s
        onSessionChanged(s)
    }

    private fun postToken(url: String, body: String): JsonObject {
        val req = Request.Builder().url(url)
            .header("apikey", PUB_KEY)
            .post(body.toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject.str("error_description") ?: json.parseToJsonElement(text).jsonObject.str("msg") }.getOrNull()
                throw OrcaCloudException(msg ?: "Sign-in failed (HTTP ${r.code})", r.code)
            }
            return json.parseToJsonElement(text).jsonObject
        }
    }

    /** Parses a GoTrue session (nested "user") or the flat format the login page may send. */
    private fun sessionFrom(o: JsonObject): OrcaSession {
        val node = (o["session"] as? JsonObject) ?: (o["data"] as? JsonObject)?.let { (it["session"] as? JsonObject) ?: it } ?: o
        val access = node.str("access_token") ?: node.str("token") ?: throw OrcaCloudException("Sign-in response has no access token")
        val refresh = node.str("refresh_token").orEmpty()
        val user = node["user"] as? JsonObject
        val meta = user?.get("user_metadata") as? JsonObject
        val userId = user?.str("id") ?: node.str("user_id") ?: jwtClaim(access, "sub") ?: throw OrcaCloudException("Sign-in response has no user")
        val username = meta?.str("username") ?: node.str("username") ?: ""
        val display = listOfNotNull(
            meta?.str("display_name"), meta?.str("nickname"), meta?.str("full_name"), meta?.str("name"),
            node.str("display_name"), node.str("nickname"), user?.str("email"),
        ).firstOrNull { it.isNotBlank() } ?: username
        val avatar = meta?.str("avatar_url") ?: node.str("avatar") ?: ""
        val exp = jwtClaim(access, "exp")?.toLongOrNull()
            ?: node["expires_at"]?.let { (it as? JsonPrimitive)?.longOrNull }
            ?: (System.currentTimeMillis() / 1000 + 3600)
        return OrcaSession(access, refresh, userId, username, display, avatar, exp)
    }

    private fun jwtClaim(token: String, claim: String): String? = runCatching {
        val payload = token.split('.')[1]
        val text = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        (json.parseToJsonElement(text).jsonObject[claim] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** Refreshes the access token when it is about to expire. Refresh tokens rotate on every use. */
    @Synchronized
    fun ensureFresh(force: Boolean = false) {
        val s = session ?: throw OrcaCloudException("Not signed in", 401)
        if (!force && s.expiresAt - System.currentTimeMillis() / 1000 > REFRESH_SKEW_S) return
        if (s.refreshToken.isBlank()) throw OrcaCloudException("Session expired, please sign in again", 401)
        val resp = try {
            postToken("$AUTH_URL$TOKEN_PATH?grant_type=refresh_token", buildJsonObject { put("refresh_token", s.refreshToken) }.toString())
        } catch (e: OrcaCloudException) {
            if (e.httpCode in 400..499) setSession(null) // refresh token rejected: signed out
            throw e
        }
        val fresh = sessionFrom(resp)
        setSession(fresh.copy(
            username = fresh.username.ifBlank { s.username },
            displayName = fresh.displayName.ifBlank { s.displayName },
            avatarUrl = fresh.avatarUrl.ifBlank { s.avatarUrl },
        ))
    }

    fun logout() {
        val s = session ?: return
        runCatching {
            val req = Request.Builder().url("$AUTH_URL/auth/v1/logout")
                .header("apikey", PUB_KEY).header("Authorization", "Bearer ${s.accessToken}")
                .post(buildJsonObject { put("refresh_token", s.refreshToken) }.toString().toRequestBody(jsonType))
                .build()
            http.newCall(req).execute().close()
        }
        setSession(null)
    }

    // ------------------------------------------------------------------ API

    private class Resp(val code: Int, val body: String)

    private fun call(method: String, path: String, body: String? = null): Resp {
        fun exec(): Resp {
            val token = session?.accessToken ?: throw OrcaCloudException("Not signed in", 401)
            val b = Request.Builder().url(apiUrl + path)
                .header("apikey", PUB_KEY)
                .header("Authorization", "Bearer $token")
            val req = when (method) {
                "GET" -> b.get()
                "DELETE" -> b.delete()
                else -> b.method(method, (body ?: "{}").toRequestBody(jsonType))
            }.build()
            http.newCall(req).execute().use { r -> return Resp(r.code, r.body?.string().orEmpty()) }
        }
        ensureFresh()
        val first = exec()
        if (first.code != 401) return first
        ensureFresh(force = true)
        return exec()
    }

    private fun contentMap(e: JsonElement?): Map<String, String> {
        val o = when {
            e is JsonObject -> e
            // content stored as a JSON-encoded string
            e is JsonPrimitive && e.isString -> runCatching { json.parseToJsonElement(e.content) as? JsonObject }.getOrNull()
            else -> null
        } ?: return emptyMap()
        return o.mapValues { (_, v) ->
            when (v) {
                is JsonPrimitive -> if (v.isString) v.content else v.toString()
                JsonNull -> ""
                else -> v.toString()
            }
        }
    }

    private fun profileFrom(o: JsonObject): CloudProfile = CloudProfile(
        id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: (o["setting_id"] as? JsonPrimitive)?.contentOrNull ?: "",
        name = o.str("name").orEmpty(),
        content = contentMap(o["content"] ?: o["values"] ?: o["setting"]),
        updatedTime = (o["updated_time"] as? JsonPrimitive)?.longOrNull ?: 0,
    )

    /** GET /api/v1/sync/pull. [cursor] 0 = everything. */
    fun pull(cursor: Long): PullResult {
        var r = call("GET", "/api/v1/sync/pull" + if (cursor != 0L) "?cursor=$cursor" else "")
        var resynced = false
        if (r.code == 410) { r = call("GET", "/api/v1/sync/pull"); resynced = true } // cursor too old: full resync
        val sample = r.body.take(4000)
        if (r.code == 304) return PullResult(emptyList(), emptyList(), cursor, 304, sample)
        if (r.code != 200) throw OrcaCloudException("Sync failed (HTTP ${r.code}): ${r.body.take(200)}", r.code)
        val root = json.parseToJsonElement(r.body)
        // Expected: {"upserts":[…],"deletes":[…],"next_cursor":…}; also accept a "data" wrapper or a bare list.
        val o: JsonObject = when (root) {
            is JsonObject -> (root["data"] as? JsonObject)?.takeIf { it.containsKey("upserts") } ?: root
            is JsonArray -> JsonObject(mapOf("upserts" to root))
            else -> JsonObject(emptyMap())
        }
        val list = (o["upserts"] ?: o["profiles"] ?: o["items"] ?: (root as? JsonObject)?.get("data")) as? JsonArray
        val ups = list?.mapNotNull { (it as? JsonObject)?.let(::profileFrom) } ?: emptyList()
        val dels = (o["deletes"] as? JsonArray)?.mapNotNull { d ->
            (d as? JsonPrimitive)?.contentOrNull ?: (d as? JsonObject)?.str("id")
        } ?: emptyList()
        val next = (o["next_cursor"] as? JsonPrimitive)?.longOrNull ?: 0
        return PullResult(ups, dels, if (resynced) 0 else next, r.code, sample)
    }

    /** POST /api/v1/sync/push (optimistic concurrency via original_updated_time). */
    fun push(id: String, name: String, content: Map<String, String>, originalUpdatedTime: Long?): PushResult {
        val body = buildJsonObject {
            put("id", id)
            put("name", name)
            put("content", JsonObject(content.mapValues { JsonPrimitive(it.value) }))
            if (originalUpdatedTime != null && originalUpdatedTime > 0) put("original_updated_time", originalUpdatedTime.toString())
        }.toString()
        if (body.length > 1_048_576) return PushResult(false, 413, message = "Preset is larger than the 1 MB cloud limit")
        val r = call("POST", "/api/v1/sync/push", body)
        if (r.code == 409) {
            val o = runCatching { json.parseToJsonElement(r.body) as? JsonObject }.getOrNull()
            val server = (o?.get("server_profile") as? JsonObject)?.let(::profileFrom)
            val code = (o?.get("code") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            return PushResult(false, 409, conflictCode = code, serverProfile = server, message = "Changed in the cloud since last sync")
        }
        if (r.code != 200) return PushResult(false, r.code, message = r.body.take(200))
        val t = runCatching { (json.parseToJsonElement(r.body).jsonObject["updated_time"] as? JsonPrimitive)?.longOrNull }.getOrNull() ?: 0
        return PushResult(t != 0L, 200, updatedTime = t, message = if (t == 0L) "Server response missing updated_time" else "")
    }

    fun delete(id: String) {
        val r = call("DELETE", "/api/v1/sync/delete?resource=profiles&id=$id")
        if (r.code >= 400 && r.code != 404) throw OrcaCloudException("Delete failed (HTTP ${r.code})", r.code)
    }

    /** GET /api/v1/printers: printers linked to the account (viewer-only entries are skipped, like OrcaSlicer). */
    fun printers(): List<CloudPrinter> {
        val r = call("GET", "/api/v1/printers")
        if (r.code != 200) throw OrcaCloudException("Could not load printers (HTTP ${r.code})", r.code)
        val data = json.parseToJsonElement(r.body).jsonObject["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val role = p.str("access_role").orEmpty()
            if (role.isEmpty() || role == "viewer") return@mapNotNull null
            val status = (p["status_snapshot"] as? JsonObject)?.get("status") as? JsonObject
            val online = ((status?.get("connection") as? JsonObject)?.str("state")) == "online"
            val job = status?.get("job") as? JsonObject
            val progress = (job?.get("progress") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            CloudPrinter(p.str("id").orEmpty(), p.str("name").orEmpty(), p.str("model").orEmpty(), online, job?.str("state"), progress)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
