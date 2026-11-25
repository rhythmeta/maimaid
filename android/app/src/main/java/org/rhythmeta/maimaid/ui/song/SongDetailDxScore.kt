package org.rhythmeta.maimaid.ui.song

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import org.rhythmeta.maimaid.core.data.ScoreRules
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SongDetailDxScore(dxScore: Int, maxDxScore: Int) {
    val progress = ScoreRules.dxStarProgress(dxScore, maxDxScore)
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = if (maxDxScore > 0) "$dxScore / $maxDxScore" else dxScore.toString(),
            style = MiuixTheme.textStyles.body2,
        )
        if (progress != null) {
            val current = "${progress.stars}★ +${progress.pointsAboveMinimum}"
            Text(
                text = progress.pointsToNextStar?.let { remaining ->
                    "$current · ${progress.stars + 1}★ -$remaining"
                } ?: current,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
        }
    }
}
