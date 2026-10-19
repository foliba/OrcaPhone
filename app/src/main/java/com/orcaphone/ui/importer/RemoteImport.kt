package com.orcaphone.ui.importer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orcaphone.core.models.RemoteFile
import com.orcaphone.core.models.RemoteFileKind
import com.orcaphone.core.models.RemoteModel
import com.orcaphone.ui.RemoteImportState
import java.util.Locale

/** Large downloads on mobile data ask for confirmation first. */
private const val METERED_WARN_BYTES = 50L * 1024 * 1024

fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024))
    b >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
    b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

/** Asks for a Printables link. */
@Composable
fun ModelLinkDialog(onLookUp: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Link, null) },
        title = { Text("Import from Printables") },
        text = {
            Column {
                Text(
                    "Paste the link of a Printables model. You'll pick which files to import next.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Model link") },
                    placeholder = { Text("printables.com/model/…") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.let { text = it.trim() } }) {
                            Icon(Icons.Outlined.ContentPaste, "Paste")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) onLookUp(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onLookUp(text) }, enabled = text.isNotBlank()) { Text("Look up") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full-screen file picker for a model from a website, with previews, sizes and download progress. */
@Composable
fun RemoteImportScreen(
    state: RemoteImportState,
    loadPreview: suspend (String) -> ImageBitmap?,
    isMetered: () -> Boolean,
    onImport: (List<RemoteFile>) -> Unit,
    onCancelDownload: () -> Unit,
    onClose: () -> Unit,
    onTryAnotherLink: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (state is RemoteImportState.Downloading) onCancelDownload() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
                    Text("Import from Printables", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                when (state) {
                    is RemoteImportState.Loading -> Centered {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Looking up the model…", style = MaterialTheme.typography.titleMedium)
                    }
                    is RemoteImportState.Failed -> {
                        val m = state.model
                        if (m != null && m.files.isEmpty()) NoImportableFiles(m, onTryAnotherLink)
                        else ErrorContent(state.message, state.pageUrl, onTryAnotherLink)
                    }
                    is RemoteImportState.Choose -> FileChooser(state.model, null, loadPreview, isMetered, onImport, onCancelDownload)
                    is RemoteImportState.Downloading -> FileChooser(state.model, state, loadPreview, isMetered, onImport, onCancelDownload)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun ErrorContent(message: String, pageUrl: String?, onTryAnotherLink: () -> Unit) {
    val uri = LocalUriHandler.current
    Centered {
        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text("Couldn't import this model", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onTryAnotherLink) { Text("Try another link") }
        if (pageUrl != null) {
            TextButton(onClick = { runCatching { uri.openUri(pageUrl) } }) { Text("Open on Printables") }
        }
    }
}

@Composable
private fun NoImportableFiles(model: RemoteModel, onTryAnotherLink: () -> Unit) {
    val uri = LocalUriHandler.current
    Centered {
        Icon(Icons.Outlined.SearchOff, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("No STL files in this model", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(model.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        val kinds = model.ignoredFiles.map { it.substringAfterLast('.', "").uppercase() }.filter { it.isNotEmpty() && it.length <= 6 }.distinct()
        Text(
            if (model.ignoredFiles.isEmpty()) "This model has no downloadable files."
            else "It only has ${if (kinds.isEmpty()) "other" else kinds.joinToString(", ")} files, which OrcaPhone can't import.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        ThreeMfNote()
        Spacer(Modifier.height(20.dp))
        Button(onClick = onTryAnotherLink) { Text("Try another link") }
        TextButton(onClick = { runCatching { uri.openUri(model.pageUrl) } }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open on Printables")
        }
    }
}

@Composable
private fun ThreeMfNote() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                "3MF files are imported as geometry only for now. Full 3MF support (plates, colours and settings) is coming soon.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun FileChooser(
    model: RemoteModel,
    download: RemoteImportState.Downloading?,
    loadPreview: suspend (String) -> ImageBitmap?,
    isMetered: () -> Boolean,
    onImport: (List<RemoteFile>) -> Unit,
    onCancelDownload: () -> Unit,
) {
    var chosen by rememberSaveable(model.id) {
        mutableStateOf(if (model.files.size == 1) setOf(model.files[0].id) else emptySet())
    }
    var confirmMetered by remember { mutableStateOf(false) }
    val selected = model.files.filter { it.id in chosen }
    val totalBytes = selected.sumOf { it.sizeBytes }
    val busy = download != null

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { ModelHeader(model) }
            if (model.files.any { it.kind == RemoteFileKind.THREE_MF }) {
                item { Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { ThreeMfNote() } }
            }
            if (model.files.size > 1) {
                item {
                    val all = chosen.size == model.files.size
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            chosen = if (all) emptySet() else model.files.map { it.id }.toSet()
                        }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = all, onCheckedChange = null, enabled = !busy, modifier = Modifier.padding(12.dp))
                        Text("Select all ${model.files.size} files", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider()
                }
            }
            items(model.files, key = { it.id }) { f ->
                FileRow(f, f.id in chosen, enabled = !busy, loadPreview = loadPreview) {
                    chosen = if (f.id in chosen) chosen - f.id else chosen + f.id
                }
            }
            if (model.ignoredFiles.isNotEmpty()) {
                item {
                    Text(
                        "Skipped ${model.ignoredFiles.size} other file${if (model.ignoredFiles.size == 1) "" else "s"}: " +
                            model.ignoredFiles.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (download != null) {
                    Text("Downloading ${download.fileName}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (download.done.toFloat() / download.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${formatBytes(download.done)} of ${formatBytes(download.total)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onCancelDownload) { Text("Cancel") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selected.isEmpty()) "Choose the files to import"
                            else "${selected.size} file${if (selected.size == 1) "" else "s"} · ${formatBytes(totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { if (totalBytes > METERED_WARN_BYTES && isMetered()) confirmMetered = true else onImport(selected) },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.height(48.dp),
                        ) { Text("Import", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }

    if (confirmMetered) {
        AlertDialog(
            onDismissRequest = { confirmMetered = false },
            title = { Text("Download on mobile data?") },
            text = { Text("You're about to download ${formatBytes(totalBytes)} over a metered connection.") },
            confirmButton = { TextButton(onClick = { confirmMetered = false; onImport(selected) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmMetered = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModelHeader(model: RemoteModel) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(model.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        model.author?.let {
            Text("by $it", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            model.license?.let { AssistChip(onClick = {}, label = { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
            TextButton(onClick = { runCatching { uri.openUri(model.pageUrl) } }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Printables page")
            }
        }
    }
}

@Composable
private fun FileRow(
    f: RemoteFile, checked: Boolean, enabled: Boolean,
    loadPreview: suspend (String) -> ImageBitmap?, onToggle: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, f.previewUrl) { value = f.previewUrl?.let { loadPreview(it) } }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img != null) Image(img, f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Icon(Icons.Outlined.ViewInAr, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    f.kind.label,
                    "geometry only".takeIf { f.kind == RemoteFileKind.THREE_MF },
                    formatBytes(f.sizeBytes).takeIf { f.sizeBytes > 0 },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
