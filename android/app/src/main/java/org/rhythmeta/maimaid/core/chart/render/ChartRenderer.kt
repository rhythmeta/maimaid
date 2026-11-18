package org.rhythmeta.maimaid.core.chart.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import org.rhythmeta.maimaid.core.chart.Chart
import org.rhythmeta.maimaid.core.chart.ChartConstants
import org.rhythmeta.maimaid.core.chart.ChartKeyDensity
import org.rhythmeta.maimaid.core.chart.ChartNote
import org.rhythmeta.maimaid.core.chart.ChartRenderConfig
import org.rhythmeta.maimaid.core.chart.HoldEndNote
import org.rhythmeta.maimaid.core.chart.HoldStartNote
import org.rhythmeta.maimaid.core.chart.PreparedChart
import org.rhythmeta.maimaid.core.chart.RenderNoteMeta
import org.rhythmeta.maimaid.core.chart.SlideNote
import org.rhythmeta.maimaid.core.chart.TapNote
import org.rhythmeta.maimaid.core.chart.TouchHoldStartNote
import org.rhythmeta.maimaid.core.chart.TouchNote
import org.rhythmeta.maimaid.core.chart.TimingTimeline

/**
 * Frame driver for the chart stage, a direct port of the reference `MainRenderer.renderFrame`
 * layer order: stage → fireworks → HUD → tracks → slide stars → approach → heads → touches →
 * effects.
 */
class ChartRenderer {
    private val context = RenderContext(Canvas(), 0f, 0f, 0f, 0f, ChartRenderConfig())
    private val notePainter = NotePainter(context)
    private val slidePainter = SlidePainter(context, notePainter)
    private val holdPainter = HoldPainter(context)
    private val touchPainter = TouchPainter(context)
    private val effectsPainter = EffectsPainter(context, notePainter, touchPainter)
    private val stagePainter = StagePainter(context)

    private val headFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var preparedChart: Chart? = null
    private var prepared: PreparedChart? = null
    private var timelineChart: Chart? = null
    private var timeline: TimingTimeline? = null
    private var keyDensityChart: Chart? = null
    private var keyPressTimes = DoubleArray(0)
    private val touchQueue = ArrayList<TouchPainter.TouchDrawCommand>()
    private val approachArcs = ArrayList<ApproachArc>()
    private val clipPath = Path()
    private val clipRect = RectF()
    private var clipRadius = -1f

    var config: ChartRenderConfig = ChartRenderConfig()

    /** Last rendered frame geometry, exposed for screenshot tests and overlays. */
    val lastFrame: FrameInfo
        get() = FrameInfo(
            centerX = context.centerX,
            centerY = context.centerY,
            radius = context.radius,
            logicalSize = context.logicalSize,
            currentTimeMs = lastTimeMs,
            currentBeats = lastBeats,
        )

    data class FrameInfo(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val logicalSize: Float,
        val currentTimeMs: Double,
        val currentBeats: Double,
    )

    private var lastTimeMs = 0.0
    private var lastBeats = 0.0

    fun prepare(chart: Chart): PreparedChart {
        if (preparedChart !== chart || prepared == null) {
            prepared = PreparedChart.build(chart)
            preparedChart = chart
        }
        return prepared!!
    }

    private fun keyPressTimesFor(chart: Chart): DoubleArray {
        if (keyDensityChart !== chart) {
            keyPressTimes = ChartKeyDensity.pressTimes(chart.notes)
            keyDensityChart = chart
        }
        return keyPressTimes
    }

    fun timelineFor(chart: Chart): TimingTimeline {
        if (timelineChart !== chart || timeline == null) {
            timeline = TimingTimeline.fromChart(chart)
            timelineChart = chart
        }
        return timeline!!
    }

