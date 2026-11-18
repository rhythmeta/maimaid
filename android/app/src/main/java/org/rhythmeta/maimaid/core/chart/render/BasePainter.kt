package org.rhythmeta.maimaid.core.chart.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.rhythmeta.maimaid.core.chart.ChartConstants
import org.rhythmeta.maimaid.core.chart.ChartPoint
import org.rhythmeta.maimaid.core.chart.ChartRenderConfig
import org.rhythmeta.maimaid.core.chart.MirrorMode
import org.rhythmeta.maimaid.core.chart.SlideNote
import org.rhythmeta.maimaid.core.chart.TouchGeometry
import kotlin.math.roundToInt

/**
 * Canvas geometry for one frame. Mirrors the reference `RenderContext`: the judgment ring radius
 * is the single scale unit, so every ratio in [ChartConstants] multiplies this value.
 */
class RenderContext(
    var canvas: Canvas,
    var logicalSize: Float,
    var centerX: Float,
    var centerY: Float,
    var radius: Float,
    var config: ChartRenderConfig,
)

/** Shared helpers: position mapping, approach timing and color math. */
abstract class BasePainter(protected val context: RenderContext) {
    protected val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    protected val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    protected val oval = RectF()

    /** Mirror transform for a 1-based button index. */
    protected fun mirrorPosition(position: Int): Int {
        val mode = context.config.mirrorMode
        if (mode == MirrorMode.NONE) return position
        val mirrorH = intArrayOf(0, 8, 7, 6, 5, 4, 3, 2, 1)
        val mirrorV = intArrayOf(0, 4, 3, 2, 1, 8, 7, 6, 5)
        val rotate180 = intArrayOf(0, 5, 6, 7, 8, 1, 2, 3, 4)
        return when (mode) {
            MirrorMode.HORIZONTAL -> mirrorH[position]
            MirrorMode.VERTICAL -> mirrorV[position]
            MirrorMode.ROTATE180 -> rotate180[position]
            MirrorMode.NONE -> position
        }
    }

    protected fun mirrorTouchPosition(position: String): String =
        TouchGeometry.mirrorTouchSensor(position, context.config.mirrorMode)

    /** Canvas-space angle of a button (already mirrored). */
    fun buttonAngle(position: Int): Double {
        val mirrored = mirrorPosition(position)
        return ChartConstants.BASE_ANGLE + ChartConstants.BUTTON_ANGLE_OFFSET +
            (mirrored - 1) * ChartConstants.BUTTON_ANGLE_STEP
    }

    fun buttonPosition(position: Int): ChartPoint {
        val angle = buttonAngle(position)
        return ChartPoint(
            context.centerX + cos(angle) * context.radius,
            context.centerY + sin(angle) * context.radius,
        )
    }

    fun touchPosition(position: String): ChartPoint = TouchGeometry.sensorPoint(
        position,
        context.centerX.toDouble(),
        context.centerY.toDouble(),
        context.radius.toDouble(),
        context.config.mirrorMode,
    )

    protected fun approachTimeMs(): Double {
        val config = context.config
        return if (config.alwaysKeepHiSpeed) {
            ChartConstants.BASE_APPROACH_TIME_MS / (config.hiSpeed / config.playbackSpeed)
        } else {
            ChartConstants.BASE_APPROACH_TIME_MS / config.hiSpeed
        }
    }

    protected fun noteApproachTimeMs(hiSpeed: Double?): Double =
        approachTimeMs() / (abs(hiSpeed ?: 1.0).takeIf { it > 0.0 } ?: 1.0)

    protected fun noteApproachDir(hiSpeed: Double?): Double =
        if ((hiSpeed ?: 1.0) < 0.0) -1.0 else 1.0

    protected fun scaleByRadius(ratio: Double): Float = (ratio * context.radius).toFloat()

    protected fun distanceToCenter(x: Double, y: Double): Double =
        hypot(x - context.centerX, y - context.centerY)

    protected fun durationToMs(duration: Double, bpm: Double): Double = 60_000.0 * duration / bpm

    /** Beat-based coloring: 1/1, 1/2, 1/4 and (extended) 1/8, 1/6. */
    protected fun ddrColor(timing: Double): Int? {
        if (!context.config.ddrColorMode) return null
        val epsilon = 0.001
        val fractional = abs(timing % 1.0)
        if (fractional < epsilon || fractional > 1 - epsilon) return ChartConstants.Colors.DDR_RED
        val half = abs(timing % 0.5)
        if (half !in epsilon..0.499) return ChartConstants.Colors.DDR_BLUE
        val quarter = abs(timing % 0.25)
        if (quarter !in epsilon..0.249) return ChartConstants.Colors.DDR_YELLOW
        if (context.config.ddrColorExtended) {
            val eighth = abs(timing % 0.125)
            if (eighth !in epsilon..0.124) return ChartConstants.Colors.DDR_ORANGE
            val sixth = 1.0 / 6.0
            val sixthRemainder = abs(timing % sixth)
            if (sixthRemainder < epsilon || sixthRemainder > sixth - epsilon) {
                return ChartConstants.Colors.DDR_CYAN
            }
        }
        return ChartConstants.Colors.DDR_GREEN
    }

