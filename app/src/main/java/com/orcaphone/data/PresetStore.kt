package com.orcaphone.data

import com.orcaphone.core.config.CloudMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
class PresetFile<T>(
    val user: List<T> = emptyList(),
    val selectedId: String? = null,
    val working: T? = null,
)

data class PresetState<T>(
    val all: List<T>,
    val selectedId: String,
    /** The saved preset that is selected. */
    val selected: T,
    /** The settings currently in use (selected preset plus unsaved edits). */
    val working: T,
    val dirty: Boolean,
)

/** User preset changes the cloud sync reacts to. */
sealed interface PresetEvent<T> {
    data class Saved<T>(val preset: T) : PresetEvent<T>
    data class Deleted<T>(val preset: T) : PresetEvent<T>
}

/**
 * Built-in + user presets of one kind with OrcaSlicer-like semantics:
 * edits modify a working copy until saved ("Save" for user presets, "Save as" for any).
 * User presets may be linked to Orca Cloud ([cloudOf]); those are kept in sync by CloudSync.
 */
class PresetStore<T : Any>(
    private val builtins: List<T>,
    private val serializer: KSerializer<T>,
    private val file: File,
    val id: (T) -> String,
    val isBuiltin: (T) -> Boolean,
    /** Copy with a new id and name, as a local user preset (drops any cloud link). */
    private val rename: (T, id: String, name: String) -> T,
    val nameOf: (T) -> String,
    val cloudOf: (T) -> CloudMeta?,
    val withCloud: (T, CloudMeta?) -> T,
) {
    /** Called after a user preset was saved or deleted locally. */
    @Volatile
    var listener: ((PresetEvent<T>) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val fileSerializer = PresetFile.serializer(serializer)
    private val _state: MutableStateFlow<PresetState<T>>
    val state: StateFlow<PresetState<T>>

    init {
        val stored = runCatching { json.decodeFromString(fileSerializer, file.readText()) }.getOrNull()
        val all = builtins + (stored?.user ?: emptyList())
        val sel = stored?.selectedId?.takeIf { s -> all.any { id(it) == s } } ?: id(builtins.first())
        val base = all.first { id(it) == sel }
        val working = stored?.working?.takeIf { id(it) == sel } ?: base
        _state = MutableStateFlow(PresetState(all, sel, base, working, working != base))
        state = _state.asStateFlow()
    }

    val current: T get() = _state.value.working

    @Synchronized
    fun select(presetId: String) {
        val s = _state.value
        val p = s.all.firstOrNull { id(it) == presetId } ?: return
        update(s.copy(selectedId = presetId, selected = p, working = p, dirty = false))
    }

    @Synchronized
    fun edit(transform: (T) -> T) {
        val s = _state.value
        val w = transform(s.working)
        update(s.copy(working = w, dirty = w != s.selected))
    }

    @Synchronized
    fun revert() {
        val s = _state.value
        update(s.copy(working = s.selected, dirty = false))
    }

    /** Overwrites the selected user preset with the working copy. Returns false for built-ins. */
    fun save(): Boolean {
        val saved: T
        synchronized(this) {
            val s = _state.value
            if (isBuiltin(s.selected)) return false
            // a cloud-linked preset now has local changes to upload
            saved = cloudOf(s.working)?.let { withCloud(s.working, it.copy(pendingPush = true)) } ?: s.working
            val all = s.all.map { if (id(it) == s.selectedId) saved else it }
            update(s.copy(all = all, selected = saved, working = saved, dirty = false))
        }
        listener?.invoke(PresetEvent.Saved(saved))
        return true
    }

    fun saveAs(name: String) {
        val preset: T
        synchronized(this) {
            val s = _state.value
            val newId = "user-" + UUID.randomUUID().toString().take(8)
            preset = rename(s.working, newId, name.trim().ifBlank { "My preset" })
            update(PresetState(s.all + preset, newId, preset, preset, false))
        }
        listener?.invoke(PresetEvent.Saved(preset))
    }

    fun delete(presetId: String) {
        val p: T
        synchronized(this) {
            val s = _state.value
            p = s.all.firstOrNull { id(it) == presetId } ?: return
            if (isBuiltin(p)) return
            val all = s.all.filter { id(it) != presetId }
            if (s.selectedId == presetId) {
                val first = all.first()
                update(PresetState(all, id(first), first, first, false))
            } else update(s.copy(all = all))
        }
        listener?.invoke(PresetEvent.Deleted(p))
    }

    /** Adds local user presets (e.g. from the OrcaSlicer library) and optionally selects the first. */
    @Synchronized
    fun addUserPresets(presets: List<T>, selectFirst: Boolean) {
        if (presets.isEmpty()) return
        val s = _state.value
        val ids = presets.map(id).toSet()
        val all = s.all.filter { id(it) !in ids } + presets
        val next = s.copy(all = all)
        update(if (selectFirst) next.copy(selectedId = id(presets[0]), selected = presets[0], working = presets[0], dirty = false) else next)
    }

    /** Replaces a stored user preset (matched by id) without touching unsaved edits of other presets. */
    @Synchronized
    fun replaceUserPreset(presetId: String, transform: (T) -> T) {
        val s = _state.value
        val old = s.all.firstOrNull { id(it) == presetId } ?: return
        val new = transform(old)
        val all = s.all.map { if (id(it) == presetId) new else it }
        update(
            if (s.selectedId == presetId) {
                s.copy(all = all, selected = new, working = if (s.dirty) s.working else new)
            } else s.copy(all = all),
        )
    }

    /**
     * Applies presets downloaded from Orca Cloud: [incoming] replace local copies with the same cloud id
     * (or are added), [deletedCloudIds] are removed. Presets with local changes not yet uploaded are kept.
     */
    @Synchronized
    fun applyCloud(incoming: List<T>, deletedCloudIds: Set<String>) {
        val s = _state.value
        val byCloud = HashMap<String, T>()
        for (p in s.all) cloudOf(p)?.let { byCloud[it.id] = p }
        val all = s.all.toMutableList()
        for (p in incoming) {
            val cid = cloudOf(p)?.id ?: continue
            val local = byCloud[cid]
            if (local != null) {
                if (cloudOf(local)?.pendingPush == true) continue
                val idx = all.indexOfFirst { id(it) == id(local) }
                all[idx] = p
            } else all.add(p)
        }
        all.removeAll { p -> cloudOf(p)?.let { it.id in deletedCloudIds && !it.pendingPush } == true }
        val selected = all.firstOrNull { id(it) == s.selectedId }
        update(
            if (selected == null) {
                val first = all.first()
                PresetState(all, id(first), first, first, false)
            } else {
                s.copy(all = all, selected = selected, working = if (s.dirty) s.working else selected)
            },
        )
    }

    /** Removes the cloud link from all presets (after signing out they stay as local presets). */
    @Synchronized
    fun unlinkCloud() {
        val s = _state.value
        val all = s.all.map { if (cloudOf(it) != null) withCloud(it, null) else it }
        val sel = all.first { id(it) == s.selectedId }
        update(s.copy(all = all, selected = sel, working = if (s.dirty) withCloud(s.working, null) else sel))
    }

    private fun update(s: PresetState<T>) {
        _state.value = s
        persist(s)
    }

    private fun persist(s: PresetState<T>) {
        runCatching {
            val data = PresetFile(s.all.filter { !isBuiltin(it) }, s.selectedId, if (s.dirty) s.working else null)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(fileSerializer, data))
            tmp.renameTo(file)
        }
    }
}