    /** Renders one frame. [sizePx] is the square stage side in device pixels. */
    fun render(
        canvas: Canvas,
        sizePx: Float,
        chart: Chart,
        currentBeats: Double,
        beatsPerMeasure: Double,
        config: ChartRenderConfig,
        isPlaying: Boolean,
        background: Bitmap? = null,
        sensorImage: Bitmap? = null,
    ) {
        this.config = config
        context.config = config
        context.canvas = canvas
        context.logicalSize = sizePx
        context.centerX = sizePx / 2f
        context.centerY = sizePx / 2f
        context.radius = sizePx * 0.45f

        val prepared = prepare(chart)
        val timing = timelineFor(chart)
        val currentTimeMs = timing.msFromBeat(currentBeats)
        val currentBpm = timing.bpmAtBeat(currentBeats)
        val divisor = timing.divisorAtBeat(currentBeats)
        lastTimeMs = currentTimeMs
        lastBeats = currentBeats

        stagePainter.clear(background)
        stagePainter.renderJudgmentLine(sensorImage)

        if (config.showFireworks && prepared.fireworkTouches.isNotEmpty()) {
            canvas.save()
            clipCircle(canvas, sizePx / 2f)
            effectsPainter.renderTouchFireworks(prepared.fireworkTouches, currentTimeMs)
            canvas.restore()
        }

        if (config.showBpm) {
            stagePainter.renderBpmDisplay(
                currentBpm,
                currentBeats,
                divisor,
                isPlaying,
                keysPerSecond = ChartKeyDensity.keysPerSecond(keyPressTimesFor(chart), currentTimeMs),
            )
        }
        if (config.showNoteTotal || config.showBreakCount) {
            stagePainter.renderNoteCounts(
                totalNotes = prepared.noteCompletionTimes.size,
                completedNotes = prepared.countCompleted(prepared.noteCompletionTimes, currentTimeMs),
                totalBreaks = prepared.breakCompletionTimes.size,
                completedBreaks = prepared.countCompleted(
                    prepared.breakCompletionTimes,
                    currentTimeMs,
                ),
                totalBreaksNoEx = prepared.breakNoExCompletionTimes.size,
                completedBreaksNoEx = prepared.countCompleted(
                    prepared.breakNoExCompletionTimes,
                    currentTimeMs,
                ),
            )
        }

        canvas.save()
        clipCircle(canvas, sizePx / 2f)

        val lookAheadMs = ChartConstants.BASE_APPROACH_TIME_MS / config.hiSpeed / prepared.minHiSpeed

        renderSlideTracks(prepared, currentTimeMs, lookAheadMs)
        renderSlideStars(prepared, currentTimeMs, lookAheadMs)
        renderApproachIndicators(prepared, currentTimeMs, currentBeats, lookAheadMs)
        renderLayeredHeads(prepared, currentTimeMs, currentBeats, lookAheadMs)
        renderTouchBorders(prepared, currentTimeMs, lookAheadMs)
        renderTouches(prepared, currentTimeMs, lookAheadMs)
        if (config.showHitEffect) {
            renderEffects(prepared, currentTimeMs, lookAheadMs)
        }
        canvas.restore()
    }

    /** Clips the stage to its disc; the path is rebuilt only when the stage size changes. */
    private fun clipCircle(canvas: Canvas, radius: Float) {
        if (clipRadius != radius) {
            clipRect.set(
                context.centerX - radius,
                context.centerY - radius,
                context.centerX + radius,
                context.centerY + radius,
            )
            clipPath.reset()
            clipPath.addOval(clipRect, Path.Direction.CW)
            clipRadius = radius
        }
        canvas.clipPath(clipPath)
    }

    private fun renderSlideTracks(
        prepared: PreparedChart,
        currentTimeMs: Double,
        lookAheadMs: Double,
    ) {
        val range = prepared.slideIndex.range(currentTimeMs, lookAheadMs)
        for (index in range.last downTo range.first) {
            val note = prepared.slides.getOrNull(index) ?: continue
            val meta = prepared.metaOf(note)
            slidePainter.renderSlide(
                note,
                currentTimeMs,
                SlidePainter.RenderMode.TRACKS,
                meta.simultaneousSlideCount >= 2,
            )
        }
    }

    private fun renderSlideStars(
        prepared: PreparedChart,
        currentTimeMs: Double,
        lookAheadMs: Double,
    ) {
        val range = prepared.slideIndex.range(currentTimeMs, lookAheadMs)
        for (index in range.last downTo range.first) {
            val note = prepared.slides.getOrNull(index) ?: continue
            val meta = prepared.metaOf(note)
            slidePainter.renderSlide(
                note,
                currentTimeMs,
                SlidePainter.RenderMode.STARS,
                meta.simultaneousSlideCount >= 2,
            )
        }
    }

