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
import com.orcaphone.core.models.ModelSourceException
import com.orcaphone.core.models.ModelSources
import com.orcaphone.core.models.RemoteFile
import com.orcaphone.core.models.RemoteModel
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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

/** One build plate. Bed numbers shown to the user are list positions + 1; [id] stays stable. */
data class Bed(val id: Long, val objects: List<PlateObject>)

/** Importing a model from a website such as Printables. */
sealed interface RemoteImportState {
    data class Loading(val link: String) : RemoteImportState
    data class Failed(val message: String, val pageUrl: String? = null, val model: RemoteModel? = null) : RemoteImportState
    data class Choose(val model: RemoteModel) : RemoteImportState
    data class Downloading(val model: RemoteModel, val fileName: String, val done: Long, val total: Long) : RemoteImportState
}

/** Imported objects waiting for the user to answer a question before they're placed. */
data class PendingPlacement(val objects: List<PlateObject>, val step: Step, val replace: Boolean = false) {
    enum class Step { REPLACE_OR_ADD, OVERSIZE }
}

data class UploadState(val connectionId: String, val progress: Float, val done: Boolean = false, val error: String? = null)

enum class Tab { PREPARE, PREVIEW, DEVICE }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repos = (app as OrcaPhoneApp).repos
    val printers = repos.printers.state
    val filaments = repos.filaments.state
    val processes = repos.processes.state
    val connections = repos.connections.items
    val cloud = repos.cloud

    val camera = OrbitCamera()
    private val _viewTick = MutableStateFlow(0)
    /** Bumped when the camera is changed programmatically so the viewport redraws. */
    val viewTick: StateFlow<Int> = _viewTick.asStateFlow()

    fun setView(yawDeg: Double, pitchDeg: Double) { camera.setView(yawDeg, pitchDeg); _viewTick.value++ }

    private val nextId = AtomicLong(1)
    private val nextBedId = AtomicLong(2)
    private val _beds = MutableStateFlow(listOf(Bed(1, emptyList())))
    val beds: StateFlow<List<Bed>> = _beds.asStateFlow()
    private val _activeBed = MutableStateFlow(0)
    /** Index into [beds] of the bed shown in Prepare and Preview. */
    val activeBed: StateFlow<Int> = _activeBed.asStateFlow()
    private val _objects = MutableStateFlow<List<PlateObject>>(emptyList())
    /** Objects on the active bed. */
    val objects: StateFlow<List<PlateObject>> = _objects.asStateFlow()
    private val _selected = MutableStateFlow<Long?>(null)
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    private val _tab = MutableStateFlow(Tab.PREPARE)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    /** Slice state per bed id. */
    private val _sliceByBed = MutableStateFlow<Map<Long, SliceUiState>>(emptyMap())
    val sliceByBed: StateFlow<Map<Long, SliceUiState>> = _sliceByBed.asStateFlow()
    private val _slice = MutableStateFlow<SliceUiState>(SliceUiState.Idle)
    /** Slice state of the active bed. */
    val slice: StateFlow<SliceUiState> = _slice.asStateFlow()

    private val _remote = MutableStateFlow<RemoteImportState?>(null)
    val remote: StateFlow<RemoteImportState?> = _remote.asStateFlow()
    private val _pending = MutableStateFlow<PendingPlacement?>(null)
    val pending: StateFlow<PendingPlacement?> = _pending.asStateFlow()
    private var remoteJob: Job? = null

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _statuses = MutableStateFlow<Map<String, Result<HostStatus>>>(emptyMap())
    val statuses: StateFlow<Map<String, Result<HostStatus>>> = _statuses.asStateFlow()
    private val _upload = MutableStateFlow<UploadState?>(null)
    val upload: StateFlow<UploadState?> = _upload.asStateFlow()

    private var sliceJob: Job? = null
    private var slicingBed: Long? = null
    private var cancelToken: CancelToken? = null

    init {
        // A settings change makes every slice result stale; plate edits mark only their bed (see setBeds).
        viewModelScope.launch {
            combine(printers, filaments, processes) { a, b, c -> listOf(a.working, b.working, c.working) }
                .drop(1)
                .collect {
                    _sliceByBed.value = _sliceByBed.value.mapValues { (_, s) ->
                        if (s is SliceUiState.Ready && !s.stale) s.copy(stale = true) else s
                    }
                    refreshSlice()
                }
        }
        frameBed()
    }

    // ------------------------------------------------------------------ beds

    private val activeBedId get() = _beds.value[_activeBed.value].id

    /** Objects on the active bed; assigning replaces them. */
    private var plate: List<PlateObject>
        get() = _beds.value[_activeBed.value].objects
        set(v) {
            val a = _activeBed.value
            setBeds(_beds.value.mapIndexed { i, b -> if (i == a) b.copy(objects = v) else b }, a)
        }

    /** Replaces all beds, marks the slices of changed beds stale and drops results of removed beds. */
    private fun setBeds(newBeds: List<Bed>, active: Int) {
        val list = newBeds.ifEmpty { listOf(Bed(nextBedId.getAndIncrement(), emptyList())) }
        val old = _beds.value.associateBy { it.id }
        val ids = list.map { it.id }.toSet()
        val map = _sliceByBed.value.filterKeys { it in ids }.mapValues { (id, s) ->
            val changed = old[id]?.objects !== list.first { it.id == id }.objects
            if (changed && s is SliceUiState.Ready && !s.stale) s.copy(stale = true) else s
        }
        for (id in _sliceByBed.value.keys - ids) gcodeDir(id).deleteRecursively()
        if (slicingBed != null && slicingBed !in ids) cancelSlice()
        _sliceByBed.value = map
        _beds.value = list
        _activeBed.value = active.coerceIn(0, list.size - 1)
        _objects.value = list[_activeBed.value].objects
        if (_objects.value.none { it.id == _selected.value }) _selected.value = null
        refreshSlice()
    }

    private fun refreshSlice() {
        _slice.value = _sliceByBed.value[activeBedId] ?: SliceUiState.Idle
    }

    private fun setSlice(bedId: Long, state: SliceUiState) {
        _sliceByBed.value = if (state is SliceUiState.Idle) _sliceByBed.value - bedId else _sliceByBed.value + (bedId to state)
        refreshSlice()
    }

    fun selectBed(index: Int) {
        if (index !in _beds.value.indices || index == _activeBed.value) return
        setBeds(_beds.value, index)
        frameContent()
    }

    fun addBed() {
        setBeds(_beds.value + Bed(nextBedId.getAndIncrement(), emptyList()), _beds.value.size)
        frameBed()
    }

    fun deleteBed(index: Int) {
        val list = _beds.value
        if (index !in list.indices) return
        if (list.size == 1) { clearPlate(); return }
        val a = _activeBed.value
        setBeds(list.filterIndexed { i, _ -> i != index }, if (a > index || a == list.size - 1) a - 1 else a)
        frameContent()
    }

    /** Moves the selected object to bed [target] (an index), or to a new bed when null. */
    fun moveSelectedToBed(target: Int?) {
        val id = _selected.value ?: return
        val obj = plate.firstOrNull { it.id == id } ?: return
        val p = printer
        val a = _activeBed.value
        var list = _beds.value.mapIndexed { i, b -> if (i == a) b.copy(objects = b.objects.filter { it.id != id }) else b }
        val dest = target ?: list.size.also { list = list + Bed(nextBedId.getAndIncrement(), emptyList()) }
        val existing = list[dest].objects
        val spot = Arranger.findFreeSpot(existing, obj.size.x, obj.size.y, p.bedWidth, p.bedDepth)
        val moved = obj.movedTo(spot?.first ?: (p.bedWidth / 2), spot?.second ?: (p.bedDepth / 2))
        list = list.mapIndexed { i, b -> if (i == dest) b.copy(objects = b.objects + moved) else b }
        setBeds(list, a)
        say(
            "Moved ${obj.name} to bed ${dest + 1}" +
                if (spot == null && existing.isNotEmpty()) ". It overlaps other objects there, use Arrange." else "",
        )
    }

    /** Packs [objs] onto the active bed, opening new beds right after it for whatever doesn't fit. */
    private fun arrangeIntoActiveBed(objs: List<PlateObject>) {
        val p = printer
        val groups = Arranger.arrangeOnBeds(objs, p.bedWidth, p.bedDepth).ifEmpty { listOf(emptyList()) }
        val a = _activeBed.value
        val list = _beds.value.toMutableList()
        list[a] = list[a].copy(objects = groups[0])
        val extra = groups.drop(1).map { Bed(nextBedId.getAndIncrement(), it) }
        list.addAll(a + 1, extra)
        setBeds(list, a)
        if (extra.isNotEmpty()) {
            say("Not everything fit on bed ${a + 1}, so ${if (extra.size == 1) "a new bed was" else "${extra.size} new beds were"} added")
        }
    }

    private fun say(msg: String) { _messages.tryEmit(msg) }

    fun notify(msg: String) = say(msg)

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
        val objs = plate
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

    // ------------------------------------------------------------------ import from websites

    private val remoteClient get() = repos.remoteModels

    /** True when [text] contains a link we can import from. */
    fun isModelLink(text: String) = ModelSources.resolve(text, remoteClient.sources) != null

    fun isMeteredNetwork() = remoteClient.isMetered()

    /** Looks up the model behind a pasted link and shows its importable files. */
    fun openModelLink(text: String) {
        val found = ModelSources.resolve(text, remoteClient.sources)
        if (found == null) {
            _remote.value = RemoteImportState.Failed(
                "That doesn't look like a Printables model link. It should look like printables.com/model/123456-name.",
            )
            return
        }
        val (source, id) = found
        remoteJob?.cancel()
        _remote.value = RemoteImportState.Loading(text.trim())
        remoteJob = viewModelScope.launch {
            _remote.value = try {
                val model = withContext(Dispatchers.IO) { source.fetchModel(id) }
                if (model.files.isEmpty()) RemoteImportState.Failed("", model.pageUrl, model) else RemoteImportState.Choose(model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelSourceException) {
                RemoteImportState.Failed(e.message ?: "Couldn't load this model")
            } catch (e: Throwable) {
                android.util.Log.e("OrcaPhone", "Model lookup failed", e)
                RemoteImportState.Failed("Couldn't load this model: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Downloads [files] of the model being shown and places them on the plate. */
    fun importRemoteFiles(files: List<RemoteFile>) {
        val model = (_remote.value as? RemoteImportState.Choose)?.model ?: return
        if (files.isEmpty()) return
        val source = remoteClient.sources.firstOrNull { it.name == model.sourceName } ?: return
        val total = files.sumOf { it.sizeBytes }.coerceAtLeast(1)
        remoteJob?.cancel()
        remoteJob = viewModelScope.launch {
            val dir = File(getApplication<Application>().cacheDir, "downloads/${model.id}").apply { mkdirs() }
            val loaded = ArrayList<PlateObject>()
            val failed = ArrayList<String>()
            var before = 0L
            try {
                for (f in files) {
                    _remote.value = RemoteImportState.Downloading(model, f.name, before, total)
                    try {
                        val models = withContext(Dispatchers.IO) {
                            val url = source.downloadUrl(model, f)
                            val dest = File(dir, f.id + "_" + f.name.replace(Regex("[^A-Za-z0-9._-]+"), "_"))
                            var last = 0L
                            remoteClient.download(url, dest, isActive = { isActive }) { n ->
                                val now = System.currentTimeMillis()
                                if (now - last > 100) {
                                    last = now
                                    _remote.value = RemoteImportState.Downloading(model, f.name, before + n, total)
                                }
                            }
                            ensureActive()
                            try { dest.inputStream().buffered().use { MeshLoader.load(f.name, it) } } finally { dest.delete() }
                        }
                        for (m in models) loaded.add(PlateObject.create(nextId.getAndIncrement(), m.name, m.mesh))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ModelSourceException) {
                        failed.add(e.message ?: f.name)
                    } catch (e: Throwable) {
                        if (!isActive) throw CancellationException()
                        android.util.Log.e("OrcaPhone", "Download failed", e)
                        failed.add("${f.name}: ${e.message ?: e.javaClass.simpleName}")
                    }
                    before += f.sizeBytes
                }
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                if (_remote.value is RemoteImportState.Downloading) _remote.value = RemoteImportState.Choose(model)
                throw e
            }
            dir.deleteRecursively()
            if (loaded.isEmpty()) {
                _remote.value = RemoteImportState.Failed(
                    "Nothing could be imported.\n" + failed.joinToString("\n"), model.pageUrl, null,
                )
                return@launch
            }
            _remote.value = null
            if (failed.isNotEmpty()) say("Some files failed: " + failed.joinToString("; "))
            beginPlacement(loaded)
        }
    }

    /** Stops a download (back to the file list) or closes the import screen. */
    fun cancelRemote() {
        val s = _remote.value
        remoteJob?.cancel()
        remoteJob = null
        _remote.value = if (s is RemoteImportState.Downloading) RemoteImportState.Choose(s.model) else null
    }

    /** Closes the import screen, stopping any download. */
    fun closeRemote() {
        remoteJob?.cancel()
        remoteJob = null
        _remote.value = null
    }

    private fun exceedsVolume(o: PlateObject): Boolean {
        val p = printer
        val s = o.size
        return s.x > p.bedWidth + 1e-6 || s.y > p.bedDepth + 1e-6 || s.z > p.maxHeight + 1e-6
    }

    /** Starts placing imported objects, asking first whether to replace the plate and what to do with oversize parts. */
    private fun beginPlacement(objs: List<PlateObject>) {
        val anyOnPlate = _beds.value.any { it.objects.isNotEmpty() }
        _pending.value = when {
            anyOnPlate -> PendingPlacement(objs, PendingPlacement.Step.REPLACE_OR_ADD)
            objs.any(::exceedsVolume) -> PendingPlacement(objs, PendingPlacement.Step.OVERSIZE, replace = true)
            else -> { finishPlacement(objs, replace = true); null }
        }
    }

    fun answerReplace(replace: Boolean) {
        val p = _pending.value ?: return
        _pending.value = if (p.objects.any(::exceedsVolume)) p.copy(step = PendingPlacement.Step.OVERSIZE, replace = replace)
        else { finishPlacement(p.objects, replace); null }
    }

    fun answerOversize(scaleToFit: Boolean) {
        val p = _pending.value ?: return
        _pending.value = null
        val pr = printer
        val objs = if (!scaleToFit) p.objects else p.objects.map { o ->
            if (!exceedsVolume(o)) return@map o
            val s = o.size
            val f = minOf(pr.bedWidth * 0.95 / s.x, pr.bedDepth * 0.95 / s.y, pr.maxHeight / s.z)
            o.scaled(Vec3(o.scale.x * f, o.scale.y * f, o.scale.z * f))
        }
        finishPlacement(objs, p.replace)
    }

    /** Throws the pending import away. */
    fun cancelPlacement() { _pending.value = null }

    /** Names of the pending objects that are larger than the build volume. */
    fun oversizeNames(p: PendingPlacement): List<String> = p.objects.filter(::exceedsVolume).map { it.name }

    private fun finishPlacement(objs: List<PlateObject>, replace: Boolean) {
        val p = printer
        if (replace) {
            cancelSlice()
            val groups = Arranger.arrangeOnBeds(objs, p.bedWidth, p.bedDepth)
            val oldIds = _beds.value.map { it.id }
            setBeds(groups.mapIndexed { i, g -> Bed(oldIds.getOrNull(i) ?: nextBedId.getAndIncrement(), g) }, 0)
            _sliceByBed.value = emptyMap()
            refreshSlice()
            say(
                "Imported ${objs.size} object${if (objs.size == 1) "" else "s"}" +
                    if (groups.size > 1) " onto ${groups.size} beds" else "",
            )
        } else {
            arrangeIntoActiveBed(plate + objs)
        }
        _selected.value = null
        frameContent()
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
        if (plate.isEmpty() && fitted.size == 1) plate = listOf(fitted[0].movedTo(p.bedWidth / 2, p.bedDepth / 2))
        else arrangeIntoActiveBed(plate + fitted)
        if (plate.any { it.id == fitted.last().id }) _selected.value = fitted.last().id
    }

    private fun updateSelected(f: (PlateObject) -> PlateObject) {
        val id = _selected.value ?: return
        plate = plate.map { if (it.id == id) f(it) else it }
    }

    fun moveObject(id: Long, dx: Double, dy: Double) {
        plate = plate.map { if (it.id == id) it.movedTo(it.x + dx, it.y + dy) else it }
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
        val src = plate.firstOrNull { it.id == id } ?: return
        val copies = (1..count).map { src.withId(nextId.getAndIncrement(), src.x, src.y) }
        arrangeIntoActiveBed(plate + copies)
        if (plate.any { it.id == copies.last().id }) _selected.value = copies.last().id
    }

    fun deleteSelected() {
        val id = _selected.value ?: return
        plate = plate.filter { it.id != id }
        _selected.value = null
    }

    /** Removes every object and every extra bed. */
    fun clearPlate() {
        cancelSlice()
        _selected.value = null
        setBeds(listOf(Bed(_beds.value.first().id, emptyList())), 0)
        setSlice(activeBedId, SliceUiState.Idle)
    }

    /** Re-packs every object across all beds, adding or removing beds as needed. */
    fun arrange() {
        val p = printer
        val all = _beds.value.flatMap { it.objects }
        if (all.isEmpty()) return
        val groups = Arranger.arrangeOnBeds(all, p.bedWidth, p.bedDepth)
        val oldIds = _beds.value.map { it.id }
        val list = groups.mapIndexed { i, g -> Bed(oldIds.getOrNull(i) ?: nextBedId.getAndIncrement(), g) }
        val selectedBed = list.indexOfFirst { b -> b.objects.any { it.id == _selected.value } }
        setBeds(list, if (selectedBed >= 0) selectedBed else _activeBed.value)
        if (list.size != oldIds.size) say("Arranged onto ${list.size} bed${if (list.size == 1) "" else "s"}")
    }

    /** Re-packs only the active bed; anything that doesn't fit moves to new beds. */
    fun arrangeActiveBed() = arrangeIntoActiveBed(plate)

    // ------------------------------------------------------------------ slicing

    fun startSlice() {
        val objs = plate
        val bedId = activeBedId
        val bedNo = _activeBed.value + 1
        val multi = _beds.value.size > 1
        if (objs.isEmpty()) { say(if (multi) "Bed $bedNo is empty" else "Add a model first"); return }
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
        slicingBed = bedId
        setSlice(bedId, SliceUiState.Running("Preparing", 0f))
        sliceJob = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    val dir = gcodeDir(bedId).apply { deleteRecursively(); mkdirs() }
                    val tmp = File(dir, "plate.gcode")
                    var lastUpdate = 0L
                    val result = Slicer(p, filament, process, token).slice(objs.map { it.worldMesh() }, tmp) { stage, f ->
                        val now = System.currentTimeMillis()
                        if (!token.isCancelled && (now - lastUpdate > 60 || f >= 1f)) {
                            lastUpdate = now
                            setSlice(bedId, SliceUiState.Running(stage, f))
                        }
                    }
                    setSlice(bedId, SliceUiState.Running("Building preview", 1f))
                    val preview = PreviewGeometry.build(result.layers) { tp, h ->
                        val area = h * (tp.width - h) + PI * h * h / 4
                        minOf(tp.speed, filament.maxVolumetricSpeed / area, p.maxSpeedXY)
                    }
                    val name = gcodeName(objs, if (multi) bedNo else null, process.layerHeight, filament.type, result.stats.timeSeconds)
                    val finalFile = File(dir, name)
                    tmp.renameTo(finalFile)
                    SliceUiState.Ready(
                        SliceResult(result.layers, result.stats, finalFile, result.warnings), preview, name,
                    )
                }
                token.check()
                setSlice(bedId, res)
                if (activeBedId == bedId) _tab.value = Tab.PREVIEW
                else say("Bed $bedNo is sliced")
            } catch (e: CancellationException) {
                if (cancelToken === token && _sliceByBed.value[bedId] is SliceUiState.Running) setSlice(bedId, SliceUiState.Idle)
            } catch (e: OutOfMemoryError) {
                setSlice(bedId, SliceUiState.Failed("Out of memory. Try a simpler model or a larger layer height."))
            } catch (e: Throwable) {
                android.util.Log.e("OrcaPhone", "Slicing failed", e)
                setSlice(bedId, SliceUiState.Failed(describe(e)))
            } finally {
                if (slicingBed == bedId && cancelToken === token) slicingBed = null
            }
        }
    }

    private fun gcodeDir(bedId: Long) = File(getApplication<Application>().cacheDir, "gcode/bed$bedId")

    /** Human-readable error including the exception type and root cause (messages alone can be cryptic). */
    private fun describe(e: Throwable): String {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val head = when (e) {
            is IllegalArgumentException -> e.message ?: "Invalid input"
            else -> "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
        }
        return if (root !== e) "$head\nCaused by ${root.javaClass.simpleName}: ${root.message ?: ""}" else head
    }

    fun cancelSlice() {
        cancelToken?.cancel()
        sliceJob?.cancel()
        sliceJob = null
        val id = slicingBed ?: return
        slicingBed = null
        if (_sliceByBed.value[id] is SliceUiState.Running) setSlice(id, SliceUiState.Idle)
    }

    fun dismissSliceError() {
        if (_slice.value is SliceUiState.Failed) setSlice(activeBedId, SliceUiState.Idle)
    }

    private fun gcodeName(objs: List<PlateObject>, bedNo: Int?, layer: Double, type: String, seconds: Double): String {
        val base = objs.first().name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40).ifBlank { "plate" }
        val more = if (objs.size > 1) "_plus${objs.size - 1}" else ""
        val t = NumFmt.duration(seconds).replace(" ", "").replace(Regex("\\d+s$"), "").ifBlank { "1m" }
        val bed = bedNo?.let { "_bed$it" } ?: ""
        return "${base}${more}${bed}_${NumFmt.format(layer, 2)}mm_${type}_$t.gcode"
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
