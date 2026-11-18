package org.rhythmeta.maimaid.ui.song

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.chart.Chart
import org.rhythmeta.maimaid.core.chart.ChartNote
import org.rhythmeta.maimaid.core.chart.HoldStartNote
import org.rhythmeta.maimaid.core.chart.SlideNote
import org.rhythmeta.maimaid.core.chart.TapNote
import org.rhythmeta.maimaid.core.chart.TimingTimeline
import org.rhythmeta.maimaid.core.chart.TouchHoldStartNote
import org.rhythmeta.maimaid.core.chart.TouchNote
import org.rhythmeta.maimaid.core.chart.render.BpmChangeFlash
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Note family of a rhythm step, coloured like the density legend. */
internal enum class RhythmStepKind(val priority: Int) {
    TAP(1),
    TOUCH(2),
    HOLD(3),
    SLIDE(4),
    BREAK(5),
}

/** Side of the note dot the duration line is drawn on. */
internal enum class DurationLineSide { NONE, ABOVE, BELOW }

/** One note time on the rhythm lane together with the measure divisor in effect there. */
internal data class RhythmStep(
    val timeMs: Double,
    val divisor: Double,
    val kind: RhythmStepKind,
    /** Notes stacked on this time; more than one is a chord and gets the yellow ring. */
    val noteCount: Int,
    /** Total length in ms (hold time, or slide launch + travel); 0 for instant notes. */
    val durationMs: Double,
    /** Slide launch (delay) length in ms before the travel starts; 0 for holds. */
    val launchMs: Double,
    /** Holds trail above the dot, slides below it. */
    val lineSide: DurationLineSide,
)

/** A BPM change point already resolved to chart time. */
internal data class BpmMark(val timeMs: Double, val bpm: Double)

/**
 * Precomputed rhythm lane content.
 *
 * Every note head becomes one step carrying the divisor of the measure it sits in, which is the
 * number simai writes in braces (`{4}`, `{12}`, …). Notes sharing a millisecond collapse into a
 * single step so chords read as one rhythm event; the strongest note family wins the colour, the
 * merged note count marks the step as a chord, and its duration feeds the hold / slide line.
 */
