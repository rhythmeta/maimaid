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

    /** False while a background decoder may still deliver frames (used for starvation metrics). */
    val isComplete: Boolean get() = true

    /** Reads up to [frames] frames starting at [startFrame]; returns the frames actually written. */
    fun readFrames(startFrame: Long, out: ShortArray, outOffset: Int, frames: Int): Int

    fun close() = Unit
}

/** Live download statistics of a track that is still being fetched. */
data class TrackDownloadProgress(
    val receivedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
) {
    /** 0..100; 0 when the total size is unknown. */
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((receivedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/**
 * Music track that can be prepared asynchronously (from a cached file or straight off the network)
 * and then read by the mixer while it is still being decoded.
 */
interface ChartTrackSource : PcmFrameSource {
    val sampleRate: Int
    val durationMillis: Int
    val error: Throwable?

    /** One past the last frame currently readable (grows while decoding). */
    val decodedFrames: Long get() = frameCount

    /** First frame still held in memory; moves forward after a decoder restart on seek. */
    val baseFrame: Long get() = 0

    /** Starts the background decode/download. Idempotent. */
    fun start()

    /** Asks a still-running decoder to restart at [frame] (used for seeks past the frontier). */
    fun requestSeek(frame: Long) = Unit

    /** Download statistics while the track is still arriving, or null for a cached file. */
    val downloadProgress: TrackDownloadProgress? get() = null

    /** Suspends until the format is known and [ChartPcmStream.PREROLL_MS] of audio is decoded. */
    suspend fun awaitReady(): Boolean
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