    private fun renderApproachIndicators(
        prepared: PreparedChart,
        currentTimeMs: Double,
        currentBeats: Double,
        lookAheadMs: Double,
    ) {
        val range = prepared.approachIndex.range(currentTimeMs, lookAheadMs)
        if (range.isEmpty()) return
        for (groupIndex in range) {
            val group = prepared.approachGroups.getOrNull(groupIndex) ?: continue
            val visible = ArrayList<Pair<Int, org.rhythmeta.maimaid.core.chart.NoteRenderPosition>>(4)
            for (item in group.notes) {
                val note = item.note
                val position = item.position
                val isHold = note is HoldStartNote
                val isSlide = note is SlideNote
                if (!isHold && !isSlide) continue
                val pos = if (isHold) {
                    notePainter.calculateNotePosition(note, currentTimeMs)
                } else {
                    slidePainter.calculateSlideStartPosition(note as SlideNote, currentTimeMs)
                }
                val meta = prepared.metaOf(note)
                val isSimultaneous = meta.simultaneousNoteCount >= 2
                val color = if (isHold) {
                    val hold = note as HoldStartNote
                    when {
                        hold.isBreakHold -> ChartConstants.Colors.BREAK_ORANGE
                        isSimultaneous -> ChartConstants.Colors.SIMULTANEOUS_GOLD
                        else -> ChartConstants.Colors.TAP_PINK
                    }
                } else {
                    val slide = note as SlideNote
                    when {
                        slide.isStartBreak -> ChartConstants.Colors.BREAK_ORANGE
                        isSimultaneous -> ChartConstants.Colors.SIMULTANEOUS_GOLD
                        slide.hasTapHead -> ChartConstants.Colors.TAP_PINK
                        else -> ChartConstants.Colors.SLIDE_CYAN
                    }
                }
                if (!pos.visible) continue
                if (note.timing - currentBeats <= 0) continue
                notePainter.renderApproachArc(position, pos.x, pos.y, color)
                visible += position to pos
            }
            if (visible.size >= 2) {
                val first = visible.first().second
                val distance = hypot(first.x - context.centerX, first.y - context.centerY)
                for (i in visible.indices) {
                    for (j in i + 1 until visible.size) {
                        notePainter.renderSimultaneousConnector(
                            visible[i].first,
                            visible[j].first,
                            distance,
                            ChartConstants.Colors.SIMULTANEOUS_GOLD,
                        )
                    }
                }
            }
        }
    }

    private fun renderLayeredHeads(
        prepared: PreparedChart,
        currentTimeMs: Double,
        currentBeats: Double,
        lookAheadMs: Double,
    ) {
        val range = prepared.layeredIndex.range(currentTimeMs, lookAheadMs)
        if (range.isEmpty()) return

        approachArcs.clear()
        for (index in range.last downTo range.first) {
            val head = prepared.layeredHeads.getOrNull(index) ?: continue
            if (head is org.rhythmeta.maimaid.core.chart.LayeredHead.Tap) {
                val tap = head.note
                if (tap.timingMs <= currentTimeMs) continue
                val pos = notePainter.calculateNotePosition(tap, currentTimeMs)
                if (!pos.visible) continue
                val meta = prepared.metaOf(tap)
                approachArcs += ApproachArc(
                    tap.position,
                    hypot(pos.x - context.centerX, pos.y - context.centerY),
                    tapApproachColor(tap, meta.simultaneousNoteCount >= 2),
                )
            }
        }
        notePainter.renderApproachArcsBatch(approachArcs)

        for (index in range.last downTo range.first) {
            val head = prepared.layeredHeads.getOrNull(index) ?: continue
            when (head) {
                is org.rhythmeta.maimaid.core.chart.LayeredHead.Tap ->
                    renderSingleTap(prepared, head.note, currentTimeMs)
                is org.rhythmeta.maimaid.core.chart.LayeredHead.Hold ->
                    renderSingleHold(prepared, head.note, currentTimeMs, currentBeats)
                is org.rhythmeta.maimaid.core.chart.LayeredHead.SlideStart ->
                    renderSingleSlideStart(prepared, head.note, currentTimeMs)
            }
        }
    }

    private fun tapApproachColor(tap: TapNote, isSimultaneous: Boolean): Int = when {
        tap.isBreak -> ChartConstants.Colors.BREAK_ORANGE
        isSimultaneous -> ChartConstants.Colors.SIMULTANEOUS_GOLD
        tap.isStar -> ChartConstants.Colors.SLIDE_CYAN
        else -> ChartConstants.Colors.TAP_PINK
    }

