package org.rhythmeta.maimaid.core.chart

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp3CoverArtTest {
    private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    private fun apicBody(pictureType: Int, mime: String = "image/png"): ByteArray {
        val head = byteArrayOf(0) + mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, pictureType.toByte()) +
            byteArrayOf(0)
        return head + image
    }

    private fun frame(major: Int, id: String, body: ByteArray): ByteArray {
        return when {
            major == 2 -> byteArrayOf(
                id[0].code.toByte(), id[1].code.toByte(), id[2].code.toByte(),
                (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte(),
            ) + body

            major >= 4 -> {
                val size = body.size
                byteArrayOf(
                    id[0].code.toByte(), id[1].code.toByte(), id[2].code.toByte(), id[3].code.toByte(),
                    ((size shr 21) and 0x7f).toByte(), ((size shr 14) and 0x7f).toByte(),
                    ((size shr 7) and 0x7f).toByte(), (size and 0x7f).toByte(),
                    0, 0,
                ) + body
            }

            else -> byteArrayOf(
                id[0].code.toByte(), id[1].code.toByte(), id[2].code.toByte(), id[3].code.toByte(),
                (body.size shr 24).toByte(), (body.size shr 16).toByte(),
                (body.size shr 8).toByte(), body.size.toByte(),
                0, 0,
            ) + body
        }
    }

    private fun tag(major: Int, vararg frames: ByteArray): ByteArray {
        val payload = frames.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val size = payload.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            major.toByte(), 0, 0,
            ((size shr 21) and 0x7f).toByte(), ((size shr 14) and 0x7f).toByte(),
            ((size shr 7) and 0x7f).toByte(), (size and 0x7f).toByte(),
        )
        return header + payload
    }

    private fun read(bytes: ByteArray): ByteArray? {
        val file = File.createTempFile("cover-test", ".mp3")
        return try {
            file.writeBytes(bytes + ByteArray(256)) // trailing audio placeholder
            Mp3CoverArt.read(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsApicFromId3v23() {
        val bytes = tag(3, frame(3, "TIT2", byteArrayOf(0, 'x'.code.toByte())), frame(3, "APIC", apicBody(3)))
        assertArrayEquals(image, read(bytes))
    }

    @Test
    fun readsApicFromId3v24() {
        val bytes = tag(4, frame(4, "APIC", apicBody(3)))
        assertArrayEquals(image, read(bytes))
    }

    @Test
    fun readsPicFromId3v22() {
        val body = byteArrayOf(0) + "PNG".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0) + image
        val bytes = tag(2, frame(2, "PIC", body))
        assertArrayEquals(image, read(bytes))
    }

    @Test
    fun fallsBackToAnyPictureWhenNoFrontCover() {
        val bytes = tag(3, frame(3, "APIC", apicBody(0)))
        assertArrayEquals(image, read(bytes))
    }

    @Test
    fun returnsNullWithoutTags() {
        assertNull(read("not an mp3".toByteArray()))
    }
}
