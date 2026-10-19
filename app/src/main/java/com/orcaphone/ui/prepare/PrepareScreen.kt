package com.orcaphone.ui.prepare

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import com.orcaphone.data.ThemeMode
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.SwapHoriz
import com.orcaphone.ui.components.BedBar
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcaphone.gl.PrepareScene
import com.orcaphone.gl.ViewportListener
import com.orcaphone.ui.AppViewModel
import com.orcaphone.ui.SliceUiState
import com.orcaphone.ui.components.Viewport3D
import com.orcaphone.ui.components.fmt
import com.orcaphone.ui.settings.EditorTarget
import kotlin.math.cos

enum class PrepareSheet { PRINTER, FILAMENT, PROCESS, QUICK, ROTATE, SCALE, ADD, LIBRARY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareScreen(vm: AppViewModel, onEdit: (EditorTarget) -> Unit) {
    val objects by vm.objects.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val printer by vm.printers.collectAsStateWithLifecycle()
    val filament by vm.filaments.collectAsStateWithLifecycle()
    val process by vm.processes.collectAsStateWithLifecycle()
    val slice by vm.slice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val tick by vm.viewTick.collectAsStateWithLifecycle()
    val beds by vm.beds.collectAsStateWithLifecycle()
    val activeBed by vm.activeBed.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var confirmDeleteBed by remember { mutableStateOf<Int?>(null) }
    var overhang by rememberSaveable { mutableStateOf(false) }
    val dark = com.orcaphone.ui.theme.LocalDarkTheme.current
    var sheet by remember { mutableStateOf<PrepareSheet?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(uris)
    }
    val openImport = { importLauncher.launch(arrayOf("*/*")) }

    val p = printer.working
    val filamentColor = parseColor(filament.working.colorHex) ?: 0x009688
    val scene = remember(objects, selectedId, p, overhang, process.working.supportThresholdAngle, filamentColor) {
        PrepareScene(
            p.bedWidth, p.bedDepth, p.maxHeight, objects, selectedId,
            if (overhang) -cos(Math.toRadians(process.working.supportThresholdAngle)).toFloat() else -2f,
            filamentColor,
        )
    }
    val listener = remember {
        object : ViewportListener {
            override fun onTapObject(id: Long?) = vm.select(id)
            override fun onDragObject(id: Long, dx: Double, dy: Double) = vm.moveObject(id, dx, dy)
            override fun onDoubleTap() = vm.frameContent()
        }
    }
    val selected = objects.firstOrNull { it.id == selectedId }

    Box(Modifier.fillMaxSize()) {
        Viewport3D(vm.camera, scene, null, editable = true, listener = listener, tick = tick, modifier = Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            PresetBar(
                printer = p.name, printerDirty = printer.dirty,
                filament = filament.working.name, filamentDirty = filament.dirty, filamentColor = filamentColor,
                process = process.working.name, processDirty = process.dirty,
                onClick = { sheet = it },
            )
            if (beds.size > 1) {
                BedBar(
                    count = beds.size, active = activeBed, onSelect = { vm.selectBed(it) },
                    chipLabel = { i -> "Bed ${i + 1} · ${beds[i].objects.size}" },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    var bedMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { bedMenu = true }) { Icon(Icons.Outlined.MoreVert, "Bed options") }
                        DropdownMenu(expanded = bedMenu, onDismissRequest = { bedMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Add empty bed") }, leadingIcon = { Icon(Icons.Outlined.AddBox, null) },
                                onClick = { bedMenu = false; vm.addBed() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete bed ${activeBed + 1}") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                onClick = {
                                    bedMenu = false
                                    if (objects.isEmpty()) vm.deleteBed(activeBed) else confirmDeleteBed = activeBed
                                },
                            )
                        }
                    }
                }
            }
        }

        ViewControls(
            overhang = overhang, onOverhang = { overhang = !overhang },
            onView = { y, pch -> vm.setView(y, pch) }, onFrame = { vm.frameContent() },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
        )