    private fun renderSingleTap(prepared: PreparedChart, tap: TapNote, currentTimeMs: Double) {
        val pos = notePainter.calculateNotePosition(tap, currentTimeMs)
        if (!pos.visible) return
        val meta = prepared.metaOf(tap)
        val isSimultaneous = meta.simultaneousNoteCount >= 2

        if (tap.isStar) {
            val rotation = if (tap.isSpinningStar) {
                -currentTimeMs * (2 * PI) / 1000.0
            } else {
                0.0
            }
            renderStarHead(
                pos.x,
                pos.y,
                pos.scale,
                starHeadColor(tap.timing, tap.isBreak, isSimultaneous),
                rotation,
                tap.isEx,
                tap.isBreak,
                isSimultaneous,
            )
        } else {
            notePainter.renderTapNote(
                pos.x,
                pos.y,
                pos.scale,
                tap.position,
                tap.isBreak,
                isSimultaneous,
                tap.isEx,
                tap.timing,
                exScale(),
            )
        }

        val breakIndex = meta.noExBreakIndex
        if (config.showBreakIndex && tap.isBreak && breakIndex != null && !tap.isEx) {
            notePainter.renderBreakIndex(pos.x, pos.y, pos.scale, breakIndex)
        }
    }

    private fun renderSingleHold(
        prepared: PreparedChart,
        hold: HoldStartNote,
        currentTimeMs: Double,
        currentBeats: Double,
    ) {
        val startPos = notePainter.calculateNotePosition(hold, currentTimeMs)
        if (!startPos.visible) return
        val holdEnd = prepared.holdEndMap["${hold.position}:${hold.timing}"] ?: return
        val endPos = notePainter.calculateNotePosition(holdEnd, currentTimeMs)
        val meta = prepared.metaOf(hold)
        val isSimultaneous = meta.simultaneousNoteCount >= 2
        val ddr = ddrColor(hold.timing)
        val colors = gradientColors(ddr, hold.isBreakHold, isSimultaneous)
        holdPainter.renderHold(
            startPos,
            endPos,
            hold.position,
            colors,
            hold.isEx,
            hold,
            holdEnd,
            currentTimeMs,
            hold.isBreakHold,
            isSimultaneous,
            exScale(),
        )
        val breakIndex = meta.noExBreakIndex
        if (config.showBreakIndex && hold.isBreakHold && breakIndex != null && !hold.isEx) {
            notePainter.renderBreakIndex(startPos.x, startPos.y, startPos.scale, breakIndex)
        }
    }

    private fun renderSingleSlideStart(
        prepared: PreparedChart,
        slide: SlideNote,
        currentTimeMs: Double,
    ) {
        val pos = slidePainter.calculateSlideStartPosition(slide, currentTimeMs)
        if (!pos.visible) return
        val meta = prepared.metaOf(slide)
        val isSimultaneous = meta.simultaneousNoteCount >= 2

        if (slide.hasTapHead) {
            notePainter.renderTapNote(
                pos.x,
                pos.y,
                pos.scale,
                slide.position,
                slide.isStartBreak,
                isSimultaneous,
                slide.isEx,
                slide.timing,
                exScale(),
            )
        } else {
            val color = starHeadColor(slide.timing, slide.isStartBreak, isSimultaneous)
            val rotation = if (config.slideRotation) {
                slidePainter.calculateStarRotation(slide, currentTimeMs)
            } else {
                0.0
            }
            if (slide.isSplitSlide) {
                val noteSize = starNoteSize(pos.scale)
                if (slide.isEx) {
                    slidePainter.renderExSplitStarRing(
                        pos.x,
                        pos.y,
                        noteSize,
                        slide.isStartBreak,
                        isSimultaneous,
                        exScale(),
                    )
                }
                renderSplitSlideStar(pos.x, pos.y, noteSize, color, rotation, slide.isEx)
            } else {
                renderStarHead(
                    pos.x,
                    pos.y,
                    pos.scale,
                    color,
                    rotation,
                    slide.isEx,
                    slide.isStartBreak,
                    isSimultaneous,
                )
            }
        }

        val breakIndex = meta.noExBreakIndex
        if (config.showBreakIndex && slide.isStartBreak && breakIndex != null && !slide.isEx) {
            notePainter.renderBreakIndex(pos.x, pos.y, pos.scale, breakIndex)
        }
    }

    private fun starHeadColor(timing: Double, isBreak: Boolean, isSimultaneous: Boolean): Int {
        val ddr = ddrColor(timing)
        if (ddr != null) return ddr
        return when {
            isBreak -> ChartConstants.Colors.BREAK_ORANGE
            isSimultaneous -> ChartConstants.Colors.SLIDE_SIMULTANEOUS
            config.pinkSlideStart -> ChartConstants.Colors.SLIDE_PINK
            else -> ChartConstants.Colors.SLIDE_CYAN
        }
    }

