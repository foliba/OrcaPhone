package com.orcaphone.ui.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.orcaphone.cloud.CloudSync
import com.orcaphone.core.orca.OrcaKind
import kotlinx.coroutines.launch

private class LibraryPrinter(val name: String, val vendor: String, val model: String)

/** Searchable list of all OrcaSlicer system printers; picking one adds it with its default process and filament. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterLibrarySheet(cloud: CloudSync, onAdded: (String) -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit) {
    var all by remember { mutableStateOf<List<LibraryPrinter>?>(null) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val cat = cloud.catalog()
        all = cat.entries(OrcaKind.PRINTER).map { (name, v) -> LibraryPrinter(name, v["vendor"].orEmpty(), v["printer_model"].orEmpty()) }
            .sortedWith(compareBy({ it.vendor.lowercase() }, { it.name.lowercase() }))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("OrcaSlicer printer library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "Adds the printer with its default process and filament from OrcaSlicer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            OutlinedTextField(
                query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Search, e.g. “Prusa MK4” or “K1”") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = all
            if (list == null || adding) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
                val filtered = list.filter { p -> terms.all { t -> p.name.lowercase().contains(t) || p.vendor.lowercase().contains(t) } }
                LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
                    items(filtered.take(300), key = { it.name }) { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = { Text(p.vendor) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                adding = true
                                scope.launch {
                                    runCatching { cloud.addFromLibrary(p.name) }
                                        .onSuccess { onAdded(p.name) }
                                        .onFailure { adding = false; onError(it.message ?: "Could not add printer") }
                                }
                            },
                        )
                    }
                    if (filtered.size > 300) item {
                        Text("${filtered.size - 300} more — refine the search", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(20.dp))
                    }
                }
            }
        }
    }
}
