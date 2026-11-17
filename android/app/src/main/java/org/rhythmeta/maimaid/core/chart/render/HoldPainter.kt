package org.rhythmeta.maimaid.core.chart.render

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import org.rhythmeta.maimaid.core.chart.ChartConstants
import org.rhythmeta.maimaid.core.chart.ChartPoint
import org.rhythmeta.maimaid.core.chart.HoldEndNote
import org.rhythmeta.maimaid.core.chart.HoldStartNote
import org.rhythmeta.maimaid.core.chart.NoteRenderPosition

/** Hold bodies: hexagonal tube, EX frame, head glow and pressed breathing brightness. */
class HoldPainter(context: RenderContext) : BasePainter(context) {
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Breathing brightness multiplier while a hold is pressed; 1.0 when not pressed. */
    private fun activeBodyBrightness(elapsedMs: Double): Double {
        if (elapsedMs < 0) return 1.0
        val steps = ChartConstants.HOLD_ACTIVE_BRIGHTNESS_CURVE.size
        val position = (elapsedMs % ChartConstants.HOLD_ACTIVE_CYCLE_MS) /
            ChartConstants.HOLD_ACTIVE_CYCLE_MS * steps
        val index = kotlin.math.floor(position).toInt()
        val t = position - index
        val a = ChartConstants.HOLD_ACTIVE_BRIGHTNESS_CURVE[index % steps]
        val b = ChartConstants.HOLD_ACTIVE_BRIGHTNESS_CURVE[(index + 1) % steps]
        return ChartConstants.HOLD_ACTIVE_BRIGHTNESS_MIN +
            (a + (b - a) * t) *
            (ChartConstants.HOLD_ACTIVE_BRIGHTNESS_MAX - ChartConstants.HOLD_ACTIVE_BRIGHTNESS_MIN)
    }

