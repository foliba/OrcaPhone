package com.orcaphone.ui.preview

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcaphone.core.config.PrinterConnection
import com.orcaphone.core.gcode.NumFmt
import com.orcaphone.core.slice.Feature
import com.orcaphone.gl.PreviewScene
import com.orcaphone.gl.ViewportListener
import com.orcaphone.ui.AppViewModel
import com.orcaphone.ui.SliceUiState
import com.orcaphone.ui.Tab
import com.orcaphone.ui.components.BedBar
import com.orcaphone.ui.components.VerticalLayerSlider
import com.orcaphone.ui.components.Viewport3D
import com.orcaphone.ui.components.fmt
import kotlin.math.roundToInt

private val PALETTE: FloatArray = FloatArray(16 * 3).also { p ->
    for (f in Feature.entries) {
        val c = f.colorArgb
        p[f.ordinal * 3] = ((c shr 16) and 0xff) / 255f
        p[f.ordinal * 3 + 1] = ((c shr 8) and 0xff) / 255f
        p[f.ordinal * 3 + 2] = (c and 0xff) / 255f
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(vm: AppViewModel) {
    val slice by vm.slice.collectAsStateWithLifecycle()
    val tick by vm.viewTick.collectAsStateWithLifecycle()
    val printer by vm.printers.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val beds by vm.beds.collectAsStateWithLifecycle()
    val activeBed by vm.activeBed.collectAsStateWithLifecycle()
    val sliceByBed by vm.sliceByBed.collectAsStateWithLifecycle()
    val ready = slice as? SliceUiState.Ready
    val multi = beds.size > 1
    val bedBar: @Composable (Modifier) -> Unit = { m ->
        if (multi) {
            BedBar(
                count = beds.size, active = activeBed, onSelect = { vm.selectBed(it) },
                checked = { i -> sliceByBed[beds[i].id].let { it is SliceUiState.Ready && !it.stale } },
                modifier = m,
            )
        }
    }

    if (ready == null) {
        Box(Modifier.fillMaxSize()) {
            bedBar(Modifier.align(Alignment.TopCenter).padding(8.dp))
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Layers, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                val running = slice as? SliceUiState.Running
                val bedName = "Bed ${activeBed + 1}"
                val empty = beds.getOrNull(activeBed)?.objects.isNullOrEmpty()
                Text(
                    when {
                        running != null -> "Slicing… ${(running.progress * 100).roundToInt()}%"
                        multi -> "$bedName isn't sliced yet"
                        else -> "Nothing sliced yet"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    running?.stage ?: when {
                        multi && empty -> "$bedName is empty. Add models to it on the Prepare tab."
                        multi -> "Slice $bedName to see every layer here. Each bed is sliced and printed on its own."
                        else -> "Arrange your models on the Prepare tab, then tap Slice plate to see every layer here."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                if (running == null) {
                    if (multi && !empty) {
                        Button(onClick = { vm.startSlice() }) { Text("Slice $bedName") }
                        TextButton(onClick = { vm.setTab(Tab.PREPARE) }) { Text("Go to Prepare") }
                    } else {
                        Button(onClick = { vm.setTab(Tab.PREPARE) }) { Text("Go to Prepare") }
                    }
                } else {
                    TextButton(onClick = { vm.cancelSlice() }) { Text("Cancel") }
                }
            }
        }
        return
    }

    val geo = ready.preview
    val stats = ready.result.stats
    val layerCount = geo.layerCount
    var layer by rememberSaveable(geo) { mutableIntStateOf(layerCount - 1) }
    var moves by rememberSaveable(geo, layer) { mutableIntStateOf(-1) }
    var colorMode by rememberSaveable { mutableIntStateOf(0) }
    var showTravel by rememberSaveable { mutableStateOf(false) }
    var hidden by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var showDetails by remember { mutableStateOf(false) }
    var sendSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(geo) { vm.frameContent() }

    val mask = Feature.entries.fold(0) { acc, f -> if (f.ordinal in hidden) acc else acc or (1 shl f.ordinal) }
    val p = printer.working
    val scene = remember(geo, layer, moves, mask, colorMode, showTravel, p) {
        PreviewScene(geo, p.bedWidth, p.bedDepth, p.maxHeight, layer, moves, mask, colorMode, showTravel, PALETTE)
    }
    val listener = remember { object : ViewportListener { override fun onDoubleTap() = vm.frameContent() } }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }

    Box(Modifier.fillMaxSize()) {
        Viewport3D(vm.camera, null, scene, editable = false, listener = listener, tick = tick, modifier = Modifier.fillMaxSize())

        // top: summary + stale warning
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp)) {
            if (multi) {
                bedBar(Modifier)
                Spacer(Modifier.height(6.dp))
            }
            Surface(
                onClick = { showDetails = true }, shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f), shadowElevation = 2.dp,
                modifier = Modifier.padding(end = 64.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(Icons.Outlined.Timer, NumFmt.duration(stats.timeSeconds).replace(Regex(" \\d+s$"), ""))
                    Stat(Icons.Outlined.Scale, "${fmt(stats.filamentGrams, 1)} g")
                    Stat(Icons.Outlined.Payments, fmt(stats.cost, 2))
                    Icon(Icons.Outlined.Info, "Details", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ready.stale) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    onClick = { vm.startSlice() }, shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.padding(end = 64.dp),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Plate or settings changed — tap to re-slice", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // right: layer slider
        VerticalLayerSlider(
            value = layer, max = layerCount - 1,
            label = "${layer + 1}/$layerCount\n${fmt(geo.layerZ[layer.coerceIn(0, layerCount - 1)], 2)} mm",
            onChange = { layer = it; moves = -1 },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.62f).padding(end = 4.dp),
        )

        // bottom panel
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(selected = colorMode == 0, onClick = { colorMode = 0 },
                            shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("Type") }
                        SegmentedButton(selected = colorMode == 1, onClick = { colorMode = 1 },
                            shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("Speed") }
                    }
                }
                val present = Feature.entries.filter { it in geo.featuresPresent }
                items(present) { f ->
                    FilterChip(
                        selected = f.ordinal !in hidden,
                        onClick = { hidden = if (f.ordinal in hidden) hidden - f.ordinal else hidden + f.ordinal },
                        label = { Text(f.label) },
                        leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(f.colorArgb))) },
                    )
                }
                item {
                    FilterChip(selected = showTravel, onClick = { showTravel = !showTravel }, label = { Text("Travel") },
                        leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(Feature.TRAVEL.colorArgb))) })
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
                shadowElevation = 4.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    val inLayer = geo.layerInstanceStart[layer + 1] - geo.layerInstanceStart[layer]
                    if (inLayer > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Moves", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = if (moves < 0) inLayer.toFloat() else moves.toFloat(),
                                onValueChange = { v -> moves = v.roundToInt().let { if (it >= inLayer) -1 else it } },
                                valueRange = 0f..inLayer.toFloat(),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { saveLauncher.launch(ready.fileName) }) { Icon(Icons.Outlined.Save, "Save G-code") }
                        IconButton(onClick = { vm.shareIntent()?.let { context.startActivity(it) } }) { Icon(Icons.Outlined.Share, "Share G-code") }
                        Text(
                            ready.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { sendSheet = true }, modifier = Modifier.height(48.dp)) {
                            Icon(Icons.Outlined.Print, null); Spacer(Modifier.width(8.dp)); Text("Print", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                Text("Print summary", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                DetailRow("Estimated time", NumFmt.duration(stats.timeSeconds))
                DetailRow("Filament", "${fmt(stats.filamentMm / 1000, 2)} m · ${fmt(stats.filamentGrams, 1)} g")
                DetailRow("Cost", fmt(stats.cost, 2))
                DetailRow("Layers", "${stats.layerCount} · ${fmt(stats.maxZ, 2)} mm tall")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Time by feature", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val total = stats.timeSeconds.coerceAtLeast(1.0)
                for ((f, t) in stats.timeByFeature.entries.sortedByDescending { it.value }) {
                    if (t < 1) continue
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(f.colorArgb)))
                        Spacer(Modifier.width(8.dp))
                        Text(f.label, Modifier.weight(1f))
                        Text("${NumFmt.duration(t)} · ${(t / total * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (sendSheet) {
        SendSheet(
            connections = connections, uploadProgress = upload,
            onSend = { c, start -> vm.sendToPrinter(c, start) },
            onAddPrinter = { sendSheet = false; vm.setTab(Tab.DEVICE) },
            onSave = { sendSheet = false; saveLauncher.launch(ready.fileName) },
            onDismiss = { sendSheet = false; vm.clearUpload() },
        )
    }
}

@Composable
private fun Stat(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendSheet(
    connections: List<PrinterConnection>,
    uploadProgress: com.orcaphone.ui.UploadState?,
    onSend: (PrinterConnection, Boolean) -> Unit,
    onAddPrinter: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Send to printer", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (connections.isEmpty()) {
                Text(
                    "No network printers yet. Add OctoPrint, Klipper (Moonraker) or PrusaLink on the Device tab, " +
                        "or save the G-code and copy it to an SD card / USB stick.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddPrinter, modifier = Modifier.weight(1f)) { Text("Add printer") }
                    OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Save file") }
                }
            }
            for (c in connections) {
                val up = uploadProgress?.takeIf { it.connectionId == c.id }
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = {
                            Text(
                                when {
                                    up == null -> "${c.type.label} · ${c.url}"
                                    up.error != null -> "Failed: ${up.error}"
                                    up.done -> "Sent"
                                    else -> "Uploading ${(up.progress * 100).roundToInt()}%"
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Outlined.Print, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    if (up != null && !up.done) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { up.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onSend(c, false) }, enabled = up == null || up.done) { Text("Upload only") }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = { onSend(c, true) }, enabled = up == null || up.done) { Text("Upload & print") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
