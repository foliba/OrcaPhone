package com.orcaphone.ui.prepare

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Asks how many instances of a model the bed should hold, or to fill the bed.
 * [max] is how many fit on the bed with room for brim, skirt and supports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesSheet(name: String, current: Int, max: Int, bedNumber: Int, onApply: (Int?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.coerceAtMost(max.coerceAtLeast(1)).toString()) }
    val n = text.toIntOrNull()
    val tooMany = n != null && n > max
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Instances", style = MaterialTheme.typography.titleLarge)
            Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { text = ((n ?: 1) - 1).coerceAtLeast(1).toString() }, enabled = (n ?: 1) > 1) {
                    Icon(Icons.Outlined.Remove, "Fewer")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    isError = tooMany || n == 0,
                    label = { Text("Number of instances") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.weight(1f).testTag("instanceCount"),
                )
                IconButton(onClick = { text = ((n ?: 0) + 1).coerceAtMost(max.coerceAtLeast(1)).toString() }, enabled = (n ?: 0) < max) {
                    Icon(Icons.Outlined.Add, "More")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    max == 0 -> "It doesn't fit on bed $bedNumber next to the other objects."
                    tooMany -> "Only $max fit on bed $bedNumber."
                    else -> "Up to $max fit on bed $bedNumber, spaced for brim, skirt and supports."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (tooMany || max == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onApply(null) }, enabled = max > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.GridView, null); Spacer(Modifier.width(6.dp)); Text("Fill bed ($max)")
                }
                Button(onClick = { onApply(n) }, enabled = max > 0 && n != null && n >= 1, modifier = Modifier.weight(1f)) {
                    Text(if (tooMany) "Place $max" else "Place ${n ?: ""}")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One model on the bed: all its instances are a single row. */
data class ModelRow(val firstId: Long, val group: Long, val name: String, val size: String, val count: Int, val outside: Boolean)

/** The models on the active bed. Instances are not listed one by one: a badge shows how many there are. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectsSheet(bedNumber: Int, rows: List<ModelRow>, selectedGroup: Long?, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                "Models on bed $bedNumber", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text("No models on this bed.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn {
                items(rows, key = { it.group }) { r ->
                    ListItem(
                        headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (r.outside) "${r.size} · outside build volume" else r.size,
                                color = if (r.outside) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = if (r.count > 1) ({
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ) {
                                Text(
                                    "×${r.count}", style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).testTag("instances_${r.name}"),
                                )
                            }
                        }) else null,
                        colors = if (r.group == selectedGroup) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        else ListItemDefaults.colors(),
                        modifier = Modifier.clickable { onSelect(r.firstId) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