        if (objects.isEmpty() && busy == null) {
            if (beds.size > 1) {
                EmptyBed(activeBed + 1, onDelete = { vm.deleteBed(activeBed) }, modifier = Modifier.align(Alignment.Center))
            } else {
                EmptyPlate(
                    onImport = openImport, onLink = { vm.openLinkPrompt() }, onSample = { vm.addSample(it) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected != null) {
                val inside = selected.isInside(p.bedWidth, p.bedDepth, p.maxHeight)
                ObjectPanel(
                    name = selected.name,
                    size = "${fmt(selected.size.x, 1)} × ${fmt(selected.size.y, 1)} × ${fmt(selected.size.z, 1)} mm",
                    outside = !inside,
                    onRotate = { sheet = PrepareSheet.ROTATE },
                    onScale = { sheet = PrepareSheet.SCALE },
                    onLayFlat = { vm.layFlatSelected() },
                    onCenter = { vm.centerSelected() },
                    onCopy = { vm.duplicateSelected() },
                    onDelete = { vm.deleteSelected() },
                    bedCount = beds.size, activeBed = activeBed,
                    onMoveToBed = { vm.moveSelectedToBed(it) },
                )
            }
            ActionBar(
                hasObjects = objects.isNotEmpty(),
                slice = slice,
                onAdd = { sheet = PrepareSheet.ADD },
                onArrange = { vm.arrange() },
                onArrangeBed = if (beds.size > 1) ({ vm.arrangeActiveBed() }) else null,
                onAddBed = { vm.addBed() },
                sliceLabel = if (beds.size > 1) "Slice bed ${activeBed + 1}" else "Slice plate",
                onTune = { sheet = PrepareSheet.QUICK },
                onClear = { vm.clearPlate() },
                onSlice = { vm.startSlice() },
                onCancel = { vm.cancelSlice() },
                dark = dark,
                onToggleDark = { vm.repos.ui.setThemeMode(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
            )
        }

        busy?.let { msg ->
            Card(Modifier.align(Alignment.Center).padding(32.dp)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(msg, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.width(200.dp))
                }
            }
        }
    }

    val s = slice
    if (s is SliceUiState.Failed) {
        AlertDialog(
            onDismissRequest = { vm.dismissSliceError() },
            icon = { Icon(Icons.Outlined.WarningAmber, null) },
            title = { Text("Slicing failed") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.dismissSliceError() }) { Text("OK") } },
        )
    }

    when (sheet) {
        PrepareSheet.PRINTER -> PresetPickerSheet(
            title = "Printer", state = printer, idOf = { it.id }, nameOf = { it.name }, builtinOf = { it.builtin },
            groupOf = { it.vendor }, cloudOf = { it.cloud != null },
            onLibrary = { sheet = PrepareSheet.LIBRARY },
            onSelect = { vm.repos.printers.select(it); vm.frameBed() },
            onEdit = { sheet = null; onEdit(EditorTarget.PRINTER) }, onDismiss = { sheet = null },
        )
        PrepareSheet.FILAMENT -> PresetPickerSheet(
            title = "Filament", state = filament, idOf = { it.id }, nameOf = { it.name }, builtinOf = { it.builtin },
            groupOf = { it.type }, colorOf = { parseColor(it.colorHex) }, cloudOf = { it.cloud != null },
            onSelect = { vm.repos.filaments.select(it) },
            onEdit = { sheet = null; onEdit(EditorTarget.FILAMENT) }, onDismiss = { sheet = null },
        )
        PrepareSheet.PROCESS -> PresetPickerSheet(
            title = "Process", state = process, idOf = { it.id }, nameOf = { it.name }, builtinOf = { it.builtin },
            cloudOf = { it.cloud != null },
            onSelect = { vm.repos.processes.select(it) },
            onEdit = { sheet = null; onEdit(EditorTarget.PROCESS) }, onDismiss = { sheet = null },
        )
        PrepareSheet.QUICK -> QuickSettingsSheet(
            process = process.working, printer = p,
            onChange = { f -> vm.repos.processes.edit(f) },
            onAllSettings = { sheet = null; onEdit(EditorTarget.PROCESS) },
            onDismiss = { sheet = null },
        )
        PrepareSheet.ROTATE -> RotateSheet(
            onRotate = { axis, deg -> vm.rotateSelected(axis, deg) },
            onLayFlat = { vm.layFlatSelected() }, onReset = { vm.resetRotation() },
            onDismiss = { sheet = null },
        )
        PrepareSheet.SCALE -> if (selected != null) {
            ScaleSheet(
                obj = selected,
                onScale = { vm.setSelectedScale(it) },
                onSize = { axis, mm -> vm.setSelectedSize(axis, mm) },
                onDismiss = { sheet = null },
            )
        }
        PrepareSheet.ADD -> AddSheet(
            onImport = { sheet = null; openImport() },
            onLink = { sheet = null; vm.openLinkPrompt() },
            onSample = { sheet = null; vm.addSample(it) },
            onDismiss = { sheet = null },
        )
        PrepareSheet.LIBRARY -> com.orcaphone.ui.cloud.PrinterLibrarySheet(
            cloud = vm.cloud,
            onAdded = { name -> sheet = null; vm.frameBed(); vm.notify("Added $name with its default process and filament") },
            onError = { vm.notify(it) },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    pending?.let { pd ->
        when (pd.step) {
            com.orcaphone.ui.PendingPlacement.Step.REPLACE_OR_ADD -> AlertDialog(
                onDismissRequest = { vm.cancelPlacement() },
                title = { Text("Your plate isn't empty") },
                text = {
                    val n = pd.objects.size
                    Text("Replace everything on the plate with the $n imported object${if (n == 1) "" else "s"}, or add ${if (n == 1) "it" else "them"} to bed ${activeBed + 1}?")
                },
                confirmButton = { TextButton(onClick = { vm.answerReplace(false) }) { Text("Add to plate") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { vm.cancelPlacement() }) { Text("Cancel") }
                        TextButton(onClick = { vm.answerReplace(true) }) { Text("Replace plate") }
                    }
                },
            )
            com.orcaphone.ui.PendingPlacement.Step.OVERSIZE -> AlertDialog(
                onDismissRequest = { vm.cancelPlacement() },
                icon = { Icon(Icons.Outlined.OpenInFull, null) },
                title = { Text("Too big for your printer") },
                text = {
                    val names = vm.oversizeNames(pd)
                    Text(
                        names.take(5).joinToString("\n") { "• $it" } + (if (names.size > 5) "\n• and ${names.size - 5} more" else "") +
                            "\n\n${if (names.size == 1) "This is" else "These are"} larger than the build volume of ${p.name} " +
                            "(${fmt(p.bedWidth, 0)} × ${fmt(p.bedDepth, 0)} × ${fmt(p.maxHeight, 0)} mm). " +
                            "Scale down to fit, or keep the original size and place ${if (names.size == 1) "it" else "each"} on its own bed?",
                    )
                },
                confirmButton = { TextButton(onClick = { vm.answerOversize(true) }) { Text("Scale to fit") } },
                dismissButton = { TextButton(onClick = { vm.answerOversize(false) }) { Text("Keep size") } },
            )
        }
    }

    confirmDeleteBed?.let { i ->
        AlertDialog(
            onDismissRequest = { confirmDeleteBed = null },
            title = { Text("Delete bed ${i + 1}?") },
            text = { Text("The ${beds.getOrNull(i)?.objects?.size ?: 0} objects on this bed will be removed.") },
            confirmButton = { TextButton(onClick = { confirmDeleteBed = null; vm.deleteBed(i) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteBed = null }) { Text("Cancel") } },
        )
    }
}

fun parseColor(hex: String): Int? {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6) return null
    return h.toIntOrNull(16)
}

