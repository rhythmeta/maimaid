package org.rhythmeta.maimaid.core.chart.render

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.rhythmeta.maimaid.core.chart.ChartConstants
import org.rhythmeta.maimaid.core.chart.ChartNote
import org.rhythmeta.maimaid.core.chart.ChartPoint
import org.rhythmeta.maimaid.core.chart.TouchHoldStartNote
import org.rhythmeta.maimaid.core.chart.TouchNote

/** Touch notes (petal diamonds), touch holds (progress ring), multi-tap borders. */
class TouchPainter(context: RenderContext) : BasePainter(context) {
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** One queued touch draw command; parts are emitted in global z-order by [flush]. */
    class TouchDrawCommand(
        val sort: TouchOrder.TouchSortNote,
        val draw: (TouchOrder.Part) -> Unit,
    )

    private class PetalGeometry(
        val index: Int,
        val tip: ChartPoint,
        val left: ChartPoint,
        val right: ChartPoint,
        val petalX: Double,
        val petalY: Double,
        val innerTip: ChartPoint,
        val innerLeft: ChartPoint,
        val innerRight: ChartPoint,
    )

    fun getTouchApproachTimeMs(hiSpeed: Double?): Double =
        noteApproachTimeMs(hiSpeed) * ChartConstants.TOUCH_APPROACH_MULTIPLIER

