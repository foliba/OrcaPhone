package io.github.foliba.phorca.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.foliba.phorca.ui.components.fmt
import java.util.Locale

/** "OrcaSlicer 2.5.3 has new profiles": Download, or Ignore (optionally for good). */
@Composable
fun ProfileUpdateDialog(state: ProfileUpdateUi, onDownload: () -> Unit, onIgnore: (dontAskAgain: Boolean) -> Unit) {
    when (state) {
        is ProfileUpdateUi.Offer -> {
            var dontAsk by remember { mutableStateOf(false) }
            val mb = state.update.sizeBytes / 1e6
            AlertDialog(
                onDismissRequest = { onIgnore(false) },
                icon = { Icon(Icons.Outlined.Update, null) },
                title = { Text("New OrcaSlicer profiles") },
                text = {
                    Column {
                        Text(
                            "OrcaSlicer ${state.update.version} has new and updated printer, filament and process profiles" +
                                (if (mb > 0) " (${fmt(mb, 1)} MB)" else "") +
                                ". Printers, filaments and processes you already added are not changed.",
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable { dontAsk = !dontAsk },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                            Text("Don't ask again", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = { Button(onClick = onDownload, enabled = !dontAsk) { Text("Download") } },
                dismissButton = { TextButton(onClick = { onIgnore(dontAsk) }) { Text("Ignore") } },
            )
        }
        is ProfileUpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Outlined.Update, null) },
            title = { Text("Downloading profiles…") },
            text = {
                Column {
                    Text("OrcaSlicer ${state.update.version} · ${String.format(Locale.ROOT, "%d", (state.progress * 100).toInt())}%")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }
}
