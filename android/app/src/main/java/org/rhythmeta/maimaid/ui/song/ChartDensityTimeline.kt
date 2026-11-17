package org.rhythmeta.maimaid.ui.song

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.chart.ChartNote
import org.rhythmeta.maimaid.core.chart.HoldStartNote
import org.rhythmeta.maimaid.core.chart.SlideNote
import org.rhythmeta.maimaid.core.chart.TapNote
import org.rhythmeta.maimaid.core.chart.TouchHoldStartNote
import org.rhythmeta.maimaid.core.chart.TouchNote
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Note-type colors used by the density timeline, matching the reference app. */
private object DensityColors {
    val tap = Color(0xFFFFD700)
    val hold = Color(0xFFFF8C00)
    val slide = Color(0xFF00CED1)
    val touch = Color(0xFF0080FF)
    val breakNote = Color(0xFFFF69B4)
}

/** One 500 ms bucket of the density timeline. */
data class DensityBucket(
    val startMs: Double,
    val tap: Int = 0,
    val hold: Int = 0,
    val slide: Int = 0,
    val touch: Int = 0,
    val breakNote: Int = 0,
) {
    val total: Int get() = tap + hold + slide + touch + breakNote
}

/** Precomputed density data for the chart timeline. */
class ChartDensityData(
    val buckets: List<DensityBucket>,
    val durationMs: Double,
    val maxCount: Int,
) {
    companion object {
        fun from(notes: List<ChartNote>, durationMs: Double, bucketMs: Double = 500.0): ChartDensityData {
            if (durationMs <= 0) return ChartDensityData(emptyList(), durationMs, 1)
            val bucketCount = floor(durationMs / bucketMs).toInt() + 1
            val counts = Array(bucketCount) { IntArray(5) }
            for (note in notes) {
                if (note.timingMs < 0 || note.timingMs > durationMs) continue
                val index = floor(note.timingMs / bucketMs).toInt()
                if (index !in 0 until bucketCount) continue
                when (note) {
                    is TapNote -> if (note.isBreak) counts[index][4]++ else counts[index][0]++
                    is HoldStartNote -> counts[index][1]++
                    is SlideNote -> counts[index][2]++
                    is TouchNote, is TouchHoldStartNote -> counts[index][3]++
                    else -> Unit
                }
            }
            val buckets = counts.mapIndexed { index, values ->
                DensityBucket(
                    startMs = index * bucketMs,
                    tap = values[0],
                    hold = values[1],
                    slide = values[2],
                    touch = values[3],
                    breakNote = values[4],
                )
            }
            val maxCount = buckets.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
            return ChartDensityData(buckets, durationMs, maxCount)
        }
    }
}

/**
 * Stacked note-density timeline with a seekable playhead, mirroring the reference app's
 * `ChartDensityTimeline`.
 */
@Composable
internal fun ChartDensityTimeline(
    data: ChartDensityData,
    positionMs: () -> Double,
    onScrubStart: (Double) -> Unit,
    onScrub: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (data.buckets.isEmpty()) return
    val density = LocalDensity.current
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val border = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val description = stringResource(R.string.chart_density_label)
    val labelTextSize = with(density) { 9.sp.toPx() }
    val badgeTextSize = with(density) { 10.sp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(data.durationMs) {
                if (data.durationMs <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun seek(x: Float): Double =
                        (x / size.width.toFloat()).coerceIn(0f, 1f) * data.durationMs
                    onScrubStart(seek(down.position.x))
                    drag(down.id) { change ->
                        onScrub(seek(change.position.x))
                        change.consume()
                    }
                    onScrubEnd()
                }
            },
    ) {
        val width = size.width
        val height = size.height
        val barsTop = 14.dp.toPx()
        val barsHeight = 32.dp.toPx()
        val barsBottom = barsTop + barsHeight
        val markerTop = 12.dp.toPx()
        val markerIntervalMs = 30_000.0
        val duration = data.durationMs
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelTextSize
            color = muted.toArgb()
            textAlign = android.graphics.Paint.Align.LEFT
        }
        val badgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = badgeTextSize
            color = Color.White.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            // 30 s grid lines.
            var markerMs = markerIntervalMs
            while (markerMs < duration) {
                val x = (markerMs / duration * width).toFloat()
                drawLine(
                    color = border,
                    start = Offset(x, markerTop),
                    end = Offset(x, height),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
                markerMs += markerIntervalMs
            }

            // Stacked bars, bottom-up: tap, hold, slide, touch, break.
            val bucketWidth = 500.0 / duration * width
            for (bucket in data.buckets) {
                if (bucket.total == 0) continue
                val left = (bucket.startMs / duration * width).toFloat()
                val barHeight = max(2.dp.toPx(), bucket.total / data.maxCount.toFloat() * barsHeight)
                var y = barsBottom
                fun segment(count: Int, color: Color) {
                    if (count == 0) return
                    val segmentHeight = barHeight * count / bucket.total
                    drawRect(
                        color = color,
                        topLeft = Offset(left, y - segmentHeight),
                        size = Size(max(1.dp.toPx(), bucketWidth.toFloat()), segmentHeight),
                    )
                    y -= segmentHeight
                }
                segment(bucket.tap, DensityColors.tap)
                segment(bucket.hold, DensityColors.hold)
                segment(bucket.slide, DensityColors.slide)
                segment(bucket.touch, DensityColors.touch)
                segment(bucket.breakNote, DensityColors.breakNote)
            }

            // Time labels, edge-clamped like the reference.
            var labelMs = 0.0
            while (labelMs <= duration) {
                val text = formatDensityTime(labelMs)
                val textWidth = labelPaint.measureText(text)
                val rawX = (labelMs / duration * width).toFloat()
                val x = when {
                    labelMs == 0.0 -> 0f
                    labelMs >= duration -> width - textWidth
                    else -> rawX - textWidth / 2f
                }
                native.drawText(text, x, 10.dp.toPx(), labelPaint)
                labelMs += markerIntervalMs
            }

            // Playhead + current-time badge.
            val playhead = positionMs().coerceIn(0.0, duration)
            val x = (playhead / duration * width).toFloat()
            drawLine(
                color = primary,
                start = Offset(x, markerTop),
                end = Offset(x, height),
                strokeWidth = 2.dp.toPx(),
            )
            val badgeText = formatDensityTime(playhead)
            val badgeWidth = badgePaint.measureText(badgeText) + 8.dp.toPx()
            val badgeHeight = 16.dp.toPx()
            val badgeLeft = (x - badgeWidth / 2f).coerceIn(0f, width - badgeWidth)
            drawRoundRect(
                color = primary,
                topLeft = Offset(badgeLeft, 0f),
                size = Size(badgeWidth, badgeHeight),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            native.drawText(
                badgeText,
                badgeLeft + badgeWidth / 2f,
                badgeHeight / 2f - (badgePaint.descent() + badgePaint.ascent()) / 2f,
                badgePaint,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartDensityLegend(modifier: Modifier = Modifier) {
    // Note-type names stay in English in every locale, matching the reference legend.
    val items = listOf(
        DensityColors.tap to "Tap",
        DensityColors.hold to "Hold",
        DensityColors.slide to "Slide",
        DensityColors.touch to "Touch",
        DensityColors.breakNote to "Break",
    )
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (color, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

private fun formatDensityTime(timeMs: Double): String {
    val totalSeconds = (timeMs / 1000.0).toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
