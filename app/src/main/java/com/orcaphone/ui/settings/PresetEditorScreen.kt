package com.orcaphone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcaphone.data.PresetState
import com.orcaphone.data.PresetStore
import com.orcaphone.ui.AppViewModel
import com.orcaphone.ui.components.fmt
import com.orcaphone.ui.components.parseNumber
import kotlin.math.roundToInt

enum class EditorTarget(val title: String) { PRINTER("Printer settings"), FILAMENT("Filament settings"), PROCESS("Process settings") }

@Composable
fun PresetEditorScreen(vm: AppViewModel, target: EditorTarget, onClose: () -> Unit) {
    when (target) {
        EditorTarget.PRINTER -> {
            val st by vm.printers.collectAsStateWithLifecycle()
            Editor(target.title, st, vm.repos.printers, PrinterSettings.pages, { it.name }, { it.builtin }, onClose)
        }
        EditorTarget.FILAMENT -> {
            val st by vm.filaments.collectAsStateWithLifecycle()
            Editor(target.title, st, vm.repos.filaments, FilamentSettings.pages, { it.name }, { it.builtin }, onClose)
        }
        EditorTarget.PROCESS -> {
            val st by vm.processes.collectAsStateWithLifecycle()
            Editor(target.title, st, vm.repos.processes, ProcessSettings.pages, { it.name }, { it.builtin }, onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Any> Editor(
    title: String,
    state: PresetState<T>,
    store: PresetStore<T>,
    pages: List<SettingPage<T>>,
    nameOf: (T) -> String,
    builtinOf: (T) -> Boolean,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Setting<T>?>(null) }
    val builtin = builtinOf(state.selected)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                nameOf(state.working) + if (state.dirty) " · modified" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.dirty) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (state.dirty && !builtin) {
                            IconButton(onClick = { store.save() }) { Icon(Icons.Outlined.Save, "Save") }
                        }
                        IconButton(onClick = { saveAs = true }) { Icon(Icons.Outlined.SaveAs, "Save as new preset") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Discard changes") }, enabled = state.dirty,
                                leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                                onClick = { menu = false; store.revert() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete preset") }, enabled = !builtin,
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    },
                )
                if (pages.size > 1) {
                    ScrollableTabRow(selectedTabIndex = page, edgePadding = 8.dp) {
                        pages.forEachIndexed { i, pg ->
                            Tab(selected = page == i, onClick = { page = i }, text = { Text(pg.title) })
                        }
                    }
                }
            }
        },
    ) { padding ->
        val pg = pages[page.coerceIn(0, pages.size - 1)]
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (builtin && state.dirty) {
                item {
                    Text(
                        "This is a system preset. Use “Save as” to keep your changes as a new preset — they are applied to slicing either way.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            for (group in pg.groups) {
                item(key = "g-${pg.title}-${group.title}") {
                    Text(
                        group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(group.items, key = { "s-${pg.title}-${group.title}-${it.label}" }) { s ->
                    SettingRow(s, state.working, state.selected, onEdit = { editing = s }, onChange = { f -> store.edit(f) })
                }
                item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }

    editing?.let { s ->
        SettingDialog(s, state.working, onDismiss = { editing = null }) { f -> store.edit(f); editing = null }
    }
    if (saveAs) {
        var name by remember { mutableStateOf(nameOf(state.working).let { if (builtinOf(state.working)) "$it (copy)" else it }) }
        AlertDialog(
            onDismissRequest = { saveAs = false },
            title = { Text("Save as new preset") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { store.saveAs(name); saveAs = false }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saveAs = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete preset?") },
            text = { Text("“${nameOf(state.selected)}” will be removed.") },
            confirmButton = { TextButton(onClick = { store.delete(state.selectedId); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun <T> SettingRow(s: Setting<T>, value: T, saved: T, onEdit: () -> Unit, onChange: ((T) -> T) -> Unit) {
    val (text, changed) = when (s) {
        is Setting.Number -> "${fmt(s.get(value), s.decimals)} ${s.unit}".trim() to (s.get(value) != s.get(saved))
        is Setting.Integer -> "${s.get(value)} ${s.unit}".trim() to (s.get(value) != s.get(saved))
        is Setting.Toggle -> "" to (s.get(value) != s.get(saved))
        is Setting.Choice<*, *> -> {
            val c = s as Setting.Choice<T, Any?>
            c.optionLabel(c.get(value)) to (c.get(value) != c.get(saved))
        }
        is Setting.Text -> s.get(value).lineSequence().firstOrNull().orEmpty().take(40).let {
            val lines = s.get(value).lines().size
            (if (lines > 1) "$it … (+${lines - 1} lines)" else it) to (s.get(value) != s.get(saved))
        }
    }
    val labelWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal
    if (s is Setting.Toggle) {
        ListItem(
            headlineContent = { Text(s.label + if (changed) " •" else "", fontWeight = labelWeight) },
            supportingContent = if (s.help != null) { { Text(s.help.orEmpty()) } } else null,
            trailingContent = { Switch(checked = s.get(value), onCheckedChange = { v -> onChange { s.set(it, v) } }) },
            modifier = Modifier.clickable { onChange { s.set(it, !s.get(it)) } },
        )
    } else {
        ListItem(
            headlineContent = { Text(s.label + if (changed) " •" else "", fontWeight = labelWeight) },
            supportingContent = if (s.help != null || s is Setting.Text) {
                {
                    Column {
                        if (s is Setting.Text) Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                        if (s.help != null) Text(s.help.orEmpty())
                    }
                }
            } else null,
            trailingContent = if (s !is Setting.Text) {
                {
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium)
                }
            } else null,
            modifier = Modifier.clickable(onClick = onEdit),
        )
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun <T> SettingDialog(s: Setting<T>, value: T, onDismiss: () -> Unit, onApply: ((T) -> T) -> Unit) {
    when (s) {
        is Setting.Number -> NumberDialog(s.label, s.unit, fmt(s.get(value), s.decimals), s.help, s.min, s.max, false, onDismiss) { v ->
            onApply { s.set(it, v) }
        }
        is Setting.Integer -> NumberDialog(s.label, s.unit, s.get(value).toString(), s.help, s.min.toDouble(), s.max.toDouble(), true, onDismiss) { v ->
            onApply { s.set(it, v.roundToInt()) }
        }
        is Setting.Choice<*, *> -> {
            val c = s as Setting.Choice<T, Any?>
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(c.label) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for (o in c.options) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected = c.get(value) == o, role = Role.RadioButton, onClick = { onApply { c.set(it, o) } }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = c.get(value) == o, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(c.optionLabel(o))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            )
        }
        is Setting.Text -> {
            var text by remember { mutableStateOf(s.get(value)) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(s.label) },
                text = {
                    Column {
                        if (s.help != null) Text(s.help.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                        OutlinedTextField(
                            text, { text = it }, singleLine = !s.multiline,
                            textStyle = if (s.code) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().then(if (s.multiline) Modifier.heightIn(min = 200.dp, max = 420.dp) else Modifier),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { onApply { s.set(it, text) } }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        is Setting.Toggle -> onDismiss()
    }
}

@Composable
private fun NumberDialog(
    label: String, unit: String, initial: String, help: String?, min: Double, max: Double, integer: Boolean,
    onDismiss: () -> Unit, onApply: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val v = parseNumber(text)
    val valid = v != null && v >= min && v <= max && (!integer || v == Math.floor(v))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column {
                if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    text, { text = it }, singleLine = true, suffix = { Text(unit) }, isError = !valid,
                    supportingText = { Text("${fmt(min, 3)} – ${fmt(max, 3)}") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (valid) onApply(v!!) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(v!!) }, enabled = valid) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
