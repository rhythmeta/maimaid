package org.rhythmeta.maimaid.core.chart.audio

import org.rhythmeta.maimaid.core.chart.ChartSfxKind

/** PCM samples of the SFX set at the mixer's sample rate. Implemented by the Android asset loader. */
interface SfxSampleBank {
    val sampleRate: Int

    /** Mono samples for [kind], or null when the asset is missing. */
    fun sample(kind: ChartSfxKind): ShortArray?
}

/** Read-only random access to the music PCM cache. Frames are interleaved stereo 16-bit. */
interface PcmFrameSource {
    val frameCount: Long

    /** Reads up to [frames] frames starting at [startFrame]; returns the frames actually written. */
    fun readFrames(startFrame: Long, out: ShortArray, outOffset: Int, frames: Int): Int

    fun close() = Unit
}

/** In-memory source used by JVM tests and as a fallback for very small tracks. */
class MemoryPcmFrameSource(private val samples: ShortArray) : PcmFrameSource {
    override val frameCount: Long = (samples.size / 2).toLong()

    override fun readFrames(
        startFrame: Long,
        out: ShortArray,
        outOffset: Int,
        frames: Int,
    ): Int {
        if (startFrame >= frameCount || frames <= 0) return 0
        val available = minOf(frames.toLong(), frameCount - startFrame).toInt()
        val sourceIndex = (startFrame * 2).toInt()
        System.arraycopy(samples, sourceIndex, out, outOffset, available * 2)
        return available
    }
}