internal class ChartRhythmData(
    val steps: List<RhythmStep>,
    val measureTicks: List<Double>,
    private val bpmMarks: List<BpmMark>,
) {
    /** BPM in effect at [timeMs], following the chart's own BPM events. */
    fun bpmAt(timeMs: Double): Double {
        var low = 0
        var high = bpmMarks.size - 1
        var bpm = bpmMarks.firstOrNull()?.bpm ?: DEFAULT_BPM
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (bpmMarks[mid].timeMs <= timeMs) {
                bpm = bpmMarks[mid].bpm
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return bpm
    }

    companion object {
        fun from(chart: Chart, timeline: TimingTimeline): ChartRhythmData {
            val byTime = LinkedHashMap<Long, RhythmStep>()
            for (note in chart.notes) {
                val kind = note.rhythmKind() ?: continue
                val key = (note.timingMs * 1_000.0).roundToLong()
                val existing = byTime[key]
                val line = note.rhythmLine()
                byTime[key] = when {
                    existing == null -> RhythmStep(
                        timeMs = note.timingMs,
                        divisor = timeline.divisorAtBeat(note.timing),
                        kind = kind,
                        noteCount = 1,
                        durationMs = line.durationMs,
                        launchMs = line.launchMs,
                        lineSide = line.side,
                    )
                    kind.priority > existing.kind.priority -> existing.copy(
                        divisor = timeline.divisorAtBeat(note.timing),
                        kind = kind,
                        noteCount = existing.noteCount + 1,
                        durationMs = line.durationMs,
                        launchMs = line.launchMs,
                        lineSide = line.side,
                    )
                    else -> existing.copy(noteCount = existing.noteCount + 1)
                }
            }
            return ChartRhythmData(
                steps = byTime.values.sortedBy { it.timeMs },
                measureTicks = (0..chart.measures).map { timeline.msFromBeat(it * 4.0) },
                bpmMarks = chart.bpmEvents
                    .map { BpmMark(timeline.msFromBeat(it.timing), it.bpm) }
                    .sortedBy { it.timeMs },
            )
        }
    }
}

/** Length of a note and how its duration line is drawn. */
private data class RhythmLine(
    val durationMs: Double,
    val launchMs: Double,
    val side: DurationLineSide,
)

/** Hold tails and touch-hold tails are not rhythm steps; heads and taps are. */
private fun ChartNote.rhythmKind(): RhythmStepKind? = when (this) {
    is TapNote -> if (isBreak) RhythmStepKind.BREAK else RhythmStepKind.TAP
    is HoldStartNote -> RhythmStepKind.HOLD
    // Headless slides carry no head note, so they add no rhythm step.
    is SlideNote -> if (isHeadless) null else RhythmStepKind.SLIDE
    is TouchNote, is TouchHoldStartNote -> RhythmStepKind.TOUCH
    else -> null
}

/**
 * Length of the note in ms and the side its duration line belongs on.
 *
 * Holds (including touch holds) trail above the dot, slides below it. A slide reports its launch
 * delay separately so the lane can dash the launch beat and keep the travel solid; its length
 * covers the whole path (the largest `delay + duration` over its segments).
 */
private fun ChartNote.rhythmLine(): RhythmLine = when (this) {
    is HoldStartNote -> RhythmLine(
        durationMs = (60_000.0 * duration / bpm).coerceAtLeast(0.0),
        launchMs = 0.0,
        side = DurationLineSide.ABOVE,
    )
    is TouchHoldStartNote -> RhythmLine(
        durationMs = durationMs.coerceAtLeast(0.0),
        launchMs = 0.0,
        side = DurationLineSide.ABOVE,
    )
    is SlideNote -> {
        var endMs = 0.0
        var launchMs = 0.0
        val paths = maxOf(allDelayMs.size, allDurationMs.size, 1)
        for (index in 0 until paths) {
            val delay = (allDelayMs.getOrNull(index) ?: delayMs).coerceAtLeast(0.0)
            val length = (allDurationMs.getOrNull(index) ?: durationMs).coerceAtLeast(0.0)
            if (delay + length > endMs) {
                endMs = delay + length
                launchMs = delay
            }
        }
        RhythmLine(
            durationMs = endMs,
            launchMs = launchMs.coerceAtMost(endMs),
            side = DurationLineSide.BELOW,
        )
    }
    else -> RhythmLine(0.0, 0.0, DurationLineSide.NONE)
}

private fun RhythmStepKind.color(): Color = when (this) {
    RhythmStepKind.TAP -> DensityColors.tap
    RhythmStepKind.HOLD -> DensityColors.hold
    RhythmStepKind.SLIDE -> DensityColors.slide
    RhythmStepKind.TOUCH -> DensityColors.touch
    RhythmStepKind.BREAK -> DensityColors.breakNote
}

/**
 * Rhythm lane under the density timeline: the upcoming notes, each carrying the measure divisor it
 * belongs to inside its dot, so the subdivision pattern of the chart can be read at a glance.
 *
 * The lane is a zoomed look-ahead window rather than a whole-song overview: at full width a dense
 * chart puts several notes on the same pixel, which would make the divisors unreadable. Judged
 * heads are dropped and the space they leave shows the live BPM, but a hold or slide keeps its
 * duration line until the tail passes, so the line runs its full length instead of vanishing with
 * the head. Holds trail above the dot with the last stretch dashed, slides below it with the launch
 * beat dashed and the travel solid.
 */
@Composable
internal fun ChartRhythmTimeline(
    data: ChartRhythmData,
    positionMs: () -> Double,
    speedScale: Double = 1.0,
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (data.steps.isEmpty()) return
    val density = LocalDensity.current
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val active = MiuixTheme.colorScheme.onSurface
    val measureTick = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val description = stringResource(R.string.chart_rhythm_label)
    val numberSize = with(density) { 8.sp.toPx() }
    val bpmCaptionSize = with(density) { 8.sp.toPx() }
    val bpmValueSize = with(density) { 13.sp.toPx() }
    val dotRadius = with(density) { 9.dp.toPx() }
    val chordRingRadius = with(density) { 11.dp.toPx() }
    val ringWidth = with(density) { 1.5.dp.toPx() }
    val chordRingWidth = with(density) { 2.dp.toPx() }
    val currentRingWidth = with(density) { 2.8.dp.toPx() }
    val durationLineWidth = with(density) { 3.dp.toPx() }
    val dotCenterY = with(density) { 21.dp.toPx() }
    val holdLineY = with(density) { 6.dp.toPx() }
    val slideLineY = with(density) { 36.dp.toPx() }
    // Keep the numbers from touching once the dots get close; follows the font scale.
    val numberGap = with(density) { 12.dp.toPx() }.coerceAtLeast(numberSize * 1.4f)
    val bpmCaptionBaseline = with(density) { 16.dp.toPx() }
    val bpmValueBaseline = with(density) { 36.dp.toPx() }
    val bpmGap = with(density) { 16.dp.toPx() }
    val bpmFlash = remember(data) { BpmChangeFlash() }
    val dashEffect = remember(density) {
        PathEffect.dashPathEffect(
            with(density) { floatArrayOf(4.dp.toPx(), 3.dp.toPx()) },
        )
    }
    val numberPaint = remember(numberSize) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = numberSize
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val bpmCaptionPaint = remember(bpmCaptionSize) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = bpmCaptionSize
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    val bpmValuePaint = remember(bpmValueSize) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = bpmValueSize
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .semantics { contentDescription = description },
    ) {
        val width = size.width
        val anchorX = width * RHYTHM_ANCHOR_FRACTION
        val pxPerMs = (width / RHYTHM_WINDOW_MS * speedScale.coerceIn(0.1, 5.0)).toFloat()
        val position = positionMs()
        fun xAt(timeMs: Double): Float =
            anchorX + ((timeMs - position) * pxPerMs).toFloat()

        for (tickMs in data.measureTicks) {
            val x = xAt(tickMs)
            if (x < 0f || x > width) continue
            drawLine(measureTick, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        drawLine(primary, Offset(anchorX, 0f), Offset(anchorX, size.height), 2.dp.toPx())

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas

            // Left of the playhead: live BPM instead of notes that were already judged.
            val textRight = anchorX - bpmGap
            if (textRight > 0f) {
                bpmCaptionPaint.color = muted.toArgb()
                native.drawText(BPM_CAPTION, textRight, bpmCaptionBaseline, bpmCaptionPaint)
                val currentBpm = data.bpmAt(position)
                bpmValuePaint.color = bpmFlash.colorFor(currentBpm, isPlaying, active.toArgb()) ?: active.toArgb()
                native.drawText(
                    formatBpm(currentBpm),
                    textRight,
                    bpmValueBaseline,
                    bpmValuePaint,
                )
            }

            // Centre the divisor inside the dot: the baseline sits half the text height below it.
            val numberBaseline = dotCenterY - (numberPaint.ascent() + numberPaint.descent()) / 2f
            var lastNumberX = Float.NEGATIVE_INFINITY
            var currentTaken = false
            for (step in data.steps) {
                val tailMs = step.timeMs + step.durationMs
                // Keep the step around until its tail passes so the line runs to completion.
                if (tailMs < position) continue
                if (xAt(step.timeMs) > width + chordRingRadius) break
                val color = step.kind.color()

                if (step.durationMs > 0.0 && step.lineSide != DurationLineSide.NONE) {
                    val lineY = if (step.lineSide == DurationLineSide.ABOVE) holdLineY else slideLineY
                    drawDurationLine(
                        step = step,
                        position = position,
                        lineY = lineY,
                        color = color.copy(alpha = 0.55f),
                        strokeWidth = durationLineWidth,
                        dashEffect = dashEffect,
                        xAt = ::xAt,
                        clipLeft = anchorX,
                        clipRight = width,
                    )
                }

                // The dot belongs to the head only; once judged, just the line is left.
                if (step.timeMs < position) continue
                val x = xAt(step.timeMs)
                val isCurrent = !currentTaken
                if (isCurrent) currentTaken = true

                drawCircle(
                    color = color,
                    radius = dotRadius,
                    center = Offset(x, dotCenterY),
                )
                if (step.noteCount > 1) {
                    // Chord: the ring sits outside the fill so the yellow stays readable even on a
                    // yellow tap dot.
                    drawCircle(
                        color = CHORD_RING_COLOR,
                        radius = chordRingRadius,
                        center = Offset(x, dotCenterY),
                        style = Stroke(if (isCurrent) currentRingWidth else chordRingWidth),
                    )
                } else {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = dotRadius,
                        center = Offset(x, dotCenterY),
                        style = Stroke(if (isCurrent) currentRingWidth else ringWidth),
                    )
                }

                if (isCurrent || x >= lastNumberX + numberGap) {
                    numberPaint.color = contrastOn(color).toArgb()
                    native.drawText(formatDivisor(step.divisor), x, numberBaseline, numberPaint)
                    lastNumberX = x
                }
            }
        }
    }
}

