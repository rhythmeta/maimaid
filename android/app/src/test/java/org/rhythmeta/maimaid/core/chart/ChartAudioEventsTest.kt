package org.rhythmeta.maimaid.core.chart

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAudioEventsTest {
    private val chart = SimaiParser.parse(
        "&bpm=120\n&inote_5=(120){4}1b/2,3b-5b[4:1],A1,C,A1h[4:2]/B1h[4:1],",
        5,
    )

    private fun eventAt(events: List<ChartAudioEvent>, timeMs: Double): ChartAudioEvent =
        events.first { abs(it.timeMs - timeMs) < 0.5 }

    @Test
    fun mergesSameMillisecondNotesAndKeepsBothJudgeKinds() {
        val events = ChartAudioEvents.prepare(chart.notes)
        val event = eventAt(events, 2000.0)
        assertTrue(event.hasBaseSound)
        assertTrue(event.hasBreakJudgeSound)
        assertTrue(event.hasTapJudgeSound)
        assertTrue(event.playsAnswerSound)
    }

    @Test
    fun breakSlideAddsLaunchAndArrivalSounds() {
        val events = ChartAudioEvents.prepare(chart.notes)
        val head = eventAt(events, 2500.0)
        assertTrue(head.hasBaseSound)
        assertTrue(head.hasBreakJudgeSound)

        val launch = eventAt(events, 3000.0)
        assertTrue(launch.hasSlideSound)
        assertTrue(launch.hasBreakSlideSound)
        // The touch note at the same millisecond merges into the same event.
        assertTrue(launch.hasTouchSound)

        val arrival = eventAt(events, 3500.0)
        assertTrue(arrival.hasBreakSlideCheerSound)
        assertFalse(arrival.hasSlideSound)
    }

    @Test
    fun touchHoldKeepsTheLongestDuration() {
        val events = ChartAudioEvents.prepare(chart.notes)
        val event = eventAt(events, 4000.0)
        assertTrue(event.hasTouchSound)
        assertTrue(event.hasTouchHoldSound)
        assertEquals(1000.0, event.touchHoldDurationMs, 0.001)
    }

    @Test
    fun schedulerUsesTheFiftyMillisecondEarlyOffset() {
        val events = ChartAudioEvents.prepare(chart.notes)
        val scheduler = ChartSoundScheduler(events)
        assertEquals(-50.0, ChartSoundScheduler.ANSWER_SOUND_BASE_OFFSET_MS, 0.0)
        scheduler.reset(0.0)
        // Nothing should be due until the first event's shifted time.
        assertTrue(events.first().timeMs > 1900.0)
    }

    @Test
    fun schedulerKeepsEventsPendingUntilOutputIsReady() {
        val first = ChartAudioEvent(100.0).apply { hasBaseSound = true }
        val output = FakeSfxOutput(ready = false)
        val scheduler = ChartSoundScheduler(listOf(first))

        scheduler.reset(0.0)
        scheduler.advance(100.0, output)
        assertEquals(emptyList<Int>(), output.playedKeys)

        output.ready = true
        scheduler.advance(100.0, output)
        assertEquals(listOf(0), output.playedKeys)
    }

    @Test
    fun schedulerStopsEachTouchHoldByItsOwnEventKey() {
        val first = ChartAudioEvent(100.0).apply {
            hasTouchHoldSound = true
            touchHoldDurationMs = 100.0
        }
        val second = ChartAudioEvent(150.0).apply {
            hasTouchHoldSound = true
            touchHoldDurationMs = 200.0
        }
        val output = FakeSfxOutput(ready = true)
        val scheduler = ChartSoundScheduler(listOf(first, second))

        scheduler.reset(0.0)
        scheduler.advance(150.0, output)
        scheduler.advance(210.0, output)
        assertEquals(listOf(0), output.stoppedKeys)
        scheduler.advance(360.0, output)
        assertEquals(listOf(0, 1), output.stoppedKeys)
    }

    @Test
    fun resetRestoresTouchHoldWhenSeekingIntoItsBody() {
        val hold = ChartAudioEvent(100.0).apply {
            hasTouchHoldSound = true
            touchHoldDurationMs = 300.0
        }
        val output = FakeSfxOutput(ready = true)
        val scheduler = ChartSoundScheduler(listOf(hold))

        scheduler.reset(250.0)
        scheduler.advance(250.0, output)

        assertEquals(listOf(0), output.playedKeys)
    }

    private class FakeSfxOutput(
        var ready: Boolean,
    ) : ChartSfxOutput {
        override val enabled: Boolean = true
        override val isReady: Boolean get() = ready
        val playedKeys = mutableListOf<Int>()
        val stoppedKeys = mutableListOf<Int>()

        override fun playEvent(event: ChartAudioEvent, eventKey: Int): Boolean {
            playedKeys += eventKey
            return true
        }

        override fun stopTouchHoldLoop(eventKey: Int) {
            stoppedKeys += eventKey
        }
    }
}
