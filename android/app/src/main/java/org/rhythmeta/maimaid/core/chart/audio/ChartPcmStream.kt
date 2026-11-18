package org.rhythmeta.maimaid.core.chart.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a chart track into memory on a background thread and exposes the decoded part as a
 * [PcmFrameSource].
 *
 * Decoding a whole track takes a few seconds because MediaCodec hands back one small buffer per MP3
 * frame; blocking the preview on that would delay playback. Instead the decode runs concurrently with
 * playback: [awaitPreroll] returns as soon as a short lead is decoded (a few hundred milliseconds),
 * and the mixer simply reads whatever is available afterwards. The decoder runs many times faster
 * than real time, so it stays ahead of the playhead once it is started.
 *
 * Nothing is written to disk: the samples live in [PcmChunkBuffer] and are released with the stream.
 */
class ChartPcmStream(private val source: File) : ChartTrackSource {
    private val buffer = PcmChunkBuffer()
    private val formatLock = Object()

    @Volatile
    private var closed = false

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var complete = false

    @Volatile
    override var sampleRate: Int = 0
        private set

    @Volatile
    private var durationUs: Long = 0L

    @Volatile
    private var thread: Thread? = null

    /** Frame the decoder should restart from, or -1 when decoding continues sequentially. */
    @Volatile
    private var pendingSeekFrame: Long = -1L

    override val frameCount: Long
        get() {
            val rate = sampleRate
            if (rate > 0 && durationUs > 0) return durationUs * rate / 1_000_000
            return buffer.base / 2 + buffer.availableFrames()
        }

    override val durationMillis: Int get() = (durationUs / 1_000).toInt()

    override val error: Throwable? get() = failure

    override suspend fun awaitReady(): Boolean =
        withContext(Dispatchers.IO) {
            if (!awaitFormat(FORMAT_TIMEOUT_MS)) return@withContext false
            awaitPreroll(sampleRate.toLong() * PREROLL_MS / 1_000, PREROLL_TIMEOUT_MS)
        }

    override val isComplete: Boolean get() = complete

    /** First frame the buffer can still serve (moves forward after a decoder restart). */
    override val baseFrame: Long get() = buffer.base / 2

    /** One past the last decodable frame currently in memory. */
    override val decodedFrames: Long get() = baseFrame + buffer.availableFrames()

    override fun readFrames(startFrame: Long, out: ShortArray, outOffset: Int, frames: Int): Int =
        buffer.readFrames(startFrame, out, outOffset, frames)

    override fun start() {
        if (thread != null) return
        thread = Thread({ decodeLoop() }, "ChartPcmDecode").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    /** Waits until at least [frames] frames are decoded, the track is complete, or it failed. */
    fun awaitPreroll(frames: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (failure != null) return false
            if (buffer.availableFrames() >= frames) return true
            if (complete) return true
            if (closed) return false
            if (System.currentTimeMillis() >= deadline) return buffer.availableFrames() > 0
            synchronized(formatLock) { runCatching { formatLock.wait(5) } }
        }
    }

    /** Waits until the decoder reported its output format (sample rate). */
    fun awaitFormat(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (sampleRate == 0) {
            if (failure != null || closed) return false
            if (System.currentTimeMillis() >= deadline) return false
            synchronized(formatLock) { runCatching { formatLock.wait(5) } }
        }
        return true
    }

    /**
     * Asks the decoder to jump to [frame]. Used when a seek lands past the decoded frontier, where
     * the sequential decoder would otherwise leave the music silent until it caught up.
     */
    override fun requestSeek(frame: Long) {
        if (complete) return
        pendingSeekFrame = frame.coerceAtLeast(0L)
    }

    override fun close() {
        closed = true
        synchronized(formatLock) { formatLock.notifyAll() }
        thread?.let { runCatching { it.join(1_000) } }
        thread = null
        buffer.clear()
    }

