package com.orcaphone.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.orcaphone.gl.GlViewport
import com.orcaphone.gl.OrbitCamera
import com.orcaphone.gl.PrepareScene
import com.orcaphone.gl.PreviewScene
import com.orcaphone.gl.ViewportListener

/** The OpenGL plate view embedded in Compose. */
@Composable
fun Viewport3D(
    camera: OrbitCamera,
    prepare: PrepareScene?,
    preview: PreviewScene?,
    editable: Boolean,
    listener: ViewportListener,
    tick: Int,
    modifier: Modifier = Modifier,
) {
    val dark = com.orcaphone.ui.theme.LocalDarkTheme.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<GlViewport?>(null) }
    DisposableEffect(lifecycle, view) {
        val v = view
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> v?.onResume()
                Lifecycle.Event.ON_PAUSE -> v?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    AndroidView(
        factory = { ctx -> GlViewport(ctx, camera).also { view = it } },
        modifier = modifier,
        update = { v ->
            v.listener = listener
            v.editable = editable
            v.renderer.dark = dark
            v.renderer.prepare = prepare
            v.renderer.preview = preview
            v.tag = tick
            v.requestRender()
        },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int, max: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min) {
            Icon(Icons.Filled.Remove, "Decrease")
        }
        Text(
            value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.width(40.dp),
        )
        FilledTonalIconButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, enabled = value < max) {
            Icon(Icons.Filled.Add, "Increase")
        }
    }
}

@Composable
fun LabeledSlider(
    label: String, value: Float, valueText: String, onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>, steps: Int = 0, onDone: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone)
    }
}

/** Parses user input accepting both '.' and ',' decimal separators. */
fun parseNumber(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

fun fmt(v: Double, decimals: Int): String = com.orcaphone.core.gcode.NumFmt.format(v, decimals)
