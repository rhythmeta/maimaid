package org.rhythmeta.maimaid.ui.song

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.rhythmeta.maimaid.core.data.ScoreRules
import org.rhythmeta.maimaid.ui.util.ScoreStatusColors
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ScoreStatusBadges(
    dxScore: Int,
    maxDxScore: Int,
    fc: String?,
    fs: String?,
    modifier: Modifier = Modifier,
    showStars: Boolean = true,
) {
    val stars = if (showStars) ScoreRules.dxStars(dxScore, maxDxScore) else 0
    val combo = ScoreRules.displayFc(fc)
    val sync = ScoreRules.displayFs(fs)?.let { if (it == "S") "SYNC" else it }
    if (stars == 0 && combo == null && sync == null) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stars > 0) ScoreTintBadge(text = "$stars ★", color = Color(0xFFFFB300))
        combo?.let {
            ScoreTintBadge(it, ScoreStatusColors.combo(fc) ?: MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        sync?.let {
            ScoreTintBadge(it, ScoreStatusColors.sync(fs) ?: MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
