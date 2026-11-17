package org.rhythmeta.maimaid.core.chart

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Kotlin port to the reference TypeScript parser output.
 *
 * The fixture and the expected lines were produced by running the reference engine's
 * `parseSimaiChart` on the same maidata (`app/src/test/resources/chart/parity-expected.txt`),
 * so any behavioural drift in note construction, timings, slide paths or flags fails here.
 */
class SimaiParityTest {
    private fun resource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "Missing test resource $path" }
            .bufferedReader()
            .readText()

    @Test
    fun parsesIdenticallyToTheReferenceEngine() {
        val maidata = resource("/chart/parity-maidata.txt")
        val expected = resource("/chart/parity-expected.txt").trim().lines()
        val chart = SimaiParser.parse(maidata, 5)

        val header = listOf(
            jsNumber(chart.bpm),
            chart.difficulty.toString(),
            chart.measures.toString(),
            chart.notes.size.toString(),
        ).joinToString(",")
        assertEquals(expected.first(), header)

        val actual = chart.notes.map(::describe)
        assertEquals(expected.size - 1, actual.size)
        expected.drop(1).forEachIndexed { index, line ->
            assertEquals("note $index", line, actual[index])
        }
    }

    @Test
    fun reportsAvailableDifficulties() {
        val maidata = resource("/chart/parity-maidata.txt")
        assertEquals(setOf(5), SimaiParser.availableDifficulties(maidata))
        assertEquals(listOf(5), SimaiParser.chartSlots(maidata))
    }

    @Test
    fun parsesSdChartWithoutDifficultyMetadata() {
        val chart = SimaiParser.parse("&title=Plain\n&bpm=120\n(120){4}1,2,3,E", null)
        assertEquals(4, chart.difficulty)
        assertEquals(3, chart.notes.size)
        assertEquals(2000.0, chart.leadInMs, 0.0001)
        assertEquals(4.0, chart.notes.first().timing, 0.0001)
    }

    private fun describe(note: ChartNote): String {
        val type = when (note) {
            is TapNote -> when {
                note.isBreak -> "break"
                note.isSameTime -> "simultaneous"
                else -> "tap"
            }
            is HoldStartNote -> if (note.isSameTime) "hold-start-simultaneous" else "hold-start"
            is HoldEndNote -> if (note.isSameTime) "hold-end-simultaneous" else "hold-end"
            is SlideNote -> "slide"
            is TouchNote -> "touch"
            is TouchHoldStartNote -> "touch-hold-start"
            is TouchHoldEndNote -> "touch-hold-end"
        }
        val position = when (note) {
            is TouchNote -> note.position
            is TouchHoldStartNote -> note.position
            is TouchHoldEndNote -> note.position
            is TapNote -> note.position.toString()
            is HoldStartNote -> note.position.toString()
            is HoldEndNote -> note.position.toString()
            is SlideNote -> note.position.toString()
        }
        val duration = when (note) {
            is HoldStartNote -> note.duration
            is SlideNote -> note.duration
            is TouchHoldStartNote -> note.duration
            else -> 0.0
        }
        val durationMs = when (note) {
            is SlideNote -> note.durationMs
            is TouchHoldStartNote -> note.durationMs
            else -> 0.0
        }
        val delayMs = (note as? SlideNote)?.delayMs ?: 0.0
        val paths = (note as? SlideNote)?.allSlideSegments.orEmpty().joinToString("|") { path ->
            path.joinToString("+") { segment ->
                "${segment.type.notation}${segment.startPos}${segment.midPos ?: ""}${segment.endPos}"
            }
        }
        val flags = listOf(
            type,
            isEx(note).toString(),
            ((note as? TapNote)?.isStar ?: false).toString(),
            ((note as? TapNote)?.isSpinningStar ?: false).toString(),
            ((note as? SlideNote)?.isHeadless ?: false).toString(),
            ((note as? SlideNote)?.hasTapHead ?: false).toString(),
            ((note as? SlideNote)?.isStartBreak ?: false).toString(),
            ((note as? HoldStartNote)?.isBreakHold ?: (note as? HoldEndNote)?.isBreakHold ?: false)
                .toString(),
            ((note as? SlideNote)?.isSplitSlide ?: false).toString(),
            note.hasDelayMarker.toString(),
            ((note as? TouchNote)?.hasFirework
                ?: (note as? TouchHoldStartNote)?.hasFirework ?: false).toString(),
            note.hiSpeed?.toString() ?: "",
        ).joinToString(",")
        return listOf(
            type,
            position,
            format(note.timing),
            format(note.timingMs),
            format(duration),
            format(durationMs),
            format(delayMs),
            paths,
            flags,
        ).joinToString(";")
    }

    private fun isEx(note: ChartNote): Boolean = when (note) {
        is TapNote -> note.isEx
        is HoldStartNote -> note.isEx
        is HoldEndNote -> note.isEx
        is SlideNote -> note.isEx
        else -> false
    }

    private fun jsNumber(value: Double): String =
        if (value == kotlin.math.floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private fun format(value: Double): String {
        val text = String.format(Locale.US, "%.6f", value)
        return if (text == "-0.000000") "0.000000" else text
    }
}
