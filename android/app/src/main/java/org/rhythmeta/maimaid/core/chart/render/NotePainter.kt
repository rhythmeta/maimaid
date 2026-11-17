package org.rhythmeta.maimaid.core.chart.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import org.rhythmeta.maimaid.core.chart.ChartConstants
import org.rhythmeta.maimaid.core.chart.ChartNote
import org.rhythmeta.maimaid.core.chart.HoldStartNote
import org.rhythmeta.maimaid.core.chart.NoteRenderPosition

/** Tap note rendering: approach arcs, chord connectors, EX rings, and the tap ring itself. */
class NotePainter(context: RenderContext) : BasePainter(context) {
    private val tapSpriteCache = HashMap<String, Bitmap>()
    private var tapSpriteBasis = ""

    private var noteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val spriteDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun getPositionOnRing(position: Int): org.rhythmeta.maimaid.core.chart.ChartPoint =
        buttonPosition(position)

    fun getAngle(position: Int): Double = buttonAngle(position)

    /** Current render position and scale of a note, or an invisible marker outside the window. */
    fun calculateNotePosition(note: ChartNote, currentTimeMs: Double): NoteRenderPosition {
        val position = when (note) {
            is org.rhythmeta.maimaid.core.chart.TapNote -> note.position
            is HoldStartNote -> note.position
            is org.rhythmeta.maimaid.core.chart.HoldEndNote -> note.position
            is org.rhythmeta.maimaid.core.chart.SlideNote -> note.position
            else -> return NoteRenderPosition.INVISIBLE
        }
        val angle = buttonAngle(position)
        val timeDiff = note.timingMs - currentTimeMs
        val approachTime = noteApproachTimeMs(note.hiSpeed)

        var holdWindow = ChartConstants.NOTE_VISIBILITY_AFTER_MS
        if (note is HoldStartNote) {
            holdWindow = durationToMs(note.duration, note.bpm)
        }
        if (timeDiff > approachTime || timeDiff < -holdWindow) return NoteRenderPosition.INVISIBLE

        val dir = noteApproachDir(note.hiSpeed)
        val halfApproach = approachTime / 2
        val distance: Double
        val scale: Double
        if (timeDiff > halfApproach) {
            distance = context.radius * (1 + dir * (ChartConstants.APPROACH_START_SCALE - 1))
            scale = 1 - (timeDiff - halfApproach) / halfApproach
        } else if (timeDiff >= 0) {
            val progress = 1 - timeDiff / halfApproach
            distance = context.radius *
                (1 + dir * (ChartConstants.APPROACH_START_SCALE - 1 + 0.75 * progress))
            scale = 1.0
        } else if (note is HoldStartNote) {
            distance = context.radius.toDouble()
            scale = 1.0
        } else {
            val fadeProgress = 1 + -timeDiff / halfApproach
            distance = context.radius *
                (1 + dir * (ChartConstants.APPROACH_START_SCALE - 1 + 0.75 * fadeProgress))
            scale = 1.0
        }

        return NoteRenderPosition(
            x = context.centerX + cos(angle) * distance,
            y = context.centerY + sin(angle) * distance,
            scale = scale,
            visible = true,
        )
    }

