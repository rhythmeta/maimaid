package org.rhythmeta.maimaid.core.chart.render

import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * Port of the reference engine's BPM-change flash.
 *
 * While the chart plays, a BPM rise flashes the readout red and a drop flashes it green, then both
 * fade back to the readout's own colour within half a second, so a tempo change is noticeable
 * without a persistent banner. The fade target is supplied by the caller because the HUD sits on
 * the dark stage while the rhythm lane follows the theme (dark text in light mode).
 */
internal class BpmChangeFlash(
    private val fadeDurationMs: Long = 500L,
    private val instantMs: Long = 50L,
) {
    private var lastBpm = Double.NaN
    private var changedAtMs = 0L
    private var direction = 0

    /** Forgets the previous tempo, e.g. after switching to another chart. */
    fun reset() {
        lastBpm = Double.NaN
        changedAtMs = 0L
        direction = 0
    }

    /**
     * Returns the colour to draw [currentBpm] with, blending from the up/down flash into
     * [baseColor], or null when the readout should keep [baseColor] unchanged.
     */
    fun colorFor(
        currentBpm: Double,
        isPlaying: Boolean,
        baseColor: Int,
        nowMs: Long = SystemClock.uptimeMillis(),
    ): Int? {
        if (!isPlaying) {
            direction = 0
            lastBpm = currentBpm
            return null
        }
        if (lastBpm.isNaN()) {
            lastBpm = currentBpm
            return null
        }
        if (currentBpm != lastBpm) {
            changedAtMs = nowMs
            direction = if (currentBpm > lastBpm) 1 else -1
            lastBpm = currentBpm
        }
        if (direction == 0) return null
        val elapsed = nowMs - changedAtMs
        if (elapsed >= fadeDurationMs) return null
        val fade = ((elapsed - instantMs).toDouble() / (fadeDurationMs - instantMs)).coerceIn(0.0, 1.0)
        // Rise: red -> base. Drop: green -> base.
        val flashRed = if (direction > 0) 255 else 0
        val flashGreen = if (direction > 0) 0 else 255
        val red = blend(flashRed, (baseColor shr 16) and 0xFF, fade)
        val green = blend(flashGreen, (baseColor shr 8) and 0xFF, fade)
        val blue = blend(0, baseColor and 0xFF, fade)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun blend(from: Int, to: Int, amount: Double): Int =
        (from + (to - from) * amount).roundToInt().coerceIn(0, 255)
}
