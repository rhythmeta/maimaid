package org.rhythmeta.maimaid.core.chart.audio

import kotlin.math.roundToLong

/**
 * Pure conversion between chart time, music position and mixer frames.
 *
 * Chart time `T` maps to music position `M = T - leadIn - musicOffset`: a positive music offset
 * delays the music relative to the chart. The inverse is `M + leadIn + musicOffset`; seek targets
 * and the playback clock must always use that pair, otherwise a non-zero offset is applied twice.
 *
 * SFX events are shifted by the reference engine's -50 ms answer offset plus the user offset and are
 * then mapped through the same music-position conversion, so music and SFX share one timeline and
 * the trigger frame is exact.
 */
object AudioTimeline {
    /** Reference engine's fixed early hit-sound offset. */
    const val BASE_SFX_OFFSET_MS: Double = -50.0

    fun musicPositionMs(chartTimeMs: Double, leadInMs: Double, musicOffsetMs: Int): Double =
        chartTimeMs - leadInMs - musicOffsetMs

    fun chartTimeMs(musicPositionMs: Double, leadInMs: Double, musicOffsetMs: Int): Double =
        musicPositionMs + leadInMs + musicOffsetMs

    fun frameForMusicPositionMs(musicPositionMs: Double, sampleRate: Int): Long =
        (musicPositionMs * sampleRate / 1000.0).roundToLong()

    fun musicPositionMsForFrame(frame: Long, sampleRate: Int): Double =
        frame * 1000.0 / sampleRate

    /**
     * Mixer frame at which an SFX event becomes audible. Events that would land before the music
     * start are clamped to frame 0 (they sound when playback starts, matching the SoundPool path).
     */
    fun frameForSfx(
        eventTimeMs: Double,
        leadInMs: Double,
        musicOffsetMs: Int,
        sfxOffsetMs: Double,
        sampleRate: Int,
    ): Long {
        val audibleChartMs = eventTimeMs + BASE_SFX_OFFSET_MS + sfxOffsetMs
        return frameForMusicPositionMs(
            musicPositionMs(audibleChartMs, leadInMs, musicOffsetMs),
            sampleRate,
        ).coerceAtLeast(0L)
    }

    fun chartTimeForFrame(
        frame: Long,
        leadInMs: Double,
        musicOffsetMs: Int,
        sampleRate: Int,
    ): Double = chartTimeMs(musicPositionMsForFrame(frame, sampleRate), leadInMs, musicOffsetMs)
}