/**
 * Draws the duration line of one step, clipped to the playhead and to the lane edge.
 *
 * Holds are solid and end with a dashed tail; slides start with a dashed launch beat and turn solid
 * once the path travels.
 */
private fun DrawScope.drawDurationLine(
    step: RhythmStep,
    position: Double,
    lineY: Float,
    color: Color,
    strokeWidth: Float,
    dashEffect: PathEffect,
    xAt: (Double) -> Float,
    clipLeft: Float,
    clipRight: Float,
) {
    val startMs = step.timeMs.coerceAtLeast(position)
    val endMs = step.timeMs + step.durationMs
    if (endMs <= startMs) return

    when (step.lineSide) {
        // Hold: solid body, dashed release.
        DurationLineSide.ABOVE -> {
            val splitMs = (endMs - HOLD_TAIL_DASH_MS).coerceAtLeast(step.timeMs)
            drawSegment(xAt(startMs), xAt(splitMs), lineY, color, strokeWidth, clipLeft, clipRight)
            drawSegment(
                xAt(splitMs),
                xAt(endMs),
                lineY,
                color,
                strokeWidth,
                clipLeft,
                clipRight,
                dashEffect,
            )
        }
        // Slide: dashed launch beat, solid travel.
        DurationLineSide.BELOW -> {
            val splitMs = (step.timeMs + step.launchMs).coerceAtMost(endMs)
            drawSegment(
                xAt(startMs),
                xAt(splitMs),
                lineY,
                color,
                strokeWidth,
                clipLeft,
                clipRight,
                dashEffect,
            )
            drawSegment(xAt(splitMs), xAt(endMs), lineY, color, strokeWidth, clipLeft, clipRight)
        }
        DurationLineSide.NONE -> Unit
    }
}

