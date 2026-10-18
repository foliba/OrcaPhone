package com.orcaphone.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orcaphone.OrcaPhoneApp
import com.orcaphone.core.config.PrinterConnection
import com.orcaphone.core.gcode.NumFmt
import com.orcaphone.core.math.Mat4
import com.orcaphone.core.math.Vec3
import com.orcaphone.core.mesh.MeshLoader
import com.orcaphone.core.mesh.Primitives
import com.orcaphone.core.plate.Arranger
import com.orcaphone.core.plate.PlateObject
import com.orcaphone.core.preview.PreviewGeometry
import com.orcaphone.core.slice.CancelToken
import com.orcaphone.core.slice.SliceResult
import com.orcaphone.core.slice.Slicer
import com.orcaphone.data.HostStatus
import com.orcaphone.data.JobCommand
import com.orcaphone.gl.OrbitCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface SliceUiState {
    data object Idle : SliceUiState
    data class Running(val stage: String, val progress: Float) : SliceUiState
    data class Ready(
        val result: SliceResult,
        val preview: PreviewGeometry,
        val fileName: String,
        val stale: Boolean = false,
    ) : SliceUiState
    data class Failed(val message: String) : SliceUiState
}

data class UploadState(val connectionId: String, val progress: Float, val done: Boolean = false, val error: String? = null)