    /** Ring colors [light end, dark end] with priority DDR > break > simultaneous > tap. */
    protected fun gradientColors(ddr: Int?, isBreak: Boolean, isSimultaneous: Boolean): IntArray =
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

    protected fun mixColor(from: Int, target: Int, amount: Float): Int {
        fun channel(color: Int, shift: Int) = (color shr shift) and 0xff
        val mix = { a: Int, b: Int -> (a + (b - a) * amount).roundToInt() }
        return Color.argb(
            255,
            mix(channel(from, 16), channel(target, 16)),
            mix(channel(from, 8), channel(target, 8)),
            mix(channel(from, 0), channel(target, 0)),
        )
    }

    protected fun scaleBrightness(color: Int, factor: Float): Int {
        fun channel(shift: Int): Int =
            ((((color shr shift) and 0xff) * factor).roundToInt()).coerceIn(0, 255)
        return Color.argb(channel(24), channel(16), channel(8), channel(0))
    }

    protected fun applyColor(paint: Paint, color: Int, alpha: Float = 1f) {
        paint.shader = null
        paint.color = color
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
    }

    protected fun strokeSettings(width: Float, cap: Paint.Cap, join: Paint.Join) {
        strokePaint.strokeWidth = width
        strokePaint.strokeCap = cap
        strokePaint.strokeJoin = join
    }

    protected fun noteStrokeWidth(): Float =
        scaleByRadius(ChartConstants.NOTE_STROKE_WIDTH_RATIO)

    /** Appends a circular arc, connecting from the current path point like `ctx.arc`. */
    protected fun arcTo(path: Path, cx: Float, cy: Float, radius: Float, startDeg: Float, sweepDeg: Float) {
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        path.arcTo(oval, startDeg, sweepDeg, false)
    }

    /**
     * Circular path built from explicit line segments. The reference draws true arcs, but
     * hardware canvases tessellate large path arcs too coarsely (visible facets on tap rings),
     * so circles are emitted as dense polygons at a fixed angular step instead.
     */
    protected fun circlePath(cx: Float, cy: Float, radius: Float): Path {
        val path = Path()
        val segments = maxOf(32, kotlin.math.ceil(radius.toDouble()).toInt())
        for (index in 0..segments) {
            val angle = PI * 2 * index / segments
            val x = cx + (cos(angle) * radius).toFloat()
            val y = cy + (sin(angle) * radius).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    protected fun arcPath(cx: Float, cy: Float, radius: Float, startDeg: Float, sweepDeg: Float): Path {
        if (abs(sweepDeg) >= 359.9f) return circlePath(cx, cy, radius)
        val path = Path()
        val startRad = Math.toRadians(startDeg.toDouble())
        path.moveTo(
            cx + (cos(startRad) * radius).toFloat(),
            cy + (sin(startRad) * radius).toFloat(),
        )
        appendArc(path, cx, cy, radius, startDeg, sweepDeg)
        // No close(): a stroked arc must not gain a chord between its endpoints.
        return path
    }

    /** Appends a polygonal arc in degrees, connecting from the current point. */
    protected fun appendArc(
        path: Path,
        cx: Float,
        cy: Float,
        radius: Float,
        startDeg: Float,
        sweepDeg: Float,
    ) {
        val steps = maxOf(2, kotlin.math.ceil(abs(sweepDeg) / 7.5f).toInt())
        val start = Math.toRadians(startDeg.toDouble())
        val sweep = Math.toRadians(sweepDeg.toDouble())
        for (index in 0..steps) {
            val angle = start + sweep * index / steps
            val x = cx + (cos(angle) * radius).toFloat()
            val y = cy + (sin(angle) * radius).toFloat()
            path.lineTo(x, y)
        }
    }

    /** Angle difference in (-π, π], matching the reference helper. */
    protected fun angleDelta(a: Double, b: Double): Double {
        var delta = (b - a) % (PI * 2)
        if (delta > PI) delta -= PI * 2
        if (delta < -PI) delta += PI * 2
        return delta
    }

    protected fun lerpAngle(a: Double, b: Double, t: Double): Double = a + angleDelta(a, b) * t

	protected fun slideTrackAppearance(note: SlideNote, currentTimeMs: Double, isWifi: Boolean) =
        org.rhythmeta.maimaid.core.chart.SlideAppearance.trackAppearance(
            noteTimeMs = note.timingMs,
            approachTimeMs = noteApproachTimeMs(note.hiSpeed),
            slideDelay = context.config.slideDelay,
            currentTimeMs = currentTimeMs,
            isWifi = isWifi,
        )
}
