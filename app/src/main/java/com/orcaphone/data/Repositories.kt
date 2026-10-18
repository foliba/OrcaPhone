package com.orcaphone.data

import android.content.Context
import com.orcaphone.core.config.BuiltinProfiles
import com.orcaphone.core.config.FilamentProfile
import com.orcaphone.core.config.PrinterConnection
import com.orcaphone.core.config.PrinterProfile
import com.orcaphone.core.config.ProcessProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** App-wide singletons (simple manual DI). */
class Repositories(context: Context) {
    private val dir = File(context.filesDir, "profiles").apply { mkdirs() }

    val printers = PresetStore(
        BuiltinProfiles.printers, PrinterProfile.serializer(), File(dir, "printers.json"),
        id = { it.id }, isBuiltin = { it.builtin }, rename = { p, id, name -> p.copy(id = id, name = name, builtin = false) },
    )
    val filaments = PresetStore(
        BuiltinProfiles.filaments, FilamentProfile.serializer(), File(dir, "filaments.json"),
        id = { it.id }, isBuiltin = { it.builtin }, rename = { p, id, name -> p.copy(id = id, name = name, builtin = false) },
    )
    val processes = PresetStore(
        BuiltinProfiles.processes, ProcessProfile.serializer(), File(dir, "processes.json"),
        id = { it.id }, isBuiltin = { it.builtin }, rename = { p, id, name -> p.copy(id = id, name = name, builtin = false) },
    )
    val connections = ConnectionStore(File(dir, "connections.json"))
    val hostClient = PrinterHostClient()
}

class ConnectionStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(PrinterConnection.serializer())
    private val _items = MutableStateFlow(
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()),
    )
    val items: StateFlow<List<PrinterConnection>> = _items.asStateFlow()

    fun upsert(c: PrinterConnection) {
        val list = _items.value
        _items.value = if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else list + c
        persist()
    }

    fun delete(id: String) {
        _items.value = _items.value.filter { it.id != id }
        persist()
    }

    private fun persist() {
        runCatching { file.writeText(json.encodeToString(serializer, _items.value)) }
    }
}
