package org.rhythmeta.maimaid.core.chart.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmChunkBufferTest {
    @Test
    fun appendsAndReadsAcrossChunkBoundaries() {
        val buffer = PcmChunkBuffer(chunkSamples = 4)
        // Three stereo frames in the first append, three more in the second.
        buffer.append(shortArrayOf(1, 1, 2, 2, 3, 3), 0, 6)
        buffer.append(shortArrayOf(4, 4, 5, 5, 6, 6), 0, 6)
        assertEquals(6L, buffer.availableFrames())

        val out = ShortArray(12)
        assertEquals(4, buffer.readFrames(1, out, 0, 4))
        assertEquals(listOf<Short>(2, 2, 3, 3, 4, 4, 5, 5), out.take(8))
    }

    @Test
    fun readsPartialAndClampsAtTheEnd() {
        val buffer = PcmChunkBuffer(chunkSamples = 8)
        buffer.append(shortArrayOf(1, 1, 2, 2, 3, 3), 0, 6)
        val out = ShortArray(16)
        // Only the last frame is left after skipping the first two.
        assertEquals(1, buffer.readFrames(2, out, 0, 8))
        assertEquals(listOf<Short>(3, 3, 0, 0), out.take(4))
        assertEquals(0, buffer.readFrames(5, out, 0, 4))
    }

    @Test
    fun appendsZeroWhenTheDecoderHasNothing() {
        val buffer = PcmChunkBuffer()
        assertEquals(0L, buffer.availableFrames())
        assertEquals(0, buffer.readFrames(0, ShortArray(8), 0, 4))
        buffer.append(shortArrayOf(7, 7), 0, 2)
        assertEquals(1L, buffer.availableFrames())
    }

    @Test
    fun resetAtMovesTheReadableBaseAfterADecoderRestart() {
        val buffer = PcmChunkBuffer(chunkSamples = 4)
        buffer.append(shortArrayOf(1, 1, 2, 2, 3, 3), 0, 6)
        // The decoder restarted at frame 1000 (a seek target past the decoded frontier).
        buffer.resetAt(1_000)
        assertEquals(1_000L, buffer.base / 2)
        assertEquals(0L, buffer.availableFrames())
        // Frames before the restart are no longer served.
        assertEquals(0, buffer.readFrames(2, ShortArray(4), 0, 2))
        buffer.append(shortArrayOf(9, 9), 0, 2)
        val out = ShortArray(4)
        assertEquals(1, buffer.readFrames(1_000, out, 0, 2))
        assertEquals(listOf<Short>(9, 9), out.take(2))
    }

    @Test
    fun clearDropsEverything() {
        val buffer = PcmChunkBuffer(chunkSamples = 2)
        buffer.append(shortArrayOf(1, 1, 2, 2), 0, 4)
        buffer.clear()
        assertEquals(0L, buffer.availableFrames())
        assertEquals(0, buffer.readFrames(0, ShortArray(4), 0, 2))
    }
}
