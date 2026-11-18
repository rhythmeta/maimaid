package org.rhythmeta.maimaid.core.chart.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimelineTest {
    @Test
    fun musicPositionAndChartTimeAreInverse() {
        for (offset in listOf(-500, -100, -1, 0, 1, 100, 500)) {
            val chart = 12_345.0
            val music = AudioTimeline.musicPositionMs(chart, 1_000.0, offset)
            assertEquals(
                chart,
                AudioTimeline.chartTimeMs(music, 1_000.0, offset),
                1e-9,
            )
        }
    }

    @Test
    fun positiveMusicOffsetDelaysTheMusic() {
        // Chart time 5000 with a +100 offset maps the music 100 ms earlier, i.e. the music lags.
        val chart = 5_000.0
        assertEquals(3_900.0, AudioTimeline.musicPositionMs(chart, 1_000.0, 100), 1e-9)
        // Reading back the clock must return the chart time, not chart - 2 * offset.
        assertEquals(
            chart,
            AudioTimeline.chartTimeMs(3_900.0, 1_000.0, 100),
            1e-9,
        )
    }

    @Test
    fun sfxFrameUsesBaseAndUserOffset() {
        // Event at 1550 ms, lead-in 1000 ms: audible at 1500 ms -> 500 ms of music -> 24000 frames.
        assertEquals(
            24_000L,
            AudioTimeline.frameForSfx(1_550.0, 1_000.0, 0, 0.0, 48_000),
        )
        // +100 ms user offset delays the SFX.
        assertEquals(
            28_800L,
            AudioTimeline.frameForSfx(1_550.0, 1_000.0, 0, 100.0, 48_000),
        )
        // +100 ms music offset makes the same chart time map 100 ms earlier in the music.
        assertEquals(
            19_200L,
            AudioTimeline.frameForSfx(1_550.0, 1_000.0, 100, 0.0, 48_000),
        )
    }

    @Test
    fun eventsBeforeTheMusicStartClampToFrameZero() {
        assertEquals(
            0L,
            AudioTimeline.frameForSfx(1_000.0, 1_000.0, 0, 0.0, 48_000),
        )
    }

    @Test
    fun chartTimeForFrameRoundTrips() {
        val frame = 240_000L
        val chart = AudioTimeline.chartTimeForFrame(frame, 1_000.0, 100, 48_000)
        val back = AudioTimeline.frameForMusicPositionMs(
            AudioTimeline.musicPositionMs(chart, 1_000.0, 100),
            48_000,
        )
        assertEquals(frame, back)
        assertTrue(abs(chart - 6_100.0) < 1e-6)
    }
}