    fun renderHold(
        startPosition: NoteRenderPosition,
        endPosition: NoteRenderPosition,
        buttonPosition: Int,
        colors: IntArray,
        isEx: Boolean,
        startNote: HoldStartNote?,
        endNote: HoldEndNote?,
        currentTimeMs: Double,
        isBreakHold: Boolean,
        isSimultaneous: Boolean,
        exScaleFactor: Double,
    ) {
        val canvas = context.canvas
        val angle = buttonAngle(buttonPosition)
        val baseSize = scaleByRadius(ChartConstants.NOTE_SIZE_RATIO) * ChartConstants.HOLD_WIDTH_RATIO
        val holdWidth = baseSize * startPosition.scale

        val startX = startPosition.x
        val startY = startPosition.y

        val endX: Double
        val endY: Double
        if (endPosition.visible) {
            endX = endPosition.x
            endY = endPosition.y
        } else {
            val dir = startNote?.let { noteApproachDir(it.hiSpeed) } ?: 1.0
            val approachDist = (1 + dir * -0.75) * context.radius
            endX = context.centerX + cos(angle) * approachDist
            endY = context.centerY + sin(angle) * approachDist
        }

        val tipAngle = angle
        val leftAngle = angle + PI / 3
        val rightAngle = angle - PI / 3
        val backAngle = angle + PI
        val backLeftAngle = angle + PI - PI / 3
        val backRightAngle = angle + PI + PI / 3

        fun point(fromX: Double, fromY: Double, a: Double, distance: Double) = ChartPoint(
            fromX + cos(a) * distance,
            fromY + sin(a) * distance,
        )

        val startTip = point(startX, startY, tipAngle, holdWidth)
        val startLeft = point(startX, startY, leftAngle, holdWidth)
        val startRight = point(startX, startY, rightAngle, holdWidth)

        var endScale = 1.0
        if (startNote != null && endNote != null && currentTimeMs != 0.0) {
            val approachHalf = noteApproachTimeMs(startNote.hiSpeed) / 2
            val timeDiff = startNote.timingMs - currentTimeMs
            if (timeDiff > approachHalf) {
                endScale = max(0.0, 1 - (timeDiff - approachHalf) / approachHalf)
            }
        }
        val endWidth = baseSize * endScale

        val endBack = point(endX, endY, backAngle, endWidth)
        val endBackLeft = point(endX, endY, backLeftAngle, endWidth)
        val endBackRight = point(endX, endY, backRightAngle, endWidth)

        val innerRatio = ChartConstants.HOLD_INNER_RATIO
        val innerStartTip = point(startX, startY, tipAngle, holdWidth * innerRatio)
        val innerStartLeft = point(startX, startY, leftAngle, holdWidth * innerRatio)
        val innerStartRight = point(startX, startY, rightAngle, holdWidth * innerRatio)
        val innerEndBack = point(endX, endY, backAngle, endWidth * innerRatio)
        val innerEndBackLeft = point(endX, endY, backLeftAngle, endWidth * innerRatio)
        val innerEndBackRight = point(endX, endY, backRightAngle, endWidth * innerRatio)

        val outerPath = Path().apply {
            moveTo(startTip.x.toFloat(), startTip.y.toFloat())
            lineTo(startLeft.x.toFloat(), startLeft.y.toFloat())
            lineTo(endBackLeft.x.toFloat(), endBackLeft.y.toFloat())
            lineTo(endBack.x.toFloat(), endBack.y.toFloat())
            lineTo(endBackRight.x.toFloat(), endBackRight.y.toFloat())
            lineTo(startRight.x.toFloat(), startRight.y.toFloat())
            close()
        }
        val innerPath = Path().apply {
            moveTo(innerStartTip.x.toFloat(), innerStartTip.y.toFloat())
            lineTo(innerStartLeft.x.toFloat(), innerStartLeft.y.toFloat())
            lineTo(innerEndBackLeft.x.toFloat(), innerEndBackLeft.y.toFloat())
            lineTo(innerEndBack.x.toFloat(), innerEndBack.y.toFloat())
            lineTo(innerEndBackRight.x.toFloat(), innerEndBackRight.y.toFloat())
            lineTo(innerStartRight.x.toFloat(), innerStartRight.y.toFloat())
            close()
        }

        if (isEx) {
            val exScale = 1.19 * exScaleFactor
            val exColor = when {
                isBreakHold -> ChartConstants.Colors.EX_OVERLAY_BREAK
                isSimultaneous -> ChartConstants.Colors.EX_OVERLAY_SIMULTANEOUS
                else -> ChartConstants.Colors.EX_OVERLAY_NORMAL
            }
            fun scaled(centerX: Double, centerY: Double, p: ChartPoint) = ChartPoint(
                centerX + (p.x - centerX) * exScale,
                centerY + (p.y - centerY) * exScale,
            )
            val exStartTip = scaled(startX, startY, startTip)
            val exStartLeft = scaled(startX, startY, startLeft)
            val exStartRight = scaled(startX, startY, startRight)
            val exEndBack = scaled(endX, endY, endBack)
            val exEndBackLeft = scaled(endX, endY, endBackLeft)
            val exEndBackRight = scaled(endX, endY, endBackRight)

            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                moveTo(exStartTip.x.toFloat(), exStartTip.y.toFloat())
                lineTo(exStartLeft.x.toFloat(), exStartLeft.y.toFloat())
                lineTo(exEndBackLeft.x.toFloat(), exEndBackLeft.y.toFloat())
                lineTo(exEndBack.x.toFloat(), exEndBack.y.toFloat())
                lineTo(exEndBackRight.x.toFloat(), exEndBackRight.y.toFloat())
                lineTo(exStartRight.x.toFloat(), exStartRight.y.toFloat())
                close()
                moveTo(startTip.x.toFloat(), startTip.y.toFloat())
                lineTo(startRight.x.toFloat(), startRight.y.toFloat())
                lineTo(endBackRight.x.toFloat(), endBackRight.y.toFloat())
                lineTo(endBack.x.toFloat(), endBack.y.toFloat())
                lineTo(endBackLeft.x.toFloat(), endBackLeft.y.toFloat())
                lineTo(startLeft.x.toFloat(), startLeft.y.toFloat())
                close()
            }
            applyColor(fillPaint, exColor)
            canvas.drawPath(path, fillPaint)
        }

        val isPressed = startNote != null && endNote != null &&
            currentTimeMs >= startNote.timingMs && currentTimeMs < endNote.timingMs
        val brightness =
            if (isPressed && startNote != null) {
                activeBodyBrightness(currentTimeMs - startNote.timingMs).toFloat()
            } else {
                1f
            }
        fun tint(color: Int): Int =
            if (brightness == 1f) color else scaleBrightness(color, brightness)

        val outlineColor = tint(ChartConstants.Colors.WHITE)
        val strokeWidth = scaleByRadius(ChartConstants.NOTE_STROKE_WIDTH_RATIO)

        strokeSettings(strokeWidth * 3, Paint.Cap.BUTT, Paint.Join.MITER)
        if (!isEx) {
            applyColor(strokePaint, ChartConstants.Colors.BLACK)
            canvas.drawPath(outerPath, strokePaint)
        }
        applyColor(strokePaint, ChartConstants.Colors.BLACK)
        canvas.drawPath(innerPath, strokePaint)

