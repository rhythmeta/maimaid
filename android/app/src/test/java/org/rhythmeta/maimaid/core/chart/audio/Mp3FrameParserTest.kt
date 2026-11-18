package org.rhythmeta.maimaid.core.chart.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp3FrameParserTest {
    /** MPEG-1 Layer III, 128 kbps, 44.1 kHz, stereo → 417-byte frames. */
    private fun frame(seed: Int): ByteArray {
        val bytes = ByteArray(417) { seed.toByte() }
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xFB.toByte()
        bytes[2] = 0x90.toByte()
        bytes[3] = 0x00
        return bytes
    }

    @Test
    fun emitsWholeFramesAndReportsTheFormat() {
        val frames = mutableListOf<ByteArray>()
        val parser = Mp3FrameParser { frames += it }
        parser.consume(frame(1), 0, 417)
        parser.consume(frame(2), 0, 417)
        assertEquals(2, frames.size)
        assertEquals(417, frames[0].size)
        assertEquals(44_100, parser.format?.sampleRate)
        assertEquals(2, parser.format?.channels)
    }

    @Test
    fun waitsForACrossChunkFrameToComplete() {
        val frames = mutableListOf<ByteArray>()
        val parser = Mp3FrameParser { frames += it }
        val whole = frame(3)
        parser.consume(whole, 0, 200)
        assertEquals(0, frames.size)
        assertEquals(200, parser.pendingBytes)
        parser.consume(whole, 200, 217)
        assertEquals(1, frames.size)
        assertEquals(417, frames[0].size)
    }

    @Test
    fun skipsAnId3v2Tag() {
        val frames = mutableListOf<ByteArray>()
        val parser = Mp3FrameParser { frames += it }
        val tag = ByteArray(32)
        tag[0] = 'I'.code.toByte()
        tag[1] = 'D'.code.toByte()
        tag[2] = '3'.code.toByte()
        tag[5] = 0
        // Remaining 22 bytes of the declared payload (32 - 10).
        tag[9] = 22
        parser.consume(tag, 0, tag.size)
        parser.consume(frame(4), 0, 417)
        assertEquals(1, frames.size)
    }

    @Test
    fun resynchronizesAfterGarbage() {
        val frames = mutableListOf<ByteArray>()
        val parser = Mp3FrameParser { frames += it }
        val junk = ByteArray(17) { 0x11 }
        parser.consume(junk, 0, junk.size)
        parser.consume(frame(5), 0, 417)
        assertEquals(1, frames.size)
    }

    @Test
    fun dropsTrailingPartialFrameOnFinish() {
        val frames = mutableListOf<ByteArray>()
        val parser = Mp3FrameParser { frames += it }
        parser.consume(frame(6), 0, 417)
        parser.consume(frame(7), 0, 100)
        parser.finish()
        assertEquals(1, frames.size)
        assertEquals(0, parser.pendingBytes)
    }

    @Test
    fun reportsNoFormatForGarbageOnly() {
        val parser = Mp3FrameParser { }
        parser.consume(ByteArray(64) { 0x00 }, 0, 64)
        assertNull(parser.format)
    }
}
