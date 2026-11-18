package org.rhythmeta.maimaid.core.chart.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAudioClockTest {
    private val rate = 48_000
    private val t0 = 1_000_000_000L

    @Test
    fun extrapolatesAtPlaybackSpeed() {
        val clock = ChartAudioClock(rate)
        clock.reset(1_000.0, t0, playing = true)
        assertEquals(1_000.0 + 48_000, clock.positionFrameAt(t0 + 1_000_000_000), 1e-6)
        clock.setSpeed(1.5f, t0 + 1_000_000_000)
        assertEquals(
            1_000.0 + 48_000 + 72_000,
            clock.positionFrameAt(t0 + 2_000_000_000),
            1e-3,
        )
    }

    @Test
    fun pauseFreezesTheClock() {
        val clock = ChartAudioClock(rate)
        clock.reset(0.0, t0, playing = true)
        clock.setPlaying(false, t0 + 500_000_000)
        assertEquals(24_000.0, clock.positionFrameAt(t0 + 5_000_000_000), 1e-6)
        clock.setPlaying(true, t0 + 5_000_000_000)
        assertEquals(48_000.0, clock.positionFrameAt(t0 + 5_500_000_000), 1e-3)
    }

    @Test
    fun smallTimestampErrorIsCorrectedByNudgingTheRate() {
        val clock = ChartAudioClock(rate)
        clock.reset(0.0, t0, playing = true)
        // At 1 s the clock projected 48000 frames; the track reports 48480 (+10 ms).
        clock.onTimestamp(48_480, t0 + 1_000_000_000)
        // The visual clock keeps running from the projected position; the rate is nudged by
        // at most +3% for the next interval.
        val at1100 = clock.positionFrameAt(t0 + 1_100_000_000)
        assertTrue(at1100 > 48_000 + 4_800)
        assertTrue(at1100 <= 48_000 + 4_800 * 1.031)
        assertTrue(clock.playbackSpeed == 1.0)
    }

    @Test
    fun largeTimestampErrorSnaps() {
        val clock = ChartAudioClock(rate)
        clock.reset(0.0, t0, playing = true)
        clock.onTimestamp(96_000, t0 + 1_000_000_000) // +1 s discontinuity (seek/flush)
        assertEquals(96_000.0, clock.positionFrameAt(t0 + 1_000_000_000), 1e-6)
        assertEquals(96_000.0 + 48_000, clock.positionFrameAt(t0 + 2_000_000_000), 1e-6)
    }

    @Test
    fun aBackwardTimestampNeverMovesTheClockBackwards() {
        val clock = ChartAudioClock(rate)
        clock.reset(0.0, t0, playing = true)
        val before = clock.positionFrameAt(t0 + 1_000_000_000)
        // The AudioTrack reports a playhead far behind the projection (typical right after resume).
        clock.onTimestamp(0, t0 + 1_000_000_000)
        val after = clock.positionFrameAt(t0 + 1_000_000_000)
        assertTrue("clock jumped back: $before -> $after", after >= before)
        // It still converges instead of ignoring the sample: the rate is nudged down.
        val later = clock.positionFrameAt(t0 + 1_500_000_000)
        assertTrue(later > after)
        assertTrue("drift was not corrected", later < after + 0.5 * rate)
    }

    @Test
    fun timestampWhilePausedIsIgnored() {
        val clock = ChartAudioClock(rate)
        clock.reset(0.0, t0, playing = false)
        assertFalse(clock.isPlaying)
        clock.onTimestamp(48_000, t0 + 1_000_000_000)
        assertEquals(0.0, clock.positionFrameAt(t0 + 2_000_000_000), 1e-6)
    }
}