    /** Queues one touch note; call [flush] after all visible touches are collected. */
    fun renderTouch(
        note: ChartNote,
        currentTimeMs: Double,
        isSimultaneous: Boolean,
        queue: MutableList<TouchDrawCommand>,
        sourceIndex: Int?,
    ) {
        val positionName = when (note) {
            is TouchNote -> note.position
            is TouchHoldStartNote -> note.position
            else -> return
        }
        val isHold = note is TouchHoldStartNote
        val timeDiff = note.timingMs - currentTimeMs
        val approachTime = getTouchApproachTimeMs(note.hiSpeed)

        var visibilityWindow = 50.0
        if (note is TouchHoldStartNote) visibilityWindow = note.durationMs + 50.0
        if (timeDiff > approachTime || timeDiff < -visibilityWindow) return

        var alpha = 1.0
        if (timeDiff > approachTime * 0.95) {
            alpha = 1 - (timeDiff - approachTime * 0.95) / (approachTime * 0.05)
        }

        var petalAlpha = 1.0
        if (timeDiff > 0 && timeDiff < approachTime) {
            val remaining = approachTime - timeDiff
            if (remaining < 150) petalAlpha = remaining / 150
        }

        val openDist = (scaleByRadius(ChartConstants.TOUCH_PETAL_OPEN_RATIO) * 1.1).toDouble()
        val closedDist = (scaleByRadius(ChartConstants.TOUCH_PETAL_CLOSED_RATIO) * 1.3).toDouble()
        var petalDist = openDist

        if (timeDiff > 0 && timeDiff <= approachTime) {
            val progress = 1 - timeDiff / approachTime
            val eased = progress * progress * progress * progress
            petalDist = openDist - (openDist - closedDist) * eased
        } else if (timeDiff <= 0) {
            petalDist = closedDist
        }

        val origin = touchPosition(positionName)
        val isHoldActive = isHold && timeDiff < 0
        val canvas = context.canvas

        val cornerRadius = scaleByRadius(8.0 / 300.0).toDouble()
        val innerCornerRadius = cornerRadius * 0.4
        val strokeWidth = noteStrokeWidth()
        val ddr = ddrColor(note.timing)
        val petalBaseAngles = doubleArrayOf(-PI / 4, PI / 4, 3 * PI / 4, -3 * PI / 4)
        val angleOffset = if (isHold) 0.0 else -PI / 4
        val combinedAlpha = (alpha * petalAlpha).toFloat()

        val petals = ArrayList<PetalGeometry>(4)
        for (index in 0 until 4) {
            val petalAngle = petalBaseAngles[index] + angleOffset
            val petalX = origin.x + cos(petalAngle) * petalDist
            val petalY = origin.y + sin(petalAngle) * petalDist
            val tipAngle = petalAngle + PI
            val leftAngle = petalAngle + PI / 2
            val rightAngle = petalAngle - PI / 2
            val petalSize = closedDist

            val tip = ChartPoint(
                petalX + cos(tipAngle) * petalSize,
                petalY + sin(tipAngle) * petalSize,
            )
            val left = ChartPoint(
                petalX + cos(leftAngle) * petalSize,
                petalY + sin(leftAngle) * petalSize,
            )
            val right = ChartPoint(
                petalX + cos(rightAngle) * petalSize,
                petalY + sin(rightAngle) * petalSize,
            )
            val innerRatio = 0.4
            val cx = (tip.x + left.x + right.x) / 3
            val cy = (tip.y + left.y + right.y) / 3
            petals += PetalGeometry(
                index = index,
                tip = tip,
                left = left,
                right = right,
                petalX = petalX,
                petalY = petalY,
                innerTip = ChartPoint(cx + (tip.x - cx) * innerRatio, cy + (tip.y - cy) * innerRatio),
                innerLeft = ChartPoint(
                    cx + (left.x - cx) * innerRatio,
                    cy + (left.y - cy) * innerRatio,
                ),
                innerRight = ChartPoint(
                    cx + (right.x - cx) * innerRatio,
                    cy + (right.y - cy) * innerRatio,
                ),
            )
        }

        fun drawPart(part: TouchOrder.Part) {
            when (part) {
                TouchOrder.Part.GAUGE -> {
                    if (isHoldActive && note is TouchHoldStartNote && note.durationMs > 0) {
                        val elapsed = -timeDiff
                        drawHoldProgress(origin, min(elapsed / note.durationMs, 1.0), alpha.toFloat())
                    }
                }
                TouchOrder.Part.PETAL_0,
                TouchOrder.Part.PETAL_1,
                TouchOrder.Part.PETAL_2,
                TouchOrder.Part.PETAL_3,
                -> {
                    val index = part.name.last().digitToInt()
                    drawPetal(
                        petal = petals[index],
                        isHold = isHold,
                        isSimultaneous = isSimultaneous,
                        ddr = ddr,
                        cornerRadius = cornerRadius,
                        innerCornerRadius = innerCornerRadius,
                        strokeWidth = strokeWidth,
                        alpha = combinedAlpha,
                        origin = origin,
                    )
                }
                TouchOrder.Part.CENTER -> {
                    val centerSize = (scaleByRadius(ChartConstants.TOUCH_CENTER_DOT_RATIO) * 0.8).toDouble()
                    strokeSettings(strokeWidth * 3, Paint.Cap.BUTT, Paint.Join.MITER)
                    applyColor(strokePaint, ChartConstants.Colors.BLACK, alpha.toFloat())
                    canvas.drawCircle(
                        origin.x.toFloat(),
                        origin.y.toFloat(),
                        centerSize.toFloat(),
                        strokePaint,
                    )
                    applyColor(
                        fillPaint,
                        if (isSimultaneous) 0xFFFFFF00.toInt() else 0xFF00BFFF.toInt(),
                        alpha.toFloat(),
                    )
                    canvas.drawCircle(
                        origin.x.toFloat(),
                        origin.y.toFloat(),
                        centerSize.toFloat(),
                        fillPaint,
                    )
                    strokeSettings(strokeWidth, Paint.Cap.BUTT, Paint.Join.MITER)
                    applyColor(strokePaint, ChartConstants.Colors.WHITE, alpha.toFloat())
                    canvas.drawCircle(
                        origin.x.toFloat(),
                        origin.y.toFloat(),
                        centerSize.toFloat(),
                        strokePaint,
                    )
                }
            }
        }

        val index = sourceIndex ?: queue.size
        val sortNote = TouchOrder.TouchSortNote(
            sourceIndex = index,
            position = mirrorTouchPosition(positionName),
            isHold = isHold,
            registeredAtMs = note.timingMs - approachTime,
        )
        queue += TouchDrawCommand(sortNote) { part -> drawPart(part) }
    }

    /** Emits queued touch parts in arcade z-order. */
    fun flush(queue: MutableList<TouchDrawCommand>) {
        if (queue.isEmpty()) return
        val commands = queue.associateBy { it.sort.sourceIndex }
        val order = TouchOrder.resolve(queue.map { it.sort })
        for (ranked in order) {
            commands[ranked.sourceIndex]?.draw?.invoke(ranked.part)
        }
    }

    // ---------------------------------------------------------------- petals

