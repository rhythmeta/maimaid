package org.rhythmeta.maimaid.core.chart.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BpmChangeFlashTest {
    private val WHITE = 0xFFFFFFFF.toInt()

    @Test
    fun staysQuietUntilTheTempoChanges() {
        val flash = BpmChangeFlash()
        assertNull(flash.colorFor(120.0, isPlaying = true, baseColor = WHITE, nowMs = 0L))
        assertNull(flash.colorFor(120.0, isPlaying = true, baseColor = WHITE, nowMs = 16L))
    }

    @Test
    fun flashesRedWhenTheTempoRisesAndFadesToWhite() {
        val flash = BpmChangeFlash()
        assertNull(flash.colorFor(120.0, isPlaying = true, baseColor = WHITE, nowMs = 0L))
        assertEquals(0xFFFF0000.toInt(), flash.colorFor(180.0, isPlaying = true, baseColor = WHITE, nowMs = 1_000L))
        assertEquals(0xFFFF8080.toInt(), flash.colorFor(180.0, isPlaying = true, baseColor = WHITE, nowMs = 1_275L))
        assertNull(flash.colorFor(180.0, isPlaying = true, baseColor = WHITE, nowMs = 1_500L))
    }

    @Test
    fun flashesGreenWhenTheTempoDrops() {
        val flash = BpmChangeFlash()
        assertNull(flash.colorFor(180.0, isPlaying = true, baseColor = WHITE, nowMs = 0L))
        assertEquals(0xFF00FF00.toInt(), flash.colorFor(120.0, isPlaying = true, baseColor = WHITE, nowMs = 500L))
    }

    @Test
    fun fadesIntoADarkBaseColour() {
        val flash = BpmChangeFlash()
        val dark = 0xFF1A1A1A.toInt()
        assertNull(flash.colorFor(120.0, isPlaying = true, baseColor = dark, nowMs = 0L))
        assertEquals(0xFFFF0000.toInt(), flash.colorFor(180.0, isPlaying = true, baseColor = dark, nowMs = 100L))
        assertEquals(0xFFBF0707.toInt(), flash.colorFor(180.0, isPlaying = true, baseColor = dark, nowMs = 275L))
        assertNull(flash.colorFor(180.0, isPlaying = true, baseColor = dark, nowMs = 650L))
    }

    @Test
    fun pauseClearsTheFlash() {
        val flash = BpmChangeFlash()
        assertNull(flash.colorFor(120.0, isPlaying = true, baseColor = WHITE, nowMs = 0L))
        assertEquals(0xFFFF0000.toInt(), flash.colorFor(180.0, isPlaying = true, baseColor = WHITE, nowMs = 100L))
        assertNull(flash.colorFor(180.0, isPlaying = false, baseColor = WHITE, nowMs = 120L))
        assertNull(flash.colorFor(180.0, isPlaying = true, baseColor = WHITE, nowMs = 140L))
    }
}
