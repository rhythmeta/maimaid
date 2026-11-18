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
    fun answerLayerCoversEveryAudibleLayer() {
        val base = ChartAudioEvent(100.0).apply { hasBaseSound = true }
        val touch = ChartAudioEvent(100.0).apply { hasTouchSound = true }
        val holdEnd = ChartAudioEvent(100.0).apply { hasHoldEndSound = true }
        val judgeOnly = ChartAudioEvent(100.0).apply { hasTapJudgeSound = true }
        assertTrue(base.playsAnswerSound)
        assertTrue(touch.playsAnswerSound)
        assertTrue(holdEnd.playsAnswerSound)
        assertFalse(judgeOnly.playsAnswerSound)
    }
}
