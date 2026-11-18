package org.rhythmeta.maimaid.core.chart.audio

/**
 * Splits a byte stream of MPEG-1/2 Layer I–III audio into whole MP3 frames so they can be fed to
 * MediaCodec while the file is still downloading.
 *
 * MediaExtractor cannot read a file that is still growing, so a streaming player has to find frame
 * boundaries itself: the 11-bit sync word plus the version/layer/bitrate/sample-rate tables give both
 * the frame size and the format. A leading ID3v2 tag is skipped, and bytes that do not parse are
 * dropped until the next sync word.
 */
class Mp3FrameParser(private val onFrame: (ByteArray) -> Unit) {
    data class Format(val sampleRate: Int, val channels: Int)

    /** Format of the first valid frame, or null before one was seen. */
    var format: Format? = null
        private set

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var id3BytesToSkip = 0

    /** Bytes buffered because they do not yet form a complete frame header/frame. */
    val pendingBytes: Int get() = size

    fun consume(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        append(bytes, offset, length)
        var position = 0
        while (true) {
            if (id3BytesToSkip > 0) {
                val drop = minOf(id3BytesToSkip, size - position)
                position += drop
                id3BytesToSkip -= drop
                if (id3BytesToSkip > 0) break
                continue
            }
            if (size - position < 10) break
            val tagSize = id3v2Size(position)
            if (tagSize > 0) {
                id3BytesToSkip = tagSize
                continue
            }
            val header = headerAt(position, size - position) ?: break
            if (header.advance > 0) {
                position += header.advance
                continue
            }
            if (size - position < header.frameLength) break
            val frame = ByteArray(header.frameLength)
            System.arraycopy(buffer, position, frame, 0, header.frameLength)
            position += header.frameLength
            onFrame(frame)
        }
        compact(position)
    }

    /** Drops a trailing partial frame when the stream ended. */
    fun finish() {
        size = 0
    }

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (size + length > buffer.size) {
            var capacity = buffer.size
            while (capacity < size + length) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        System.arraycopy(bytes, offset, buffer, size, length)
        size += length
    }

    private fun compact(consumed: Int) {
        if (consumed <= 0) return
        val remaining = size - consumed
        System.arraycopy(buffer, consumed, buffer, 0, remaining)
        size = remaining
    }

    /** Header parse result: either a viable frame, or how many bytes to skip before retrying. */
    private class Parsed(val frameLength: Int, val advance: Int)

    private fun headerAt(offset: Int, available: Int): Parsed? {
        var position = offset
        while (position + 1 < offset + available) {
            if (buffer[position].toInt() and 0xFF == 0xFF &&
                buffer[position + 1].toInt() and 0xE0 == 0xE0
            ) {
                break
            }
            position++
        }
        // Junk before the sync word can be discarded right away.
        if (position > offset) return Parsed(0, position - offset)
        if (available < 4) return null

        val b1 = buffer[offset + 1].toInt() and 0xFF
        val b2 = buffer[offset + 2].toInt() and 0xFF
        val b3 = buffer[offset + 3].toInt() and 0xFF
        val versionBits = (b1 shr 3) and 0x03
        val layerBits = (b1 shr 1) and 0x03
        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01

        val version = when (versionBits) {
            0 -> VERSION_25
            2 -> VERSION_2
            3 -> VERSION_1
            else -> return Parsed(0, 1)
        }
        val layer = when (layerBits) {
            1 -> 3
            2 -> 2
            3 -> 1
            else -> return Parsed(0, 1)
        }
        if (bitrateIndex == 0 || bitrateIndex == 0x0F || sampleRateIndex == 3) {
            return Parsed(0, 1)
        }
        val bitrate = bitrateKbps(version, layer, bitrateIndex) ?: return Parsed(0, 1)
        val sampleRate = sampleRate(version, sampleRateIndex) ?: return Parsed(0, 1)
        val frameLength = when {
            layer == 1 -> (12 * bitrate * 1000 / sampleRate + padding) * 4
            layer == 2 -> 144 * bitrate * 1000 / sampleRate + padding
            version == VERSION_1 -> 144 * bitrate * 1000 / sampleRate + padding
            else -> 72 * bitrate * 1000 / sampleRate + padding
        }
        if (frameLength < 24) return Parsed(0, 1)

        val channels = if ((b3 shr 6) and 0x03 == 3) 1 else 2
        if (format == null) format = Format(sampleRate, channels)
        return Parsed(frameLength, 0)
    }

    private fun id3v2Size(offset: Int): Int {
        if (buffer[offset].toInt() != 'I'.code) return 0
        if (buffer[offset + 1].toInt() != 'D'.code) return 0
        if (buffer[offset + 2].toInt() != '3'.code) return 0
        val flags = buffer[offset + 5].toInt() and 0xFF
        val declared = ((buffer[offset + 6].toInt() and 0x7F) shl 21) or
            ((buffer[offset + 7].toInt() and 0x7F) shl 14) or
            ((buffer[offset + 8].toInt() and 0x7F) shl 7) or
            (buffer[offset + 9].toInt() and 0x7F)
        val footer = if (flags and 0x10 != 0) 10 else 0
        return 10 + declared + footer
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1_024
        const val VERSION_25 = 0
        const val VERSION_2 = 2
        const val VERSION_1 = 3

        val BITRATES_V1_LAYER3 = intArrayOf(
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0,
        )
        val BITRATES_V2_LAYER3 = intArrayOf(
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0,
        )
        val BITRATES_V1_LAYER2 = intArrayOf(
            0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0,
        )
        val BITRATES_V2_LAYER2 = intArrayOf(
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0,
        )
        val BITRATES_V1_LAYER1 = intArrayOf(
            0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0,
        )
        val SAMPLE_RATES: Map<Int, IntArray> = mapOf(
            VERSION_1 to intArrayOf(44_100, 48_000, 32_000),
            VERSION_2 to intArrayOf(22_050, 24_000, 16_000),
            VERSION_25 to intArrayOf(11_025, 12_000, 8_000),
        )

        fun bitrateKbps(version: Int, layer: Int, index: Int): Int? {
            val table = when {
                layer == 1 -> BITRATES_V1_LAYER1
                layer == 2 -> if (version == VERSION_1) BITRATES_V1_LAYER2 else BITRATES_V2_LAYER2
                version == VERSION_1 -> BITRATES_V1_LAYER3
                else -> BITRATES_V2_LAYER3
            }
            return table.getOrNull(index)?.takeIf { it > 0 }
        }

        fun sampleRate(version: Int, index: Int): Int? =
            SAMPLE_RATES[version]?.getOrNull(index)
    }
}