    private fun drawPetal(
        petal: PetalGeometry,
        isHold: Boolean,
        isSimultaneous: Boolean,
        ddr: Int?,
        cornerRadius: Double,
        innerCornerRadius: Double,
        strokeWidth: Float,
        alpha: Float,
        origin: ChartPoint,
    ) {
        val canvas = context.canvas
        val shadowOffset = scaleByRadius(2.0 / 300.0)
        val outer = buildRoundedTriangle(petal.tip, petal.left, petal.right, cornerRadius)
        val inner = buildRoundedTriangle(
            petal.innerTip,
            petal.innerLeft,
            petal.innerRight,
            innerCornerRadius,
        )

        // Soft drop shadow approximated by layered offset copies.
        canvas.save()
        canvas.translate(shadowOffset, shadowOffset)
        applyColor(fillPaint, ChartConstants.Colors.BLACK, alpha * 0.18f)
        canvas.drawPath(outer, fillPaint)
        canvas.drawPath(offsetPath(outer, -1f, 0f), fillPaint)
        canvas.drawPath(offsetPath(outer, 1f, 0f), fillPaint)
        canvas.restore()

        strokeSettings(strokeWidth * 3, Paint.Cap.BUTT, Paint.Join.MITER)
        applyColor(strokePaint, ChartConstants.Colors.BLACK, alpha)
        canvas.drawPath(outer, strokePaint)
        if (!isHold) canvas.drawPath(inner, strokePaint)

        val shader: Shader = when {
            ddr != null -> {
                if (isHold) {
                    LinearGradient(
                        petal.petalX.toFloat(),
                        petal.petalY.toFloat(),
                        petal.tip.x.toFloat(),
                        petal.tip.y.toFloat(),
                        intArrayOf(
                            mixColor(ddr, ChartConstants.Colors.WHITE, 0.24f),
                            ddr,
                            mixColor(ddr, ChartConstants.Colors.BLACK, 0.22f),
                            mixColor(ddr, ChartConstants.Colors.WHITE, 0.5f),
                        ),
                        floatArrayOf(0f, 0.35f, 0.72f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        petal.petalX.toFloat(),
                        petal.petalY.toFloat(),
                        petal.tip.x.toFloat(),
                        petal.tip.y.toFloat(),
                        ddr,
                        ddr,
                        Shader.TileMode.CLAMP,
                    )
                }
            }
            isHold -> {
                val palette = holdPalettes[petal.index]
                LinearGradient(
                    petal.petalX.toFloat(),
                    petal.petalY.toFloat(),
                    petal.tip.x.toFloat(),
                    petal.tip.y.toFloat(),
                    intArrayOf(palette[0], palette[1], palette[2], palette[3]),
                    floatArrayOf(0f, 0.35f, 0.72f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            else -> {
                LinearGradient(
                    petal.petalX.toFloat(),
                    petal.petalY.toFloat(),
                    petal.tip.x.toFloat(),
                    petal.tip.y.toFloat(),
                    if (isSimultaneous) {
                        ChartConstants.Colors.TOUCH_SIMULTANEOUS_YELLOW
                    } else {
                        ChartConstants.Colors.TOUCH_CYAN
                    },
                    if (isSimultaneous) {
                        ChartConstants.Colors.SIMULTANEOUS_GOLD
                    } else {
                        ChartConstants.Colors.TOUCH_BLUE
                    },
                    Shader.TileMode.CLAMP,
                )
            }
        }

        val fillPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addPath(outer)
            if (!isHold) addPath(inner)
        }
        gradientPaint.shader = shader
        gradientPaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(fillPath, gradientPaint)
        gradientPaint.shader = null

        strokeSettings(strokeWidth, Paint.Cap.BUTT, Paint.Join.MITER)
        applyColor(strokePaint, ChartConstants.Colors.WHITE, alpha)
        canvas.drawPath(outer, strokePaint)
        if (!isHold) canvas.drawPath(inner, strokePaint)
    }

    /** Rounded triangle path used by every petal layer. */
    private fun buildRoundedTriangle(
        tip: ChartPoint,
        left: ChartPoint,
        right: ChartPoint,
        cornerRadius: Double,
    ): Path {
        val path = Path()
        if (cornerRadius <= 0) {
            path.moveTo(tip.x.toFloat(), tip.y.toFloat())
            path.lineTo(left.x.toFloat(), left.y.toFloat())
            path.lineTo(right.x.toFloat(), right.y.toFloat())
            path.close()
            return path
        }
        val vertices = arrayOf(tip, left, right)
        for (i in 0 until 3) {
            val corner = vertices[i]
            val previous = vertices[(i + 2) % 3]
            val next = vertices[(i + 1) % 3]
            val dx1 = corner.x - previous.x
            val dy1 = corner.y - previous.y
            val len1 = sqrt(dx1 * dx1 + dy1 * dy1)
            val dx2 = next.x - corner.x
            val dy2 = next.y - corner.y
            val len2 = sqrt(dx2 * dx2 + dy2 * dy2)
            if (len1 == 0.0 || len2 == 0.0) continue
            val nx1 = dx1 / len1
            val ny1 = dy1 / len1
            val nx2 = dx2 / len2
            val ny2 = dy2 / len2
            val r = min(cornerRadius, min(len1 / 2, len2 / 2))
            val beforeX = corner.x - nx1 * r
            val beforeY = corner.y - ny1 * r
            val afterX = corner.x + nx2 * r
            val afterY = corner.y + ny2 * r
            if (i == 0) path.moveTo(beforeX.toFloat(), beforeY.toFloat())
            else path.lineTo(beforeX.toFloat(), beforeY.toFloat())
            path.quadTo(
                corner.x.toFloat(),
                corner.y.toFloat(),
                afterX.toFloat(),
                afterY.toFloat(),
            )
        }
        path.close()
        return path
    }

    private fun offsetPath(path: Path, dx: Float, dy: Float): Path =
        Path(path).apply { offset(dx, dy) }

    // ---------------------------------------------------------------- touch hold gauge

    private fun drawHoldProgress(origin: ChartPoint, progress: Double, alpha: Float) {
        val unit = context.radius / ChartConstants.PANEL_RADIUS_UNITS.toFloat()
        val half = (unit * TOUCH_HOLD_CANVAS_HALF).toFloat()
        if (half <= 0f) return
        val canvas = context.canvas
        canvas.save()
        val pie = Path().apply {
            moveTo(origin.x.toFloat(), origin.y.toFloat())
            arcTo(
                this,
                origin.x.toFloat(),
                origin.y.toFloat(),
                half,
                -90f,
                (progress * 360).toFloat(),
            )
            close()
        }
        canvas.clipPath(pie)

        // The diamond helper is centered on (0,0); the gauge has to sit on the note.
        fun diamondAt(axis: Double, diagonal: Double): Path =
            roundedDiamond(axis, diagonal, unit).apply {
                offset(origin.x.toFloat(), origin.y.toFloat())
            }
        val ring = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addPath(diamondAt(TOUCH_HOLD_OUTER_AXIS, TOUCH_HOLD_OUTER_DIAGONAL))
            addPath(diamondAt(TOUCH_HOLD_INNER_AXIS, TOUCH_HOLD_INNER_DIAGONAL))
        }
        canvas.save()
        canvas.clipPath(ring)
        for (index in 0 until 4) {
            val angle = -PI / 4 + index * PI / 2
            val dx = cos(angle)
            val dy = sin(angle)
            val shader = LinearGradient(
                (origin.x + dx * TOUCH_HOLD_INNER_DIAGONAL * unit).toFloat(),
                (origin.y + dy * TOUCH_HOLD_INNER_DIAGONAL * unit).toFloat(),
                (origin.x + dx * TOUCH_HOLD_OUTER_DIAGONAL * unit).toFloat(),
                (origin.y + dy * TOUCH_HOLD_OUTER_DIAGONAL * unit).toFloat(),
                intArrayOf(
                    mixColor(progressColors[index], ChartConstants.Colors.BLACK, 0.28f),
                    progressColors[index],
                    mixColor(progressColors[index], ChartConstants.Colors.WHITE, 0.32f),
                    mixColor(progressColors[index], ChartConstants.Colors.WHITE, 0.08f),
                ),
                floatArrayOf(0f, 0.35f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            gradientPaint.shader = shader
            gradientPaint.alpha = (255 * alpha).toInt()
            val left = if (dx > 0) origin.x else origin.x - half
            val top = if (dy > 0) origin.y else origin.y - half
            canvas.drawRect(
                left.toFloat(),
                top.toFloat(),
                (left + half).toFloat(),
                (top + half).toFloat(),
                gradientPaint,
            )
        }
        gradientPaint.shader = null
        canvas.restore()
        canvas.restore()
    }

    private fun roundedDiamond(axisRadius: Double, diagonalRadius: Double, unit: Float): Path {
        val radius = diagonalRadius * sqrt(2.0) * unit
        val offset = 2 * (radius - axisRadius * unit)
        val path = Path()
        path.moveTo((-offset).toFloat(), (-radius + offset).toFloat())
        path.quadTo(0f, (-radius).toFloat(), offset.toFloat(), (-radius + offset).toFloat())
        path.lineTo((radius - offset).toFloat(), (-offset).toFloat())
        path.quadTo(radius.toFloat(), 0f, (radius - offset).toFloat(), offset.toFloat())
        path.lineTo(offset.toFloat(), (radius - offset).toFloat())
        path.quadTo(0f, radius.toFloat(), (-offset).toFloat(), (radius - offset).toFloat())
        path.lineTo((-radius + offset).toFloat(), offset.toFloat())
        path.quadTo((-radius).toFloat(), 0f, (-radius + offset).toFloat(), (-offset).toFloat())
        path.close()
        return path
    }

    // ---------------------------------------------------------------- multi-touch border

    fun renderTouchBorder(position: ChartPoint, isSimultaneous: Boolean, visibleTouchCount: Int) {
        if (visibleTouchCount < 2) return
        val boxSize = scaleByRadius(1.0 / 4.46) * 1.1f * 1.2f
        val cornerRadius = scaleByRadius(ChartConstants.NOTE_SIZE_RATIO)
        val color = if (isSimultaneous) {
            ChartConstants.Colors.SIMULTANEOUS_GOLD
        } else {
            ChartConstants.Colors.TOUCH_CYAN
        }
        if (visibleTouchCount >= 3) {
            drawTouchBorderBox(position, boxSize * 1.2f, cornerRadius, color, 3)
        }
        drawTouchBorderBox(position, boxSize, cornerRadius, color, 3)
    }

    private fun drawTouchBorderBox(
        position: ChartPoint,
        size: Float,
        cornerRadius: Float,
        color: Int,
        lineWidth: Int,
    ) {
        val half = size / 2
        val left = position.x.toFloat() - half
        val top = position.y.toFloat() - half
        val gap = size * 0.3f
        val path = Path()
        path.moveTo(left + cornerRadius, top)
        path.lineTo(left + size / 2 - gap / 2, top)
        path.moveTo(left + size / 2 + gap / 2, top)
        path.lineTo(left + size - cornerRadius, top)
        // Corner radii are centered on (left+size-r, top+r) and friends; using the box edges as
        // the rect made the arcs bulge outside the box.
        path.arcTo(
            android.graphics.RectF(
                left + size - 2 * cornerRadius,
                top,
                left + size,
                top + 2 * cornerRadius,
            ),
            -90f,
            90f,
        )

        path.lineTo(left + size, top + size / 2 - gap / 2)
        path.moveTo(left + size, top + size / 2 + gap / 2)
        path.lineTo(left + size, top + size - cornerRadius)
        path.arcTo(
            android.graphics.RectF(
                left + size - 2 * cornerRadius,
                top + size - 2 * cornerRadius,
                left + size,
                top + size,
            ),
            0f,
            90f,
        )

        path.lineTo(left + size / 2 + gap / 2, top + size)
        path.moveTo(left + size / 2 - gap / 2, top + size)
        path.lineTo(left + cornerRadius, top + size)
        path.arcTo(
            android.graphics.RectF(
                left,
                top + size - 2 * cornerRadius,
                left + 2 * cornerRadius,
                top + size,
            ),
            90f,
            90f,
        )

        path.lineTo(left, top + size / 2 + gap / 2)
        path.moveTo(left, top + size / 2 - gap / 2)
        path.lineTo(left, top + cornerRadius)
        path.arcTo(
            android.graphics.RectF(
                left,
                top,
                left + 2 * cornerRadius,
                top + 2 * cornerRadius,
            ),
            180f,
            90f,
        )

        borderPaint.color = color
        borderPaint.strokeWidth = lineWidth * context.radius / 300f
        borderPaint.strokeCap = Paint.Cap.BUTT
        borderPaint.strokeJoin = Paint.Join.ROUND
        context.canvas.drawPath(path, borderPaint)
    }

    companion object {
        private const val TOUCH_HOLD_CANVAS_HALF = 99.75
        private const val TOUCH_HOLD_INNER_AXIS = 64.0
        private const val TOUCH_HOLD_INNER_DIAGONAL = 50.5
        private const val TOUCH_HOLD_OUTER_AXIS = 97.75
        private const val TOUCH_HOLD_OUTER_DIAGONAL = 82.75

        private val progressColors = intArrayOf(
            0xFFE95513.toInt(),
            0xFFFAED00.toInt(),
            0xFF0DAC67.toInt(),
            0xFF2CA6E0.toInt(),
        )

        private val holdPalettes = arrayOf(
            intArrayOf(
                0xFFFF5511.toInt(),
                0xFFF74601.toInt(),
                0xFFE74201.toInt(),
                0xFFFFF6F2.toInt(),
            ),
            intArrayOf(
                0xFFF8DE00.toInt(),
                0xFFF6ED00.toInt(),
                0xFFECF402.toInt(),
                0xFFFFFBEF.toInt(),
            ),
            intArrayOf(
                0xFF1EB476.toInt(),
                0xFF12A86A.toInt(),
                0xFF0AA062.toInt(),
                0xFFECFFF8.toInt(),
            ),
            intArrayOf(
                0xFF00AAF8.toInt(),
                0xFF0097F6.toInt(),
                0xFF0289F4.toInt(),
                0xFFEFFCFF.toInt(),
            ),
        )
    }
}