    /** One note's approach arc plus its fading trails. */
    fun renderApproachArc(position: Int, noteX: Double, noteY: Double, color: Int) {
        val angle = buttonAngle(position)
        val distance = distanceToCenter(noteX, noteY).toFloat()
        val trailStep = PI / 8 / 4
        val lineWidth = scaleByRadius(ChartConstants.NOTE_STROKE_WIDTH_RATIO)
        strokeSettings(lineWidth, Paint.Cap.BUTT, Paint.Join.MITER)
        val canvas = context.canvas
        for (i in 4 downTo 0) {
            val alpha = if (i == 0) 0.4f else (0.4 * (1 - i / 5.0)).toFloat()
            val path = Path()
            if (i == 0) {
                path.moveTo(
                    (context.centerX + cos(angle - PI / 8) * distance).toFloat(),
                    (context.centerY + sin(angle - PI / 8) * distance).toFloat(),
                )
                appendArc(
                    path,
                    context.centerX,
                    context.centerY,
                    distance,
                    Math.toDegrees(angle - PI / 8).toFloat(),
                    Math.toDegrees(PI / 4).toFloat(),
                )
            } else {
                val leftStart = angle - PI / 8 - i * trailStep
                val leftEnd = angle - PI / 8 - (i - 1) * trailStep
                path.moveTo(
                    (context.centerX + cos(leftStart) * distance).toFloat(),
                    (context.centerY + sin(leftStart) * distance).toFloat(),
                )
                appendArc(
                    path,
                    context.centerX,
                    context.centerY,
                    distance,
                    Math.toDegrees(leftStart).toFloat(),
                    Math.toDegrees(leftEnd - leftStart).toFloat(),
                )
                val rightStart = angle + PI / 8 + (i - 1) * trailStep
                val rightEnd = angle + PI / 8 + i * trailStep
                path.moveTo(
                    (context.centerX + cos(rightStart) * distance).toFloat(),
                    (context.centerY + sin(rightStart) * distance).toFloat(),
                )
                appendArc(
                    path,
                    context.centerX,
                    context.centerY,
                    distance,
                    Math.toDegrees(rightStart).toFloat(),
                    Math.toDegrees(rightEnd - rightStart).toFloat(),
                )
            }
            applyColor(strokePaint, color, alpha)
            canvas.drawPath(path, strokePaint)
        }
    }

