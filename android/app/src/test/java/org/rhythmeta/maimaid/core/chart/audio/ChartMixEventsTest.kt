package org.rhythmeta.maimaid.core.chart.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import org.rhythmeta.maimaid.core.chart.ChartAudioEvent
import org.rhythmeta.maimaid.core.chart.ChartSfxKind

class ChartMixEventsTest {
    @Test
    fun mapsEveryLayerOfATap() {
        val event = ChartAudioEvent(1_000.0).apply {
            hasBaseSound = true
            hasTapJudgeSound = true
        }
        val mix = ChartMixEvents.build(
            listOf(event),
            leadInMs = 1_000.0,
            musicOffsetMs = 0,
            sfxOffsetMs = 0.0,
            sampleRate = 48_000,
        ).single()
        assertEquals(0L, mix.frame)
        assertEquals(true, mix.playsAnswer)
        assertEquals(
            listOf(ChartSfxKind.TAP.ordinal),
            mix.judgeKinds.toList(),
        )
    }

    @Test
    fun breakPlaysBreakAndCheer() {
        val event = ChartAudioEvent(2_000.0).apply { hasBreakJudgeSound = true }
        val kinds = ChartMixEvents.judgeKinds(event).toList()
        assertEquals(
            listOf(ChartSfxKind.BREAK.ordinal, ChartSfxKind.CHEER.ordinal),
            kinds,
        )
    }

    @Test
    fun touchHoldCarriesItsLoopEndFrame() {
        val event = ChartAudioEvent(2_000.0).apply {
            hasTouchSound = true
            hasTouchHoldSound = true
            touchHoldDurationMs = 500.0
        }
        val mix = ChartMixEvents.build(
            listOf(event),
            leadInMs = 1_000.0,
            musicOffsetMs = 0,
            sfxOffsetMs = 0.0,
            sampleRate = 48_000,
        ).single()
        assertEquals(45_600L, mix.frame) // audible at 1950 ms -> 950 ms of music
        assertEquals(69_600L, mix.touchHoldEndFrame) // 2450 ms
        assertEquals(
            listOf(
                ChartSfxKind.TOUCH.ordinal,
                ChartSfxKind.TOUCH_HOLD.ordinal,
            ),
            mix.judgeKinds.toList(),
        )
    }
}
