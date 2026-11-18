package org.rhythmeta.maimaid.core.chart.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Plays a chart track that is still downloading: bytes arrive from the network, [Mp3FrameParser]
 * turns them into whole frames, and a decoder thread feeds MediaCodec and appends PCM into
 * [PcmChunkBuffer] for the mixer.
 *
 * MediaExtractor cannot follow a growing file, so frames are handed to MediaCodec directly. The
 * frame queue is bounded, which back-pressures the download instead of buffering the track twice.
 *
 * @param onDownload runs the transfer; it must push every received chunk into the supplied sink and
 * return when the stream is complete.
 */
class ChartStreamingTrack(
    private val onDownload: suspend (ChunkSink) -> Unit,
) : ChartTrackSource {
    fun interface ChunkSink {
        fun onChunk(bytes: ByteArray, offset: Int, length: Int)
    }

    private val buffer = PcmChunkBuffer()
    private val frames = LinkedBlockingQueue<ByteArray>(FRAME_QUEUE_CAPACITY)
    private val lock = Object()

    @Volatile
    private var closed = false

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var complete = false

    @Volatile
    private var downloadDone = false

    @Volatile
    override var sampleRate: Int = 0
        private set

    @Volatile
    private var channelCount = 2

    @Volatile
    private var downloadThread: Thread? = null

    @Volatile
    private var decodeThread: Thread? = null

    private var framesSeen = 0L

    override val frameCount: Long
        get() {
            if (sampleRate > 0) return framesSeen * SAMPLES_PER_FRAME
            return buffer.availableFrames()
        }

    override val durationMillis: Int
        get() {
            val rate = sampleRate
            if (rate <= 0) return 0
            return (framesSeen * SAMPLES_PER_FRAME * 1_000 / rate).toInt()
        }

    override val error: Throwable? get() = failure

    override val isComplete: Boolean get() = complete

    override fun readFrames(startFrame: Long, out: ShortArray, outOffset: Int, frames: Int): Int =
        buffer.readFrames(startFrame, out, outOffset, frames)

    override fun start() {
        if (downloadThread != null) return
        decodeThread = Thread({ decodeLoop() }, "ChartStreamDecode").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
            start()
        }
        downloadThread = Thread({ downloadLoop() }, "ChartStreamDownload").apply {
            isDaemon = true
            start()
        }
    }

    override suspend fun awaitReady(): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (true) {
            if (failure != null) return@withContext false
            val rate = sampleRate
            if (rate > 0 && buffer.availableFrames() >= rate.toLong() * PREROLL_MS / 1_000) {
                return@withContext true
            }
            if (complete || closed) return@withContext buffer.availableFrames() > 0
            if (System.currentTimeMillis() >= deadline) {
                // A slow network may not deliver a full preroll in time; start with what we have
                // rather than failing the preview.
                return@withContext buffer.availableFrames() > 0
            }
            synchronized(lock) { runCatching { lock.wait(10) } }
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        offerEos()
        downloadThread?.let { runCatching { it.join(500) } }
        decodeThread?.let { runCatching { it.join(1_000) } }
        downloadThread = null
        decodeThread = null
        buffer.clear()
    }

    private fun downloadLoop() {
        val parser = Mp3FrameParser { frame ->
            framesSeen++
            // Blocking offer: back-pressures the download instead of growing without bound.
            while (!closed) {
                if (frames.offer(frame, 100, TimeUnit.MILLISECONDS)) break
            }
            synchronized(lock) { lock.notifyAll() }
        }
        try {
            runBlocking {
                onDownload { bytes, offset, length ->
                    // Abort the transfer as soon as the preview is gone instead of downloading a
                    // whole track nobody will hear.
                    if (closed) throw CancellationException("Chart stream closed")
                    parser.consume(bytes, offset, length)
                }
            }
            downloadDone = true
        } catch (error: Throwable) {
            if (!closed) failure = error
            Log.w(TAG, "stream download failed", error)
        } finally {
            offerEos()
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /**
     * Queues end-of-stream, waiting for space: the queue is usually full at that moment, and a plain
     * `offer` would silently drop the marker so the decoder would never finish.
     */
    private fun offerEos() {
        var attempts = 0
        while (attempts++ < 100) {
            if (frames.offer(EOS, 50, TimeUnit.MILLISECONDS)) return
        }
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        try {
            // Wait for the first frame so the decoder can be configured with the real format.
            var firstFrame: ByteArray? = null
            while (firstFrame == null && !closed) {
                val frame = frames.poll(100, TimeUnit.MILLISECONDS)
                if (frame === EOS) break
                firstFrame = frame
            }
            val head = firstFrame
            if (head == null) {
                if (!closed) failure = IllegalStateException("No MP3 frames in the stream")
                return
            }
            val parsedFormat = probeFormat(head) ?: error("Unable to parse the MP3 header")
            sampleRate = parsedFormat.sampleRate
            channelCount = parsedFormat.channels
            synchronized(lock) { lock.notifyAll() }

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_MPEG,
                parsedFormat.sampleRate,
                parsedFormat.channels,
            ).apply { setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES) }
            val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
            codec = created
            created.configure(format, null, null, 0)
            created.start()

            val info = MediaCodec.BufferInfo()
            val frameDurationUs = SAMPLES_PER_FRAME * 1_000_000L / parsedFormat.sampleRate
            var pending: ByteArray? = head
            var endOfStreamSent = false
            var presentationUs = frameDurationUs
            var idleLoops = 0

            while (!closed) {
                var progressed = false
                // Dequeue an input buffer only while a frame is ready: a dequeued buffer that is
                // never queued back is lost by the codec, which then stalls forever.
                if (!endOfStreamSent && pending == null) {
                    pending = frames.poll(20, TimeUnit.MILLISECONDS)
                }
                if (!endOfStreamSent && pending != null) {
                    val inputIndex = created.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val input = created.getInputBuffer(inputIndex) ?: error("No input buffer")
                        val frame = pending
                        pending = null
                        if (frame === EOS) {
                            created.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            endOfStreamSent = true
                        } else if (frame != null) {
                            input.clear()
                            input.put(frame)
                            created.queueInputBuffer(inputIndex, 0, frame.size, presentationUs, 0)
                            presentationUs += frameDurationUs
                        }
                        progressed = true
                    }
                }

                val outputIndex = created.dequeueOutputBuffer(info, if (progressed) 0L else 5_000L)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> progressed = true
                    outputIndex >= 0 -> {
                        val endOfStream =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !codecConfig) {
                            val output = created.getOutputBuffer(outputIndex)
                                ?: error("No output buffer")
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            appendStereo(output, channelCount)
                            synchronized(lock) { lock.notifyAll() }
                        }
                        created.releaseOutputBuffer(outputIndex, false)
                        if (endOfStream) {
                            complete = true
                            synchronized(lock) { lock.notifyAll() }
                            break
                        }
                        progressed = true
                    }
                }
                if (!progressed) {
                    idleLoops++
                    if (idleLoops > MAX_IDLE_LOOPS && downloadDone) {
                        complete = true
                        break
                    }
                } else {
                    idleLoops = 0
                }
            }
            if (!closed && failure == null) complete = true
            Log.i(
                TAG,
                "streamed: sr=$sampleRate frames=${buffer.availableFrames()} " +
                    "duration=${durationMillis}ms complete=$complete",
            )
        } catch (error: Throwable) {
            if (!closed) {
                failure = error
                Log.w(TAG, "stream decode failed", error)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            synchronized(lock) { lock.notifyAll() }
        }
    }

    private fun probeFormat(frame: ByteArray): Mp3FrameParser.Format? {
        val probe = Mp3FrameParser { }
        probe.consume(frame, 0, frame.size)
        return probe.format
    }

    private fun appendStereo(output: java.nio.ByteBuffer, channels: Int) {
        val channelCount = channels.coerceAtLeast(1)
        val shorts = output.order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = shorts.remaining() / channelCount
        val stereo = ShortArray(frames * 2)
        if (channelCount == 2) {
            shorts.get(stereo, 0, frames * 2)
        } else {
            for (frame in 0 until frames) {
                val sample = shorts.get(frame)
                stereo[frame * 2] = sample
                stereo[frame * 2 + 1] = sample
            }
        }
        buffer.append(stereo, 0, stereo.size)
    }

    private companion object {
        private const val TAG = "ChartStreamingTrack"
        private const val FRAME_QUEUE_CAPACITY = 96
        private const val MAX_INPUT_BYTES = 64 * 1024
        private const val MAX_IDLE_LOOPS = 400
        private const val READY_TIMEOUT_MS = 8_000L
        private const val PREROLL_MS = 400L
        private const val SAMPLES_PER_FRAME = 1_152
        private val EOS = ByteArray(0)
    }
}
