package com.orcaphone.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.withContext

const val SOURCE_URL = "https://github.com/foliba/OrcaPhone"

/** A bundled third-party component. [asset] is the bundled licence text, [url] the licence page otherwise. */
private data class Component(
    val name: String,
    val detail: String,
    val license: String,
    val asset: String?,
    val url: String? = null,
)

/** Data bundled from other projects (not a Gradle dependency, so not in the generated list). */
private val BUNDLED_DATA = listOf(
    Component(
        "OrcaSlicer system profiles",
        "© SoftFever and the OrcaSlicer contributors; profiles derived from Bambu Studio (Bambu Lab) and PrusaSlicer (Prusa Research)",
        "AGPL-3.0", "AGPL-3.0.txt",
    ),
)

/** Licence texts shipped in assets/licenses, by SPDX id. */
private val LICENSE_ASSETS = mapOf("Apache-2.0" to "Apache-2.0.txt", "AGPL-3.0" to "AGPL-3.0.txt", "AGPL-3.0-only" to "AGPL-3.0.txt")

/**
 * Reads assets/licenses/libraries.json, generated at build time by the licensee Gradle plugin from
 * every library in the APK.
 */
private fun loadLibraries(context: android.content.Context): List<Component> = runCatching {
    val text = context.assets.open("licenses/libraries.json").bufferedReader().use { it.readText() }
    Json.parseToJsonElement(text).jsonArray.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        val group = o.str("groupId") ?: return@mapNotNull null
        val artifact = o.str("artifactId") ?: return@mapNotNull null
        val coords = "$group:$artifact ${o.str("version") ?: ""}".trim()
        if (group == "org.locationtech.jts") {
            return@mapNotNull Component(o.str("name") ?: "JTS Topology Suite", coords, "EDL-1.0 (chosen from EDL-1.0 / EPL-2.0)", "EDL-1.0.txt")
        }
        val spdx = (o["spdxLicenses"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val unknown = (o["unknownLicenses"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val ids = spdx.mapNotNull { it.str("identifier") } + unknown.mapNotNull { it.str("name") }
        Component(
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: artifact,
            detail = coords,
            license = ids.joinToString(" / ").ifBlank { "see project page" },
            asset = spdx.firstNotNullOfOrNull { LICENSE_ASSETS[it.str("identifier")] },
            url = (spdx + unknown).firstNotNullOfOrNull { it.str("url") } ?: (o["scm"] as? JsonObject)?.str("url"),
        )
    }.sortedBy { it.name.lowercase() }
}.getOrDefault(emptyList())

/** App information, AGPL notices and third-party licences. */
@Composable
fun AboutScreen(onClose: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var openTitle by rememberSaveable { mutableStateOf("") }
    val shown = open
    BackHandler { if (shown != null) open = null else onClose() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (shown != null) IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                else IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
                Text(if (shown != null) openTitle else "About & licences", style = MaterialTheme.typography.titleLarge)
            }
            if (shown != null) LicenseText(shown) else Overview(onOpen = { title, asset -> openTitle = title; open = asset })
        }
    }
}

@Composable
private fun Overview(onOpen: (String, String) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    val libraries by produceState(emptyList<Component>()) { value = withContext(Dispatchers.IO) { loadLibraries(context) } }
    val open: (Component) -> Unit = { c ->
        when {
            c.asset != null -> onOpen(c.name, c.asset)
            c.url != null -> runCatching { uri.openUri(c.url) }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("OrcaPhone $version", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("Copyright © 2026 the OrcaPhone contributors", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This program is free software: you can redistribute it and/or modify it under the terms of the " +
                            "GNU Affero General Public License, version 3. It comes with ABSOLUTELY NO WARRANTY.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "OrcaPhone is an independent project and is not affiliated with or endorsed by OrcaSlicer / SoftFever, " +
                            "Prusa Research, Printables or Bambu Lab.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = { onOpen("GNU AGPL v3.0", "AGPL-3.0.txt") }) { Text("Licence") }
                        TextButton(onClick = { runCatching { uri.openUri(SOURCE_URL) } }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.padding(start = 6.dp)); Text("Source code")
                        }
                    }
                }
            }
        }
        item { SectionTitle("Bundled data") }
        items(BUNDLED_DATA) { c -> ComponentRow(c) { open(c) } }
        item { SectionTitle("Libraries (${libraries.size})") }
        items(libraries) { c -> ComponentRow(c) { open(c) } }
        item {
            Text(
                "Models imported from Printables keep the licence chosen by their authors, shown before importing. " +
                    "Please respect it when printing or sharing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ComponentRow(c: Component, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(c.name, style = MaterialTheme.typography.bodyLarge)
        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(c.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun LicenseText(asset: String) {
    val context = LocalContext.current
    val text by produceState("Loading…", asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/$asset").bufferedReader().use { it.readText() } }
                .getOrElse { "Licence text not found." }
        }
    }
    SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