@Composable
private fun PresetBar(
    printer: String, printerDirty: Boolean,
    filament: String, filamentDirty: Boolean, filamentColor: Int,
    process: String, processDirty: Boolean,
    onClick: (PrepareSheet) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetChip("Printer", printer, printerDirty, Icons.Outlined.Print, null) { onClick(PrepareSheet.PRINTER) }
        PresetChip("Filament", filament, filamentDirty, null, Color(0xFF000000.toInt() or filamentColor)) { onClick(PrepareSheet.FILAMENT) }
        PresetChip("Process", process, processDirty, Icons.Outlined.Layers, null) { onClick(PrepareSheet.PROCESS) }
    }
}

@Composable
private fun PresetChip(label: String, value: String, dirty: Boolean, icon: ImageVector?, swatch: Color?, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        tonalElevation = 2.dp, shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 220.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            if (swatch != null) Box(Modifier.size(18.dp).clip(CircleShape).background(swatch))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value + if (dirty) " *" else "", style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ViewControls(
    overhang: Boolean, onOverhang: () -> Unit, onView: (Double, Double) -> Unit, onFrame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallFloatingActionButton(onClick = { onView(-120.0, 35.0) }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(Icons.Outlined.ViewInAr, "Iso view")
        }
        SmallFloatingActionButton(onClick = { onView(-90.0, 89.0) }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(Icons.Outlined.VerticalAlignTop, "Top view")
        }
        SmallFloatingActionButton(onClick = { onView(-90.0, 8.0) }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(Icons.Outlined.CropFree, "Front view")
        }
        SmallFloatingActionButton(onClick = onFrame, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(Icons.Outlined.CenterFocusStrong, "Zoom to fit")
        }
        SmallFloatingActionButton(
            onClick = onOverhang,
            containerColor = if (overhang) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(Icons.Outlined.WarningAmber, "Highlight overhangs")
        }
    }
}

