package com.orcaphone.data

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

/**
 * Built-in + user presets of one kind with OrcaSlicer-like semantics:
 * edits modify a working copy until saved ("Save" for user presets, "Save as" for any).
 */
class PresetStore<T : Any>(
    private val builtins: List<T>,
    private val serializer: KSerializer<T>,
    private val file: File,
    private val id: (T) -> String,
    private val isBuiltin: (T) -> Boolean,
    private val rename: (T, id: String, name: String) -> T,
) {
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

    fun select(presetId: String) {
        val s = _state.value
        val p = s.all.firstOrNull { id(it) == presetId } ?: return
        update(s.copy(selectedId = presetId, selected = p, working = p, dirty = false))
    }

    fun edit(transform: (T) -> T) {
        val s = _state.value
        val w = transform(s.working)
        update(s.copy(working = w, dirty = w != s.selected))
    }

    fun revert() {
        val s = _state.value
        update(s.copy(working = s.selected, dirty = false))
    }

    /** Overwrites the selected user preset with the working copy. Returns false for built-ins. */
    fun save(): Boolean {
        val s = _state.value
        if (isBuiltin(s.selected)) return false
        val all = s.all.map { if (id(it) == s.selectedId) s.working else it }
        update(s.copy(all = all, selected = s.working, dirty = false))
        return true
    }

    fun saveAs(name: String) {
        val s = _state.value
        val newId = "user-" + UUID.randomUUID().toString().take(8)
        val preset = rename(s.working, newId, name.trim().ifBlank { "My preset" })
        update(PresetState(s.all + preset, newId, preset, preset, false))
    }

    fun delete(presetId: String) {
        val s = _state.value
        val p = s.all.firstOrNull { id(it) == presetId } ?: return
        if (isBuiltin(p)) return
        val all = s.all.filter { id(it) != presetId }
        if (s.selectedId == presetId) {
            val first = all.first()
            update(PresetState(all, id(first), first, first, false))
        } else update(s.copy(all = all))
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