        val lightColor = tint(
            mixColor(
                colors[0],
                ChartConstants.Colors.WHITE,
                ChartConstants.NOTE_LIGHTEN_RATIO.toFloat(),
            ),
        )
        val bodyColor = tint(colors[1])
        val segments = listOf(
            Segment(startTip, startLeft, innerStartTip, innerStartLeft),
            Segment(startLeft, endBackLeft, innerStartLeft, innerEndBackLeft),
            Segment(endBackLeft, endBack, innerEndBackLeft, innerEndBack),
            Segment(endBack, endBackRight, innerEndBack, innerEndBackRight),
            Segment(endBackRight, startRight, innerEndBackRight, innerStartRight),
            Segment(startRight, startTip, innerStartRight, innerStartTip),
        )
        for (segment in segments) {
            val shader = LinearGradient(
                segment.os.x.toFloat(),
                segment.os.y.toFloat(),
                segment.oe.x.toFloat(),
                segment.oe.y.toFloat(),
                lightColor,
                bodyColor,
                Shader.TileMode.CLAMP,
            )
            val path = Path().apply {
                moveTo(segment.os.x.toFloat(), segment.os.y.toFloat())
                lineTo(segment.oe.x.toFloat(), segment.oe.y.toFloat())
                lineTo(segment.ie.x.toFloat(), segment.ie.y.toFloat())
                lineTo(segment.`is`.x.toFloat(), segment.`is`.y.toFloat())
                close()
            }
            gradientPaint.shader = shader
            gradientPaint.alpha = 255
            canvas.drawPath(path, gradientPaint)
        }

        strokeSettings(strokeWidth, Paint.Cap.BUTT, Paint.Join.MITER)
        applyColor(strokePaint, outlineColor)
        canvas.drawPath(outerPath, strokePaint)
        canvas.drawPath(innerPath, strokePaint)

        if (isPressed) {
            drawActiveHeadGlow(startX, startY, colors[0])
        } else {
            val centerSize = holdWidth * 0.15
            applyColor(fillPaint, colors[0])
            canvas.drawCircle(startX.toFloat(), startY.toFloat(), centerSize.toFloat(), fillPaint)
        }

        if (endPosition.visible && endNote != null && currentTimeMs != 0.0) {
            val approachHalf = noteApproachTimeMs(endNote.hiSpeed) / 2
            val endTimeDiff = endNote.timingMs - currentTimeMs
            if (endTimeDiff <= approachHalf) {
                val endCenterSize = endWidth * 0.15
                applyColor(fillPaint, tint(colors[0]))
                canvas.drawCircle(endX.toFloat(), endY.toFloat(), endCenterSize.toFloat(), fillPaint)
            }
        }
    }

    /** Radial head glow while a hold is pressed. Constant brightness, no breathing. */
    private fun drawActiveHeadGlow(x: Double, y: Double, noteColor: Int) {
        val outer = scaleByRadius(ChartConstants.HOLD_ACTIVE_GLOW_RATIO)
        if (outer < 1f) return
        val base = intArrayOf((noteColor shr 16) and 0xff, (noteColor shr 8) and 0xff, noteColor and 0xff)
        val colors = IntArray(ChartConstants.HOLD_ACTIVE_GLOW_RAMP.size + 1)
        val positions = FloatArray(ChartConstants.HOLD_ACTIVE_GLOW_RAMP.size + 1)
        ChartConstants.HOLD_ACTIVE_GLOW_RAMP.forEachIndexed { index, stop ->
            fun channel(value: Int, mul: Double) =
                maxOf(0, minOf(255, Math.round(value * mul).toInt()))
            colors[index] = android.graphics.Color.rgb(
                channel(base[0], stop[1]),
                channel(base[1], stop[2]),
                channel(base[2], stop[3]),
            )
            positions[index] = stop[0].toFloat()
        }
        colors[colors.size - 1] = 0x00000000
        positions[positions.size - 1] = 1f
        val shader = RadialGradient(
            x.toFloat(),
            y.toFloat(),
            outer,
            colors,
            positions,
            Shader.TileMode.CLAMP,
        )
        gradientPaint.shader = shader
        gradientPaint.alpha = 255
        context.canvas.drawCircle(x.toFloat(), y.toFloat(), outer, gradientPaint)
    }

    private class Segment(
        val os: ChartPoint,
        val oe: ChartPoint,
        val `is`: ChartPoint,
        val ie: ChartPoint,
    )
}