    private fun exScale(): Double = if (config.highlightExNotes) 1.2 else 1.0

    private fun starNoteSize(scale: Double): Double =
        (context.radius / 12.5) * scale * 1.15 * 1.25

    private fun renderStarHead(
        x: Double,
        y: Double,
        scale: Double,
        color: Int,
        rotation: Double,
        isEx: Boolean,
        isBreak: Boolean,
        isSimultaneous: Boolean,
    ) {
        val noteSize = starNoteSize(scale)
        if (isEx) {
            slidePainter.renderExStarRing(x, y, noteSize, isBreak, isSimultaneous, exScale())
        }
        slidePainter.drawStar(x, y, noteSize, color, rotation, isEx)
    }

    /** Split-slide head: two interleaved stars instead of one. */
    private fun renderSplitSlideStar(
        x: Double,
        y: Double,
        size: Double,
        color: Int,
        rotation: Double,
        isEx: Boolean,
    ) {
        val canvas = context.canvas
        canvas.save()
        if (rotation != 0.0) {
            canvas.translate(x.toFloat(), y.toFloat())
            canvas.rotate(Math.toDegrees(rotation).toFloat())
            canvas.translate(-x.toFloat(), -y.toFloat())
        }

        val noteStroke = context.radius * ChartConstants.NOTE_STROKE_WIDTH_RATIO.toFloat()
        fun star(outer: Double, inner: Double, baseAngle: Double): Path {
            val path = Path()
            for (index in 0 until 10) {
                val angle = index * PI / 5 + baseAngle
                val radius = if (index % 2 == 0) outer else inner
                val px = (x + cos(angle) * radius).toFloat()
                val py = (y + sin(angle) * radius).toFloat()
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            return path
        }

        // Black underlay (skipped for EX where the glow ring takes the outer edge).
        if (!isEx) {
            headStrokePaint.strokeWidth = (2 * context.radius / 300f) * 3
            headStrokePaint.strokeCap = Paint.Cap.BUTT
            headStrokePaint.strokeJoin = Paint.Join.MITER
            headStrokePaint.color = ChartConstants.Colors.BLACK
            for (baseAngle in doubleArrayOf(-PI / 2, PI / 2)) {
                canvas.drawPath(star(size, size * 0.5, baseAngle), headStrokePaint)
            }
        }

        for (baseAngle in doubleArrayOf(-PI / 2, PI / 2)) {
            val ringPath = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addPath(star(size, size * 0.5, baseAngle))
                addPath(star(size * 0.55, size * 0.5 * 0.55, baseAngle))
            }
            headFillPaint.color = color
            headFillPaint.alpha = 255
            canvas.drawPath(ringPath, headFillPaint)
        }

        headStrokePaint.strokeWidth = 2 * context.radius / 300f
        headStrokePaint.color = ChartConstants.Colors.WHITE
        canvas.drawPath(star(size, size * 0.5, -PI / 2), headStrokePaint)
        canvas.drawPath(star(size, size * 0.5, PI / 2), headStrokePaint)

        headFillPaint.color = color
        canvas.drawCircle(x.toFloat(), y.toFloat(), (size * 0.15).toFloat(), headFillPaint)
        canvas.restore()
    }

    private fun renderTouchBorders(
        prepared: PreparedChart,
        currentTimeMs: Double,
        lookAheadMs: Double,
    ) {
        val range = prepared.touchIndex.range(currentTimeMs, lookAheadMs)
        if (range.isEmpty()) return
        val baseApproachTime = ChartConstants.BASE_APPROACH_TIME_MS / config.hiSpeed
        val visibleByPos = HashMap<String, Int>()
        for (index in range) {
            val touch = prepared.touches.getOrNull(index) ?: continue
            if (touch is org.rhythmeta.maimaid.core.chart.TouchHoldStartNote) continue
            val timeDiff = touch.timingMs - currentTimeMs
            val approachTime = baseApproachTime / (abs(touch.hiSpeed ?: 1.0).takeIf { it > 0 } ?: 1.0)
            if (timeDiff <= approachTime && timeDiff >= -50) {
                val position = when (touch) {
                    is org.rhythmeta.maimaid.core.chart.TouchNote -> touch.position
                    else -> ""
                }
                if (position.isNotEmpty()) {
                    visibleByPos[position] = (visibleByPos[position] ?: 0) + 1
                }
            }
        }
        for (index in range.last downTo range.first) {
            val touch = prepared.touches.getOrNull(index) ?: continue
            val positionName = when (touch) {
                is org.rhythmeta.maimaid.core.chart.TouchNote -> touch.position
                is org.rhythmeta.maimaid.core.chart.TouchHoldStartNote -> touch.position
                else -> continue
            }
            val position = touchPainter.touchPosition(positionName)
            val isSimultaneous = prepared.metaOf(touch).simultaneousNoteCount >= 2
            touchPainter.renderTouchBorder(
                position,
                isSimultaneous,
                visibleByPos[positionName] ?: 0,
            )
        }
    }

