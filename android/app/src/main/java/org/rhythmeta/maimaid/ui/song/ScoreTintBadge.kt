package org.rhythmeta.maimaid.ui.song

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.rhythmeta.maimaid.ui.components.SquircleExtension
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ScoreTintBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .squircleSurface(color.copy(alpha = 0.14f), 5.dp, SquircleExtension)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
