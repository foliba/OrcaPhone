package com.orcaphone.cloud

import android.content.Context
import android.util.Log
import com.orcaphone.core.config.CloudMeta
import com.orcaphone.core.config.FilamentProfile
import com.orcaphone.core.config.PrinterProfile
import com.orcaphone.core.config.ProcessProfile
import com.orcaphone.core.orca.OrcaCatalog
import com.orcaphone.core.orca.OrcaKind
import com.orcaphone.core.orca.OrcaPresetMapper
import com.orcaphone.data.PresetEvent
import com.orcaphone.data.PresetStore
import com.orcaphone.data.Repositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** What the last sync did, shown in "Sync details" to diagnose sync problems. */
data class SyncReport(
    val time: Long,
    val cursorBefore: Long,
    val cursorAfter: Long,
    val pages: Int,
    val httpCodes: List<Int>,
    val received: Int,
    val printers: Int,
    val filaments: Int,
    val processes: Int,
    val deletes: Int,
    val skipped: List<String>,
    val pushed: Int,
    val error: String?,
    val rawSample: String,
) {
    fun asText(): String = buildString {
        appendLine("OrcaPhone Orca Cloud sync report")
        appendLine("time: ${java.util.Date(time)}")
        appendLine("cursor: $cursorBefore -> $cursorAfter, pages: $pages, http: $httpCodes")
        appendLine("received: $received (printers $printers, filaments $filaments, processes $processes), deletes: $deletes, uploaded: $pushed")
        if (error != null) appendLine("error: $error")
        if (skipped.isNotEmpty()) { appendLine("skipped:"); skipped.forEach { appendLine("  - $it") } }
        appendLine("response sample:")
        append(rawSample)
    }
}

data class CloudState(
    val session: OrcaSession? = null,
    val syncing: Boolean = false,
    val lastSync: Long = 0,
    val lastError: String? = null,
    val syncedPresets: Int = 0,
    val localOnlyPresets: Int = 0,
    val printers: List<CloudPrinter> = emptyList(),
    val autoUpload: Boolean = true,
    val lastReport: SyncReport? = null,
)

/**
 * Keeps OrcaPhone's user presets in sync with Orca Cloud (the account used by OrcaSlicer desktop):
 * printer, filament and process presets are pulled on sign-in / app start / "Sync now", and local
 * saves and deletes of synced presets are uploaded. Also lists the account's cloud printers.
 */
