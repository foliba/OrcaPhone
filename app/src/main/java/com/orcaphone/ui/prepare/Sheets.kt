package com.orcaphone.ui.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.orcaphone.core.config.BrimType
import com.orcaphone.core.config.InfillPattern
import com.orcaphone.core.config.PrinterProfile
import com.orcaphone.core.config.ProcessProfile
import com.orcaphone.core.config.SupportType
import com.orcaphone.core.math.Vec3
import com.orcaphone.core.plate.PlateObject
import com.orcaphone.data.PresetState
import com.orcaphone.ui.AppViewModel
import com.orcaphone.ui.components.LabeledSlider
import com.orcaphone.ui.components.SectionTitle
import com.orcaphone.ui.components.Stepper
import com.orcaphone.ui.components.fmt
import com.orcaphone.ui.components.parseNumber
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PresetPickerSheet(
    title: String,
    state: PresetState<T>,
    idOf: (T) -> String,
    nameOf: (T) -> String,
    builtinOf: (T) -> Boolean,
    groupOf: ((T) -> String)? = null,
    colorOf: ((T) -> Int?)? = null,
    cloudOf: (T) -> Boolean = { false },
    onLibrary: (() -> Unit)? = null,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(6.dp)); Text("Edit settings")
            }
        }
        if (state.dirty) {
            Text(
                "Current preset has unsaved changes. Selecting another preset discards them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (onLibrary != null) {
            ListItem(
                headlineContent = { Text("Add from OrcaSlicer library") },
                supportingContent = { Text("1,600+ printers with their default process and filament") },
                leadingContent = { Icon(Icons.Outlined.LibraryAdd, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = 4.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onLibrary),
            )
        }
        val cloud = state.all.filter { !builtinOf(it) && cloudOf(it) }
        val user = state.all.filter { !builtinOf(it) && !cloudOf(it) }
        val builtin = state.all.filter { builtinOf(it) }
        val sections = buildList {
            if (cloud.isNotEmpty()) add("Orca Cloud" to cloud)
            if (user.isNotEmpty()) add("On this phone" to user)
            if (groupOf == null) add("System presets" to builtin)
            else builtin.groupBy(groupOf).forEach { (g, list) -> add(g to list) }
        }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            for ((header, list) in sections) {
                item(key = "h-$header") {
                    Text(header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                }
                items(list, key = { idOf(it) }) { item ->
                    val id = idOf(item)
                    val swatch = colorOf?.invoke(item)
                    ListItem(
                        headlineContent = { Text(nameOf(item)) },
                        leadingContent = {
                            RadioButton(selected = id == state.selectedId, onClick = { onSelect(id); onDismiss() })
                        },
                        trailingContent = if (swatch != null || cloudOf(item)) {
                            {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (cloudOf(item)) Icon(Icons.Outlined.Cloud, "Synced with Orca Cloud", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    if (swatch != null) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or swatch)))
                                    }
                                }
                            }
                        } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.padding(horizontal = 4.dp).clip(MaterialTheme.shapes.medium)
                            .clickable { onSelect(id); onDismiss() },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickSettingsSheet(
    process: ProcessProfile,
    printer: PrinterProfile,
    onChange: ((ProcessProfile) -> ProcessProfile) -> Unit,
    onAllSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
        ) {
            Text("Quick settings", style = MaterialTheme.typography.titleLarge)
            Text(process.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Layer height")
            val heights = listOf(0.08, 0.12, 0.16, 0.2, 0.24, 0.28)
                .filter { it >= printer.minLayerHeight - 1e-6 && it <= printer.maxLayerHeight + 1e-6 }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                heights.forEachIndexed { i, h ->
                    SegmentedButton(
                        selected = abs(process.layerHeight - h) < 1e-6,
                        onClick = { onChange { it.copy(layerHeight = h) } },
                        shape = SegmentedButtonDefaults.itemShape(i, heights.size),
                        icon = {},
                    ) { Text(fmt(h, 2), style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text(
                "Lower = finer detail, longer print.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )

            SectionTitle("Strength")
            var density by remember(process.sparseInfillDensity) { mutableFloatStateOf(process.sparseInfillDensity.toFloat()) }
            LabeledSlider(
                "Infill density", density, "${density.roundToInt()}%", { density = it }, 0f..100f, steps = 19,
                onDone = { val d = density.roundToInt().toDouble(); onChange { it.copy(sparseInfillDensity = d) } },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pat in InfillPattern.entries) {
                    FilterChip(
                        selected = process.sparseInfillPattern == pat,
                        onClick = { onChange { it.copy(sparseInfillPattern = pat) } },
                        label = { Text(pat.label) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wall loops", style = MaterialTheme.typography.bodyLarge)
                    Text("More walls = stronger parts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Stepper(process.wallLoops, { v -> onChange { it.copy(wallLoops = v) } }, 1, 10)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Top / bottom layers", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Stepper(process.topShellLayers, { v -> onChange { it.copy(topShellLayers = v, bottomShellLayers = (v - 1).coerceAtLeast(1)) } }, 0, 20)
            }

            SectionTitle("Support & adhesion")
            SwitchRow("Supports", "Generate support under overhangs", process.enableSupport) { v -> onChange { it.copy(enableSupport = v) } }
            if (process.enableSupport) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SupportType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = process.supportType == t,
                            onClick = { onChange { it.copy(supportType = t) } },
                            shape = SegmentedButtonDefaults.itemShape(i, SupportType.entries.size),
                        ) { Text(when (t) { SupportType.NORMAL -> "Normal"; SupportType.TREE -> "Tree"; SupportType.ORGANIC -> "Organic" }) }
                    }
                }
                SwitchRow("On build plate only", "Never place supports on the model", process.supportOnBuildPlateOnly) { v ->
                    onChange { it.copy(supportOnBuildPlateOnly = v) }
                }
                var angle by remember(process.supportThresholdAngle) { mutableFloatStateOf(process.supportThresholdAngle.toFloat()) }
                LabeledSlider(
                    "Threshold angle", angle, "${angle.roundToInt()}°", { angle = it }, 5f..85f,
                    onDone = { val a = angle.roundToInt().toDouble(); onChange { it.copy(supportThresholdAngle = a) } },
                )
            }
            SwitchRow("Brim", "Extra loops around the first layer for adhesion", process.brimType != BrimType.NONE) { v ->
                onChange { it.copy(brimType = if (v) BrimType.OUTER else BrimType.NONE) }
            }

            SectionTitle("Special")
            SwitchRow("Spiral vase", "Single continuous wall, no top", process.spiralMode) { v -> onChange { it.copy(spiralMode = v) } }
            SwitchRow("Ironing", "Smoother top surfaces", process.ironing) { v -> onChange { it.copy(ironing = v) } }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAllSettings, modifier = Modifier.fillMaxWidth()) { Text("All process settings") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 0.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotateSheet(onRotate: (Char, Double) -> Unit, onLayFlat: () -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Rotate", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            for ((axis, color) in listOf('x' to Color(0xFFE53935), 'y' to Color(0xFF43A047), 'z' to Color(0xFF1E88E5))) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Text(axis.uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (deg in listOf(-90.0, -15.0, 15.0, 90.0)) {
                            OutlinedButton(
                                onClick = { onRotate(axis, deg) }, modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                            ) { Text((if (deg > 0) "+" else "") + deg.roundToInt() + "°") }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onLayFlat, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.VerticalAlignBottom, null); Spacer(Modifier.width(6.dp)); Text("Lay flat")
                }
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Reset")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleSheet(obj: PlateObject, onScale: (Vec3) -> Unit, onSize: (Int, Double) -> Unit, onDismiss: () -> Unit) {
    var uniform by remember { mutableStateOf(abs(obj.scale.x - obj.scale.y) < 1e-9 && abs(obj.scale.y - obj.scale.z) < 1e-9) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scale", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconToggleButton(checked = uniform, onCheckedChange = { uniform = it }) {
                    Icon(if (uniform) Icons.Outlined.Lock else Icons.Outlined.LockOpen, "Uniform scale")
                }
                Text(if (uniform) "Uniform" else "Free", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pct in listOf(50, 100, 150, 200)) {
                    OutlinedButton(
                        onClick = { val f = pct / 100.0; onScale(Vec3(f, f, f)) }, modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                    ) { Text("$pct%") }
                }
            }
            Spacer(Modifier.height(12.dp))
            val size = obj.size
            val scales = listOf(obj.scale.x, obj.scale.y, obj.scale.z)
            val sizes = listOf(size.x, size.y, size.z)
            for (axis in 0..2) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "${"XYZ"[axis]} size", unit = "mm", value = sizes[axis], decimals = 2,
                        modifier = Modifier.weight(1f),
                    ) { mm ->
                        if (uniform) onSize(axis, mm)
                        else {
                            val f = mm / sizes[axis]
                            val s = scales.toMutableList(); s[axis] = s[axis] * f
                            onScale(Vec3(s[0], s[1], s[2]))
                        }
                    }
                    NumberField(
                        label = "${"XYZ"[axis]} scale", unit = "%", value = scales[axis] * 100, decimals = 1,
                        modifier = Modifier.weight(1f),
                    ) { pct ->
                        val f = pct / 100.0
                        if (uniform) onScale(Vec3(f, f, f))
                        else { val s = scales.toMutableList(); s[axis] = f; onScale(Vec3(s[0], s[1], s[2])) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Text field that commits a positive number on IME "Done" or focus loss-free confirm. */
@Composable
fun NumberField(
    label: String, unit: String, value: Double, decimals: Int, modifier: Modifier = Modifier, onCommit: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf(fmt(value, decimals)) }
    LaunchedEffect(value) { text = fmt(value, decimals) }
    OutlinedTextField(
        value = text, onValueChange = { text = it },
        label = { Text(label) }, suffix = { Text(unit) }, singleLine = true,
        isError = parseNumber(text)?.let { it <= 0 } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { parseNumber(text)?.takeIf { it > 0 }?.let(onCommit) }),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(onImport: () -> Unit, onSample: (AppViewModel.Sample) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("Add to plate", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            ListItem(
                headlineContent = { Text("Import file") },
                supportingContent = { Text("STL, 3MF or OBJ from your phone or cloud storage") },
                leadingContent = { Icon(Icons.Outlined.FileOpen, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = 4.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onImport),
            )
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            Text("Samples", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp))
            for (s in AppViewModel.Sample.entries) {
                ListItem(
                    headlineContent = { Text(s.label) },
                    leadingContent = { Icon(Icons.Outlined.Category, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.padding(horizontal = 4.dp).clip(MaterialTheme.shapes.medium).clickable { onSample(s) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
