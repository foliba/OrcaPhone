package io.github.foliba.phorca.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.foliba.phorca.ui.Tab

/**
 * On tablets in landscape the Prepare / Preview / Device switcher sits in the middle of each screen's own
 * bottom bar instead of in a navigation bar of its own. Null everywhere else: screens then draw nothing.
 */
val LocalDockedTabs = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** Compact tab switcher for a screen's bottom bar. */
@Composable
fun TabSwitcher(tab: Tab, previewStale: Boolean, onSelect: (Tab) -> Unit) {
    Row(Modifier.selectableGroup().testTag("dockedTabs"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TabItem(Icons.Outlined.ViewInAr, "Prepare", tab == Tab.PREPARE) { onSelect(Tab.PREPARE) }
        TabItem(Icons.Outlined.Layers, "Preview", tab == Tab.PREVIEW, badge = previewStale) { onSelect(Tab.PREVIEW) }
        TabItem(Icons.Outlined.Print, "Device", tab == Tab.DEVICE) { onSelect(Tab.DEVICE) }
    }
}

@Composable
private fun TabItem(icon: ImageVector, label: String, selected: Boolean, badge: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            BadgedBox(badge = { if (badge) Badge() }) { Icon(icon, null) }
        }
        Text(
            label, style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