    private fun decodeLoop() {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            require(trackIndex >= 0 && format != null) { "No audio track in ${source.name}" }
            extractor.selectTrack(trackIndex)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = format.getLong(MediaFormat.KEY_DURATION)
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("No MIME")

            val created = MediaCodec.createDecoderByType(mime)
            codec = created
            created.configure(format, null, null, 0)
            created.start()

            val info = MediaCodec.BufferInfo()
            var channels = 1
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var outputDone = false
            var idleLoops = 0
            var skipUntilUs = -1L
            while (!outputDone && !closed) {
                val seekTarget = pendingSeekFrame
                if (seekTarget >= 0 && sampleRate > 0) {
                    pendingSeekFrame = -1
                    skipUntilUs = seekTarget * 1_000_000 / sampleRate
                    runCatching { extractor.seekTo(skipUntilUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
                    runCatching { created.flush() }
                    buffer.resetAt(seekTarget)
                    inputDone = false
                    idleLoops = 0
                }
                var progressed = false
                if (!inputDone) {
                    val inputIndex = created.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val input = created.getInputBuffer(inputIndex) ?: error("No input buffer")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            created.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            created.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                val outputIndex = created.dequeueOutputBuffer(info, if (progressed) 0L else 2_000L)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = created.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        synchronized(formatLock) { formatLock.notifyAll() }
                        progressed = true
                    }
                    outputIndex >= 0 -> {
                        val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !codecConfig) {
                            val output = created.getOutputBuffer(outputIndex)
                                ?: error("No output buffer")
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            if (skipUntilUs >= 0) {
                                // Drop everything decoded from the sync point before the target.
                                if (info.presentationTimeUs >= skipUntilUs) skipUntilUs = -1L
                            }
                            if (skipUntilUs < 0) appendStereo(output, channels, encoding)
                        }
                        created.releaseOutputBuffer(outputIndex, false)
                        if (endOfStream) outputDone = true
                        progressed = true
                    }
                }
                if (!progressed) {
                    idleLoops++
                    if (inputDone && idleLoops > MAX_IDLE_LOOPS) {
                        error("Decoder stalled after end of stream")
                    }
                } else {
                    idleLoops = 0
                }
            }
            complete = true
            Log.i(
                TAG,
                "decoded ${source.name}: sr=$sampleRate frames=${buffer.availableFrames()} " +
                    "bytes=${buffer.availableSamples() * 2}",
            )
        } catch (error: Throwable) {
            if (!closed) {
                failure = error
                Log.w(TAG, "decode failed for ${source.name}", error)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            synchronized(formatLock) { formatLock.notifyAll() }
        }
    }

    /** Converts one decoder output buffer into interleaved stereo and appends it. */
    private fun appendStereo(
        output: java.nio.ByteBuffer,
        channels: Int,
        encoding: Int,
    ) {
        val channelCount = max(channels, 1)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = output.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val frames = floats.remaining() / channelCount
            val stereo = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channelCount) {
                    sum += floats.get(frame * channelCount + channel)
                }
                val sample = ((sum / channelCount) * 32767f).toInt().coerceIn(-32768, 32767)
                stereo[frame * 2] = sample.toShort()
                stereo[frame * 2 + 1] = sample.toShort()
            }
            buffer.append(stereo, 0, stereo.size)
            return
        }
        val shorts = output.order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = shorts.remaining() / channelCount
        val stereo = ShortArray(frames * 2)
        if (channelCount == 2) {
            shorts.get(stereo, 0, frames * 2)
        } else {
            for (frame in 0 until frames) {
                val sample = if (channelCount == 1) {
                    shorts.get(frame)
                } else {
                    var sum = 0
                    for (channel in 0 until channelCount) {
                        sum += shorts.get(frame * channelCount + channel).toInt()
                    }
                    (sum / channelCount).toShort()
                }
                stereo[frame * 2] = sample
                stereo[frame * 2 + 1] = sample
            }
        }
        buffer.append(stereo, 0, stereo.size)
    }

    companion object {
        private const val TAG = "ChartPcmStream"
        private const val MAX_IDLE_LOOPS = 2_000
        private const val FORMAT_TIMEOUT_MS = 3_000L
        private const val PREROLL_TIMEOUT_MS = 3_000L

        /** Lead time decoded before playback is allowed to start. */
        const val PREROLL_MS = 400L
    }
}
