package org.rhythmeta.maimaid.ui.song

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.rhythmeta.maimaid.ui.components.SquircleExtension
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HistorySortOption(
    text: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = if (selected) accentColor else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .squircleSurface(
                color = if (selected) accentColor.copy(alpha = 0.12f) else Color.Transparent,
                cornerRadius = 8.dp,
                extension = SquircleExtension,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}
