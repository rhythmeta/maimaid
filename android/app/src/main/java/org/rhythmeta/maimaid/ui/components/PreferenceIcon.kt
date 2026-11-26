package org.rhythmeta.maimaid.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A monochrome leading icon for preference rows, matching the Settings rows' icon tint
 * (`onSurfaceVariantActions`) instead of a coloured tile.
 */
@Composable
fun PreferenceIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .padding(end = 6.dp)
            .size(24.dp),
        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}
