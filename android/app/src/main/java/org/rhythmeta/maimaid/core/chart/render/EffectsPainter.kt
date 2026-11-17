package org.rhythmeta.maimaid.core.chart.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.rhythmeta.maimaid.core.chart.ChartConstants
import org.rhythmeta.maimaid.core.chart.ChartNote
import org.rhythmeta.maimaid.core.chart.ChartPoint
import org.rhythmeta.maimaid.core.chart.HoldEndNote
import org.rhythmeta.maimaid.core.chart.HoldStartNote
import org.rhythmeta.maimaid.core.chart.PreparedChart
import org.rhythmeta.maimaid.core.chart.SlideNote
import org.rhythmeta.maimaid.core.chart.TapNote
import org.rhythmeta.maimaid.core.chart.TouchHoldStartNote
import org.rhythmeta.maimaid.core.chart.TouchNote

/**
 * Time-driven effects: tap hit stamps, hold press ripples, touch hit bursts and touch fireworks.
 * The reference builds blurred sprites offscreen; here the blur is approximated with stacked
 * strokes/gradients so the result stays visually equivalent while remaining allocation-light.
 */
class EffectsPainter(
    context: RenderContext,
    private val notePainter: NotePainter,
    private val touchPainter: TouchPainter,
) : BasePainter(context) {
    private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var fireworkScratch: Bitmap? = null
    private val effectStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // ------------------------------------------------------------------ tap hit effects

    /** Renders the hit stamp of every button note that entered the 450 ms effect window. */
    fun renderTapHitEffects(prepared: PreparedChart, currentTimeMs: Double) {
        val notes = prepared.hitEffectNotes
        if (notes.isEmpty()) return
        val windowStart = currentTimeMs - ChartConstants.NOTE_HIT_EFFECT_DURATION_MS
        var lo = 0
        var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].timingMs < windowStart) lo = mid + 1 else hi = mid
        }
        if (lo >= notes.size) return

        // A newer hit on the same button truncates the previous effect.
        val lastHitByPosition = HashMap<Int, Double>()
        for (index in lo until notes.size) {
            val note = notes[index]
            if (note.timingMs > currentTimeMs) break
            val position = buttonPositionOf(note) ?: continue
            val previous = lastHitByPosition[position]
            if (previous == null || note.timingMs > previous) {
                lastHitByPosition[position] = note.timingMs
            }
        }

        for (index in lo until notes.size) {
            val note = notes[index]
            if (note.timingMs > currentTimeMs) break
            val position = buttonPositionOf(note) ?: continue
            val latest = lastHitByPosition[position]
            if (latest != null && latest > note.timingMs) continue
            val timeDiff = currentTimeMs - note.timingMs
            if (timeDiff < 0 || timeDiff > ChartConstants.NOTE_HIT_EFFECT_DURATION_MS) continue
            val progress = timeDiff / ChartConstants.NOTE_HIT_EFFECT_DURATION_MS
            val angle = notePainter.getAngle(position)
            val type = if (
                (note is TapNote && (note.isBreak || note.isStar)) ||
                (note is HoldEndNote && note.isBreakHold) ||
                (note is SlideNote && note.isStartBreak)
            ) {
                "star"
            } else {
                "hexagon"
            }
            renderHitEffectAt(
                context.centerX + cos(angle) * context.radius,
                context.centerY + sin(angle) * context.radius,
                angle,
                ChartConstants.Colors.HIT_EFFECT_GOLD,
                progress,
                type,
            )
        }
    }

    private fun buttonPositionOf(note: ChartNote): Int? = when (note) {
        is TapNote -> note.position
        is HoldEndNote -> note.position
        is HoldStartNote -> note.position
        is SlideNote -> note.position
        else -> null
    }

    /** Draws one hit stamp cluster at a given progress. */
    fun renderHitEffectAt(
        x: Double,
        y: Double,
        angle: Double,
        color: Int,
        progress: Double,
        type: String,
    ) {
        val scale = 1 - 0.75 * (progress - 1) * (progress - 1)
        val alpha = 1 - 4 * (progress - 0.5) * (progress - 0.5)
        if (alpha <= 0 || scale <= 0) return
        val subAng = 1 - (progress - 1) * (progress - 1)
        val subRad = maxOf(0.0, min(1.0, 1 - (8.0 / 9.0) * progress * progress))
        val baseR = scaleByRadius(ChartConstants.NOTE_SIZE_RATIO) * 1.36 * 1.5
        if (baseR <= 0) return

        val r0 = baseR * scale
        val rSmall = r0 * 0.7
        val rBig = r0 * 0.8
        val offset = baseR * subRad * 0.7

        context.canvas.save()
        context.canvas.translate(x.toFloat(), y.toFloat())
        context.canvas.rotate(Math.toDegrees(angle).toFloat())

        fun blit(lx: Double, ly: Double, radius: Double) {
            drawStamp(lx, ly, radius, color, alpha.toFloat(), type)
        }

        blit(0.0, 0.0, r0)
        val sub1 = PI / 6
        val sub2 = -PI / 6
        val a1a = sub1 + PI * subAng
        blit(cos(a1a) * offset, sin(a1a) * offset * 0.7, rSmall)
        val a1b = sub1 + PI * (1 + subAng)
        blit(cos(a1b) * offset, sin(a1b) * offset, rSmall)
        val a2a = sub2 - PI * subAng
        blit(cos(a2a) * offset, sin(a2a) * offset, rBig)
        val a2b = sub2 + PI * (1 - subAng)
        blit(cos(a2b) * offset, sin(a2b) * offset, rBig)
        context.canvas.restore()
    }

    private fun drawStamp(
        x: Double,
        y: Double,
        radius: Double,
        color: Int,
        alpha: Float,
        type: String,
    ) {
        if (radius <= 0) return
        val path = if (type == "star") {
            starPath(x, y, radius)
        } else {
            hexagonPath(x, y, radius)
        }
        val core = scaleByRadius(ChartConstants.NOTE_STROKE_WIDTH_RATIO) * 2f
        val canvas = context.canvas
        effectStroke.strokeJoin = Paint.Join.ROUND
        effectStroke.strokeCap = Paint.Cap.ROUND
        // Soft halo: wide, low-alpha strokes that do not thicken the core stroke.
        effectStroke.strokeWidth = core + scaleByRadius(10.0 / 300.0)
        effectStroke.color = color
        effectStroke.alpha = (255 * alpha * 0.10f).toInt()
        canvas.drawPath(path, effectStroke)
        effectStroke.strokeWidth = core + scaleByRadius(5.0 / 300.0)
        effectStroke.alpha = (255 * alpha * 0.22f).toInt()
        canvas.drawPath(path, effectStroke)
        effectStroke.strokeWidth = core
        effectStroke.alpha = (255 * alpha).toInt()
        canvas.drawPath(path, effectStroke)
    }

    private fun hexagonPath(x: Double, y: Double, radius: Double): Path {
        val path = Path()
        for (i in 0 until 6) {
            val angle = i * PI / 3
            val px = (x + radius * cos(angle)).toFloat()
            val py = (y + radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    private fun starPath(x: Double, y: Double, radius: Double): Path {
        val path = Path()
        val inner = radius * 0.5
        var angle = (PI / 2) * 3 + PI
        for (i in 0 until 5) {
            path.moveTo(
                (x + cos(angle) * radius).toFloat(),
                (y + sin(angle) * radius).toFloat(),
            )
            angle += PI / 5
            path.lineTo(
                (x + cos(angle) * inner).toFloat(),
                (y + sin(angle) * inner).toFloat(),
            )
            angle += PI / 5
        }
        path.close()
        return path
    }

    // ------------------------------------------------------------------ hold ripples

    /** Expanding ring ripples while a button hold is pressed. */
    fun renderHoldPressEffects(
        holds: List<HoldStartNote>,
        holdEndMap: Map<String, HoldEndNote>,
        currentTimeMs: Double,
        range: IntRange,
    ) {
        for (index in range) {
            if (index !in holds.indices) continue
            val hold = holds[index]
            val end = holdEndMap["${hold.position}:${hold.timing}"] ?: continue
            if (currentTimeMs < hold.timingMs || currentTimeMs >= end.timingMs) continue
            val origin = notePainter.getPositionOnRing(hold.position)
            renderPressRipple(origin, hold.timingMs, end.timingMs, currentTimeMs)
        }
    }

    /** Ripple rings at an arbitrary sensor/button origin (shared by touch holds). */
    fun renderPressRipple(
        origin: ChartPoint,
        startMs: Double,
        endMs: Double,
        currentTimeMs: Double,
    ) {
        if (currentTimeMs < startMs || currentTimeMs >= endMs) return
        if (context.radius <= 0f) return
        val canvas = context.canvas
        canvas.save()
        effectPaint.blendMode = BlendMode.SCREEN
        val elapsedSec = (currentTimeMs - startMs) / 1000.0
        val firstCycle = kotlin.math.floor((elapsedSec - RIPPLE_LIFETIME) / RIPPLE_CYCLE).toInt()
        val lastCycle = kotlin.math.floor(elapsedSec / RIPPLE_CYCLE).toInt()
        val radiusPx = context.radius.toDouble()

        data class Wave(val peakRadius: Double, val alpha: Double)

        val waves = mutableListOf<Wave>()
        for (cycle in firstCycle..lastCycle) {
            for (emitOffset in RIPPLE_EMIT_OFFSETS) {
                val age = elapsedSec - (cycle * RIPPLE_CYCLE + emitOffset)
                if (age < 0 || age >= RIPPLE_LIFETIME) continue
                val peakRadius =
                    (RIPPLE_BIRTH_RADIUS_RATIO + RIPPLE_GROWTH_RATIO_PER_SEC * age) * radiusPx
                val alpha = min(1.0, (RIPPLE_LIFETIME - age) / (RIPPLE_LIFETIME - RIPPLE_ATTACK))
                if (peakRadius < 0.75 || alpha <= 0.02) continue
                waves += Wave(peakRadius, alpha)
            }
        }
        waves.sortByDescending { it.peakRadius }
        for (wave in waves) {
            drawRippleRing(origin, wave.peakRadius, wave.alpha)
        }
        effectPaint.blendMode = null
        canvas.restore()
    }

    private fun drawRippleRing(origin: ChartPoint, peakRadius: Double, alpha: Double) {
        val inner = peakRadius * RIPPLE_INNER_SCALE
        val outer = peakRadius * RIPPLE_OUTER_SCALE
        if (outer - inner < 0.5 || alpha <= 0.02) return
        val bandStart = (inner / outer).toFloat()
        val colors = IntArray(RIPPLE_RING_PROFILE.size + 2)
        val positions = FloatArray(RIPPLE_RING_PROFILE.size + 2)
        colors[0] = 0
        positions[0] = 0f
        colors[1] = 0
        positions[1] = bandStart
        val brightness = alpha / RIPPLE_PEAK_ALPHA
        RIPPLE_RING_PROFILE.forEachIndexed { index, stop ->
            val profileValue = stop[1]
            val whiten = RIPPLE_CORE_WHITEN * profileValue * brightness * brightness
            val red = RIPPLE_EDGE_R + (1 - RIPPLE_EDGE_R) * whiten
            val green = RIPPLE_EDGE_G + (1 - RIPPLE_EDGE_G) * whiten
            val blue = RIPPLE_EDGE_B + (1 - RIPPLE_EDGE_B) * whiten
            colors[index + 2] = android.graphics.Color.argb(
                (255 * profileValue * alpha).toInt().coerceIn(0, 255),
                (red * 255).toInt(),
                (green * 255).toInt(),
                (blue * 255).toInt(),
            )
            positions[index + 2] = bandStart + (1 - bandStart) * stop[0].toFloat()
        }
        val shader = RadialGradient(
            origin.x.toFloat(),
            origin.y.toFloat(),
            outer.toFloat(),
            colors,
            positions,
            Shader.TileMode.CLAMP,
        )
        effectPaint.shader = shader
        effectPaint.alpha = 255
        context.canvas.drawCircle(origin.x.toFloat(), origin.y.toFloat(), outer.toFloat(), effectPaint)
        effectPaint.shader = null
    }

    // ------------------------------------------------------------------ touch hold release

    fun renderTouchHoldEffects(
        touchHolds: List<TouchHoldStartNote>,
        currentTimeMs: Double,
        range: IntRange,
    ) {
        for (index in range) {
            val note = touchHolds.getOrNull(index) ?: continue
            val endMs = note.timingMs + note.durationMs
            if (currentTimeMs >= note.timingMs && currentTimeMs < endMs) {
                val origin = touchPainter.touchPosition(note.position)
                renderPressRipple(origin, note.timingMs, endMs, currentTimeMs)
            }
            if (currentTimeMs >= endMs &&
                currentTimeMs < endMs + ChartConstants.NOTE_HIT_EFFECT_DURATION_MS
            ) {
                val origin = touchPainter.touchPosition(note.position)
                val angle = if (note.position == "C") {
                    ChartConstants.TOUCH_HOLD_CENTRE_BURST_ANGLE
                } else {
                    kotlin.math.atan2(
                        context.centerY - origin.y,
                        context.centerX - origin.x,
                    )
                }
                renderHitEffectAt(
                    origin.x,
                    origin.y,
                    angle,
                    ChartConstants.Colors.HIT_EFFECT_GOLD,
                    (currentTimeMs - endMs) / ChartConstants.NOTE_HIT_EFFECT_DURATION_MS,
                    "hexagon",
                )
            }
        }
    }

    // ------------------------------------------------------------------ touch hit burst

    /** Center ring plus star sparks for a touch hit; additive blending. */
    fun renderTouchHitEffects(touches: List<ChartNote>, currentTimeMs: Double) {
        if (touches.isEmpty()) return
        val unitPx = context.radius / ChartConstants.PANEL_RADIUS_UNITS.toFloat()
        if (unitPx <= 0f) return
        val lifeMs = 500.0
        val windowStart = currentTimeMs - lifeMs
        var lo = 0
        var hi = touches.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (touches[mid].timingMs < windowStart) lo = mid + 1 else hi = mid
        }
        if (lo >= touches.size) return

        val lastByPos = HashMap<String, Double>()
        for (index in lo until touches.size) {
            val note = touches[index]
            if (note.timingMs > currentTimeMs) break
            if (note !is TouchNote) continue
            val previous = lastByPos[note.position]
            if (previous == null || note.timingMs > previous) lastByPos[note.position] = note.timingMs
        }

        val canvas = context.canvas
        canvas.save()
        effectPaint.blendMode = BlendMode.PLUS
        for (index in lo until touches.size) {
            val note = touches[index]
            if (note.timingMs > currentTimeMs) break
            if (note !is TouchNote) continue
            val latest = lastByPos[note.position]
            if (latest != null && latest > note.timingMs) continue
            val age = (currentTimeMs - note.timingMs) / 1000.0
            if (age < 0 || age >= 0.5) continue
            val origin = touchPainter.touchPosition(note.position)
            var seed = Math.round(note.timingMs * 1000).toInt()
            note.position.forEachIndexed { charIndex, char ->
                seed = seed xor (char.code * (charIndex + 1) * 73856093)
            }
            drawTouchCenterRing(origin, age, unitPx)
            drawStarBurst(origin, age, unitPx, seed, SMALL_COUNT, SMALL_SHAPE_R, SMALL_SIZE, 0.0, 0.0)
            drawStarBurst(
                origin,
                age,
                unitPx,
                seed xor 0x85ebca77.toInt(),
                ROUND_COUNT,
                ROUND_SHAPE_R,
                ROUND_SIZE,
                ROUND_START_SPEED,
                ROUND_CLAMP_SPEED,
            )
        }
        effectPaint.blendMode = null
        canvas.restore()
        effectStroke.blendMode = null
    }

    private fun drawTouchCenterRing(origin: ChartPoint, age: Double, unitPx: Float) {
        val life = age / 0.5
        val sizeMul = maxOf(0.0, evalHermite(RING_SIZE_KEYS, life))
        var diameter = RING_START_SIZE * sizeMul
        if (diameter > RING_MAX_SIZE) diameter = RING_MAX_SIZE
        val radius = ((diameter * unitPx) / 2f).toFloat()
        if (radius < 0.5f) return
        val alpha = evalKeys(RING_ALPHA_KEYS, life)
        if (alpha <= 0.01) return

        val colors = IntArray(TOUCH_RING_ALPHA_PROFILE.size + 1)
        val positions = FloatArray(TOUCH_RING_ALPHA_PROFILE.size + 1)
        val last = TOUCH_RING_ALPHA_PROFILE.size - 1
        for (index in 0..last) {
            val stopAlpha = (TOUCH_RING_ALPHA_PROFILE[index] * alpha).coerceIn(0.0, 1.0)
            colors[index] = (TOUCH_HIT_COLOR and 0x00FFFFFF) or ((stopAlpha * 255).toInt() shl 24)
            positions[index] = (index.toFloat() / last) * 0.96f
        }
        colors[last + 1] = TOUCH_HIT_COLOR and 0x00FFFFFF
        positions[last + 1] = 1f
        val shader = RadialGradient(
            origin.x.toFloat(),
            origin.y.toFloat(),
            radius,
            colors,
            positions,
            Shader.TileMode.CLAMP,
        )
        effectPaint.shader = shader
        effectPaint.alpha = 255
        context.canvas.drawCircle(origin.x.toFloat(), origin.y.toFloat(), radius, effectPaint)
        effectPaint.shader = null
    }

    private fun drawStarBurst(
        origin: ChartPoint,
        age: Double,
        unitPx: Float,
        seedBase: Int,
        count: Int,
        shapeR: Double,
        startSize: Double,
        startSpeed: Double,
        clampSpeed: Double,
    ) {
        val life = age / 0.5
        val sizeMul = maxOf(0.0, evalHermite(STAR_SIZE_KEYS, life))
        if (sizeMul <= 0.02) return
        val alpha = min(1.0, sizeMul * 1.05)
        val travel =
            if (startSpeed > 0 && clampSpeed > 0) clampedTravel(age, startSpeed, clampSpeed) else 0.0

        for (index in 0 until count) {
            val h1 = hash01(seedBase + index * 4 + 1)
            val h2 = hash01(seedBase + index * 4 + 2)
            val h3 = hash01(seedBase + index * 4 + 3)
            val h4 = hash01(seedBase + index * 4 + 4)
            val angle = h1 * PI * 2
            val cosA = cos(angle)
            val sinA = sin(angle)
            val px = origin.x + (cosA * shapeR + cosA * travel) * unitPx
            val py = origin.y + (sinA * shapeR + sinA * travel) * unitPx
            val size = startSize * sizeMul * unitPx * (0.9 + 0.2 * h2)
            val outerR = size * 0.55
            if (outerR < 0.5) continue
            val frame = min(3, kotlin.math.floor(h4 * 4).toInt())
            drawSparkle(px, py, outerR, h3 * PI * 2, alpha.toFloat(), frame)
        }
    }

    private fun drawSparkle(
        x: Double,
        y: Double,
        outerR: Double,
        rotation: Double,
        alpha: Float,
        frame: Int,
    ) {
        val canvas = context.canvas
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        canvas.rotate(Math.toDegrees(rotation).toFloat())
        when (frame) {
            0, 1, 2 -> {
                val tipR = when (frame) {
                    0 -> outerR * 1.08
                    1 -> outerR
                    else -> outerR * 0.94
                }
                val waistR = when (frame) {
                    0 -> outerR * 0.23
                    1 -> outerR * 0.23
                    else -> outerR * 0.19
                }
                val path = sparklePath(tipR, waistR)
                val shader = RadialGradient(
                    0f,
                    0f,
                    tipR.toFloat(),
                    intArrayOf(
                        (TOUCH_HIT_COLOR and 0x00FFFFFF) or ((alpha * 0.95f * 255).toInt() shl 24),
                        (TOUCH_HIT_COLOR and 0x00FFFFFF) or ((alpha * 0.72f * 255).toInt() shl 24),
                        TOUCH_HIT_COLOR and 0x00FFFFFF,
                    ),
                    floatArrayOf(0f, 0.82f, 1f),
                    Shader.TileMode.CLAMP,
                )
                effectPaint.shader = shader
                effectPaint.alpha = 255
                canvas.drawPath(path, effectPaint)
                effectPaint.shader = null
            }
            else -> {
                effectStroke.strokeJoin = Paint.Join.ROUND
                effectStroke.strokeCap = Paint.Cap.ROUND
                effectStroke.strokeWidth = maxOf(1f, (outerR * 0.13).toFloat())
                effectStroke.color = HIT_GOLD
                effectStroke.alpha = (alpha * 0.22f * 255).toInt()
                canvas.drawPath(sparklePath(outerR * 1.08, outerR * 0.2 * 1.08), effectStroke)
                effectStroke.strokeWidth = maxOf(0.8f, (outerR * 0.055).toFloat())
                effectStroke.color = 0xFFFFFEF0.toInt()
                effectStroke.alpha = (alpha * 0.88f * 255).toInt()
                canvas.drawPath(sparklePath(outerR, outerR * 0.2), effectStroke)
            }
        }
        canvas.restore()
    }

    private fun sparklePath(tipR: Double, waistR: Double): Path {
        val path = Path()
        for (i in 0 until 4) {
            val aTip = i * PI / 2 - PI / 2
            val aWaist = aTip + PI / 4
            val aNext = aTip + PI / 2
            val tx = (cos(aTip) * tipR).toFloat()
            val ty = (sin(aTip) * tipR).toFloat()
            val wx = (cos(aWaist) * waistR).toFloat()
            val wy = (sin(aWaist) * waistR).toFloat()
            val nx = (cos(aNext) * tipR).toFloat()
            val ny = (sin(aNext) * tipR).toFloat()
            if (i == 0) path.moveTo(tx, ty) else path.lineTo(tx, ty)
            path.quadTo(wx, wy, nx, ny)
        }
        path.close()
        return path
    }

    // ------------------------------------------------------------------ fireworks

    fun renderTouchFireworks(touches: List<ChartNote>, currentTimeMs: Double) {
        if (touches.isEmpty()) return
        var lo = 0
        var hi = touches.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (fireworkTrigger(touches[mid]) <= currentTimeMs) lo = mid + 1 else hi = mid
        }
        val latest = if (lo > 0) touches[lo - 1] else return
        val ageMs = currentTimeMs - fireworkTrigger(latest)
        if (ageMs >= FIREWORK_DURATION_MS) return
        val tSec = ageMs / 1000.0
        if (tSec >= FIREWORK_END_SEC) return

        val scale = when {
            tSec < 0.1 -> 0.0
            tSec < 0.5 -> {
                val u = (tSec - 0.1) / 0.4
                (1 - Math.pow(1 - u, 3.0)) * FIREWORK_SCALE_PEAK
            }
            else -> FIREWORK_SCALE_PEAK
        }
        if (scale <= 0) return

        val position = when (latest) {
            is TouchNote -> touchPainter.touchPosition(latest.position)
            is TouchHoldStartNote -> touchPainter.touchPosition(latest.position)
            else -> return
        }
        val rotation = (ageMs / FIREWORK_DURATION_MS) * (72 * PI / 180)
        val canvas = context.canvas

        if (tSec > FIREWORK_HOLE_START_SEC) {
            val outerR = (context.radius / 4.5 * scale).toDouble()
            val linearU = min(
                1.0,
                (tSec - FIREWORK_HOLE_START_SEC) / (FIREWORK_END_SEC - FIREWORK_HOLE_START_SEC),
            )
            val u = linearU * linearU * (3 - 2 * linearU)
            val solidR = outerR * (0.05 + u * 0.95)
            val totalR = solidR + outerR * 0.5
            val maskAlpha = min(1.0, linearU / 0.2)
            if (totalR <= 0 || maskAlpha <= 0) return

            // Composite the burst offscreen, then punch a growing feathered hole through it with
            // DST_OUT. Applying the mask as an opacity (as a ring clip would) made the burst
            // vanish at 0.6 s and fade back in, which read as a replay.
            val fireworkHalf = (context.radius / 4.5 * FIREWORK_SCALE_PEAK * 1.05).toInt() + 2
            val sizePx = maxOf(2, fireworkHalf * 2)
            val scratch = ensureFireworkScratch(sizePx)
            val scratchCanvas = Canvas(scratch)
            scratchCanvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            val mainCanvas = context.canvas
            context.canvas = scratchCanvas
            try {
                drawFirework(
                    ChartPoint(sizePx / 2.0, sizePx / 2.0),
                    scale,
                    rotation,
                    tSec,
                    1f,
                )
            } finally {
                context.canvas = mainCanvas
            }

            val centerX = sizePx / 2f
            val centerY = sizePx / 2f
            val maskShader = RadialGradient(
                centerX,
                centerY,
                totalR.toFloat(),
                intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
                floatArrayOf(0f, (solidR / totalR).toFloat(), 1f),
                Shader.TileMode.CLAMP,
            )
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = maskShader
                xfermode = android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_OUT,
                )
                alpha = (255 * maskAlpha).toInt()
            }
            scratchCanvas.drawCircle(centerX, centerY, totalR.toFloat(), maskPaint)
            mainCanvas.drawBitmap(
                scratch,
                (position.x - fireworkHalf).toFloat(),
                (position.y - fireworkHalf).toFloat(),
                blitPaint,
            )
        } else {
            drawFirework(position, scale, rotation, tSec, 1f)
        }
    }

    private fun ensureFireworkScratch(sizePx: Int): Bitmap {
        val existing = fireworkScratch
        if (existing != null && existing.width == sizePx && existing.height == sizePx) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also {
            fireworkScratch = it
        }
    }

    private fun fireworkTrigger(note: ChartNote): Double = when (note) {
        is TouchHoldStartNote -> note.timingMs + note.durationMs
        else -> note.timingMs
    }

    private fun drawFirework(
        position: ChartPoint,
        scale: Double,
        rotation: Double,
        tSec: Double,
        alpha: Float,
    ) {
        val outerR = context.radius / 4.5 * scale
        if (outerR <= 0) return
        val canvas = context.canvas
        canvas.save()
        canvas.translate(position.x.toFloat(), position.y.toFloat())
        canvas.rotate(Math.toDegrees(rotation).toFloat())

        val count = FIREWORK_PETAL_COLORS.size
        val halfWidth = PI / count * 0.45
        for (index in 0 until count) {
            val angle = index.toDouble() / count * PI * 2
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(
                    (cos(angle - halfWidth) * outerR).toFloat(),
                    (sin(angle - halfWidth) * outerR).toFloat(),
                )
                lineTo(
                    (cos(angle + halfWidth) * outerR).toFloat(),
                    (sin(angle + halfWidth) * outerR).toFloat(),
                )
                close()
            }
            val start = FIREWORK_PETAL_COLORS[index]
            val baseAlpha = (0x96 * alpha).toInt().coerceIn(0, 255)
            val shader = RadialGradient(
                0f,
                0f,
                outerR.toFloat(),
                intArrayOf(
                    (start and 0x00FFFFFF) or (baseAlpha shl 24),
                    (start and 0x00FFFFFF) or (baseAlpha shl 24),
                    start and 0x00FFFFFF,
                ),
                floatArrayOf(0f, 0.9f, 1f),
                Shader.TileMode.CLAMP,
            )
            effectPaint.shader = shader
            effectPaint.alpha = 255
            canvas.drawPath(path, effectPaint)
        }
        effectPaint.shader = null
        canvas.restore()

        drawFireworkBalls(position, tSec - 0.1, alpha)
    }

    private fun drawFireworkBalls(position: ChartPoint, growT: Double, alpha: Float) {
        if (growT < 0) return
        val baseRadius = context.radius / 4.5
        val canvas = context.canvas
        canvas.save()
        effectPaint.blendMode = BlendMode.PLUS

        val bigGrow = 0.06
        val bigRise = 0.04
        val bigRMax = baseRadius * 1.3
        val bigDecayEnd = FIREWORK_DURATION_MS / 1000.0 - 0.1
        val bigPeakAlpha = 0.7
        var bigAlpha = 0.0
        var bigR = 0.0
        if (growT < bigGrow) {
            bigR = growT / bigGrow * bigRMax
            bigAlpha = bigPeakAlpha * min(1.0, growT / bigRise)
        } else {
            val d = (growT - bigGrow) / (bigDecayEnd - bigGrow)
            bigR = bigRMax * (1 - d)
            bigAlpha = bigPeakAlpha * (1 - d)
        }
        if (bigAlpha > 0 && bigR > 0) {
            val shader = RadialGradient(
                position.x.toFloat(),
                position.y.toFloat(),
                bigR.toFloat(),
                intArrayOf(0xFFFFEBC8.toInt(), 0x00FFB464),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            effectPaint.shader = shader
            effectPaint.alpha = (255 * bigAlpha * alpha).toInt()
            canvas.drawCircle(position.x.toFloat(), position.y.toFloat(), bigR.toFloat(), effectPaint)
        }

        val smallGrow = 0.04
        val smallRise = 0.02
        val smallRMax = baseRadius * 0.55
        val smallDecayEnd = 0.55
        val smallPeakAlpha = 0.6
        var smallAlpha = 0.0
        var smallR = 0.0
        if (growT < smallGrow) {
            smallR = growT / smallGrow * smallRMax
            smallAlpha = smallPeakAlpha * min(1.0, growT / smallRise)
        } else if (growT < smallDecayEnd) {
            val d = (growT - smallGrow) / (smallDecayEnd - smallGrow)
            smallR = smallRMax * (1 - d)
            smallAlpha = smallPeakAlpha * (1 - d)
        }
        if (smallAlpha > 0 && smallR > 0) {
            val shader = RadialGradient(
                position.x.toFloat(),
                position.y.toFloat(),
                smallR.toFloat(),
                intArrayOf(0xFFFFF5DC.toInt(), 0x00FFDC9F),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            effectPaint.shader = shader
            effectPaint.alpha = (255 * smallAlpha * alpha).toInt()
            canvas.drawCircle(
                position.x.toFloat(),
                position.y.toFloat(),
                smallR.toFloat(),
                effectPaint,
            )
        }
        effectPaint.shader = null
        effectPaint.blendMode = null
        canvas.restore()
    }

    // ------------------------------------------------------------------ interpolation

    private class HermiteKey(val t: Double, val v: Double, val slope: Double)

    private class Keyframe(val t: Double, val v: Double)

    private fun evalHermite(keys: List<HermiteKey>, t: Double): Double {
        if (t <= keys.first().t) return keys.first().v
        if (t >= keys.last().t) return keys.last().v
        for (index in 0 until keys.size - 1) {
            val a = keys[index]
            val b = keys[index + 1]
            if (t < a.t || t > b.t) continue
            val dt = (b.t - a.t).takeIf { it != 0.0 } ?: 1.0
            val u = (t - a.t) / dt
            val u2 = u * u
            val u3 = u2 * u
            return (2 * u3 - 3 * u2 + 1) * a.v +
                (u3 - 2 * u2 + u) * a.slope * dt +
                (-2 * u3 + 3 * u2) * b.v +
                (u3 - u2) * b.slope * dt
        }
        return keys.last().v
    }

    private fun evalKeys(keys: List<Keyframe>, t: Double): Double {
        if (t <= keys.first().t) return keys.first().v
        if (t >= keys.last().t) return keys.last().v
        for (index in 0 until keys.size - 1) {
            val a = keys[index]
            val b = keys[index + 1]
            if (t >= a.t && t <= b.t) {
                val u = (t - a.t) / ((b.t - a.t).takeIf { it != 0.0 } ?: 1.0)
                return a.v + (b.v - a.v) * u
            }
        }
        return keys.last().v
    }

    private fun clampedTravel(age: Double, startSpeed: Double, clampSpeed: Double): Double {
        if (age <= 0) return 0.0
        val steps = maxOf(6, kotlin.math.ceil(age * 60).toInt())
        val dt = age / steps
        var speed = startSpeed
        var distance = 0.0
        for (index in 0 until steps) {
            val life = min(1.0, (index + 0.5) * dt / 0.5)
            val maxSpeed = maxOf(0.0, evalHermite(CLAMP_MAG_KEYS, life) * clampSpeed)
            if (speed > maxSpeed) speed = maxSpeed
            distance += speed * dt
        }
        return distance
    }

    private fun hash01(seed: Int): Double {
        var x = seed * 1664525 + 1013904223
        x = (x xor (x ushr 16))
        return (x.toLong() and 0xffffffffL) / 4294967296.0
    }

    companion object {
        private const val HIT_GOLD = 0xFFF5EA72.toInt()
        private const val TOUCH_HIT_COLOR = 0xFFFFFF00.toInt()

        private const val RIPPLE_CYCLE = (16_000.0 / 60.0) / 1000.0
        private val RIPPLE_EMIT_OFFSETS = doubleArrayOf(0.0, 4.0 / 60.0, 8.0 / 60.0)
        private const val RIPPLE_LIFETIME = 11.0 / 60.0
        private const val RIPPLE_ATTACK = 1.0 / 60.0
        private const val RIPPLE_PEAK_ALPHA = 1.0
        private const val RIPPLE_BIRTH_RADIUS_RATIO = 0.1355
        private const val RIPPLE_GROWTH_RATIO_PER_SEC = 0.735
        private const val RIPPLE_INNER_SCALE = 0.8
        private const val RIPPLE_OUTER_SCALE = 1.2
        private const val RIPPLE_CORE_WHITEN = 0.58
        private const val RIPPLE_EDGE_R = 1.0
        private const val RIPPLE_EDGE_G = 0.889
        private const val RIPPLE_EDGE_B = 0.213

        private val RIPPLE_RING_PROFILE = arrayOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(0.063, 0.01),
            doubleArrayOf(0.125, 0.08),
            doubleArrayOf(0.188, 0.17),
            doubleArrayOf(0.25, 0.33),
            doubleArrayOf(0.313, 0.52),
            doubleArrayOf(0.375, 0.68),
            doubleArrayOf(0.438, 0.85),
            doubleArrayOf(0.5, 1.0),
            doubleArrayOf(0.563, 0.88),
            doubleArrayOf(0.625, 0.71),
            doubleArrayOf(0.688, 0.56),
            doubleArrayOf(0.75, 0.37),
            doubleArrayOf(0.813, 0.2),
            doubleArrayOf(0.875, 0.09),
            doubleArrayOf(0.938, 0.03),
            doubleArrayOf(1.0, 0.01),
        )

        private const val ROOT_SCALE = 70.0
        private const val RING_START_SIZE = 2.5 * ROOT_SCALE
        private const val RING_MAX_SIZE = 540.0
        private const val SMALL_COUNT = 8
        private const val SMALL_SHAPE_R = 0.5 * ROOT_SCALE
        private const val SMALL_SIZE = 0.3 * ROOT_SCALE
        private const val ROUND_COUNT = 8
        private const val ROUND_SHAPE_R = 0.6 * ROOT_SCALE
        private const val ROUND_SIZE = 0.6 * ROOT_SCALE
        private const val ROUND_START_SPEED = 10 * ROOT_SCALE
        private const val ROUND_CLAMP_SPEED = 2.0 * ROOT_SCALE

        private val RING_SIZE_KEYS = listOf(
            HermiteKey(0.0, 0.0, 10.02783),
            HermiteKey(0.0734887, 0.482223, 2.93804),
            HermiteKey(0.3209003, 0.834577, 0.592962),
            HermiteKey(1.0, 1.0, 0.180534),
        )

        private val RING_ALPHA_KEYS = listOf(
            Keyframe(0.0, 1.0),
            Keyframe(5484 / 65535.0, 1.0),
            Keyframe(18591 / 65535.0, 0.141176),
            Keyframe(31029 / 65535.0, 0.031373),
            Keyframe(45607 / 65535.0, 0.0),
            Keyframe(1.0, 0.0),
        )

        private val STAR_SIZE_KEYS = listOf(
            HermiteKey(0.0, 0.755245, 2.38315),
            HermiteKey(0.127286, 1.0, 0.061337),
            HermiteKey(0.272332, 0.892134, -0.927502),
            HermiteKey(0.540943, 0.811183, -0.114026),
            HermiteKey(1.0, 0.0, 0.036313),
        )

        private val CLAMP_MAG_KEYS = listOf(
            HermiteKey(0.0, 1.0, -2.726415),
            HermiteKey(1.0, 0.0, 0.0),
        )

        private val TOUCH_RING_ALPHA_PROFILE = doubleArrayOf(
            0.666, 0.667, 0.671, 0.679, 0.685, 0.693, 0.7, 0.709, 0.718, 0.727, 0.736, 0.744,
            0.753, 0.763, 0.772, 0.781, 0.791, 0.8, 0.809, 0.818, 0.826, 0.835, 0.843, 0.851,
            0.858, 0.866, 0.872, 0.879, 0.887, 0.896, 0.905, 0.913, 0.922, 0.93, 0.938, 0.946,
            0.953, 0.959, 0.964, 0.969, 0.974, 0.977, 0.981, 0.984, 0.986, 0.987, 0.989, 0.993,
            0.998, 1.0, 0.896, 0.584, 0.374, 0.248, 0.155, 0.087, 0.038, 0.008, 0.002, 0.0,
            0.0, 0.0,
        )

        private const val FIREWORK_DURATION_MS = 1333.0
        private const val FIREWORK_SCALE_PEAK = 5.0
        private const val FIREWORK_HOLE_START_SEC = 0.6
        private const val FIREWORK_END_SEC = 1.1

        private val FIREWORK_PETAL_COLORS = intArrayOf(
            0xFFFFB3BA.toInt(), 0xFFFFD0A8.toInt(), 0xFFFFFAB8.toInt(), 0xFFD8F0A8.toInt(),
            0xFFB8E0B0.toInt(), 0xFFA8E0CC.toInt(), 0xFFA8C8E8.toInt(), 0xFFB8B0E8.toInt(),
            0xFFD0B0E8.toInt(), 0xFFF0B0D8.toInt(), 0xFFFFC9A8.toInt(), 0xFFFFEFA8.toInt(),
            0xFFC8E8A8.toInt(), 0xFFA8E0BC.toInt(), 0xFFA8CCE8.toInt(),
        )
    }
}
