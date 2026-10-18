package com.orcaphone.ui.cloud

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orcaphone.cloud.CloudPrinter
import com.orcaphone.cloud.CloudState
import kotlin.math.roundToInt

@Composable
fun CloudAccountCard(
    state: CloudState,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onUploadLocal: () -> Unit,
    onAutoUpload: (Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val s = state.session
    var menu by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            if (s == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Cloud, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text("Orca Cloud", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sign in with your OrcaSlicer account to sync your printer, filament and process presets with OrcaSlicer on your computer, and see your cloud printers.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSignIn) { Text("Sign in") }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.label.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        when {
                            state.syncing -> "Syncing…"
                            state.lastError != null -> "Sync failed: ${state.lastError}"
                            state.lastSync > 0 -> "Synced " + DateUtils.getRelativeTimeSpanString(state.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                            else -> "Not synced yet"
                        },
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (state.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (state.syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Account options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Sign out") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                            onClick = { menu = false; confirmSignOut = true },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${state.syncedPresets} synced preset${if (state.syncedPresets == 1) "" else "s"}" +
                    if (state.localOnlyPresets > 0) " · ${state.localOnlyPresets} only on this phone" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Upload new presets automatically", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Switch(checked = state.autoUpload, onCheckedChange = onAutoUpload)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilledTonalButton(onClick = onSync, enabled = !state.syncing) {
                    Icon(Icons.Outlined.CloudSync, null); Spacer(Modifier.width(6.dp)); Text("Sync now")
                }
                if (state.localOnlyPresets > 0) {
                    OutlinedButton(onClick = onUploadLocal, enabled = !state.syncing) {
                        Icon(Icons.Outlined.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("Upload ${state.localOnlyPresets}")
                    }
                }
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Orca Cloud?") },
            text = { Text("Synced presets stay on this phone as local presets. Nothing is deleted from your account.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun CloudPrintersCard(printers: List<CloudPrinter>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "Orca Cloud printers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            printers.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Print, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name.ifBlank { p.model }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val job = p.jobState?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
                        Text(
                            listOfNotNull(p.model.takeIf { it.isNotBlank() && p.name.isNotBlank() }, job).joinToString(" · ").ifBlank { " " },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        p.progress?.let { pr ->
                            val f = (if (pr > 1.0) pr / 100.0 else pr).toFloat().coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            Text("${(f * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(10.dp).background(if (p.online) Color(0xFF43A047) else Color(0xFF9E9E9E), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(if (p.online) "Online" else "Offline", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
