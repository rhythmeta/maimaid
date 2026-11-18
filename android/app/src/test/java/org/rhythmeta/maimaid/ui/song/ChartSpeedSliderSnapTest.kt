package org.rhythmeta.maimaid.ui.song

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartSpeedSliderSnapTest {
    private val snapPoints = listOf(1f)
    private val threshold = 0.04f

    @Test
    fun pullsValuesNearOneTimesOntoExactlyOne() {
        assertEquals(1f, snapSliderValue(1f, snapPoints, threshold), 0f)
        assertEquals(1f, snapSliderValue(1.03f, snapPoints, threshold), 0f)
        assertEquals(1f, snapSliderValue(0.97f, snapPoints, threshold), 0f)
    }

    @Test
    fun leavesValuesOutsideTheThresholdAlone() {
        assertEquals(1.09f, snapSliderValue(1.09f, snapPoints, threshold), 0f)
        assertEquals(0.9f, snapSliderValue(0.9f, snapPoints, threshold), 0f)
        assertEquals(2f, snapSliderValue(2f, snapPoints, threshold), 0f)
        assertEquals(0.1f, snapSliderValue(0.1f, snapPoints, threshold), 0f)
    }

    @Test
    fun withoutSnapPointsTheValueIsUnchanged() {
        assertEquals(1.37f, snapSliderValue(1.37f, emptyList(), threshold), 0f)
    }
}
