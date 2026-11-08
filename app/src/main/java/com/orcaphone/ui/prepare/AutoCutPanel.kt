package com.orcaphone.ui.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orcaphone.core.cut.AutoCut
import com.orcaphone.ui.AutoCutState
import com.orcaphone.ui.components.fmt

/** Distinct colours for the flat faces a part can be laid on. */
val FACE_PALETTE = intArrayOf(
    0x42A5F5, 0x66BB6A, 0xAB47BC, 0xFFCA28, 0xEC407A, 0x26C6DA, 0xD4E157, 0x8D6E63,
)
private const val FACE_NONE = 0x9E9E9E
private const val FACE_PICKED = 0xFF7043

/** Per-triangle colours for the auto-cut view: palette colours for flat areas, the chosen face stands out. */
fun autoCutColors(st: AutoCutState): FloatArray {
    val n = st.faces.groupOf.size
    val out = FloatArray(n * 3)
    for (t in 0 until n) {
        val g = st.faces.groupOf[t]
        var c = if (g >= 0) FACE_PALETTE[g % FACE_PALETTE.size] else FACE_NONE
        val chosen = (st.group >= 0 && g == st.group) || (st.group < 0 && t == st.triangle)
        if (st.group < 0 && t == st.triangle) c = FACE_PICKED
        var r = ((c shr 16) and 0xff) / 255f; var gr = ((c shr 8) and 0xff) / 255f; var b = (c and 0xff) / 255f
        if (st.triangle >= 0 && !chosen) { // dim the other faces once one is chosen
            r = r * 0.45f + 0.3f; gr = gr * 0.45f + 0.3f; b = b * 0.45f + 0.3f
        }
        out[t * 3] = r; out[t * 3 + 1] = gr; out[t * 3 + 2] = b
    }
    return out
}

private fun swatch(st: AutoCutState): Color {
    val c = if (st.group >= 0) FACE_PALETTE[st.group % FACE_PALETTE.size] else FACE_PICKED
    return Color(0xFF000000.toInt() or c)
}

@Composable
fun AutoCutHeader(st: AutoCutState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f), shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ContentCut, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Auto cut", style = MaterialTheme.typography.titleSmall)
                Text(st.objectName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun AutoCutPanel(
    st: AutoCutState,
    onCut: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        tonalElevation = 3.dp, shadowElevation = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val progress = st.progress
            if (progress != null) {
                Text("Cutting into ${st.plan?.parts ?: ""} parts…", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                return@Column
            }
            if (st.normal == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.TouchApp, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Which side should lie on the bed?", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Drag to turn the part, then tap the face it should lie on. Coloured faces are flat, best for printing; " +
                        "each colour is a different side.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (st.faces.count > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (g in 0 until st.faces.count) {
                            Box(Modifier.size(16.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or FACE_PALETTE[g % FACE_PALETTE.size])))
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(swatch(st)))
                    Spacer(Modifier.width(10.dp))
                    Text("Lies on this face", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("tap another face to change", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                val plan = st.plan
                Text(
                    when {
                        plan == null -> "This printer's bed is too small to cut for: it needs at least ${fmt(2 * AutoCut.MARGIN + AutoCut.JOINT + 5, 0)} mm."
                        !plan.needsCut -> "Fits on the bed this way, no cut needed."
                        else -> {
                            val c = plan.counts
                            val grid = listOfNotNull(c[0].takeIf { it > 1 }, c[1].takeIf { it > 1 }, c[2].takeIf { it > 1 }).joinToString(" × ")
                            "Cut into $grid = ${plan.parts} parts joined by dovetails, " +
                                "each up to ${fmt(plan.pieceSize.x, 0)} × ${fmt(plan.pieceSize.y, 0)} × ${fmt(plan.pieceSize.z, 0)} mm " +
                                "with ${fmt(AutoCut.MARGIN, 0)} mm free around it on its own bed."
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
                val plan = st.plan
                Button(
                    onClick = onCut, enabled = st.normal != null && plan != null,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Outlined.ContentCut, null); Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            plan == null || st.normal == null -> "Cut"
                            !plan.needsCut -> "Lay it down"
                            else -> "Cut into ${plan.parts}"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
