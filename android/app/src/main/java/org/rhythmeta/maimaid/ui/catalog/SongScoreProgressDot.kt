package org.rhythmeta.maimaid.ui.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.rhythmeta.maimaid.core.database.ScoreEntity
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.ui.util.SongVisualUtils

/** Achievement rate where the hollow ring starts filling with the accent color. */
private const val RingStartRate = 97.0

/** Achievement rate where the hollow ring is complete and the inner dot starts filling. */
private const val RingEndRate = 100.0

/**
 * Difficulty progress dot shown next to a sheet in the catalog.
 *
 * The hollow ring fills from [RingStartRate] (97%) to [RingEndRate] (100%), then the inner dot
 * fills from 100% to 101%. Both stages use the same difficulty accent color, so a sheet at 98%
 * shows one third of the ring in accent color and a sheet at 101% shows a solid dot.
 */
@Composable
internal fun SongScoreProgressDot(
    sheet: SheetEntity,
    score: ScoreEntity?,
    darkTheme: Boolean,
) {
    val color = SongVisualUtils.difficultyColor(sheet.difficulty, sheet.type, darkTheme)
    val achievement = score?.achievement?.takeIf { it > 0.0 } ?: 0.0
    val ringProgress = ((achievement - RingStartRate) / (RingEndRate - RingStartRate))
        .coerceIn(0.0, 1.0)
        .toFloat()
    val dotProgress = (achievement - RingEndRate)
        .coerceIn(0.0, 1.0)
        .toFloat()

    Canvas(modifier = Modifier.size(8.dp)) {
        val outerStroke = 1.2.dp.toPx()
        val ringDiameter = size.minDimension - outerStroke
        val ringTopLeft = Offset((size.width - ringDiameter) / 2f, (size.height - ringDiameter) / 2f)

        drawCircle(
            color = color.copy(alpha = 0.3f),
            radius = ringDiameter / 2f,
            style = Stroke(width = outerStroke),
        )
        if (ringProgress > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = ringProgress * 360f,
                useCenter = false,
                topLeft = ringTopLeft,
                size = Size(ringDiameter, ringDiameter),
                style = Stroke(width = outerStroke, cap = StrokeCap.Butt),
            )
        }
        if (dotProgress > 0f) {
            val innerSize = 4.dp.toPx()
            val inset = (size.minDimension - innerSize) / 2f
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = dotProgress * 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(innerSize, innerSize),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Butt),
            )
        }
    }
}