    private fun renderTouches(
        prepared: PreparedChart,
        currentTimeMs: Double,
        lookAheadMs: Double,
    ) {
        val range = prepared.touchIndex.range(currentTimeMs, lookAheadMs)
        if (range.isEmpty()) return
        touchQueue.clear()
        for (index in range.last downTo range.first) {
            val touch = prepared.touches.getOrNull(index) ?: continue
            val meta = prepared.metaOf(touch)
            val isSimultaneous = meta.simultaneousNoteCount >= 2
            touchPainter.renderTouch(
                touch,
                currentTimeMs,
                isSimultaneous,
                touchQueue,
                meta.sourceNoteIndex,
            )
        }
        touchPainter.flush(touchQueue)
    }

    private fun renderEffects(
        prepared: PreparedChart,
        currentTimeMs: Double,
        lookAheadMs: Double,
    ) {
        val holdRange = prepared.holdEffectIndex.range(currentTimeMs, lookAheadMs)
        effectsPainter.renderHoldPressEffects(
            prepared.holdEffectNotes,
            prepared.holdEndMap,
            currentTimeMs,
            holdRange,
        )
        val touchHoldRange = prepared.touchHoldIndex.range(currentTimeMs, lookAheadMs)
        effectsPainter.renderTouchHoldEffects(
            prepared.touchHoldNotes,
            currentTimeMs,
            touchHoldRange,
        )
        effectsPainter.renderTouchHitEffects(prepared.touches, currentTimeMs)
        effectsPainter.renderTapHitEffects(prepared, currentTimeMs)
    }

    private fun gradientColors(ddr: Int?, isBreak: Boolean, isSimultaneous: Boolean): IntArray =
        when {
            ddr != null -> intArrayOf(
                ddr,
                mixColor(ddr, ChartConstants.Colors.BLACK, ChartConstants.DDR_DARKEN_RATIO.toFloat()),
            )
            isBreak -> intArrayOf(
                ChartConstants.Colors.BREAK_GRADIENT_START,
                ChartConstants.Colors.BREAK_GRADIENT_END,
            )
            isSimultaneous -> intArrayOf(
                ChartConstants.Colors.SIMULTANEOUS_GRADIENT_START,
                ChartConstants.Colors.SIMULTANEOUS_GRADIENT_END,
            )
            else -> intArrayOf(
                ChartConstants.Colors.TAP_GRADIENT_START,
                ChartConstants.Colors.TAP_GRADIENT_END,
            )
        }

    private fun mixColor(from: Int, target: Int, amount: Float): Int {
        fun channel(color: Int, shift: Int) = (color shr shift) and 0xff
        val mix = { a: Int, b: Int -> Math.round(a + (b - a) * amount) }
        return android.graphics.Color.argb(
            255,
            mix(channel(from, 16), channel(target, 16)),
            mix(channel(from, 8), channel(target, 8)),
            mix(channel(from, 0), channel(target, 0)),
        )
    }

    private fun ddrColor(timing: Double): Int? {
        if (!config.ddrColorMode) return null
        val epsilon = 0.001
        val fractional = abs(timing % 1.0)
        if (fractional < epsilon || fractional > 1 - epsilon) return ChartConstants.Colors.DDR_RED
        val half = abs(timing % 0.5)
        if (half < epsilon || half > 0.499) return ChartConstants.Colors.DDR_BLUE
        val quarter = abs(timing % 0.25)
        if (quarter < epsilon || quarter > 0.249) return ChartConstants.Colors.DDR_YELLOW
        if (config.ddrColorExtended) {
            val eighth = abs(timing % 0.125)
            if (eighth < epsilon || eighth > 0.124) return ChartConstants.Colors.DDR_ORANGE
            val sixth = 1.0 / 6.0
            val remainder = abs(timing % sixth)
            if (remainder < epsilon || remainder > sixth - epsilon) {
                return ChartConstants.Colors.DDR_CYAN
            }
        }
        return ChartConstants.Colors.DDR_GREEN
    }
}