enum class Tab { PREPARE, PREVIEW, DEVICE }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repos = (app as OrcaPhoneApp).repos
    val printers = repos.printers.state
    val filaments = repos.filaments.state
    val processes = repos.processes.state
    val connections = repos.connections.items

    val camera = OrbitCamera()
    private val _viewTick = MutableStateFlow(0)
    /** Bumped when the camera is changed programmatically so the viewport redraws. */
    val viewTick: StateFlow<Int> = _viewTick.asStateFlow()

    fun setView(yawDeg: Double, pitchDeg: Double) { camera.setView(yawDeg, pitchDeg); _viewTick.value++ }

    private val nextId = AtomicLong(1)
    private val _objects = MutableStateFlow<List<PlateObject>>(emptyList())
    val objects: StateFlow<List<PlateObject>> = _objects.asStateFlow()
    private val _selected = MutableStateFlow<Long?>(null)
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    private val _tab = MutableStateFlow(Tab.PREPARE)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _slice = MutableStateFlow<SliceUiState>(SliceUiState.Idle)
    val slice: StateFlow<SliceUiState> = _slice.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _statuses = MutableStateFlow<Map<String, Result<HostStatus>>>(emptyMap())
    val statuses: StateFlow<Map<String, Result<HostStatus>>> = _statuses.asStateFlow()
    private val _upload = MutableStateFlow<UploadState?>(null)
    val upload: StateFlow<UploadState?> = _upload.asStateFlow()

    private var sliceJob: Job? = null
    private var cancelToken: CancelToken? = null

    init {
        // Any change to the plate or the settings makes an existing slice result stale.
        viewModelScope.launch {
            combine(_objects, printers, filaments, processes) { o, a, b, c -> listOf(o, a.working, b.working, c.working) }
                .drop(1)
                .collect {
                    val s = _slice.value
                    if (s is SliceUiState.Ready && !s.stale) _slice.value = s.copy(stale = true)
                }
        }
        frameBed()
    }

    private fun say(msg: String) { _messages.tryEmit(msg) }

    fun setTab(t: Tab) { _tab.value = t }

    // ------------------------------------------------------------------ plate

    private val printer get() = printers.value.working

    fun frameBed() {
        val p = printer
        camera.frame(p.bedWidth / 2, p.bedDepth / 2, 0.0, max(p.bedWidth, p.bedDepth) * 0.62)
        _viewTick.value++
    }

    /** Frames the objects on the plate (or the sliced print), falling back to the bed. */
    fun frameContent() {
        val objs = _objects.value
        if (objs.isEmpty()) { frameBed(); return }
        var b = objs.first().worldBounds
        for (o in objs.drop(1)) b = b.union(o.worldBounds)
        val r = kotlin.math.sqrt(b.sizeX * b.sizeX + b.sizeY * b.sizeY + b.sizeZ * b.sizeZ) / 2
        camera.frame(b.centerX, b.centerY, b.centerZ, max(r, 20.0))
        _viewTick.value++
    }

    fun select(id: Long?) { _selected.value = id }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = "Loading model…"
            val cr = getApplication<Application>().contentResolver
            val loaded = ArrayList<PlateObject>()
            for (uri in uris) {
                try {
                    val name = displayName(uri)
                    val models = withContext(Dispatchers.IO) {
                        cr.openInputStream(uri)?.use { MeshLoader.load(name, it) } ?: emptyList()
                    }
                    for (m in models) loaded.add(PlateObject.create(nextId.getAndIncrement(), m.name, m.mesh))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    say("Could not open ${displayName(uri)}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            _busy.value = null
            addObjects(loaded)
        }
    }

    private fun displayName(uri: Uri): String {
        val cr = getApplication<Application>().contentResolver
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.stl"
    }

    enum class Sample(val label: String) { CUBE("Calibration cube"), CYLINDER("Cylinder"), SPHERE("Sphere (overhang test)") }

    fun addSample(s: Sample) {
        val mesh = when (s) {
            Sample.CUBE -> Primitives.box(20.0, 20.0, 20.0)
            Sample.CYLINDER -> Primitives.cylinder(15.0, 30.0)
            Sample.SPHERE -> Primitives.sphere(20.0)
        }
        addObjects(listOf(PlateObject.create(nextId.getAndIncrement(), s.label, mesh)))
    }

    private fun addObjects(newOnes: List<PlateObject>) {
        if (newOnes.isEmpty()) return
        val p = printer
        val fitted = newOnes.map { o ->
            val s = o.size
            val factor = minOf(p.bedWidth * 0.95 / s.x, p.bedDepth * 0.95 / s.y, p.maxHeight / s.z)
            when {
                factor < 1.0 -> {
                    say("${o.name} was larger than the build volume and was scaled to ${(factor * 100).roundToInt()}%")
                    o.scaled(Vec3(factor, factor, factor))
                }
                maxOf(s.x, s.y, s.z) < 2.0 -> {
                    say("${o.name} is tiny (${NumFmt.format(maxOf(s.x, s.y, s.z), 2)} mm). If it was exported in inches or metres, use Scale.")
                    o
                }
                else -> o
            }
        }
        val all = _objects.value + fitted
        _objects.value = if (_objects.value.isEmpty() && fitted.size == 1) {
            listOf(fitted[0].movedTo(p.bedWidth / 2, p.bedDepth / 2))
        } else Arranger.arrange(all, p.bedWidth, p.bedDepth)
        _selected.value = fitted.last().id
    }

    private fun updateSelected(f: (PlateObject) -> PlateObject) {
        val id = _selected.value ?: return
        _objects.value = _objects.value.map { if (it.id == id) f(it) else it }
    }

    fun moveObject(id: Long, dx: Double, dy: Double) {
        _objects.value = _objects.value.map { if (it.id == id) it.movedTo(it.x + dx, it.y + dy) else it }
    }

    fun rotateSelected(axis: Char, degrees: Double) = updateSelected { o ->
        val r = degrees * PI / 180
        o.rotated(
            when (axis) {
                'x' -> Mat4.rotationX(r)
                'y' -> Mat4.rotationY(r)
                else -> Mat4.rotationZ(r)
            },
        )
    }

    fun resetRotation() = updateSelected { it.withRotation(Mat4.identity()) }

    fun layFlatSelected() = updateSelected { it.layFlat() }

    fun setSelectedScale(scale: Vec3) = updateSelected {
        it.scaled(Vec3(scale.x.coerceIn(0.001, 1000.0), scale.y.coerceIn(0.001, 1000.0), scale.z.coerceIn(0.001, 1000.0)))
    }

    fun setSelectedSize(axis: Int, mm: Double) = updateSelected { o ->
        val size = o.size
        val cur = when (axis) { 0 -> size.x; 1 -> size.y; else -> size.z }
        if (cur <= 1e-6 || mm <= 0) return@updateSelected o
        val f = mm / cur
        o.scaled(Vec3(o.scale.x * f, o.scale.y * f, o.scale.z * f))
    }

    fun centerSelected() = updateSelected { it.movedTo(printer.bedWidth / 2, printer.bedDepth / 2) }

    fun duplicateSelected(count: Int = 1) {
        val id = _selected.value ?: return
        val src = _objects.value.firstOrNull { it.id == id } ?: return
        val copies = (1..count).map { src.withId(nextId.getAndIncrement(), src.x, src.y) }
        _objects.value = Arranger.arrange(_objects.value + copies, printer.bedWidth, printer.bedDepth)
        _selected.value = copies.last().id
    }

    fun deleteSelected() {
        val id = _selected.value ?: return
        _objects.value = _objects.value.filter { it.id != id }
        _selected.value = null
    }

    fun clearPlate() {
        _objects.value = emptyList()
        _selected.value = null
        cancelSlice()
        _slice.value = SliceUiState.Idle
    }

    fun arrange() {
        _objects.value = Arranger.arrange(_objects.value, printer.bedWidth, printer.bedDepth)
    }

    // ------------------------------------------------------------------ slicing

    fun startSlice() {
        val objs = _objects.value
        if (objs.isEmpty()) { say("Add a model first"); return }
        val p = printer
        if (objs.any { !it.isInside(p.bedWidth, p.bedDepth, p.maxHeight) }) {
            say("Some objects are outside the build volume (shown in red). Use Arrange or move them.")
            return
        }
        cancelSlice()
        val filament = filaments.value.working
        val process = processes.value.working
        val token = CancelToken()
        cancelToken = token
        _slice.value = SliceUiState.Running("Preparing", 0f)
        sliceJob = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    val dir = File(getApplication<Application>().cacheDir, "gcode").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val tmp = File(dir, "plate.gcode")
                    var lastUpdate = 0L
                    val result = Slicer(p, filament, process, token).slice(objs.map { it.worldMesh() }, tmp) { stage, f ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 60 || f >= 1f) {
                            lastUpdate = now
                            _slice.value = SliceUiState.Running(stage, f)
                        }
                    }
                    _slice.value = SliceUiState.Running("Building preview", 1f)
                    val preview = PreviewGeometry.build(result.layers) { tp, h ->
                        val area = h * (tp.width - h) + PI * h * h / 4
                        minOf(tp.speed, filament.maxVolumetricSpeed / area, p.maxSpeedXY)
                    }
                    val name = gcodeName(objs, process.layerHeight, filament.type, result.stats.timeSeconds)
                    val finalFile = File(dir, name)
                    tmp.renameTo(finalFile)
                    SliceUiState.Ready(
                        SliceResult(result.layers, result.stats, finalFile, result.warnings), preview, name,
                    )
                }
                _slice.value = res
                _tab.value = Tab.PREVIEW
            } catch (e: CancellationException) {
                if (_slice.value is SliceUiState.Running) _slice.value = SliceUiState.Idle
            } catch (e: OutOfMemoryError) {
                _slice.value = SliceUiState.Failed("Out of memory. Try a simpler model or a larger layer height.")
            } catch (e: Throwable) {
                _slice.value = SliceUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelSlice() {
        cancelToken?.cancel()
        sliceJob?.cancel()
        sliceJob = null
        if (_slice.value is SliceUiState.Running) _slice.value = SliceUiState.Idle
    }

    fun dismissSliceError() {
        if (_slice.value is SliceUiState.Failed) _slice.value = SliceUiState.Idle
    }

    private fun gcodeName(objs: List<PlateObject>, layer: Double, type: String, seconds: Double): String {
        val base = objs.first().name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40).ifBlank { "plate" }
        val more = if (objs.size > 1) "_plus${objs.size - 1}" else ""
        val t = NumFmt.duration(seconds).replace(" ", "").replace(Regex("\\d+s$"), "").ifBlank { "1m" }
        return "${base}${more}_${NumFmt.format(layer, 2)}mm_${type}_$t.gcode"
    }

    // ------------------------------------------------------------------ export / device

    val readyResult: SliceUiState.Ready? get() = _slice.value as? SliceUiState.Ready

    fun exportTo(uri: Uri) {
        val r = readyResult ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        r.result.gcodeFile.inputStream().use { it.copyTo(out, 1 shl 16) }
                    } ?: error("Cannot write file")
                }
                say("G-code saved")
            } catch (e: Throwable) {
                say("Save failed: ${e.message}")
            }
        }
    }

    fun shareIntent(): Intent? {
        val r = readyResult ?: return null
        val ctx = getApplication<Application>()
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", r.result.gcodeFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, r.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share G-code")
    }

    fun saveConnection(c: PrinterConnection) = repos.connections.upsert(c)
    fun deleteConnection(id: String) = repos.connections.delete(id)
    fun newConnectionId() = UUID.randomUUID().toString()

    suspend fun testConnection(c: PrinterConnection): Result<HostStatus> =
        runCatching { repos.hostClient.status(c) }

    fun refreshStatuses() {
        val list = connections.value
        viewModelScope.launch {
            val map = HashMap<String, Result<HostStatus>>()
            for (c in list) map[c.id] = runCatching { repos.hostClient.status(c) }
            _statuses.value = map
        }
    }

    fun sendToPrinter(c: PrinterConnection, startPrint: Boolean) {
        val r = readyResult ?: run { say("Slice the plate first"); return }
        if (_upload.value?.done == false) return
        _upload.value = UploadState(c.id, 0f)
        viewModelScope.launch {
            try {
                var last = 0L
                repos.hostClient.upload(c, r.result.gcodeFile, r.fileName, startPrint) { f ->
                    val now = System.currentTimeMillis()
                    if (now - last > 100) { last = now; _upload.value = UploadState(c.id, f) }
                }
                _upload.value = UploadState(c.id, 1f, done = true)
                say(if (startPrint) "Sent to ${c.name}, print starting" else "Uploaded to ${c.name}")
                refreshStatuses()
            } catch (e: Throwable) {
                _upload.value = UploadState(c.id, 0f, done = true, error = e.message)
                say("Upload failed: ${e.message}")
            }
        }
    }

    fun clearUpload() { _upload.value = null }

    fun jobCommand(c: PrinterConnection, cmd: JobCommand) {
        viewModelScope.launch {
            try {
                repos.hostClient.command(c, cmd, _statuses.value[c.id]?.getOrNull())
                refreshStatuses()
            } catch (e: Throwable) {
                say("${cmd.name.lowercase().replaceFirstChar { it.uppercase() }} failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        cancelToken?.cancel()
        super.onCleared()
    }

    companion object {
        fun clampLayer(v: Int, count: Int) = min(max(v, 0), max(0, count - 1))
    }
}
