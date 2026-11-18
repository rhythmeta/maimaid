package org.rhythmeta.maimaid.core.chart.audio

import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Read-only, memory-mapped music PCM cache.
 *
 * The mapping is created once per track; mixing only reads page-cache memory, so the audio thread
 * never performs file I/O or allocation. Frames are little-endian 16-bit; the cache is always stereo
 * but mono files are accepted defensively.
 */
class MappedPcmFrameSource(
    file: File,
    override val frameCount: Long,
    val sampleRate: Int,
    val channels: Int,
) : PcmFrameSource {
    private var mapping = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        require(size > 0) { "Empty PCM cache ${file.name}" }
        require(size >= frameCount * channels * 2) {
            "PCM cache ${file.name} is shorter than its $frameCount frames"
        }
        channel.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun readFrames(
        startFrame: Long,
        out: ShortArray,
        outOffset: Int,
        frames: Int,
    ): Int {
        val buffer = mapping ?: return 0
        if (startFrame >= frameCount || frames <= 0 || channels <= 0) return 0
        val available = minOf(frames.toLong(), frameCount - startFrame).toInt()
        var sourceIndex = (startFrame * channels * 2).toInt()
        var destination = outOffset
        if (channels == 2) {
            for (frame in 0 until available) {
                out[destination] = buffer.getShort(sourceIndex)
                out[destination + 1] = buffer.getShort(sourceIndex + 2)
                sourceIndex += 4
                destination += 2
            }
        } else if (channels == 1) {
            for (frame in 0 until available) {
                val sample = buffer.getShort(sourceIndex)
                out[destination] = sample
                out[destination + 1] = sample
                sourceIndex += 2
                destination += 2
            }
        } else {
            for (frame in 0 until available) {
                var sum = 0
                for (channel in 0 until channels) {
                    sum += buffer.getShort(sourceIndex + channel * 2).toInt()
                }
                val sample = (sum / channels).toShort()
                out[destination] = sample
                out[destination + 1] = sample
                sourceIndex += channels * 2
                destination += 2
            }
        }
        return available
    }

    override fun close() {
        mapping = null
    }
}