/** One horizontal piece of a duration line; a non-null [dashEffect] draws it dashed. */
private fun DrawScope.drawSegment(
    startX: Float,
    endX: Float,
    lineY: Float,
    color: Color,
    strokeWidth: Float,
    clipLeft: Float,
    clipRight: Float,
    dashEffect: PathEffect? = null,
) {
    val from = startX.coerceAtLeast(clipLeft)
    val to = endX.coerceAtMost(clipRight)
    if (to <= from) return
    if (dashEffect == null) {
        drawLine(
            color = color,
            start = Offset(from, lineY),
            end = Offset(to, lineY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        return
    }
    val path = Path().apply {
        moveTo(from, lineY)
        lineTo(to, lineY)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt, pathEffect = dashEffect),
    )
}

/** Dark numerals on light fills, white on dark ones, so every note colour stays readable. */
private fun contrastOn(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.5f) Color.Black.copy(alpha = 0.85f) else Color.White
}

/** Divisors are whole numbers in practice; keep a decimal only when the chart really uses one. */
private fun formatDivisor(divisor: Double): String {
    val rounded = divisor.roundToInt()
    return if (abs(divisor - rounded) < 0.01) rounded.toString() else divisor.toString()
}

/** BPM readout: whole values stay integral, fractional charts keep one decimal. */
private fun formatBpm(bpm: Double): String {
    val rounded = bpm.roundToInt()
    if (abs(bpm - rounded) < 0.05) return rounded.toString()
    return (bpm * 10.0).roundToInt().div(10.0).toString()
}

/**
 * How much of the chart the rhythm lane shows ahead of the playhead. Kept short so consecutive
 * dots stay separated instead of overlapping in dense charts.
 */
private const val RHYTHM_WINDOW_MS = 2_000.0

/** Fraction of the lane width that sits at the playhead; the rest of the left side is BPM. */
private const val RHYTHM_ANCHOR_FRACTION = 0.15f

/** The last stretch of a hold is dashed so its release point is readable. */
private const val HOLD_TAIL_DASH_MS = 200.0

private const val DEFAULT_BPM = 120.0

/** Latin acronym, matching the stage HUD; not localized on purpose. */
private const val BPM_CAPTION = "BPM"

/** Yellow ring that marks a chord (双押). */
private val CHORD_RING_COLOR = Color(0xFFFFD400)