class CloudSync(context: Context, private val repos: Repositories) {
    private companion object {
        const val SYNC_VERSION = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val secure = SecureStore(File(context.filesDir, "orca_cloud_session.bin"))
    private val prefs = context.getSharedPreferences("orca_cloud", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()
    private val catalogMutex = Mutex()
    @Volatile private var catalog: OrcaCatalog? = null

    private val _state = MutableStateFlow(CloudState(autoUpload = prefs.getBoolean("auto_upload", true)))
    val state: StateFlow<CloudState> = _state.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    /** Emits once a sign-in finished (e.g. from the browser redirect) so the login screen can close. */
    private val _loginCompleted = MutableSharedFlow<OrcaSession>(extraBufferCapacity = 1)
    val loginCompleted: SharedFlow<OrcaSession> = _loginCompleted

    val client = OrcaCloudClient { s ->
        secure.write(s?.let { json.encodeToString(OrcaSession.serializer(), it) })
        _state.update { it.copy(session = s) }
    }

    private inner class Kind<T : Any>(
        val kind: OrcaKind,
        val store: PresetStore<T>,
        val convert: (raw: Map<String, String>, id: String, name: String) -> T,
    )

    private val printerKind = Kind(OrcaKind.PRINTER, repos.printers) { raw, id, name -> OrcaPresetMapper.toPrinter(raw, id, name) }
    private val filamentKind = Kind(OrcaKind.FILAMENT, repos.filaments) { raw, id, name -> OrcaPresetMapper.toFilament(raw, id, name) }
    private val processKind = Kind(OrcaKind.PROCESS, repos.processes) { raw, id, name ->
        OrcaPresetMapper.toProcess(raw, id, name, repos.printers.current.nozzleDiameter)
    }
    @Suppress("UNCHECKED_CAST")
    private val kinds: List<Kind<Any>> = listOf(printerKind, filamentKind, processKind).map { it as Kind<Any> }

    init {
        // Sync logic changed in v3: download everything once more (an older version could save a
        // cursor without having imported anything).
        if (prefs.getInt("sync_version", 0) < SYNC_VERSION) {
            prefs.edit().putLong("cursor", 0).putInt("sync_version", SYNC_VERSION).apply()
        }
        val restored = secure.read()?.let { runCatching { json.decodeFromString(OrcaSession.serializer(), it) }.getOrNull() }
        client.restore(restored)
        _state.update { it.copy(session = restored, lastSync = prefs.getLong("last_sync", 0)) }
        for (k in kinds) listen(k)
        refreshCounts()
        if (restored != null) syncNow(quiet = true)
    }

    private fun say(msg: String) { _messages.tryEmit(msg) }

    private fun <T : Any> listen(k: Kind<T>) {
        k.store.listener = { ev ->
            if (client.session != null) scope.launch {
                try {
                    when (ev) {
                        is PresetEvent.Saved -> if (k.store.cloudOf(ev.preset) != null || _state.value.autoUpload) {
                            syncMutex.withLock { push(k, ev.preset) }
                        }
                        is PresetEvent.Deleted -> k.store.cloudOf(ev.preset)?.let { client.delete(it.id) }
                    }
                } catch (e: Exception) {
                    say("Orca Cloud: ${e.message}")
                }
                refreshCounts()
            } else refreshCounts()
        }
    }

    suspend fun catalog(): OrcaCatalog = catalog ?: catalogMutex.withLock {
        catalog ?: withContext(Dispatchers.Default) { OrcaCatalog.load() }.also { catalog = it }
    }

    fun setAutoUpload(on: Boolean) {
        prefs.edit().putBoolean("auto_upload", on).apply()
        _state.update { it.copy(autoUpload = on) }
    }

    // ------------------------------------------------------------------ sign-in

    @Volatile private var pkce: OrcaCloudClient.Pkce? = null
    private var loopback: LoopbackServer? = null

    /** Prepares a sign-in: PKCE values plus the loopback server for browser-based providers. */
    fun beginLogin(): OrcaCloudClient.Pkce {
        val server = loopback ?: LoopbackServer { params -> handleRedirect(params) }.also { loopback = it }
        val port = server.start(OrcaCloudClient.LOOPBACK_PORT)
        return client.newPkce(port).also { pkce = it }
    }

    fun loginConfig(): String = client.loginConfig(pkce ?: beginLogin())

    fun loginPort(): Int = pkce?.port ?: OrcaCloudClient.LOOPBACK_PORT

    fun endLogin() {
        loopback?.stop()
        loopback = null
    }

    private fun handleRedirect(params: Map<String, String>): Boolean {
        val code = params["code"] ?: return false
        val state = params["orca_state"] ?: params["state"]
        return runCatching { completeWithCode(code, state) }.onFailure { say("Sign-in failed: ${it.message}") }.isSuccess
    }

    private fun completeWithCode(code: String, state: String?): OrcaSession {
        val p = pkce ?: throw OrcaCloudException("Sign-in expired, please try again")
        if (state != null && state != p.state) throw OrcaCloudException("Sign-in state mismatch, please try again")
        return client.exchangeCode(code, p).also { onSignedIn(it) }
    }

    /** Handles the login page's "user_login" message: an auth code or an already issued session. */
    suspend fun completeLogin(data: JsonObject): OrcaSession = withContext(Dispatchers.IO) {
        val state = (data["state"] as? JsonPrimitive)?.content ?: (data["orca_state"] as? JsonPrimitive)?.content
        val code = (data["code"] as? JsonPrimitive)?.content
        if (!code.isNullOrBlank()) completeWithCode(code, state)
        else {
            val expected = pkce?.state
            if (expected != null && state != null && state != expected) throw OrcaCloudException("Sign-in state mismatch, please try again")
            client.acceptSession(data).also { onSignedIn(it) }
        }
    }

    private fun onSignedIn(s: OrcaSession) {
        endLogin()
        prefs.edit().putLong("cursor", 0).apply()
        _loginCompleted.tryEmit(s)
        say("Signed in to Orca Cloud as ${s.label}")
        syncNow()
    }

    fun signOut(keepPresets: Boolean = true) {
        scope.launch {
            syncMutex.withLock {
                client.logout()
                if (keepPresets) for (k in kinds) k.store.unlinkCloud()
                prefs.edit().remove("cursor").remove("last_sync").apply()
                _state.update { it.copy(printers = emptyList(), lastSync = 0, lastError = null) }
                refreshCounts()
            }
            say("Signed out of Orca Cloud. Your presets stay on this phone.")
        }
    }

    // ------------------------------------------------------------------ sync

    /** Forgets the sync cursor and downloads every preset again. */
    fun fullResync() {
        prefs.edit().putLong("cursor", 0).apply()
        syncNow()
    }

    private class Tally {
        var printers = 0; var filaments = 0; var processes = 0
        val skipped = ArrayList<String>()
    }

    fun syncNow(quiet: Boolean = false) {
        if (client.session == null) return
        scope.launch {
            if (!syncMutex.tryLock()) return@launch
            _state.update { it.copy(syncing = true, lastError = null) }
            val cursorBefore = prefs.getLong("cursor", 0)
            var cursor = cursorBefore
            var pages = 0
            var received = 0
            var deletes = 0
            var pushed = 0
            val codes = ArrayList<Int>()
            var sample = ""
            val tally = Tally()
            var error: String? = null
            try {
                val cat = catalog()
                // 1. upload local changes first so they are not overwritten by the pull
                for (k in kinds) pushed += pushPending(k)
                // 2. pull cloud changes
                while (pages++ < 20) {
                    val res = client.pull(cursor)
                    codes.add(res.httpCode)
                    if (sample.isEmpty()) sample = res.rawSample
                    if (res.upserts.isNotEmpty() || res.deletes.isNotEmpty()) apply(cat, res, tally)
                    received += res.upserts.size
                    deletes += res.deletes.size
                    if (res.nextCursor == 0L || res.nextCursor == cursor || res.upserts.isEmpty()) {
                        if (res.nextCursor != 0L) cursor = res.nextCursor
                        break
                    }
                    cursor = res.nextCursor
                }
                prefs.edit().putLong("cursor", cursor).putLong("last_sync", System.currentTimeMillis()).apply()
                val printers = runCatching { client.printers() }.getOrElse { _state.value.printers }
                _state.update { it.copy(lastSync = System.currentTimeMillis(), printers = printers) }
                val imported = tally.printers + tally.filaments + tally.processes
                when {
                    imported > 0 && (!quiet || cursorBefore == 0L) -> say(
                        "Orca Cloud: ${tally.printers} printer, ${tally.filaments} filament and ${tally.processes} process preset" +
                            (if (imported == 1) "" else "s") + " synced",
                    )
                    cursorBefore == 0L && received == 0 -> say("Your Orca Cloud account has no synced presets yet. In OrcaSlicer, sign in and save a user preset to sync it.")
                    tally.skipped.isNotEmpty() -> say("Orca Cloud: ${tally.skipped.size} preset(s) could not be imported. See Sync details.")
                    !quiet && deletes > 0 -> say("Orca Cloud: $deletes preset(s) removed")
                }
            } catch (e: Exception) {
                Log.w("OrcaPhone", "Cloud sync failed", e)
                error = e.message ?: e.javaClass.simpleName
                _state.update { it.copy(lastError = error) }
                if (!quiet) say("Orca Cloud sync failed: $error")
            } finally {
                val report = SyncReport(
                    System.currentTimeMillis(), cursorBefore, cursor, pages, codes, received,
                    tally.printers, tally.filaments, tally.processes, deletes, tally.skipped, pushed, error, sample,
                )
                _state.update { it.copy(syncing = false, lastReport = report) }
                refreshCounts()
                syncMutex.unlock()
            }
        }
    }

    private fun apply(cat: OrcaCatalog, res: PullResult, tally: Tally) {
        val deletes = res.deletes.toSet()
        val valid = res.upserts.filter { p ->
            when {
                p.id.isBlank() -> { tally.skipped.add("“${p.name}”: no id"); false }
                p.content.isEmpty() -> { tally.skipped.add("“${p.name.ifBlank { p.id }}”: empty content"); false }
                else -> true
            }
        }
        val byKind = valid.groupBy { OrcaPresetMapper.kindOf(it.content, cat) }
        tally.printers += applyKind(printerKind, cat, byKind[OrcaKind.PRINTER].orEmpty(), deletes, tally)
        tally.filaments += applyKind(filamentKind, cat, byKind[OrcaKind.FILAMENT].orEmpty(), deletes, tally)
        tally.processes += applyKind(processKind, cat, byKind[OrcaKind.PROCESS].orEmpty(), deletes, tally)
    }

    private fun <T : Any> applyKind(k: Kind<T>, cat: OrcaCatalog, ups: List<CloudProfile>, deletes: Set<String>, tally: Tally): Int {
        val presets = ups.mapNotNull { p ->
            runCatching { toLocal(k, cat, p) }
                .onFailure { tally.skipped.add("“${p.name.ifBlank { p.id }}” (${k.kind.iot}): ${it.message ?: it.javaClass.simpleName}") }
                .getOrNull()
        }
        k.store.applyCloud(presets, deletes)
        return presets.size
    }

    private fun <T : Any> toLocal(k: Kind<T>, cat: OrcaCatalog, p: CloudProfile): T {
        val name = p.content["name"]?.let { OrcaPresetMapper.firstString(it) }?.ifBlank { null } ?: p.name.ifBlank { p.id }
        val raw = OrcaPresetMapper.resolve(cat, k.kind, p.content)
        val local = k.convert(raw, "cloud-${p.id}", name)
        return k.store.withCloud(local, CloudMeta(p.id, p.updatedTime, p.content, pendingPush = false))
    }

    private suspend fun <T : Any> pushPending(k: Kind<T>): Int {
        val pending = k.store.state.value.all.filter { k.store.cloudOf(it)?.pendingPush == true }
        var n = 0
        for (p in pending) {
            try {
                push(k, p)
                n++
            } catch (e: OrcaCloudException) {
                if (e.httpCode == 401) throw e
                say(e.message ?: "Upload failed")
            }
        }
        return n
    }

    /** Uploads one user preset (creates it in the cloud if it is not linked yet). */
    private suspend fun <T : Any> push(k: Kind<T>, preset: T) {
        val s = client.session ?: return
        if (k.store.isBuiltin(preset)) return
        val cat = catalog()
        val meta = k.store.cloudOf(preset)
        val name = k.store.nameOf(preset)
        val localId = k.store.id(preset)
        val cloudId = meta?.id ?: OrcaPresetMapper.settingId(name, s.userId)
        val imported = meta?.let { k.convert(OrcaPresetMapper.resolve(cat, k.kind, it.content), localId, name) }
        val content = OrcaPresetMapper.buildContent(k.kind, name, preset, imported, meta?.content ?: emptyMap())
        val r = client.push(cloudId, name, content, meta?.updatedTime)
        when {
            r.ok -> k.store.replaceUserPreset(localId) { k.store.withCloud(it, CloudMeta(cloudId, r.updatedTime, content, pendingPush = false)) }
            r.httpCode == 409 && meta == null && r.conflictCode == -2 && r.serverProfile != null -> {
                // A preset with this name already exists in the account (created earlier): take the
                // cloud version and keep the phone's settings as a separate local copy.
                keepCopyAndTakeServer(k, cat, preset, localId, r.serverProfile, linkInPlace = false)
            }
            r.httpCode == 409 && r.serverProfile != null -> keepCopyAndTakeServer(k, cat, preset, localId, r.serverProfile, linkInPlace = true)
            r.httpCode == 409 -> {
                // deleted in the cloud meanwhile: keep it as a local preset
                k.store.replaceUserPreset(localId) { k.store.withCloud(it, null) }
                say("“$name” was deleted in Orca Cloud; kept it on this phone only")
            }
            else -> {
                if (meta != null) k.store.replaceUserPreset(localId) { k.store.withCloud(it, meta.copy(pendingPush = true)) }
                throw OrcaCloudException("Could not upload “$name”: ${r.message.ifBlank { "HTTP ${r.httpCode}" }}", r.httpCode)
            }
        }
    }

    private fun <T : Any> keepCopyAndTakeServer(
        k: Kind<T>, cat: OrcaCatalog, local: T, localId: String, server: CloudProfile, linkInPlace: Boolean,
    ) {
        val copyName = k.store.nameOf(local) + " (phone copy)"
        val copy = k.store.withCloud(
            k.convert(emptyMap(), "user-${System.nanoTime().toString(36)}", copyName), null,
        )
        // Rebuild the copy from the local preset's fields under the new identity.
        val copyFull = copyFields(k, local, copy)
        k.store.addUserPresets(listOf(copyFull), selectFirst = false)
        val serverLocal = toLocal(k, cat, server)
        if (linkInPlace) k.store.replaceUserPreset(localId) { serverLocal }
        else {
            k.store.replaceUserPreset(localId) { k.store.withCloud(it, null) }
            k.store.applyCloud(listOf(serverLocal), emptySet())
        }
        say("“${k.store.nameOf(local)}” changed in Orca Cloud too. Took the cloud version; your phone version was saved as “$copyName”.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> copyFields(k: Kind<T>, from: T, identity: T): T = when (k.kind) {
        OrcaKind.PRINTER -> (from as PrinterProfile).copy(id = (identity as PrinterProfile).id, name = identity.name, builtin = false, cloud = null) as T
        OrcaKind.FILAMENT -> (from as FilamentProfile).copy(id = (identity as FilamentProfile).id, name = identity.name, builtin = false, cloud = null) as T
        OrcaKind.PROCESS -> (from as ProcessProfile).copy(id = (identity as ProcessProfile).id, name = identity.name, builtin = false, cloud = null) as T
    }

    /** Uploads all local user presets that are not in the cloud yet. */
    fun uploadLocalPresets() {
        if (client.session == null) return
        scope.launch {
            syncMutex.withLock {
                var n = 0
                try {
                    for (k in kinds) n += uploadLocal(k)
                    say("Uploaded $n preset${if (n == 1) "" else "s"} to Orca Cloud")
                } catch (e: Exception) {
                    say("Upload failed: ${e.message}")
                }
            }
            refreshCounts()
        }
    }

    private suspend fun <T : Any> uploadLocal(k: Kind<T>): Int {
        val local = k.store.state.value.all.filter { !k.store.isBuiltin(it) && k.store.cloudOf(it) == null }
        for (p in local) push(k, p)
        return local.size
    }

    fun refreshPrinters() {
        if (client.session == null) return
        scope.launch {
            runCatching { client.printers() }.onSuccess { list -> _state.update { it.copy(printers = list) } }
        }
    }

    private fun refreshCounts() {
        var synced = 0
        var local = 0
        for (k in kinds) {
            for (p in k.store.state.value.all) {
                if (k.store.isBuiltin(p)) continue
                if (k.store.cloudOf(p) != null) synced++ else local++
            }
        }
        _state.update { it.copy(syncedPresets = synced, localOnlyPresets = local) }
    }

    // ------------------------------------------------------------------ OrcaSlicer library

    /**
     * Adds an OrcaSlicer system printer as a local preset, together with its default process and
     * filament presets, and selects all three.
     */
    suspend fun addFromLibrary(printerName: String): PrinterProfile = withContext(Dispatchers.Default) {
        val cat = catalog()
        val raw = cat.get(OrcaKind.PRINTER, printerName) ?: throw IllegalArgumentException("Unknown printer $printerName")
        val printer = OrcaPresetMapper.toPrinter(raw, "lib-" + OrcaPresetMapper.settingId(printerName, ""), printerName)
        repos.printers.addUserPresets(listOf(printer), selectFirst = true)
        raw["default_print_profile"]?.let { n ->
            cat.get(OrcaKind.PROCESS, n)?.let { pr ->
                repos.processes.addUserPresets(listOf(OrcaPresetMapper.toProcess(pr, "lib-" + OrcaPresetMapper.settingId(n, ""), n, printer.nozzleDiameter)), selectFirst = true)
            }
        }
        raw["default_filament_profile"]?.let { OrcaPresetMapper.firstString(it) }?.let { n ->
            cat.get(OrcaKind.FILAMENT, n)?.let { fr ->
                repos.filaments.addUserPresets(listOf(OrcaPresetMapper.toFilament(fr, "lib-" + OrcaPresetMapper.settingId(n, ""), n)), selectFirst = true)
            }
        }
        printer
    }
}
