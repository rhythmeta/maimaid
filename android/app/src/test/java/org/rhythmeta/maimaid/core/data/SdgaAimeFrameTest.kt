package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SdgaAimeFrameTest {
    @Test
    fun writesTheLittleEndianHeader() {
        val frame = SdgaAimeFrame.build(
            command = SdgaAimeFrame.CMD_LOOKUP_EX,
            body = ByteArray(16),
            placeId = 0x2F6D,
            keychip = "A63E01D6774",
            gameId = "SDGA",
            protocolVersion = 0x4020,
        )
        assertEquals(48, frame.size)
        assertEquals(0xA13E, SdgaAimeFrame.le16(frame, 0))
        assertEquals(0x4020, SdgaAimeFrame.le16(frame, 2))
        assertEquals(0x0F, SdgaAimeFrame.le16(frame, 4))
        assertEquals(48, SdgaAimeFrame.le16(frame, 6))
        assertEquals(0, SdgaAimeFrame.le16(frame, 8))
        assertEquals("SDGA", String(frame, 10, 4, Charsets.US_ASCII))
        assertEquals(0x2F6DL, SdgaAimeFrame.le32(frame, 16))
        assertEquals("A63E01D6774", String(frame, 20, 11, Charsets.US_ASCII))
        assertEquals(0.toByte(), frame[31])
    }

    @Test
    fun bodyFollowsTheHeaderVerbatim() {
        val body = byteArrayOf(1, 2, 3, 4)
        val frame = SdgaAimeFrame.build(1, body, 1, "A63E01D6774", "SDGA", 0x4020)
        assertEquals(body.toList(), frame.drop(SdgaAimeFrame.HEADER_SIZE).toList())
        assertEquals(36, SdgaAimeFrame.le16(frame, 6))
    }

    @Test
    fun chipIdIsAlphanumericAndPaddedToTwelveBytes() {
        assertEquals(
            listOf<Byte>(0x41, 0x36, 0x33, 0x45, 0x30, 0x31, 0x44, 0x36, 0x37, 0x37, 0x34, 0),
            SdgaAimeFrame.chipId("A63E-01D6774").toList(),
        )
        // Longer values keep the first 11 characters, like the reference client.
        assertEquals(
            "A63E01D6774",
            String(SdgaAimeFrame.chipId("A63E01D6774EXTRA"), Charsets.US_ASCII).trimEnd('\u0000'),
        )
    }

    /**
     * Byte-exact fixture produced by the reference client for the `0x0F` lookup of
     * `01035000000000000001` at place `2F6D` from keychip `A63E01D6774`. A change here means
     * the wire format drifted, not just the test.
     */
    @Test
    fun matchesTheReferenceLookupFrame() {
        val body = "01035000000000000001".chunked(2).map { it.toInt(16).toByte() }.toByteArray() +
            byteArrayOf(0x01, 0x03) +
            byteArrayOf(0, 0, 0, 0)
        val frame = SdgaAimeFrame.build(
            command = SdgaAimeFrame.CMD_LOOKUP_EX,
            body = body,
            placeId = 0x2F6D,
            keychip = "A63E01D6774",
            gameId = "SDGA",
            protocolVersion = 0x4020,
        )
        assertEquals(
            "3EA120400F00300000005344474100006D2F0000413633453031443637373400" +
                "01035000000000000001010300000000",
            frame.joinToString("") { "%02X".format(it) },
        )
    }

    @Test
    fun gameIdNeverOverflowsItsSixByteField() {
        val frame = SdgaAimeFrame.build(1, ByteArray(0), 1, "A63E01D6774", "SDGB-LONG", 0x4020)
        // The field is exactly six bytes; anything longer is truncated, never shifted.
        assertEquals("SDGB-L", String(frame, 10, 6, Charsets.US_ASCII))
    }
}