    /** Batched approach arcs, grouped by color so all arcs of one color share one stroke. */
    fun renderApproachArcsBatch(arcs: List<ApproachArc>) {
        if (arcs.isEmpty()) return
        val trailStep = PI / 8 / 4
        val lineWidth = scaleByRadius(ChartConstants.NOTE_STROKE_WIDTH_RATIO)
        strokeSettings(lineWidth, Paint.Cap.BUTT, Paint.Join.MITER)
        val canvas = context.canvas
        val byColor = LinkedHashMap<Int, MutableList<ApproachArc>>()
        for (arc in arcs) byColor.getOrPut(arc.color) { mutableListOf() } += arc

        for ((color, group) in byColor) {
            for (i in 4 downTo 0) {
                val alpha = if (i == 0) 0.4f else (0.4 * (1 - i / 5.0)).toFloat()
                val path = Path()
                for (arc in group) {
                    val angle = buttonAngle(arc.position)
                    val d = arc.distance
                    if (i == 0) {
                        path.moveTo(
                            (context.centerX + cos(angle - PI / 8) * d).toFloat(),
                            (context.centerY + sin(angle - PI / 8) * d).toFloat(),
                        )
                        appendArc(
                            path,
                            context.centerX,
                            context.centerY,
                            d.toFloat(),
                            Math.toDegrees(angle - PI / 8).toFloat(),
                            Math.toDegrees(PI / 4).toFloat(),
                        )
                    } else {
                        val leftStart = angle - PI / 8 - i * trailStep
                        val leftEnd = angle - PI / 8 - (i - 1) * trailStep
                        path.moveTo(
                            (context.centerX + cos(leftStart) * d).toFloat(),
                            (context.centerY + sin(leftStart) * d).toFloat(),
                        )
                        appendArc(
                            path,
                            context.centerX,
                            context.centerY,
                            d.toFloat(),
                            Math.toDegrees(leftStart).toFloat(),
                            Math.toDegrees(leftEnd - leftStart).toFloat(),
                        )
                        val rightStart = angle + PI / 8 + (i - 1) * trailStep
                        val rightEnd = angle + PI / 8 + i * trailStep
                        path.moveTo(
                            (context.centerX + cos(rightStart) * d).toFloat(),
                            (context.centerY + sin(rightStart) * d).toFloat(),
                        )
                        appendArc(
                            path,
                            context.centerX,
                            context.centerY,
                            d.toFloat(),
                            Math.toDegrees(rightStart).toFloat(),
                            Math.toDegrees(rightEnd - rightStart).toFloat(),
                        )
                    }
                }
                applyColor(strokePaint, color, alpha)
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    /** Connects two simultaneous notes along the shorter arc; opposite buttons get a full circle. */
    fun renderSimultaneousConnector(startPos: Int, endPos: Int, distance: Double, color: Int) {
        val startAngle = buttonAngle(startPos)
        val endAngle = buttonAngle(endPos)
        var angleDiff = endPos - startPos
        while (angleDiff > 4) angleDiff -= 8
        while (angleDiff < -4) angleDiff += 8
        val isFullCircle = kotlin.math.abs(angleDiff) == 4

        strokeSettings(noteStrokeWidth(), Paint.Cap.BUTT, Paint.Join.MITER)
        applyColor(strokePaint, color, 0.7f)
        val path = if (isFullCircle) {
            arcPath(
                context.centerX,
                context.centerY,
                distance.toFloat(),
                0f,
                360f,
            )
        } else {
            val arcStart = if (angleDiff > 0) startAngle else endAngle
            val arcEnd = if (angleDiff > 0) endAngle else startAngle
            arcPath(
                context.centerX,
                context.centerY,
                distance.toFloat(),
                Math.toDegrees(arcStart).toFloat(),
                Math.toDegrees(arcEnd - arcStart).toFloat(),
            )
        }
        context.canvas.drawPath(path, strokePaint)
    }

    /** Glowing EX ring drawn behind tap and star heads. */
    fun renderExRing(
        x: Double,
        y: Double,
        noteSize: Double,
        isBreak: Boolean,
        isSimultaneous: Boolean,
        scaleFactor: Double,
    ) {
        val innerRadius = noteSize
        val outerRadius = noteSize * 1.19 * scaleFactor
        val color = when {
            isBreak -> ChartConstants.Colors.EX_OVERLAY_BREAK
            isSimultaneous -> ChartConstants.Colors.EX_OVERLAY_SIMULTANEOUS
            else -> ChartConstants.Colors.EX_OVERLAY_NORMAL
        }
        val path = ringPath(x, y, innerRadius, outerRadius)
        applyColor(fillPaint, color)
        context.canvas.drawPath(path, fillPaint)
    }

    /** Break triangle with the tip pointing outward. */
    fun renderBreakTriangle(x: Double, y: Double, size: Double, position: Int) {
        val angle = buttonAngle(position)
        val triangleSize = size * 1.4
        val tipAngle = angle
        val leftAngle = angle + PI - PI / 3
        val rightAngle = angle + PI + PI / 3
        val path = Path().apply {
            moveTo(
                (x + cos(tipAngle) * triangleSize).toFloat(),
                (y + sin(tipAngle) * triangleSize).toFloat(),
            )
            lineTo(
                (x + cos(leftAngle) * triangleSize).toFloat(),
                (y + sin(leftAngle) * triangleSize).toFloat(),
            )
            lineTo(
                (x + cos(rightAngle) * triangleSize).toFloat(),
                (y + sin(rightAngle) * triangleSize).toFloat(),
            )
            close()
        }
        strokeSettings(scaleByRadius(4.0 / 300.0), Paint.Cap.BUTT, Paint.Join.MITER)
        applyColor(strokePaint, ChartConstants.Colors.WHITE, 0.8f)
        context.canvas.drawPath(path, strokePaint)
    }

    /** Draws a tap note using a baked sprite, falling back to vectors in DDR color mode. */
    fun renderTapNote(
        x: Double,
        y: Double,
        noteScale: Double,
        position: Int,
        isBreak: Boolean,
        isSimultaneous: Boolean,
        isEx: Boolean,
        timing: Double,
        highlightExScale: Double,
    ) {
        // Vector drawing matches the reference artwork exactly; the reference's offscreen sprite
        // bake is a performance cache, not a different look.
        drawTapNoteVector(
            x, y, noteScale, position, isBreak, isSimultaneous, isEx, timing,
            highlightExScale,
        )
    }

    private fun getTapSprite(
        position: Int,
        isBreak: Boolean,
        isSimultaneous: Boolean,
        isEx: Boolean,
        highlightExScale: Double,
    ): Bitmap {
        val basis = "${context.radius}|${context.config.mirrorMode}"
        if (basis != tapSpriteBasis) {
            tapSpriteCache.values.forEach { it.recycle() }
            tapSpriteCache.clear()
            tapSpriteBasis = basis
        }
        val key = "$position|${if (isBreak) 1 else 0}|${if (isSimultaneous) 1 else 0}|" +
            "${if (isEx) 1 else 0}|$highlightExScale"
        tapSpriteCache[key]?.let { return it }

        val half = scaleByRadius(ChartConstants.NOTE_SIZE_RATIO) * 1.36 * TAP_SPRITE_HALF_RATIO
        val size = maxOf(2, ceil(half * 2 * TAP_SPRITE_SUPERSAMPLE).toInt())
        val sprite = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val spriteCanvas = Canvas(sprite)
        spriteCanvas.scale(
            TAP_SPRITE_SUPERSAMPLE.toFloat(),
            TAP_SPRITE_SUPERSAMPLE.toFloat(),
            size / 2f,
            size / 2f,
        )
        val mainCanvas = context.canvas
        context.canvas = spriteCanvas
        try {
            drawTapNoteVector(
                0.0, 0.0, 1.0, position, isBreak, isSimultaneous, isEx, 0.0, highlightExScale,
            )
        } finally {
            context.canvas = mainCanvas
        }
        tapSpriteCache[key] = sprite
        return sprite
    }

    private fun drawTapNoteVector(
        x: Double,
        y: Double,
        noteScale: Double,
        position: Int,
        isBreak: Boolean,
        isSimultaneous: Boolean,
        isEx: Boolean,
        timing: Double,
        highlightExScale: Double,
    ) {
        val baseSize = scaleByRadius(ChartConstants.NOTE_SIZE_RATIO) * noteScale * 1.36
        val outerRadius = baseSize
        val innerRadius = baseSize * ChartConstants.TAP_INNER_RING_RATIO
        val canvas = context.canvas

        if (isEx) {
            renderExRing(x, y, outerRadius, isBreak, isSimultaneous, highlightExScale)
        }

        val ddr = ddrColor(timing)
        val colors = gradientColors(ddr, isBreak, isSimultaneous)
        renderDirectionalTapRing(x, y, innerRadius, outerRadius, position, colors)

        val strokeW = noteStrokeWidth()
        val blackBandW = strokeW
        if (!isEx) {
            strokeSettings(blackBandW, Paint.Cap.BUTT, Paint.Join.MITER)
            applyColor(strokePaint, ChartConstants.Colors.BLACK)
            val outerPath = arcPath(
                x.toFloat(),
                y.toFloat(),
                (outerRadius + strokeW / 2 + blackBandW / 2).toFloat(),
                0f,
                360f,
            )
            canvas.drawPath(outerPath, strokePaint)
        }
        val innerBlackR = innerRadius - strokeW / 2 - blackBandW / 2
        if (innerBlackR > 0) {
            strokeSettings(blackBandW, Paint.Cap.BUTT, Paint.Join.MITER)
            applyColor(strokePaint, ChartConstants.Colors.BLACK)
            canvas.drawPath(
                arcPath(x.toFloat(), y.toFloat(), innerBlackR.toFloat(), 0f, 360f),
                strokePaint,
            )
        }

        strokeSettings(strokeW, Paint.Cap.BUTT, Paint.Join.MITER)
        applyColor(strokePaint, ChartConstants.Colors.WHITE)
        canvas.drawPath(
            arcPath(x.toFloat(), y.toFloat(), outerRadius.toFloat(), 0f, 360f),
            strokePaint,
        )
        canvas.drawPath(
            arcPath(x.toFloat(), y.toFloat(), innerRadius.toFloat(), 0f, 360f),
            strokePaint,
        )

        val centerSize = outerRadius * 0.15
        val centerColor = when {
            ddr != null -> ddr
            isBreak -> ChartConstants.Colors.BREAK_ORANGE
            isSimultaneous -> ChartConstants.Colors.SIMULTANEOUS_GOLD
            else -> ChartConstants.Colors.TAP_PINK
        }
        applyColor(fillPaint, centerColor)
        canvas.drawPath(
            arcPath(x.toFloat(), y.toFloat(), centerSize.toFloat(), 0f, 360f),
            fillPaint,
        )
    }

    /** Four directional gradient sectors aligned with the button angle. */
    private fun renderDirectionalTapRing(
        x: Double,
        y: Double,
        innerRadius: Double,
        outerRadius: Double,
        position: Int,
        colors: IntArray,
    ) {
        val canvas = context.canvas
        val startAngle = buttonAngle(position)
        val lightColor = mixColor(colors[0], ChartConstants.Colors.WHITE, ChartConstants.NOTE_LIGHTEN_RATIO.toFloat())

        for (i in 0 until 4) {
            val sectorStart = startAngle + i * PI / 2
            val sectorEnd = sectorStart + PI / 2
            val midR = (innerRadius + outerRadius) / 2
            val x0 = (x + cos(sectorStart) * midR).toFloat()
            val y0 = (y + sin(sectorStart) * midR).toFloat()
            val x1 = (x + cos(sectorEnd) * midR).toFloat()
            val y1 = (y + sin(sectorEnd) * midR).toFloat()

            val shader = LinearGradient(x0, y0, x1, y1, lightColor, colors[1], Shader.TileMode.CLAMP)
            val path = Path()
            path.moveTo(
                (x + cos(sectorStart) * outerRadius).toFloat(),
                (y + sin(sectorStart) * outerRadius).toFloat(),
            )
            appendArc(
                path,
                x.toFloat(),
                y.toFloat(),
                outerRadius.toFloat(),
                Math.toDegrees(sectorStart).toFloat(),
                Math.toDegrees(PI / 2).toFloat(),
            )
            appendArc(
                path,
                x.toFloat(),
                y.toFloat(),
                innerRadius.toFloat(),
                Math.toDegrees(sectorEnd).toFloat(),
                Math.toDegrees(-PI / 2).toFloat(),
            )
            path.close()
            fillPaint.shader = shader
            fillPaint.alpha = 255
            canvas.drawPath(path, fillPaint)
        }
        fillPaint.shader = null
    }

    /** Break ordering number above a break note. */
    fun renderBreakIndex(x: Double, y: Double, scale: Double, index: Int) {
        val fontSize = Math.round(30f * context.radius / 300f * scale).toFloat()
        textPaint.textSize = fontSize
        textPaint.color = ChartConstants.Colors.WHITE
        val metrics = textPaint.fontMetrics
        val baseline = (y - (metrics.ascent + metrics.descent) / 2).toFloat()
        textPaint.color = 0xE6000000.toInt()
        context.canvas.drawText(index.toString(), (x + 1).toFloat(), baseline + 1f, textPaint)
        textPaint.color = ChartConstants.Colors.WHITE
        context.canvas.drawText(index.toString(), x.toFloat(), baseline, textPaint)
    }

    private fun ringPath(x: Double, y: Double, innerRadius: Double, outerRadius: Double): Path {
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        val rect = android.graphics.RectF(
            (x - outerRadius).toFloat(),
            (y - outerRadius).toFloat(),
            (x + outerRadius).toFloat(),
            (y + outerRadius).toFloat(),
        )
        path.addOval(rect, Path.Direction.CW)
        val innerRect = android.graphics.RectF(
            (x - innerRadius).toFloat(),
            (y - innerRadius).toFloat(),
            (x + innerRadius).toFloat(),
            (y + innerRadius).toFloat(),
        )
        path.addOval(innerRect, Path.Direction.CCW)
        return path
    }

    companion object {
        private const val TAP_SPRITE_HALF_RATIO = 1.7
        private const val TAP_SPRITE_SUPERSAMPLE = 2
    }
}

/** One batched approach-arc entry. */
data class ApproachArc(val position: Int, val distance: Double, val color: Int)
