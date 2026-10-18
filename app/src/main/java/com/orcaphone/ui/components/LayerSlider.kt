package com.orcaphone.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Tall vertical slider for scrubbing layers (like OrcaSlicer's preview slider), with
 * step buttons for precise single-layer moves and a value bubble next to the thumb.
 */
@Composable
fun VerticalLayerSlider(
    value: Int,
    max: Int,
    label: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val onChangeState by rememberUpdatedState(onChange)
    var trackPx by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val thumbRadius = with(density) { 12.dp.toPx() }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    fun valueAt(y: Float): Int {
        if (max <= 0) return 0
        val usable = (trackPx - 2 * thumbRadius).coerceAtLeast(1f)
        val f = 1f - ((y - thumbRadius) / usable).coerceIn(0f, 1f)
        return (f * max).roundToInt()
    }

    Column(modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = { if (current < max) onChange(current + 1) }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, "Layer up")
        }
        Box(
            Modifier.weight(1f).width(56.dp).padding(vertical = 4.dp)
                .onSizeChanged { trackPx = it.height }
                .pointerInput(max) {
                    detectTapGestures { o -> onChangeState(valueAt(o.y)) }
                }
                .pointerInput(max) {
                    detectDragGestures { change, _ ->
                        val v = valueAt(change.position.y)
                        if (v != current) {
                            if (v % 10 == 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChangeState(v)
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxHeight().width(56.dp)) {
                val cx = size.width / 2
                val usable = size.height - 2 * thumbRadius
                val f = if (max > 0) current.toFloat() / max else 1f
                val ty = thumbRadius + (1f - f) * usable
                drawLine(track, Offset(cx, thumbRadius), Offset(cx, size.height - thumbRadius), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
                drawLine(primary, Offset(cx, ty), Offset(cx, size.height - thumbRadius), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(primary, thumbRadius, Offset(cx, ty))
                drawCircle(androidx.compose.ui.graphics.Color.White, thumbRadius * 0.45f, Offset(cx, ty))
            }
            // value bubble left of the thumb
            val usablePx = (trackPx - 2 * thumbRadius).coerceAtLeast(1f)
            val f = if (max > 0) value.toFloat() / max else 1f
            val y = (thumbRadius + (1f - f) * usablePx - with(density) { 16.dp.toPx() }).roundToInt()
            Surface(
                shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.offset { IntOffset(-with(density) { 76.dp.toPx() }.roundToInt(), y) },
            ) {
                Text(
                    label, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp).width(64.dp),
                    maxLines = 2,
                )
            }
        }
        FilledTonalIconButton(onClick = { if (current > 0) onChange(current - 1) }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, "Layer down")
        }
    }
}