@Composable
private fun EmptyBed(number: Int, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.padding(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Bed $number is empty", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("Add a model, or move objects here from another bed with Move to bed.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Delete this bed")
            }
        }
    }
}

@Composable
private fun EmptyPlate(onImport: () -> Unit, onLink: () -> Unit, onSample: (AppViewModel.Sample) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.padding(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ViewInAr, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Your plate is empty", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("Import an STL, 3MF or OBJ file, or start with a sample.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import model")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Link, null); Spacer(Modifier.width(8.dp)); Text("Printables link")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onSample(AppViewModel.Sample.CUBE) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Category, null); Spacer(Modifier.width(8.dp)); Text("Add calibration cube")
            }
        }
    }
}

@Composable
private fun ObjectPanel(
    name: String, size: String, outside: Boolean,
    onRotate: () -> Unit, onScale: () -> Unit, onLayFlat: () -> Unit, onCenter: () -> Unit,
    onCopy: () -> Unit, onDelete: () -> Unit,
    bedCount: Int = 1, activeBed: Int = 0, onMoveToBed: (Int?) -> Unit = {},
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (outside) "$size · outside build volume" else "$size · drag to move",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (outside) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                var moveMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { moveMenu = true }) {
                        Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Move to bed")
                    }
                    DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                        for (i in 0 until bedCount) {
                            if (i == activeBed) continue
                            DropdownMenuItem(text = { Text("Bed ${i + 1}") }, onClick = { moveMenu = false; onMoveToBed(i) })
                        }
                        DropdownMenuItem(
                            text = { Text("New bed") }, leadingIcon = { Icon(Icons.Outlined.AddBox, null) },
                            onClick = { moveMenu = false; onMoveToBed(null) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ToolButton(Icons.Outlined.Rotate90DegreesCcw, "Rotate", onRotate)
                ToolButton(Icons.Outlined.OpenInFull, "Scale", onScale)
                ToolButton(Icons.Outlined.VerticalAlignBottom, "Lay flat", onLayFlat)
                ToolButton(Icons.Outlined.CenterFocusStrong, "Center", onCenter)
                ToolButton(Icons.Outlined.ContentCopy, "Copy", onCopy)
                ToolButton(Icons.Outlined.DeleteOutline, "Delete", onDelete)
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionBar(
    hasObjects: Boolean, slice: SliceUiState,
    onAdd: () -> Unit, onArrange: () -> Unit, onTune: () -> Unit, onClear: () -> Unit,
    onSlice: () -> Unit, onCancel: () -> Unit,
    onArrangeBed: (() -> Unit)? = null, onAddBed: () -> Unit = {}, sliceLabel: String = "Slice plate",
    dark: Boolean = false, onToggleDark: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        tonalElevation = 3.dp, shadowElevation = 4.dp,
    ) {
        if (slice is SliceUiState.Running) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(slice.stage, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { slice.progress }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            return@Surface
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add model") }
            var arrangeMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { if (onArrangeBed != null) arrangeMenu = true else onArrange() },
                    enabled = hasObjects || onArrangeBed != null,
                ) { Icon(Icons.Outlined.AutoAwesomeMosaic, "Arrange") }
                DropdownMenu(expanded = arrangeMenu, onDismissRequest = { arrangeMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Arrange all beds") }, leadingIcon = { Icon(Icons.Outlined.AutoAwesomeMosaic, null) },
                        onClick = { arrangeMenu = false; onArrange() },
                    )
                    DropdownMenuItem(
                        text = { Text("Arrange this bed only") }, leadingIcon = { Icon(Icons.Outlined.CropFree, null) },
                        enabled = hasObjects,
                        onClick = { arrangeMenu = false; onArrangeBed?.invoke() },
                    )
                }
            }
            IconButton(onClick = onTune) { Icon(Icons.Outlined.Tune, "Quick settings") }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (dark) "Light mode" else "Dark mode") },
                        leadingIcon = { Icon(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null) },
                        onClick = { menu = false; onToggleDark() },
                    )
                    DropdownMenuItem(
                        text = { Text("Add empty bed") },
                        leadingIcon = { Icon(Icons.Outlined.AddBox, null) },
                        onClick = { menu = false; onAddBed() },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear plate") }, enabled = hasObjects || onArrangeBed != null,
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        onClick = { menu = false; onClear() },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val stale = slice is SliceUiState.Ready && slice.stale
            FilledTonalButton(
                onClick = onSlice, enabled = hasObjects,
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.padding(end = 4.dp).height(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ViewList, null)
                Spacer(Modifier.width(8.dp))
                Text(if (stale) "Re-slice" else sliceLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
