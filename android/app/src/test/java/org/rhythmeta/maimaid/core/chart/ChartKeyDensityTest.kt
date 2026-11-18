package org.rhythmeta.maimaid.core.chart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartKeyDensityTest {
    private fun tap(ms: Double, position: Int = 1) = TapNote(
        position = position,
        timing = ms / 500.0,
        timingMs = ms,
        measure = 0,
        positionInMeasure = 0,
        scale = 1.0,
        bpm = 120.0,
        hasDelayMarker = false,
    )

    private fun holdEnd(ms: Double) = HoldEndNote(
        position = 1,
        timing = ms / 500.0,
        timingMs = ms,
        measure = 0,
        positionInMeasure = 0,
        scale = 1.0,
        bpm = 120.0,
        hasDelayMarker = false,
        holdStartTiming = 0.0,
    )

    private fun touchHoldEnd(ms: Double) = TouchHoldEndNote(
        position = "A1",
        timing = ms / 500.0,
        timingMs = ms,
        measure = 0,
        positionInMeasure = 0,
        scale = 1.0,
        bpm = 120.0,
        hasDelayMarker = false,
        holdStartTiming = 0.0,
    )

    @Test
    fun countsHeadsOnly() {
        val times = ChartKeyDensity.pressTimes(
            listOf(tap(1_000.0), holdEnd(1_000.0), touchHoldEnd(2_000.0), tap(3_000.0, position = 2)),
        )
        assertArrayEquals(doubleArrayOf(1_000.0, 3_000.0), times, 0.0)
    }

    @Test
    fun countsPressesInsideTheTrailingSecond() {
        val times = doubleArrayOf(0.0, 500.0, 1_000.0, 1_500.0)
        assertEquals(2, ChartKeyDensity.keysPerSecond(times, 1_000.0))
        assertEquals(2, ChartKeyDensity.keysPerSecond(times, 1_500.0))
        assertEquals(0, ChartKeyDensity.keysPerSecond(times, 4_000.0))
    }

    @Test
    fun excludesPressesExactlyOneSecondBefore() {
        val times = doubleArrayOf(1_000.0, 2_000.0)
        assertEquals(1, ChartKeyDensity.keysPerSecond(times, 2_000.0))
    }

    @Test
    fun handlesEmptyCharts() {
        assertEquals(0, ChartKeyDensity.keysPerSecond(DoubleArray(0), 5_000.0))
        assertArrayEquals(DoubleArray(0), ChartKeyDensity.pressTimes(emptyList()), 0.0)
    }

    @Test
    fun countsChordsPerKey() {
        val times = ChartKeyDensity.pressTimes(listOf(tap(2_000.0), tap(2_000.0, position = 2), tap(2_000.0, position = 3)))
        assertEquals(3, ChartKeyDensity.keysPerSecond(times, 2_000.0))
    }
}
