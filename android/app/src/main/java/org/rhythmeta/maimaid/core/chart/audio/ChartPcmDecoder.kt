package org.rhythmeta.maimaid.core.chart.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Decodes a chart track into interleaved stereo 16-bit PCM held in memory.
 *
 * Nothing is written to disk: the decoded samples live in a `ShortArray` owned by the mixer and are
 * dropped when the preview closes, so `filesDir/chart-audio` only ever stores the downloaded MP3.
 * A full decode takes well under a second to a few seconds, which happens while the preview shows
 * its loading stage.
 *
 * The PCM starts at the decoder's first output sample. Decoder priming is deliberately not trimmed,
 * so frame 0 keeps the same reference as the published track and `musicOffsetMs` keeps its meaning.
 */
object ChartPcmDecoder {
    private const val TAG = "ChartPcmDecoder"
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val MAX_IDLE_LOOPS = 2_000
    private const val CHUNK_SAMPLES = 1 shl 16

    /** Hard ceiling for one decoded track (96 MB ≈ 8.5 minutes at 48 kHz stereo). */
    const val MAX_PCM_BYTES = 96L * 1024 * 1024

    class DecodedPcm(val samples: ShortArray, val sampleRate: Int) {
        val frameCount: Long get() = (samples.size / 2).toLong()
    }

    /**
     * Decodes [source] on [Dispatchers.IO]. Cancellation stops the decoder and releases it without
     * leaving anything behind; an over-long track fails with a clear message instead of an OOM.
     */
    suspend fun decode(
        source: File,
        onProgress: (Float) -> Unit = {},
    ): DecodedPcm = withContext(Dispatchers.IO) {
        decodeBlocking(source, onProgress) { coroutineContext.ensureActive() }
    }

    private fun decodeBlocking(
        source: File,
        onProgress: (Float) -> Unit,
        isActive: () -> Unit,
    ): DecodedPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
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
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("No MIME")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val created = MediaCodec.createDecoderByType(mime)
            codec = created
            created.configure(format, null, null, 0)
            created.start()

            val info = MediaCodec.BufferInfo()
            val chunks = ArrayList<ShortArray>(64)
            var chunk = ShortArray(CHUNK_SAMPLES)
            var chunkSize = 0
            var totalSamples = 0L
            var sampleRate = 0
            var channels = 0
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var outputDone = false
            var idleLoops = 0
            var loopCount = 0

            while (!outputDone) {
                if (++loopCount % 64 == 0) isActive()
                if (!inputDone) {
                    val inputIndex = created.dequeueInputBuffer(CODEC_TIMEOUT_US)
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
                            val sampleTime = extractor.sampleTime
                            created.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                            extractor.advance()
                            if (durationUs > 0) {
                                onProgress(
                                    (sampleTime.toFloat() / durationUs.toFloat())
                                        .coerceIn(0f, 0.99f),
                                )
                            }
                        }
                    }
                }
                val outputIndex = created.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
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
                        idleLoops = 0
                    }
                    outputIndex >= 0 -> {
                        idleLoops = 0
                        val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !codecConfig) {
                            val buffer = created.getOutputBuffer(outputIndex)
                                ?: error("No output buffer")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            require(sampleRate > 0 && channels > 0) {
                                "Decoder produced PCM before its output format"
                            }
                            val stereo = readStereo(buffer, encoding, channels)
                            var offset = 0
                            while (offset < stereo.size) {
                                val count = minOf(CHUNK_SAMPLES - chunkSize, stereo.size - offset)
                                System.arraycopy(stereo, offset, chunk, chunkSize, count)
                                chunkSize += count
                                offset += count
                                totalSamples += count
                                check(totalSamples * 2 <= MAX_PCM_BYTES) {
                                    "Decoded track exceeds ${MAX_PCM_BYTES / (1024 * 1024)} MB"
                                }
                                if (chunkSize == CHUNK_SAMPLES) {
                                    chunks += chunk
                                    chunk = ShortArray(CHUNK_SAMPLES)
                                    chunkSize = 0
                                }
                            }
                        }
                        created.releaseOutputBuffer(outputIndex, false)
                        if (endOfStream) outputDone = true
                    }
                    else -> {
                        if (inputDone) {
                            idleLoops++
                            if (idleLoops > MAX_IDLE_LOOPS) {
                                error("Decoder stalled after end of stream")
                            }
                        }
                    }
                }
            }
            require(sampleRate > 0 && totalSamples > 0) { "Decoder produced no PCM" }
            onProgress(1f)
            val samples = ShortArray(totalSamples.toInt())
            var written = 0
            for (index in chunks.indices) {
                val stored = chunks[index]
                System.arraycopy(stored, 0, samples, written, stored.size)
                written += stored.size
            }
            if (chunkSize > 0) System.arraycopy(chunk, 0, samples, written, chunkSize)
            Log.i(
                TAG,
                "decoded ${source.name}: sr=$sampleRate frames=${samples.size / 2} " +
                    "bytes=${samples.size * 2}",
            )
            DecodedPcm(samples, sampleRate)
        } catch (error: CancellationException) {
            throw error
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Converts one decoder output buffer into interleaved stereo 16-bit samples. */
    private fun readStereo(buffer: java.nio.ByteBuffer, encoding: Int, channels: Int): ShortArray {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val frames = floats.remaining() / channels.coerceAtLeast(1)
            val stereo = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) {
                    sum += floats.get(frame * channels + channel)
                }
                val sample = ((sum / channels) * 32767f).toInt().coerceIn(-32768, 32767)
                stereo[frame * 2] = sample.toShort()
                stereo[frame * 2 + 1] = sample.toShort()
            }
            return stereo
        }
        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = shorts.remaining() / channels.coerceAtLeast(1)
        val stereo = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            if (channels == 1) {
                val sample = shorts.get(frame)
                stereo[frame * 2] = sample
                stereo[frame * 2 + 1] = sample
            } else {
                var sum = 0
                for (channel in 0 until channels) {
                    sum += shorts.get(frame * channels + channel).toInt()
                }
                val sample = (sum / channels).toShort()
                stereo[frame * 2] = sample
                stereo[frame * 2 + 1] = sample
            }
        }
        return stereo
    }
}
