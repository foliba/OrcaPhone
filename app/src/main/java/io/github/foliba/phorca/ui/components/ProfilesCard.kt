package io.github.foliba.phorca.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.foliba.phorca.core.orca.ProfilePack

/** Which OrcaSlicer profiles are in use, the start-up check for newer ones, and Check now. */
@Composable
fun ProfilesCard(version: String, checkOnStartup: Boolean, checking: Boolean, onCheckOnStartup: (Boolean) -> Unit, onCheckNow: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            Text("OrcaSlicer profiles", style = MaterialTheme.typography.titleMedium)
            Text(
                "Printers, filaments, processes and bed graphics from OrcaSlicer $version" +
                    if (version == ProfilePack.BUNDLED_VERSION) " (built in)." else " (downloaded).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check for new profiles at start-up", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Asks before downloading when OrcaSlicer has a new release.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = checkOnStartup, onCheckedChange = onCheckOnStartup, modifier = Modifier.testTag("profileCheckSwitch"))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCheckNow, enabled = !checking) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Check now")
            }
        }
    }
}
