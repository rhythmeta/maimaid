package org.rhythmeta.maimaid.core.chart

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the cover art that community MP3s carry in their ID3v2 tag.
 *
 * Those images are usually much larger than the catalog cover the app downloads separately, so
 * the chart preview prefers them when they exist.
 */
internal object Mp3CoverArt {
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private const val PICTURE_TYPE_FRONT_COVER = 3

    /** Front-cover image bytes embedded in [file], or null when the file has no usable picture. */
    fun read(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(10)
            if (input.read(header) != header.size) return null
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) {
                return null
            }
            val major = header[3].toInt() and 0xff
            if (major !in 2..4) return null
            val size = syncSafe(header, 6)
            if (size <= 0) return null
            val length = minOf(size, MAX_TAG_BYTES)
            val tag = ByteArray(length)
            input.seek(10)
            input.readFully(tag)
            pictures(tag, major)
        }
    }.getOrNull()

    private fun pictures(tag: ByteArray, major: Int): ByteArray? {
        val headerSize = if (major == 2) 6 else 10
        var offset = 0
        var fallback: ByteArray? = null
        while (offset + headerSize <= tag.size) {
            if (tag[offset] == 0.toByte()) break // padding
            val idBytes = if (major == 2) 3 else 4
            val id = String(tag, offset, idBytes, Charsets.ISO_8859_1)
            val frameSize = if (major == 2) {
                ((tag[offset + 3].toInt() and 0xff) shl 16) or
                    ((tag[offset + 4].toInt() and 0xff) shl 8) or
                    (tag[offset + 5].toInt() and 0xff)
            } else if (major >= 4) {
                syncSafe(tag, offset + 4)
            } else {
                ((tag[offset + 4].toInt() and 0xff) shl 24) or
                    ((tag[offset + 5].toInt() and 0xff) shl 16) or
                    ((tag[offset + 6].toInt() and 0xff) shl 8) or
                    (tag[offset + 7].toInt() and 0xff)
            }
            if (frameSize <= 0 || offset + headerSize + frameSize > tag.size) break
            val bodyStart = offset + headerSize
            when (id) {
                "APIC" -> {
                    val picture = apic(tag, bodyStart, bodyStart + frameSize)
                    if (picture.first == PICTURE_TYPE_FRONT_COVER) return picture.second
                    if (fallback == null) fallback = picture.second
                }
                "PIC" -> {
                    val picture = pic(tag, bodyStart, bodyStart + frameSize)
                    if (picture.first == PICTURE_TYPE_FRONT_COVER) return picture.second
                    if (fallback == null) fallback = picture.second
                }
            }
            offset = bodyStart + frameSize
        }
        return fallback
    }

    /** APIC: encoding, NUL-terminated MIME, picture type, NUL-terminated description, data. */
    private fun apic(tag: ByteArray, start: Int, end: Int): Pair<Int, ByteArray?> {
        var index = start
        if (index >= end) return -1 to null
        val encoding = tag[index].toInt() and 0xff
        index += 1
        index = skipTerminated(tag, index, end, terminatedByTwoBytes = false)
        if (index < 0 || index >= end) return -1 to null
        val pictureType = tag[index].toInt() and 0xff
        index += 1
        index = skipTerminated(tag, index, end, terminatedByTwoBytes = encoding == 1 || encoding == 2)
        if (index < 0 || index >= end) return -1 to null
        return pictureType to tag.copyOfRange(index, end)
    }

    /** PIC (ID3v2.2): encoding, 3-character image format, picture type, description, data. */
    private fun pic(tag: ByteArray, start: Int, end: Int): Pair<Int, ByteArray?> {
        var index = start
        if (index + 4 >= end) return -1 to null
        val encoding = tag[index].toInt() and 0xff
        index += 4
        val pictureType = tag[index].toInt() and 0xff
        index += 1
        index = skipTerminated(tag, index, end, terminatedByTwoBytes = encoding == 1)
        if (index < 0 || index >= end) return -1 to null
        return pictureType to tag.copyOfRange(index, end)
    }

    private fun skipTerminated(
        bytes: ByteArray,
        start: Int,
        end: Int,
        terminatedByTwoBytes: Boolean,
    ): Int {
        var index = start
        while (index < end) {
            if (terminatedByTwoBytes) {
                if (bytes[index] == 0.toByte() && index + 1 < end && bytes[index + 1] == 0.toByte()) {
                    return index + 2
                }
                index += 2
            } else {
                if (bytes[index] == 0.toByte()) return index + 1
                index += 1
            }
        }
        return -1
    }

    private fun syncSafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7f) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
            (bytes[offset + 3].toInt() and 0x7f)
}
