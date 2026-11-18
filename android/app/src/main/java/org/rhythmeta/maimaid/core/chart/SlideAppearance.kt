package org.rhythmeta.maimaid.core.chart

import kotlin.math.roundToInt

/** Track appearance (alpha and whether it is still fading in) for a slide segment. */
data class SlideTrackAppearance(val alpha: Double, val isFading: Boolean)

/** Slide track fade timing, ported from `core/timing/slideAppearance.ts`. */
object SlideAppearance {
    private const val SLIDE_JUDGE_ADJUST_MS = 50.0
    private const val TRACK_FADE_RAMP_MS = 200.0
    private const val WIFI_TRACK_ALPHA = 120.0 / 255.0

    fun appearanceStartMs(noteTimeMs: Double, approachTimeMs: Double, slideDelay: Double): Double {
        val optionIndex = ((slideDelay + 1.0) * 10.0).roundToInt()
        return noteTimeMs - approachTimeMs + (approachTimeMs * optionIndex) / 21.0
    }

    private fun isWifiTrackHalfAlpha(noteTimeMs: Double, currentTimeMs: Double): Boolean =
        noteTimeMs - currentTimeMs + TRACK_FADE_RAMP_MS <= currentTimeMs

    fun trackAppearance(
        noteTimeMs: Double,
        approachTimeMs: Double,
        slideDelay: Double,
        currentTimeMs: Double,
        isWifi: Boolean,
    ): SlideTrackAppearance {
        val appearanceStart = appearanceStartMs(noteTimeMs, approachTimeMs, slideDelay)
        if (isWifi && currentTimeMs <= appearanceStart) return SlideTrackAppearance(0.0, false)

        if (currentTimeMs + SLIDE_JUDGE_ADJUST_MS >= noteTimeMs) {
            return SlideTrackAppearance(if (isWifi) WIFI_TRACK_ALPHA else 1.0, false)
        }

        val elapsed = currentTimeMs - appearanceStart
        if (elapsed <= 0.0) return SlideTrackAppearance(0.0, false)

        if (isWifi && elapsed > TRACK_FADE_RAMP_MS) {
            return SlideTrackAppearance(
                if (isWifiTrackHalfAlpha(noteTimeMs, currentTimeMs)) 0.5 else WIFI_TRACK_ALPHA,
                false,
            )
        }

        val fadeWindow = noteTimeMs - appearanceStart
        val alpha = if (isWifi || fadeWindow >= TRACK_FADE_RAMP_MS) {
            minOf(0.5, (0.5 * elapsed) / TRACK_FADE_RAMP_MS)
        } else {
            maxOf(0.0, minOf(0.5, 0.5 * (1.0 - (noteTimeMs - currentTimeMs) / elapsed)))
        }
        return SlideTrackAppearance(alpha, alpha > 0.0 && alpha < 0.5)
    }
}
