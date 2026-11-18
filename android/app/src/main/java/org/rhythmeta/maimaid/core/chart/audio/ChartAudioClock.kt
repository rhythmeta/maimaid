package org.rhythmeta.maimaid.core.chart.audio

import kotlin.math.abs

/**
 * Extrapolates the audible music position from `AudioTrack.getTimestamp()` samples.
 *
 * `getTimestamp` reports a frame position and the `nanoTime` at which that position is presented.
 * Between samples the clock runs on the monotonic clock at the current playback speed. Small
 * discrepancies are corrected by nudging the clock rate (at most ±3% of the playback speed, over
 * [convergenceSeconds]); a large discrepancy is treated as a discontinuity and snapped, which is what
 * a seek or a track flush looks like.
 */
class ChartAudioClock(
    private val sampleRate: Int,
    private val convergenceSeconds: Double = 2.0,
    private val maximumRelativeCorrection: Double = 0.03,
    private val snapThresholdMs: Double = 50.0,
) {
    private var anchorFrame = 0.0
    private var anchorNanos = 0L
    private var speed = 1.0
    private var correction = 0.0
    private var seeded = false

    var isPlaying: Boolean = false
        private set

    val playbackSpeed: Double get() = speed

    fun reset(frame: Double, nanoTime: Long, playing: Boolean) {
        anchorFrame = frame
        anchorNanos = nanoTime
        correction = 0.0
        isPlaying = playing
        seeded = true
    }

    fun setPlaying(playing: Boolean, nanoTime: Long) {
        if (isPlaying == playing) return
        anchorFrame = positionFrameAt(nanoTime)
        anchorNanos = nanoTime
        correction = 0.0
        isPlaying = playing
    }

    fun setSpeed(newSpeed: Float, nanoTime: Long) {
        val clamped = newSpeed.toDouble().coerceIn(0.1, 2.0)
        if (clamped == speed) return
        anchorFrame = positionFrameAt(nanoTime)
        anchorNanos = nanoTime
        correction = 0.0
        speed = clamped
    }

    fun positionFrameAt(nanoTime: Long): Double {
        if (!seeded || !isPlaying) return anchorFrame
        val elapsedSeconds = (nanoTime - anchorNanos) / 1_000_000_000.0
        return anchorFrame + elapsedSeconds * sampleRate * (speed + correction)
    }

    fun onTimestamp(timestampFrame: Long, nanoTime: Long) {
        if (!seeded) {
            reset(timestampFrame.toDouble(), nanoTime, isPlaying)
            return
        }
        if (!isPlaying) return
        val sampled = timestampFrame.toDouble()
        val projected = positionFrameAt(nanoTime)
        val errorFrames = sampled - projected
        val snapFrames = snapThresholdMs * sampleRate / 1000.0
        if (errorFrames > snapFrames) {
            // The audio is clearly ahead (e.g. after a track flush): snap forward.
            anchorFrame = sampled
            anchorNanos = nanoTime
            correction = 0.0
            return
        }
        // Never snap backwards: right after a resume the framework still reports a playhead behind
        // the projected clock, and jumping back is exactly the visible jitter this clock exists to
        // avoid. A real backwards jump is delivered as an explicit reset instead.
        val errorSeconds = errorFrames / sampleRate.toDouble()
        val limit = maximumRelativeCorrection * speed
        correction = (errorSeconds / convergenceSeconds).coerceIn(-limit, limit)
        // Keep the visual clock continuous: continue from the projected position at the sampled
        // instant while the corrected rate converges over the next timestamp interval.
        anchorFrame = projected
        anchorNanos = nanoTime
    }
}
