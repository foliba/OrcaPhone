package com.orcaphone.ui.device

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcaphone.core.config.HostType
import com.orcaphone.core.config.PrinterConnection
import com.orcaphone.data.HostStatus
import com.orcaphone.data.JobCommand
import com.orcaphone.ui.AppViewModel
import com.orcaphone.ui.SliceUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DeviceScreen(vm: AppViewModel, onSignIn: () -> Unit) {
    val connections by vm.connections.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val slice by vm.slice.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val cloudState by vm.cloud.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PrinterConnection?>(null) }
    var confirmCancel by remember { mutableStateOf<PrinterConnection?>(null) }
    val ready = slice as? SliceUiState.Ready

    // Cloud printers: refresh while visible.
    LaunchedEffect(cloudState.session?.userId) {
        while (cloudState.session != null) {
            vm.cloud.refreshPrinters()
            delay(30_000)
        }
    }
    // Poll while this screen is visible.
    LaunchedEffect(connections) {
        while (true) {
            if (connections.isNotEmpty()) vm.refreshStatuses()
            delay(3000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                com.orcaphone.ui.cloud.CloudAccountCard(
                    state = cloudState,
                    onSignIn = onSignIn,
                    onSync = { vm.cloud.syncNow() },
                    onUploadLocal = { vm.cloud.uploadLocalPresets() },
                    onAutoUpload = { vm.cloud.setAutoUpload(it) },
                    onSignOut = { vm.cloud.signOut() },
                )
            }
            if (cloudState.session != null && cloudState.printers.isNotEmpty()) {
                item { com.orcaphone.ui.cloud.CloudPrintersCard(cloudState.printers) }
            }
            item {
                Text("Printers", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(4.dp))
                Text(
                    "Monitor and send prints over your local network.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (connections.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(20.dp)) {
                            Icon(Icons.Outlined.Print, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Text("No printers connected", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Supported: OctoPrint, Klipper via Moonraker (Mainsail/Fluidd) and PrusaLink. " +
                                    "Your phone must be on the same network as the printer.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            FilledTonalButton(onClick = { editing = PrinterConnection(vm.newConnectionId(), "My printer") }) {
                                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add printer")
                            }
                        }
                    }
                }
            }
            items(connections, key = { it.id }) { c ->
                PrinterCard(
                    c = c, status = statuses[c.id],
                    canSend = ready != null, fileName = ready?.fileName,
                    uploading = upload?.takeIf { it.connectionId == c.id && !it.done }?.progress,
                    onSend = { vm.sendToPrinter(c, true) },
                    onCommand = { cmd -> if (cmd == JobCommand.CANCEL) confirmCancel = c else vm.jobCommand(c, cmd) },
                    onEdit = { editing = c },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = PrinterConnection(vm.newConnectionId(), "Printer ${connections.size + 1}") },
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add printer") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { c ->
        ConnectionDialog(
            initial = c, isNew = connections.none { it.id == c.id }, vm = vm,
            onSave = { vm.saveConnection(it); editing = null; vm.refreshStatuses() },
            onDelete = { vm.deleteConnection(c.id); editing = null },
            onDismiss = { editing = null },
        )
    }
    confirmCancel?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("Cancel print?") },
            text = { Text("The current print on ${c.name} will be stopped. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.jobCommand(c, JobCommand.CANCEL); confirmCancel = null }) { Text("Cancel print") } },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Keep printing") } },
        )
    }
}

@Composable
private fun PrinterCard(
    c: PrinterConnection, status: Result<HostStatus>?, canSend: Boolean, fileName: String?, uploading: Float?,
    onSend: () -> Unit, onCommand: (JobCommand) -> Unit, onEdit: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    Text("${c.type.label} · ${c.url}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit connection") }
            }
            Spacer(Modifier.height(8.dp))
            val s = status?.getOrNull()
            when {
                status == null -> Text("Connecting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                s == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WifiOff, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(status.exceptionOrNull()?.message ?: "Offline", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    Text(s.state, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    if (s.printing && s.progress != null) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { (s.progress ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${(s.progress * 100).roundToInt()}%" + (s.fileName?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Thermostat, null, Modifier.size(18.dp))
                        Text("Nozzle ${temp(s.nozzleTemp, s.nozzleTarget)}", style = MaterialTheme.typography.bodyMedium)
                        Text("Bed ${temp(s.bedTemp, s.bedTarget)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (s.printing) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.paused) OutlinedButton(onClick = { onCommand(JobCommand.RESUME) }) {
                                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Resume")
                            } else OutlinedButton(onClick = { onCommand(JobCommand.PAUSE) }) {
                                Icon(Icons.Outlined.Pause, null); Spacer(Modifier.width(4.dp)); Text("Pause")
                            }
                            OutlinedButton(onClick = { onCommand(JobCommand.CANCEL) }) {
                                Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(4.dp)); Text("Cancel")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (uploading != null) {
                Text("Uploading ${(uploading * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { uploading }, modifier = Modifier.fillMaxWidth())
            } else {
                FilledTonalButton(
                    onClick = onSend, enabled = canSend && s != null && !s.printing, modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CloudUpload, null); Spacer(Modifier.width(8.dp))
                    Text(if (fileName != null) "Print $fileName" else "Slice a plate to print", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun temp(actual: Double?, target: Double?): String {
    val a = actual?.let { "${it.roundToInt()}°" } ?: "–"
    return if (target != null && target > 0) "$a / ${target.roundToInt()}°" else a
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDialog(
    initial: PrinterConnection, isNew: Boolean, vm: AppViewModel,
    onSave: (PrinterConnection) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var url by remember { mutableStateOf(initial.url.removePrefix("http://").ifBlank { "" }) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun current() = initial.copy(
        name = name.trim().ifBlank { "Printer" }, type = type,
        url = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" },
        apiKey = key.trim(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add printer" else "Edit printer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    HostType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = type == t, onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(i, HostType.entries.size), icon = {},
                        ) {
                            Text(when (t) { HostType.OCTOPRINT -> "OctoPrint"; HostType.MOONRAKER -> "Klipper"; HostType.PRUSALINK -> "PrusaLink" },
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it }, label = { Text("Address") }, singleLine = true,
                    placeholder = { Text(if (type == HostType.MOONRAKER) "192.168.1.50 or 192.168.1.50:7125" else "192.168.1.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    key, { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (type == HostType.MOONRAKER) "API key (optional)" else "API key") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    when (type) {
                        HostType.OCTOPRINT -> "OctoPrint: Settings → Application Keys (or the global API key)."
                        HostType.MOONRAKER -> "Moonraker usually needs no key on a trusted network. Mainsail/Fluidd use the same address."
                        HostType.PRUSALINK -> "PrusaLink: printer menu Settings → Network → PrusaLink shows the API key."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !testing && url.isNotBlank(), onClick = {
                        testing = true; testResult = null
                        scope.launch {
                            val r = vm.testConnection(current())
                            testResult = r.fold({ "Connected: ${it.state}" }, { "Failed: ${it.message}" })
                            testing = false
                        }
                    }) { Text(if (testing) "Testing…" else "Test connection") }
                }
                testResult?.let {
                    Text(it, color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("Remove printer", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current()) }, enabled = url.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
