package com.orcaphone.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Switches between build plates: ‹ › arrows for one-handed stepping, a chip per bed for jumping, and an
 * "n/m" indicator. [checked] marks beds with a ready slice; [trailing] holds screen-specific actions.
 */
@Composable
fun BedBar(
    count: Int,
    active: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    chipLabel: (Int) -> String = { "Bed ${it + 1}" },
    checked: (Int) -> Boolean = { false },
    trailing: @Composable () -> Unit = {},
) {
    val list = rememberLazyListState()
    LaunchedEffect(active, count) { if (active in 0 until count) list.animateScrollToItem(active) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = 2.dp, shadowElevation = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelect(active - 1) }, enabled = active > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous bed")
            }
            LazyRow(
                state = list, modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(count) { i ->
                    FilterChip(
                        selected = i == active,
                        onClick = { onSelect(i) },
                        label = { Text(chipLabel(i)) },
                        leadingIcon = if (checked(i)) {
                            { Icon(Icons.Filled.Check, "Sliced", Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
            Text(
                "${active + 1}/$count", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp),
            )
            IconButton(onClick = { onSelect(active + 1) }, enabled = active < count - 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next bed")
            }
            trailing()
        }
    }
}
