package org.rhythmeta.maimaid.core.chart.render

import android.graphics.Paint
import android.graphics.Path
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.rhythmeta.maimaid.core.chart.ArcLutPoint
import org.rhythmeta.maimaid.core.chart.ChartConstants
import org.rhythmeta.maimaid.core.chart.ChartPoint
import org.rhythmeta.maimaid.core.chart.NoteRenderPosition
import org.rhythmeta.maimaid.core.chart.SlideBars
import org.rhythmeta.maimaid.core.chart.SlideChainPoint
import org.rhythmeta.maimaid.core.chart.SlideGeometry
import org.rhythmeta.maimaid.core.chart.SlideNote
import org.rhythmeta.maimaid.core.chart.SlidePathType
import org.rhythmeta.maimaid.core.chart.SlideSegment
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withSave
import kotlin.math.roundToInt

/** Slide tracks (arrow chains / Wi-Fi fans) and slide star heads. */
class SlidePainter(
    context: RenderContext,
    private val notePainter: NotePainter,
) : BasePainter(context) {
    enum class RenderMode { TRACKS, STARS }

    private val arrowCache = IdentityHashMap<SlideSegment, MutableMap<String, List<ArrowPaths>>>()
    private val arrowCacheBasis = IdentityHashMap<SlideSegment, String>()

	private class ArrowPaths(val main: Path, val shadow: Path, val leftHalf: Path)

    /** Calculates the slide head position; first half fades in place, second half expands to the ring. */
    fun calculateSlideStartPosition(note: SlideNote, currentTimeMs: Double): NoteRenderPosition {
        val angle = buttonAngle(note.position)
        val timeDiff = note.timingMs - currentTimeMs
        val approachTime = noteApproachTimeMs(note.hiSpeed)
        if (timeDiff > approachTime || timeDiff < -ChartConstants.NOTE_VISIBILITY_AFTER_MS) {
            return NoteRenderPosition.INVISIBLE
        }
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

    /** Renders one slide, either its guide track or its moving star head. */
    fun renderSlide(
        note: SlideNote,
        currentTimeMs: Double,
        mode: RenderMode,
        hasSimultaneousSlide: Boolean,
    ) {
        if (note.isSplitSlide && note.allSlideSegments.isNotEmpty()) {
            val paths = note.allSlideSegments
            val indexes = renderPathIndexes(note)
            for (index in indexes) {
                if (paths[index].firstOrNull()?.type == SlidePathType.WIFI) {
                    renderSlidePath(note, currentTimeMs, paths[index], index, mode, hasSimultaneousSlide)
                }
            }
            for (index in indexes) {
                if (paths[index].firstOrNull()?.type != SlidePathType.WIFI) {
                    renderSlidePath(note, currentTimeMs, paths[index], index, mode, hasSimultaneousSlide)
                }
            }
        } else if (note.slideSegments.isNotEmpty()) {
            renderSlidePath(note, currentTimeMs, note.slideSegments, 0, mode, hasSimultaneousSlide)
        }
    }

    /** De-duplicates identical concurrent paths so they are not drawn twice. */
    private fun renderPathIndexes(note: SlideNote): List<Int> {
        val paths = note.allSlideSegments
        val seen = HashSet<String>()
        val result = mutableListOf<Int>()
        for (index in paths.indices) {
            val segments = paths[index]
            if (segments.isEmpty()) continue
            val key = buildString {
                append(segments.joinToString("+") { it.signatureKey() })
                append('|').append(note.allDelayMs.getOrNull(index) ?: "")
                append('|').append(note.allDurationMs.getOrNull(index) ?: "")
                append('|').append(if (note.allSlideBreaks.getOrNull(index) == true) 1 else 0)
            }
            if (!seen.add(key)) continue
            result += index
        }
        return result
    }

    private fun renderSlidePath(
        note: SlideNote,
        currentTimeMs: Double,
        segments: List<SlideSegment>,
        pathIndex: Int,
        mode: RenderMode,
        hasSimultaneousSlide: Boolean,
    ) {
        val durationMs = note.allDurationMs.getOrNull(pathIndex) ?: note.durationMs
        val delayMs = note.allDelayMs.getOrNull(pathIndex) ?: note.delayMs
        val slideStart = note.timingMs + delayMs
        if (currentTimeMs > slideStart + durationMs) return

        var progress = 0.0
        if (currentTimeMs >= slideStart) {
            progress = if (durationMs > 0) min(1.0, (currentTimeMs - slideStart) / durationMs) else 1.0
        }

        val isSimultaneous = hasSimultaneousSlide || note.isSplitSlide
        if (mode == RenderMode.STARS) {
            if (currentTimeMs >= note.timingMs) {
                renderSlideStar(note, progress, segments, pathIndex, currentTimeMs, isSimultaneous)
            }
            return
        }

        val ordinary = slideTrackAppearance(note, currentTimeMs, false)
        val wifi = slideTrackAppearance(note, currentTimeMs, true)
        if (ordinary.alpha == 0.0 && wifi.alpha == 0.0) return

        val isBreak = note.allSlideBreaks.getOrNull(pathIndex) == true
        val lengths = DoubleArray(segments.size) { getSegmentLength(segments[it]) }
        val totalLength = lengths.sum()
        if (totalLength <= 0) return
        val ranges = arrayOfNulls<Pair<Double, Double>>(segments.size)
        var cumulative = 0.0
        for (index in segments.indices) {
            val start = cumulative / totalLength
            cumulative += lengths[index]
            ranges[index] = start to cumulative / totalLength
        }

        for (index in segments.indices.reversed()) {
            val segment = segments[index]
            val alpha = if (segment.type == SlidePathType.WIFI) wifi.alpha else ordinary.alpha
            if (alpha == 0.0) continue
            val range = ranges[index] ?: continue
            var segmentProgress = 0.0
            if (progress > range.first) {
                segmentProgress = if (progress >= range.second) {
                    1.0
                } else {
                    (progress - range.first) / (range.second - range.first)
                }
            }
            renderSlideSegment(
                segment,
                isBreak,
                segmentProgress,
                isSimultaneous,
                context.config.normalColorBreakSlide,
                index < segments.size - 1,
                alpha.toFloat(),
            )
        }
    }

    private fun renderSlideSegment(
        segment: SlideSegment,
        isBreak: Boolean,
        progress: Double,
        isSimultaneous: Boolean,
        normalBreakColor: Boolean,
        isJunctionEnd: Boolean,
        alpha: Float,
    ) {
        val color = when {
            isBreak && !normalBreakColor -> ChartConstants.Colors.BREAK_ORANGE
            isSimultaneous -> ChartConstants.Colors.SLIDE_SIMULTANEOUS
            else -> ChartConstants.Colors.SLIDE_CYAN
        }
        if (segment.type == SlidePathType.WIFI) {
            renderWifiBars(segment, progress, color, alpha)
            return
        }
        drawSegmentArrows(segment, progress, isJunctionEnd, color, alpha)
    }

    // ---------------------------------------------------------------- guide chain

    /** Transforms the unit-circle template into canvas space (mirror + start rotation). */
    private fun getBarChain(segment: SlideSegment): List<SlideChainPoint>? {
        val shape = SlideGeometry.detectSlideShape(
            segment.type,
            segment.startPos,
            segment.endPos,
            segment.midPos,
        ) ?: return null
        val bars = SlideBars[shape.shape] ?: return null

        val mode = context.config.mirrorMode
        val cached = segment.cachedChain
        if (cached != null && segment.cachedChainRadius == context.radius &&
            segment.cachedChainMirror == mode
        ) {
            return cached
        }

        val startRotation = (segment.startPos - (if (shape.mirror) 0 else 1)) * PI / 4
        val cosR = cos(startRotation)
        val sinR = sin(startRotation)
        val radius = context.radius.toDouble()
        val centerX = context.centerX.toDouble()
        val centerY = context.centerY.toDouble()
        val sx = if (mode == org.rhythmeta.maimaid.core.chart.MirrorMode.HORIZONTAL ||
            mode == org.rhythmeta.maimaid.core.chart.MirrorMode.ROTATE180
        ) {
            -1.0
        } else {
            1.0
        }
        val sy = if (mode == org.rhythmeta.maimaid.core.chart.MirrorMode.VERTICAL ||
            mode == org.rhythmeta.maimaid.core.chart.MirrorMode.ROTATE180
        ) {
            -1.0
        } else {
            1.0
        }

        val isCircle = shape.shape.startsWith("circle")
        val isLine = shape.shape.startsWith("line")

        val lastBar = bars.last()
        val firstX = if (shape.mirror) -bars[0].x else bars[0].x
        val firstY = bars[0].y
        val lastX = if (shape.mirror) -lastBar.x else lastBar.x
        val lineDx = lastX - firstX
        val lineDy = lastBar.y - firstY
        val lineLen2 = (lineDx * lineDx + lineDy * lineDy).takeIf { it != 0.0 } ?: 1.0

        val chain = bars.map { bar ->
            var bx = if (shape.mirror) -bar.x else bar.x
            var by = bar.y
            if (isCircle) {
                val angle = atan2(by, bx)
                val snapped = (angle * 32.0 / PI).roundToInt() * (PI / 32.0)
                bx = cos(snapped)
                by = sin(snapped)
            } else if (isLine) {
                val t = ((bx - firstX) * lineDx + (by - firstY) * lineDy) / lineLen2
                bx = firstX + t * lineDx
                by = firstY + t * lineDy
            }
            val baked = (if (shape.mirror) PI - bar.r else bar.r) + startRotation
            SlideChainPoint(
                x = centerX + sx * radius * (bx * cosR - by * sinR),
                y = centerY + sy * radius * (bx * sinR + by * cosR),
                angle = atan2(sy * sin(baked), sx * cos(baked)),
            )
        }

        val mutableChain = chain.toMutableList()
        val isCup = segment.type == SlidePathType.SPIRAL_CLOCKWISE ||
            segment.type == SlidePathType.SPIRAL_COUNTER_CLOCKWISE ||
            segment.type == SlidePathType.SPIRAL_CLOCKWISE_DOUBLE ||
            segment.type == SlidePathType.SPIRAL_COUNTER_CLOCKWISE_DOUBLE
        if ((isCup || isCircle) && mutableChain.size >= 2) {
            applyGameArrowAngles(mutableChain)
        }

        val immutable = mutableChain.toList()
        segment.cachedChain = immutable
        segment.cachedChainRadius = context.radius
        segment.cachedChainMirror = mode
        return immutable
    }

    /** Smooths tangents along the bisector so large-curvature chains have no visible kinks. */
    private fun applyGameArrowAngles(chain: MutableList<SlideChainPoint>) {
        val count = chain.size
        val segment = DoubleArray(count)
        for (index in 0 until count - 1) {
            segment[index] = atan2(
                chain[index + 1].y - chain[index].y,
                chain[index + 1].x - chain[index].x,
            )
        }
        segment[count - 1] = segment[count - 2]
        chain[0] = chain[0].copy(angle = segment[0])
        for (index in 1 until count - 1) {
            chain[index] = chain[index].copy(
                angle = lerpAngle(chain[index - 1].angle, segment[index], 0.5),
            )
        }
        chain[count - 1] = chain[count - 1].copy(angle = segment[count - 1])
    }

    // ---------------------------------------------------------------- segment arcs

    private fun getHiddenCount(segment: SlideSegment, progress: Double): Int {
        val shape = SlideGeometry.detectSlideShape(
            segment.type,
            segment.startPos,
            segment.endPos,
            segment.midPos,
        ) ?: return 0
        val steps = SlideGeometry.stepMap[shape.shape]
        val hidden = if (steps != null && steps.size >= 2) {
            steps[min(steps.size - 1, kotlin.math.floor(progress * (steps.size - 1)).toInt())]
        } else {
            0
        }
        return if (segment.type == SlidePathType.WIFI && progress > 0) hidden + 1 else hidden
    }

    /** Draws the chevron arrows of one segment: shadow, body, optional break half tint, black edge. */
    private fun drawSegmentArrows(
        segment: SlideSegment,
        progress: Double,
        isJunctionEnd: Boolean,
        color: Int,
        alpha: Float,
    ) {
        val chain = getBarChain(segment) ?: return
        val basis = "${context.radius}|${context.config.mirrorMode}"
        if (arrowCacheBasis[segment] != basis) {
            arrowCacheBasis[segment] = basis
            arrowCache.remove(segment)
        }
        val cache = arrowCache.getOrPut(segment) { HashMap() }
        val hiddenCount = getHiddenCount(segment, progress)
        val key = "$hiddenCount|${if (isJunctionEnd) 1 else 0}"
        var paths = cache[key]
        if (paths == null) {
            val bars = getVisibleBarsForSegment(segment, chain, hiddenCount, isJunctionEnd)
            if (bars.isEmpty()) return
            paths = buildArrowPaths(bars)
            if (cache.size > 64) cache.clear()
            cache[key] = paths
        }

        val isBreak = color == ChartConstants.Colors.BREAK_ORANGE
        val leftColor = if (isBreak) ChartConstants.Colors.SLIDE_SIMULTANEOUS else color
        val rightColor = if (isBreak) ChartConstants.Colors.SLIDE_ARROW_RIGHT else color
        val canvas = context.canvas
        val noteStroke = noteStrokeWidth()

        for (arrow in paths) {
            applyColor(fillPaint, ChartConstants.Colors.BLACK, alpha * 0.4f)
            canvas.drawPath(arrow.shadow, fillPaint)
            applyColor(fillPaint, rightColor, alpha)
            canvas.drawPath(arrow.main, fillPaint)
            if (isBreak) {
                applyColor(fillPaint, leftColor, alpha)
                canvas.drawPath(arrow.leftHalf, fillPaint)
            }
            strokeSettings(noteStroke, Paint.Cap.BUTT, Paint.Join.MITER)
            applyColor(strokePaint, ChartConstants.Colors.BLACK, alpha)
            canvas.drawPath(arrow.main, strokePaint)
        }
    }

    private fun getVisibleBarsForSegment(
        segment: SlideSegment,
        chain: List<SlideChainPoint>,
        hiddenCount: Int,
        isJunctionEnd: Boolean,
    ): List<SlideChainPoint> {
        if (segment.type == SlidePathType.WIFI) return emptyList()
        val usePrecomputedAngle = segment.type != SlidePathType.LINE
        val result = mutableListOf<SlideChainPoint>()
        val last = chain.size - 1

        if (isJunctionEnd && chain.size >= 2 && hiddenCount < chain.size) {
            val a = chain[last]
            val b = chain[last - 1]
            val jx = 2 * a.x - b.x
            val jy = 2 * a.y - b.y
            if (distanceToCenter(jx, jy) <= context.radius) {
                val angle = if (usePrecomputedAngle) a.angle else atan2(a.y - b.y, a.x - b.x)
                result += SlideChainPoint(jx, jy, angle)
            }
        }

        for (index in last downTo hiddenCount) {
            val angle = if (usePrecomputedAngle) {
                chain[index].angle
            } else {
                val lo = max(0, index - 1)
                val hi = min(last, index + 1)
                atan2(chain[hi].y - chain[lo].y, chain[hi].x - chain[lo].x)
            }
            result += SlideChainPoint(chain[index].x, chain[index].y, angle)
        }
        return result
    }

    /** Builds filled chevron polygons plus their baked shadow offset. */
    private fun buildArrowPaths(arrows: List<SlideChainPoint>): List<ArrowPaths> {
        val arrowHeight = scaleByRadius(ChartConstants.SLIDE_ARROW_HEIGHT_RATIO).toDouble()
        val arrowWidth = scaleByRadius(ChartConstants.SLIDE_ARROW_SPAN_RATIO).toDouble()
        val lineWidth = scaleByRadius(ChartConstants.SLIDE_ARROW_WIDTH_RATIO).toDouble()
        val padding = scaleByRadius(ChartConstants.SLIDE_ARROW_PADDING_RATIO).toDouble()
        val shadowOffset = scaleByRadius(5.0 / 300.0).toDouble()

        val result = mutableListOf<ArrowPaths>()
        for ((x, y, angle) in arrows) {
            val cosA = cos(angle)
            val sinA = sin(angle)
            val ax = x + cosA * padding
            val ay = y + sinA * padding

            val x1 = ax + cosA * (-arrowWidth / 2) - sinA * (-arrowHeight / 2)
            val y1 = ay + sinA * (-arrowWidth / 2) + cosA * (-arrowHeight / 2)
            val x2 = ax + cosA * (arrowWidth / 2)
            val y2 = ay + sinA * (arrowWidth / 2)
            val x3 = ax + cosA * (-arrowWidth / 2) - sinA * (arrowHeight / 2)
            val y3 = ay + sinA * (-arrowWidth / 2) + cosA * (arrowHeight / 2)

            val bisector = bisectorPoint(x1, y1, x2, y2, x3, y3, lineWidth) ?: continue
            val bx = bisector.first
            val by = bisector.second
            val bx3 = bx + x3 - x2
            val by3 = by + y3 - y2
            val bx1 = bx + x1 - x2
            val by1 = by + y1 - y2

            val main = Path().apply {
                moveTo(x2.toFloat(), y2.toFloat())
                lineTo(x3.toFloat(), y3.toFloat())
                lineTo(bx3.toFloat(), by3.toFloat())
                lineTo(bx.toFloat(), by.toFloat())
                lineTo(bx1.toFloat(), by1.toFloat())
                lineTo(x1.toFloat(), y1.toFloat())
                close()
            }
            val sx = -cosA * shadowOffset
            val sy = -sinA * shadowOffset
            val shadow = Path().apply {
                moveTo((x2 + sx).toFloat(), (y2 + sy).toFloat())
                lineTo((x3 + sx).toFloat(), (y3 + sy).toFloat())
                lineTo((bx3 + sx).toFloat(), (by3 + sy).toFloat())
                lineTo((bx + sx).toFloat(), (by + sy).toFloat())
                lineTo((bx1 + sx).toFloat(), (by1 + sy).toFloat())
                lineTo((x1 + sx).toFloat(), (y1 + sy).toFloat())
                close()
            }
            val leftHalf = Path().apply {
                moveTo(x2.toFloat(), y2.toFloat())
                lineTo(bx.toFloat(), by.toFloat())
                lineTo(bx3.toFloat(), by3.toFloat())
                lineTo(x3.toFloat(), y3.toFloat())
                close()
            }
            result += ArrowPaths(main, shadow, leftHalf)
        }
        return result
    }

    private fun bisectorPoint(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
        width: Double,
    ): Pair<Double, Double>? {
        val rLen = hypot(x1 - x2, y1 - y2)
        val lLen = hypot(x3 - x2, y3 - y2)
        if (rLen == 0.0 || lLen == 0.0) return null
        val cosAngle = max(
            -1.0,
            min(1.0, ((x1 - x2) * (x3 - x2) + (y1 - y2) * (y3 - y2)) / (rLen * lLen)),
        )
        val sinHalf = kotlin.math.sqrt((1 - cosAngle) / 2)
        if (sinHalf <= 0.001) return null
        val midX = (x1 + x3) / 2
        val midY = (y1 + y3) / 2
        val bLen = hypot(midX - x2, midY - y2)
        val bux = (midX - x2) / bLen
        val buy = (midY - y2) / bLen
        val edgeLen = width / sinHalf
        return (x2 + bux * edgeLen) to (y2 + buy * edgeLen)
    }

    // ---------------------------------------------------------------- wifi

    private fun renderWifiBars(segment: SlideSegment, progress: Double, color: Int, alpha: Float) {
        val steps = SlideGeometry.stepMap["wifi"] ?: return
        val count = steps.last()
        val hiddenCount = getHiddenCount(segment, progress)
        val pivot = notePainter.getPositionOnRing(segment.startPos)
        val endPivot = notePainter.getPositionOnRing(segment.endPos)
        val axisLen = hypot(endPivot.x - pivot.x, endPivot.y - pivot.y)
        if (axisLen == 0.0) return
        val axisUx = (endPivot.x - pivot.x) / axisLen
        val axisUy = (endPivot.y - pivot.y) / axisLen
        val fanAngle = atan2(axisUy, axisUx)

        val armHalfAngle = 67.4 * PI / 180
        val cosA = cos(armHalfAngle)
        val sinA = sin(armHalfAngle)
        val dFirst = 0.075 * axisLen
        val dLast = 0.975 * axisLen
        val startExtra = scaleByRadius(0.075).toDouble()

        val chevrons = mutableListOf<WifiChevron>()
        for (index in count - 1 downTo hiddenCount) {
            if (index < 0 || index >= ChartConstants.SLIDE_WIFI_CORNER_FRACS.size) continue
            val t = index.toDouble() / (count - 1)
            val d = dFirst + (dLast - dFirst) * ChartConstants.SLIDE_WIFI_CORNER_FRACS[index]
            val armLen = cosA * d + startExtra * (1 - t)
            val width = scaleByRadius(
                ChartConstants.SLIDE_WIFI_LINE_WIDTH_RATIO * (0.7 + 0.3 * t),
            ).toDouble()
            chevrons += WifiChevron(
                x = pivot.x + axisUx * d,
                y = pivot.y + axisUy * d,
                arm1Dx = -armLen * cosA,
                arm1Dy = armLen * sinA,
                arm2Dx = -armLen * cosA,
                arm2Dy = -armLen * sinA,
                width = width,
            )
        }
        if (chevrons.isEmpty()) return
        drawWifiChevronsBatch(chevrons, fanAngle, color, alpha)
    }

    private class WifiChevron(
        val x: Double,
        val y: Double,
        val arm1Dx: Double,
        val arm1Dy: Double,
        val arm2Dx: Double,
        val arm2Dy: Double,
        val width: Double,
    )

    private fun drawWifiChevronsBatch(
        chevrons: List<WifiChevron>,
        fanAngle: Double,
        color: Int,
        alpha: Float,
    ) {
        if (chevrons.isEmpty()) return
        val canvas = context.canvas
        val shadowOffset = scaleByRadius(5.0 / 300.0).toDouble()
        val cosA = cos(fanAngle)
        val sinA = sin(fanAngle)

        val fanPath = Path()
        for (c in chevrons) {
            val a1x = c.x + cosA * c.arm1Dx - sinA * c.arm1Dy
            val a1y = c.y + sinA * c.arm1Dx + cosA * c.arm1Dy
            val a2x = c.x + cosA * c.arm2Dx - sinA * c.arm2Dy
            val a2y = c.y + sinA * c.arm2Dx + cosA * c.arm2Dy
            buildChevronPath(fanPath, c.x, c.y, a1x, a1y, a2x, a2y, c.width, cosA, sinA)
        }

        canvas.withTranslation((-cosA * shadowOffset).toFloat(), (-sinA * shadowOffset).toFloat()) {
            applyColor(fillPaint, ChartConstants.Colors.BLACK, alpha * 0.4f)
            drawPath(fanPath, fillPaint)
        }

        applyColor(fillPaint, color, alpha)
        canvas.drawPath(fanPath, fillPaint)
    }

    private fun buildChevronPath(
			path: Path,
			cornerX: Double,
			cornerY: Double,
			arm1X: Double,
			arm1Y: Double,
			arm2X: Double,
			arm2Y: Double,
			width: Double,
			cosA: Double,
        sinA: Double,
    ) {
        val arm1Dx = arm1X - cornerX
        val arm1Dy = arm1Y - cornerY
        val arm2Dx = arm2X - cornerX
        val arm2Dy = arm2Y - cornerY
        val arm1Len = hypot(arm1Dx, arm1Dy)
        val arm2Len = hypot(arm2Dx, arm2Dy)
        if (arm1Len == 0.0 || arm2Len == 0.0) return
        val cosAngle = max(
            -1.0,
            min(1.0, (arm1Dx * arm2Dx + arm1Dy * arm2Dy) / (arm1Len * arm2Len)),
        )
        val sinHalf = kotlin.math.sqrt((1 - cosAngle) / 2)
        if (sinHalf <= 0.001) return
        val armAxisProjection = -((arm1Dx * cosA + arm1Dy * sinA) / arm1Len)
        if (armAxisProjection <= 0) return
        val outerDistance = arm1Len / armAxisProjection
        val innerDistance = max(0.0, outerDistance - width / sinHalf)
        val innerScale = innerDistance / outerDistance
        val pivotX = cornerX - cosA * outerDistance
        val pivotY = cornerY - sinA * outerDistance
        val innerCornerX = pivotX + (cornerX - pivotX) * innerScale
        val innerCornerY = pivotY + (cornerY - pivotY) * innerScale
        val innerArm1X = pivotX + (arm1X - pivotX) * innerScale
        val innerArm1Y = pivotY + (arm1Y - pivotY) * innerScale
        val innerArm2X = pivotX + (arm2X - pivotX) * innerScale
        val innerArm2Y = pivotY + (arm2Y - pivotY) * innerScale

        path.moveTo(arm1X.toFloat(), arm1Y.toFloat())
        path.lineTo(cornerX.toFloat(), cornerY.toFloat())
        path.lineTo(arm2X.toFloat(), arm2Y.toFloat())
        path.lineTo(innerArm2X.toFloat(), innerArm2Y.toFloat())
        path.lineTo(innerCornerX.toFloat(), innerCornerY.toFloat())
        path.lineTo(innerArm1X.toFloat(), innerArm1Y.toFloat())
        path.close()
    }

    // ---------------------------------------------------------------- arc length LUT

    private fun getSegmentLength(segment: SlideSegment): Double {
        val cached = segment.cachedLength
        if (cached != null && segment.cachedRadius == context.radius &&
            segment.cachedMirrorMode == context.config.mirrorMode
        ) {
            return cached
        }
        val lut = getSegmentLut(segment)
        return lut.last().s
    }

    private fun getSegmentLut(segment: SlideSegment): List<ArcLutPoint> {
        val mode = context.config.mirrorMode
        val cached = segment.cachedLut
        if (cached != null && segment.cachedRadius == context.radius &&
            segment.cachedMirrorMode == mode
        ) {
            return cached
        }
        val samples = 64
        val lut = ArrayList<ArcLutPoint>(samples + 1)
        val p0 = getPointOnSegment(segment, 0.0)
        val p1 = getPointOnSegment(segment, 1.0 / samples)
        lut += ArcLutPoint(p0.x, p0.y, atan2(p1.y - p0.y, p1.x - p0.x), 0.0)
        var previous = p0
        var accumulated = 0.0
        for (index in 1..samples) {
            val current = if (index == 1) p1 else getPointOnSegment(segment, index.toDouble() / samples)
            val dx = current.x - previous.x
            val dy = current.y - previous.y
            accumulated += hypot(dx, dy)
            lut += ArcLutPoint(current.x, current.y, atan2(dy, dx), accumulated)
            previous = current
        }
        val immutable = lut.toList()
        segment.cachedLut = immutable
        segment.cachedLength = immutable.last().s
        segment.cachedRadius = context.radius
        segment.cachedMirrorMode = mode
        return immutable
    }

    private fun sampleArcLut(lut: List<ArcLutPoint>, s: Double): SlideChainPoint {
        if (s <= 0) {
            val first = lut.first()
            return SlideChainPoint(first.x, first.y, first.angle)
        }
        val last = lut.last()
        if (s >= last.s) return SlideChainPoint(last.x, last.y, last.angle)
        var lo = 0
        var hi = lut.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (lut[mid].s <= s) lo = mid else hi = mid
        }
        val a = lut[lo]
        val b = lut[hi]
        val span = b.s - a.s
        val u = if (span > 0) (s - a.s) / span else 0.0
        return SlideChainPoint(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, b.angle)
    }

    /** Samples a segment at normalized distance t, following the guide chain with ring anchors. */
    fun getPointOnSegment(segment: SlideSegment, t: Double): ChartPoint {
        val chain = getBarChain(segment)
        if (chain != null && chain.size >= 2) {
            val start = notePainter.getPositionOnRing(segment.startPos)
            val end = notePainter.getPositionOnRing(segment.endPos)
            val count = chain.size + 2
            val index = max(0.0, min((count - 1).toDouble(), t * (count - 1)))
            val base = min(count - 2, kotlin.math.floor(index).toInt())
            val fraction = index - base
            fun at(i: Int): ChartPoint = when (i) {
                0 -> start
                count - 1 -> end
                else -> ChartPoint(chain[i - 1].x, chain[i - 1].y)
            }
            val p0 = at(base)
            val p1 = at(base + 1)
            return ChartPoint(
                p0.x + (p1.x - p0.x) * fraction,
                p0.y + (p1.y - p0.y) * fraction,
            )
        }
        val start = notePainter.getPositionOnRing(segment.startPos)
        val end = notePainter.getPositionOnRing(segment.endPos)
        return ChartPoint(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
    }

    private fun pointAlongPath(progress: Double, segments: List<SlideSegment>): ChartPoint {
        if (segments.isEmpty()) return ChartPoint(context.centerX.toDouble(), context.centerY.toDouble())
        val luts = segments.map { getSegmentLut(it) }
        val lengths = luts.map { it.last().s }
        val totalLength = lengths.sum()
        if (totalLength == 0.0) return notePainter.getPositionOnRing(segments.first().startPos)
        val target = progress * totalLength
        var cumulative = 0.0
        for (index in segments.indices) {
            if (cumulative + lengths[index] >= target) {
                val point = sampleArcLut(luts[index], target - cumulative)
                return ChartPoint(point.x, point.y)
            }
            cumulative += lengths[index]
        }
        return notePainter.getPositionOnRing(segments.last().endPos)
    }

    private fun pathTangentAngle(progress: Double, segments: List<SlideSegment>): Double {
        if (segments.isEmpty()) return 0.0
        var total = 0.0
        val zs = mutableListOf<Double>()
        val angles = mutableListOf<Double>()
        for (segment in segments) {
            val chain = getBarChain(segment) ?: continue
            if (chain.isEmpty()) continue
            val start = notePainter.getPositionOnRing(segment.startPos)
            val end = notePainter.getPositionOnRing(segment.endPos)
            val last = chain.size - 1
            val poly = mutableListOf<SlideChainPoint>()
            poly += SlideChainPoint(start.x, start.y, chain.first().angle)
            poly += chain
            poly += SlideChainPoint(end.x, end.y, chain[last].angle)
            var previous: ChartPoint? = null
            for ((x, y, angle) in poly) {
                if (previous != null) total += hypot(x - previous.x, y - previous.y)
                zs += total
                angles += angle
                previous = ChartPoint(x, y)
            }
        }
        if (angles.isEmpty()) return 0.0
        if (angles.size == 1) return angles[0]
        val target = max(0.0, min(1.0, progress)) * total
        for (index in 0 until zs.size - 1) {
            if (zs[index] <= target && target <= zs[index + 1]) {
                val span = zs[index + 1] - zs[index]
                return lerpAngle(angles[index], angles[index + 1], if (span > 0) (target - zs[index]) / span else 0.0)
            }
        }
        return angles.last()
    }

    // ---------------------------------------------------------------- stars

    fun renderSlideStar(
        note: SlideNote,
        progress: Double,
        segments: List<SlideSegment>,
        pathIndex: Int,
        currentTimeMs: Double,
        isSimultaneous: Boolean,
    ) {
        if (segments.isNotEmpty() && segments.first().type == SlidePathType.WIFI) {
            renderWifiStars(note, progress, segments, pathIndex, isSimultaneous, currentTimeMs)
            return
        }

        val delayMs = note.allDelayMs.getOrNull(pathIndex) ?: note.delayMs
        val slideStart = note.timingMs + delayMs

        val starPos: ChartPoint
        var starAlpha = 1.0
        var starScale = 1.0
        var rotation = 0.0
        val isSliding = currentTimeMs >= slideStart

        if (!isSliding) {
            if (note.headlessMode == "pop") return
            starPos = notePainter.getPositionOnRing(segments.first().startPos)
            val elapsed = currentTimeMs - note.timingMs
            val waitingProgress = min(1.0, elapsed / delayMs)
            starAlpha = waitingProgress
            starScale = ChartConstants.SLIDE_STAR_WAITING_MIN_SCALE +
                (1 - ChartConstants.SLIDE_STAR_WAITING_MIN_SCALE) * waitingProgress
            if (context.config.slideRotation) {
                rotation = pathTangentAngle(0.0, segments) + PI / 2
            }
        } else {
            starPos = pointAlongPath(progress, segments)
            if (context.config.slideRotation) {
                rotation = pathTangentAngle(progress, segments) + PI / 2
            }
        }

        val size = context.radius * ChartConstants.SLIDE_STAR_SIZE_RATIO * starScale
        val isBreak = note.allSlideBreaks.getOrNull(pathIndex) == true &&
            !context.config.normalColorBreakSlide
        val color = when {
            isBreak -> ChartConstants.Colors.BREAK_ORANGE
            isSimultaneous -> ChartConstants.Colors.SLIDE_SIMULTANEOUS
            context.config.pinkSlideStart -> ChartConstants.Colors.SLIDE_PINK
            else -> ChartConstants.Colors.SLIDE_CYAN
        }
        drawStar(starPos.x, starPos.y, size, color, rotation, note.isEx, starAlpha.toFloat())
    }

    private fun renderWifiStars(
        note: SlideNote,
        progress: Double,
        segments: List<SlideSegment>,
        pathIndex: Int,
        isSimultaneous: Boolean,
        currentTimeMs: Double,
    ) {
        val delayMs = note.allDelayMs.getOrNull(pathIndex) ?: note.delayMs
        val slideStart = note.timingMs + delayMs
        val startPos = segments.first().startPos
        val endPos = segments.first().endPos
        val fanPositions = listOf(
            endPos,
            ((endPos - 1 - 1 + 8) % 8) + 1,
            ((endPos - 1 + 1) % 8) + 1,
        )

        for (fanEnd in fanPositions) {
            val start = notePainter.getPositionOnRing(startPos)
            val end = notePainter.getPositionOnRing(fanEnd)
            val direction = atan2(end.y - start.y, end.x - start.x)
            val rotation = if (context.config.slideRotation) direction + PI / 2 else 0.0

            var starPos: ChartPoint
            var starAlpha = 1.0
            var starScale = 1.0
            if (currentTimeMs < slideStart) {
                if (note.headlessMode == "pop") continue
                starPos = start
                val elapsed = currentTimeMs - note.timingMs
                val waitingProgress = min(1.0, elapsed / delayMs)
                starAlpha = waitingProgress
                starScale = ChartConstants.SLIDE_STAR_WAITING_MIN_SCALE +
                    (1 - ChartConstants.SLIDE_STAR_WAITING_MIN_SCALE) * waitingProgress
            } else {
                starPos = ChartPoint(
                    start.x + (end.x - start.x) * progress,
                    start.y + (end.y - start.y) * progress,
                )
            }

            val size = context.radius * ChartConstants.SLIDE_STAR_SIZE_RATIO * starScale
            val isBreak = note.allSlideBreaks.getOrNull(pathIndex) == true &&
                !context.config.normalColorBreakSlide
            val color = when {
                isBreak -> ChartConstants.Colors.BREAK_ORANGE
                isSimultaneous -> ChartConstants.Colors.SLIDE_SIMULTANEOUS
                context.config.pinkSlideStart -> ChartConstants.Colors.SLIDE_PINK
                else -> ChartConstants.Colors.SLIDE_CYAN
            }
            drawStar(starPos.x, starPos.y, size, color, rotation, note.isEx, starAlpha.toFloat())
        }
    }

    /** Five-pointed star head: black underlay, hollow colored ring, white edges, center dot. */
    fun drawStar(
        x: Double,
        y: Double,
        size: Double,
        color: Int,
        rotation: Double,
        isEx: Boolean,
        alpha: Float = 1f,
    ) {
        val canvas = context.canvas
        canvas.withSave {
            if (rotation != 0.0) {
                translate(x.toFloat(), y.toFloat())
                rotate(Math.toDegrees(rotation).toFloat())
                translate(-x.toFloat(), -y.toFloat())
            }
					val innerRadius = size * 0.5
            val innerHoleOuter = size * 0.55
					val innerHoleInner = innerRadius * 0.55
					val strokeW = noteStrokeWidth()

					fun starPath(outer: Double, inner: Double, baseAngle: Double): Path {
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

					if (!isEx) {
						strokeSettings(strokeW * 3, Paint.Cap.BUTT, Paint.Join.MITER)
						applyColor(strokePaint, ChartConstants.Colors.BLACK, alpha)
						drawPath(starPath(size, innerRadius, -PI / 2), strokePaint)
					}
					strokeSettings(strokeW * 3, Paint.Cap.BUTT, Paint.Join.MITER)
					applyColor(strokePaint, ChartConstants.Colors.BLACK, alpha)
					drawPath(starPath(innerHoleOuter, innerHoleInner, -PI / 2), strokePaint)

					val ringPath = Path().apply {
						fillType = Path.FillType.EVEN_ODD
						addPath(starPath(size, innerRadius, -PI / 2))
						addPath(starPath(innerHoleOuter, innerHoleInner, -PI / 2))
					}
					applyColor(fillPaint, color, alpha)
					drawPath(ringPath, fillPaint)

					strokeSettings(strokeW, Paint.Cap.BUTT, Paint.Join.MITER)
					applyColor(strokePaint, ChartConstants.Colors.WHITE, alpha)
					drawPath(starPath(size, innerRadius, -PI / 2), strokePaint)
					drawPath(starPath(innerHoleOuter, innerHoleInner, -PI / 2), strokePaint)

					applyColor(fillPaint, color, alpha)
					drawCircle(x.toFloat(), y.toFloat(), (size * 0.15).toFloat(), fillPaint)
				}
		}

	/** EX outer glow star ring. */
	fun renderExStarRing(
		x: Double,
		y: Double,
		size: Double,
		isBreak: Boolean,
		isSimultaneous: Boolean,
		scaleFactor: Double,
	) {
		val scale = 1.19 * scaleFactor
		val outerRadius = size * scale
		val outerInner = size * 0.5 * scale
		val innerInner = size * 0.5
        val color = when {
            isBreak -> 0xCCFFC878.toInt()
            isSimultaneous -> 0xCCFFF596.toInt()
            context.config.pinkSlideStart -> 0xCCFFB4D2.toInt()
            else -> 0xCC64E6E6.toInt()
        }
        fun star(outer: Double, inner: Double): Path {
            val path = Path()
            for (index in 0 until 10) {
                val angle = index * PI / 5 - PI / 2
                val radius = if (index % 2 == 0) outer else inner
                val px = (x + cos(angle) * radius).toFloat()
                val py = (y + sin(angle) * radius).toFloat()
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            return path
        }
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addPath(star(outerRadius, outerInner))
            addPath(star(size, innerInner))
        }
        applyColor(fillPaint, color)
        context.canvas.drawPath(path, fillPaint)
    }

    /** Split-slide EX glow: two interleaved glowing star rings. */
    fun renderExSplitStarRing(
        x: Double,
        y: Double,
        size: Double,
        isBreak: Boolean,
        isSimultaneous: Boolean,
        scaleFactor: Double,
    ) {
        val scale = 1.19 * scaleFactor
        val outerRadius = size * scale
        val outerInner = size * 0.5 * scale
			val innerInner = size * 0.5
        val color = when {
            isBreak -> 0xCCFFC878.toInt()
            isSimultaneous -> 0xCCFFF596.toInt()
            context.config.pinkSlideStart -> 0xCCFFB4D2.toInt()
            else -> 0xCC64E6E6.toInt()
        }
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
        for (baseAngle in doubleArrayOf(-PI / 2, PI / 2)) {
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addPath(star(outerRadius, outerInner, baseAngle))
                addPath(star(size, innerInner, baseAngle))
            }
            applyColor(fillPaint, color)
            context.canvas.drawPath(path, fillPaint)
        }
    }

    /** Star spin rate: proportional to normalized path length, capped at 1.08 deg/ms. */
    fun calculateStarRotation(note: SlideNote, currentTimeMs: Double): Double {
        val durationMs = note.allDurationMs.firstOrNull() ?: note.durationMs
        val segments = note.allSlideSegments.firstOrNull() ?: note.slideSegments
        if (segments.isEmpty() || durationMs == 0.0) return 0.0
        var totalLengthPixels = 0.0
        for (segment in segments) totalLengthPixels += getSegmentLength(segment)
        val normalizedLength = totalLengthPixels * (ChartConstants.PANEL_RADIUS_UNITS / context.radius)
        val rotationSpeedDegPerMs = normalizedLength / PI / durationMs * 15 * 0.06
        val capped = min(rotationSpeedDegPerMs, 1.08)
        val rotationSpeedRadPerMs = -capped * (PI / 180)
        val approachTime = noteApproachTimeMs(note.hiSpeed)
        val visibilityStart = note.timingMs - approachTime
        if (currentTimeMs < visibilityStart) return 0.0
        val elapsedMs = currentTimeMs - visibilityStart
        return rotationSpeedRadPerMs * elapsedMs
    }
}
