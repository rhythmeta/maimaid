package org.rhythmeta.maimaid.core.chart.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin
import org.rhythmeta.maimaid.core.chart.ChartConstants
import org.rhythmeta.maimaid.core.chart.JudgmentLineDesign

/** Background, judgment ring and in-canvas HUD. */
class StagePainter(context: RenderContext) : BasePainter(context) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val coverClip = Path()
    private val coverClipRect = RectF()
    private val coverDest = RectF()
    private var coverClipSize = -1f
    private var coverDestKey = -1.0

    /** Fills the stage and draws the optional cover-art disc background. */
    fun clear(background: Bitmap?) {
        val canvas = context.canvas
        val size = context.logicalSize
        canvas.drawColor(ChartConstants.Colors.BLACK)
        if (background == null || background.width <= 0 || background.height <= 0) return

        canvas.save()
        // The disc clip and the destination rect only change with the stage or cover size.
        if (coverClipSize != size) {
            coverClipRect.set(
                context.centerX - size / 2,
                context.centerY - size / 2,
                context.centerX + size / 2,
                context.centerY + size / 2,
            )
            coverClip.reset()
            coverClip.addOval(coverClipRect, Path.Direction.CW)
            coverClipSize = size
        }
        canvas.clipPath(coverClip)
        val scale = minOf(size / background.width, size / background.height)
        val dw = background.width * scale
        val dh = background.height * scale
        val destinationKey = (dw * 10_000.0) + dh
        if (coverDestKey != destinationKey) {
            coverDest.set(
                (size - dw) / 2,
                (size - dh) / 2,
                (size - dw) / 2 + dw,
                (size - dh) / 2 + dh,
            )
            coverDestKey = destinationKey
        }
        canvas.drawBitmap(background, null, coverDest, bitmapPaint)
        // The dim overlay carries its alpha in the color; forcing paint.alpha would make it
        // opaque and hide the artwork.
        fillPaint.shader = null
        fillPaint.color = 0x99000000.toInt()
        canvas.drawRect(0f, 0f, size, size, fillPaint)
        canvas.restore()
    }

    /** Judgment ring and the eight button markers. */
    fun renderJudgmentLine(sensorImage: Bitmap?) {
        val design = context.config.judgmentLineDesign
        if (design == JudgmentLineDesign.BLIND) return
        val canvas = context.canvas

        if (design == JudgmentLineDesign.SENSOR && sensorImage != null) {
            val imageSize = context.logicalSize - 10
            val left = context.centerX - imageSize / 2
            val top = context.centerY - imageSize / 2 + 8
            oval.set(left, top, left + imageSize, top + imageSize)
            canvas.drawBitmap(sensorImage, null, oval, bitmapPaint)
        }

        if (design == JudgmentLineDesign.SIMPLE || design == JudgmentLineDesign.SENSOR) {
            strokeSettings(
                scaleByRadius(ChartConstants.JUDGMENT_LINE_WIDTH_RATIO),
                Paint.Cap.BUTT,
                Paint.Join.MITER,
            )
            applyColor(strokePaint, ChartConstants.Colors.WHITE)
            canvas.drawCircle(context.centerX, context.centerY, context.radius, strokePaint)
        }

        val markerSize = scaleByRadius(ChartConstants.BUTTON_MARKER_RATIO)
        applyColor(fillPaint, ChartConstants.Colors.WHITE)
        for (position in 1..8) {
            val angle = buttonAngle(position)
            canvas.drawCircle(
                (context.centerX + cos(angle) * context.radius).toFloat(),
                (context.centerY + sin(angle) * context.radius).toFloat(),
                markerSize,
                fillPaint,
            )
        }
    }

    /** BPM readout plus the measure/beat/divisor line. */
    fun renderBpmDisplay(currentBpm: Double, currentBeats: Double, divisor: Double, isPlaying: Boolean) {
        val radius = context.radius
        val fontSize = Math.round(22 * radius / 300f).toFloat()
        val smallFontSize = Math.round(16 * radius / 300f).toFloat()
        val padding = Math.round(20 * radius / 300f).toFloat()
        val lineGap = Math.round(4 * radius / 300f).toFloat()
        val shadowOffset = 2 * radius / 300f
        val canvas = context.canvas

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = fontSize
        textPaint.color = ChartConstants.Colors.WHITE
        val bpmText = "BPM: ${kotlin.math.floor(currentBpm).toInt()}"
        val metrics = textPaint.fontMetrics
        val baseline = padding - metrics.ascent

        drawShadowedText(bpmText, padding, baseline, shadowOffset, ChartConstants.Colors.WHITE, fontSize)

        val measure = kotlin.math.floor(currentBeats / 4).toInt()
        val beatInMeasure = currentBeats - measure * 4
        val beat = kotlin.math.floor(beatInMeasure).toInt() + 1
        val fraction = beatInMeasure - kotlin.math.floor(beatInMeasure)
        var currentY = padding + fontSize + lineGap
        textPaint.textSize = smallFontSize
        val fractionText = kotlin.math.floor(fraction * 100).toInt().toString().padStart(2, '0')
        val beatText = "$measure:$beat.$fractionText [1/${formatDivisor(divisor)}]"
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = ChartConstants.Colors.HUD_MUTED
        canvas.drawText(beatText, padding, currentY - textPaint.fontMetrics.ascent, textPaint)
    }

    private fun formatDivisor(divisor: Double): String =
        if (divisor == kotlin.math.floor(divisor)) divisor.toInt().toString() else divisor.toString()

    /** Note / break counters around the ring. */
    fun renderNoteCounts(
        totalNotes: Int,
        completedNotes: Int,
        totalBreaks: Int,
        completedBreaks: Int,
        totalBreaksNoEx: Int,
        completedBreaksNoEx: Int,
    ) {
        val config = context.config
        val radius = context.radius
        val fontSize = Math.round(22 * radius / 300f).toFloat()
        val smallFontSize = Math.round(18 * radius / 300f).toFloat()
        val padding = Math.round(20 * radius / 300f).toFloat()
        val lineGap = Math.round(4 * radius / 300f).toFloat()
        val shadowOffset = 2 * radius / 300f
        val size = context.logicalSize
        val canvas = context.canvas

        textPaint.textSize = fontSize
        if (config.showNoteTotal) {
            textPaint.textAlign = Paint.Align.RIGHT
            val text = "${config.hudComboLabel}: $completedNotes / $totalNotes"
            val baseline = padding - textPaint.fontMetrics.ascent
            drawShadowedText(
                text,
                size - padding,
                baseline,
                shadowOffset,
                ChartConstants.Colors.WHITE,
                fontSize,
            )
        }

        if (config.showBreakCount) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = fontSize
            val bottomBaseline = size - padding - textPaint.fontMetrics.descent
            drawShadowedText(
                "BREAK: $completedBreaks / $totalBreaks",
                padding,
                bottomBaseline,
                shadowOffset,
                ChartConstants.Colors.BREAK_ORANGE,
                fontSize,
            )

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = fontSize
            drawShadowedText(
                "BREAK: $completedBreaksNoEx / $totalBreaksNoEx",
                size - padding,
                bottomBaseline,
                shadowOffset,
                0xFFFFA500.toInt(),
                fontSize,
            )
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = smallFontSize
            val labelBaseline = bottomBaseline - fontSize - lineGap
            drawShadowedText(
                config.hudBreakNoExLabel,
                size - padding,
                labelBaseline,
                shadowOffset,
                0xFFFFA500.toInt(),
                smallFontSize,
            )
        }
    }

    private fun drawShadowedText(
        text: String,
        x: Float,
        baseline: Float,
        shadowOffset: Float,
        color: Int,
        textSize: Float,
    ) {
        val canvas = context.canvas
        textPaint.textSize = textSize
        textPaint.color = 0xBF000000.toInt()
        canvas.drawText(text, x + shadowOffset, baseline + shadowOffset, textPaint)
        textPaint.color = color
        canvas.drawText(text, x, baseline, textPaint)
    }
}
