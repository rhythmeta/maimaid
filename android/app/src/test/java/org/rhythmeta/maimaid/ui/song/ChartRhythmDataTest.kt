package org.rhythmeta.maimaid.ui.song

import org.junit.Assert.assertEquals
import org.junit.Test
import org.rhythmeta.maimaid.core.chart.SimaiParser
import org.rhythmeta.maimaid.core.chart.TimingTimeline

class ChartRhythmDataTest {
    // One measure of quarter notes followed by one measure of twelfths.
    private val chart = SimaiParser.parse(
        "&bpm=120\n&inote_5={4}1b/2,3,4,5,{12}1,2,3,4,5,6,7,8,1,2,3,4,",
        5,
    )
    private val timeline = TimingTimeline.fromChart(chart)
    private val data = ChartRhythmData.from(chart, timeline)

    @Test
    fun oneStepPerNoteTimeWithTheDivisorInEffect() {
        // 4 quarter notes plus 12 twelfths; the break/tap chord collapses into a single step.
        assertEquals(16, data.steps.size)
        assertEquals(chart.leadInMs, data.steps[0].timeMs, 0.01)
        assertEquals(4.0, data.steps[0].divisor, 0.01)
        assertEquals(12.0, data.steps[4].divisor, 0.01)
        assertEquals(chart.leadInMs + 2_000.0, data.steps[4].timeMs, 0.01)
        assertEquals(chart.leadInMs + 2_000.0 + 500.0 / 3.0, data.steps[5].timeMs, 0.01)
    }

    @Test
    fun chordTakesTheStrongestNoteFamilyAndCountsAsChord() {
        assertEquals(RhythmStepKind.BREAK, data.steps[0].kind)
        assertEquals(2, data.steps[0].noteCount)
        assertEquals(RhythmStepKind.TAP, data.steps[1].kind)
        assertEquals(1, data.steps[1].noteCount)
    }

    @Test
    fun holdTailsAreNotRhythmSteps() {
        val held = SimaiParser.parse("&bpm=120\n&inote_5={4}1h[4:1],2,3,4,", 5)
        val heldData = ChartRhythmData.from(held, TimingTimeline.fromChart(held))
        // Four heads, no separate step for the hold release.
        assertEquals(4, heldData.steps.size)
        assertEquals(RhythmStepKind.HOLD, heldData.steps[0].kind)
    }

    @Test
    fun holdTrailsItsLengthAboveTheDot() {
        // Four beats at 120 BPM.
        val held = SimaiParser.parse("&bpm=120\n&inote_5={4}1h[4:4],", 5)
        val heldData = ChartRhythmData.from(held, TimingTimeline.fromChart(held))
        assertEquals(DurationLineSide.ABOVE, heldData.steps[0].lineSide)
        assertEquals(2_000.0, heldData.steps[0].durationMs, 0.01)
        // Holds have no launch beat; the whole length is the hold body plus its dashed release.
        assertEquals(0.0, heldData.steps[0].launchMs, 0.01)
        // The chart ends on that hold, so the step length must match the timeline end.
        assertEquals(held.endTimeMs - heldData.steps[0].timeMs, heldData.steps[0].durationMs, 0.01)
    }

    @Test
    fun slideSplitsItsLaunchFromItsTravel() {
        val slid = SimaiParser.parse("&bpm=120\n&inote_5={4}1-3[4:4],", 5)
        val slidData = ChartRhythmData.from(slid, TimingTimeline.fromChart(slid))
        assertEquals(DurationLineSide.BELOW, slidData.steps[0].lineSide)
        assertEquals(slid.endTimeMs - slidData.steps[0].timeMs, slidData.steps[0].durationMs, 0.01)
        // One beat of launch delay, then four beats of travel.
        assertEquals(500.0, slidData.steps[0].launchMs, 0.01)
        assertEquals(2_500.0, slidData.steps[0].durationMs, 0.01)
        // Instant notes carry no line at all.
        val taps = SimaiParser.parse("&bpm=120\n&inote_5={4}1,2,3,4,", 5)
        val tapsData = ChartRhythmData.from(taps, TimingTimeline.fromChart(taps))
        assertEquals(DurationLineSide.NONE, tapsData.steps[0].lineSide)
        assertEquals(0.0, tapsData.steps[0].durationMs, 0.01)
    }

    @Test
    fun measureTicksCoverTheWholeTimeline() {
        assertEquals(0.0, data.measureTicks.first(), 0.01)
        assertEquals(chart.leadInMs, data.measureTicks[1], 0.01)
        assertEquals(chart.measures + 1, data.measureTicks.size)
    }

    @Test
    fun bpmReadoutPicksTheMarkInEffect() {
        val marked = ChartRhythmData(
            steps = emptyList(),
            measureTicks = emptyList(),
            bpmMarks = listOf(
                BpmMark(0.0, 120.0),
                BpmMark(4_000.0, 240.0),
                BpmMark(9_000.0, 180.0),
            ),
        )
        assertEquals(120.0, marked.bpmAt(0.0), 0.01)
        assertEquals(120.0, marked.bpmAt(3_999.0), 0.01)
        assertEquals(240.0, marked.bpmAt(4_000.0), 0.01)
        assertEquals(240.0, marked.bpmAt(8_999.0), 0.01)
        assertEquals(180.0, marked.bpmAt(9_000.0), 0.01)
        assertEquals(180.0, marked.bpmAt(600_000.0), 0.01)
    }

    @Test
    fun bpmReadoutMatchesTheTimingTimeline() {
        // Cross-check the lane's readout against the timeline the renderer uses, whatever BPM
        // events the parser produced for this chart.
        for (beat in listOf(0.0, 3.0, 4.0, 7.5, 8.0, 12.0)) {
            assertEquals(
                timeline.bpmAtBeat(beat),
                data.bpmAt(timeline.msFromBeat(beat)),
                0.01,
            )
        }
    }
}
