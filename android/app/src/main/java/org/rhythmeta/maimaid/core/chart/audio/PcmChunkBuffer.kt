package org.rhythmeta.maimaid.core.chart.audio

import kotlin.math.min

/**
 * Thread-safe, append-only store for interleaved stereo 16-bit samples.
 *
 * The decode thread appends while the mix thread reads, so every access is guarded by one lock; both
 * operations are pure array copies, which keeps the mix thread's critical section far below a buffer.
 */
class PcmChunkBuffer(private val chunkSamples: Int = DEFAULT_CHUNK_SAMPLES) {
    private val lock = Any()
    private val chunks = ArrayList<ShortArray>()
    private var current = ShortArray(chunkSamples)
    private var used = 0
    private var totalSamples = 0L
    private var baseSample = 0L

    /** Samples currently available (interleaved, so two per frame). */
    fun availableSamples(): Long = synchronized(lock) { totalSamples }

    fun availableFrames(): Long = availableSamples() / 2

    /** First sample kept in the buffer; non-zero after a decoder restart at a seek target. */
    val base: Long get() = synchronized(lock) { baseSample }

    fun append(source: ShortArray, offset: Int, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            var read = offset
            var remaining = count
            while (remaining > 0) {
                if (used == current.size) {
                    chunks += current
                    current = ShortArray(chunkSamples)
                    used = 0
                }
                val copy = min(remaining, current.size - used)
                System.arraycopy(source, read, current, used, copy)
                used += copy
                read += copy
                remaining -= copy
                totalSamples += copy
            }
        }
    }

    fun readFrames(startFrame: Long, out: ShortArray, outOffset: Int, frames: Int): Int {
        if (frames <= 0) return 0
        synchronized(lock) {
            val startSample = startFrame * 2 - baseSample
            if (startSample < 0 || startSample >= totalSamples) return 0
            val available = min(frames.toLong() * 2, totalSamples - startSample).toInt()
            var sample = startSample
            var destination = outOffset
            var remaining = available
            while (remaining > 0) {
                val chunkIndex = (sample / chunkSamples).toInt()
                val withinChunk = (sample % chunkSamples).toInt()
                val chunk = if (chunkIndex < chunks.size) chunks[chunkIndex] else current
                val chunkAvailable = if (chunkIndex < chunks.size) {
                    chunk.size
                } else {
                    used
                }
                if (withinChunk >= chunkAvailable) break
                val copy = min(remaining, chunkAvailable - withinChunk)
                System.arraycopy(chunk, withinChunk, out, destination, copy)
                sample += copy
                destination += copy
                remaining -= copy
            }
            return available / 2
        }
    }

    fun clear() {
        synchronized(lock) {
            chunks.clear()
            current = ShortArray(chunkSamples)
            used = 0
            totalSamples = 0
            baseSample = 0
        }
    }

    /** Drops everything and makes [startFrame] the first frame this buffer can serve. */
    fun resetAt(startFrame: Long) {
        synchronized(lock) {
            chunks.clear()
            current = ShortArray(chunkSamples)
            used = 0
            totalSamples = 0
            baseSample = startFrame * 2
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SAMPLES = 1 shl 15
    }
}
