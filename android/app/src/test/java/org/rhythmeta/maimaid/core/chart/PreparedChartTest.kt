package org.rhythmeta.maimaid.core.chart

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the chord detection used for simultaneous (yellow) colouring. Release events are not
 * hits, so a tap that lands on a hold or touch-hold release must stay a single note.
 */
class PreparedChartTest {
    private fun chartOf(body: String): Chart =
        SimaiParser.parse("&title=test\n&inote_5=$body\n", 5)

    @Test
    fun touchHoldReleaseDoesNotPairWithTap() {
        // Touch hold from beat 0 lasting one beat, then a tap exactly on its release.
        val chart = chartOf("(120){4}A1h[4:1],2,,")
        val prepared = PreparedChart.build(chart)
        val tap = chart.notes.filterIsInstance<TapNote>().single()

        assertEquals(1, prepared.metaOf(tap).simultaneousNoteCount)
    }

    @Test
    fun holdReleaseDoesNotPairWithTap() {
        val chart = chartOf("(120){4}1h[4:1],2,,")
        val prepared = PreparedChart.build(chart)
        val tap = chart.notes.filterIsInstance<TapNote>().single()

        assertEquals(1, prepared.metaOf(tap).simultaneousNoteCount)
    }

    @Test
    fun tapsOnTheSameBeatStaySimultaneous() {
        val chart = chartOf("(120){4}1/2,,,")
        val prepared = PreparedChart.build(chart)
        val taps = chart.notes.filterIsInstance<TapNote>()

        assertEquals(2, taps.size)
        taps.forEach { assertEquals(2, prepared.metaOf(it).simultaneousNoteCount) }
    }
}
