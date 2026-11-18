package org.rhythmeta.maimaid.core.chart.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Decodes a downloaded chart track into a cached, raw stereo 16-bit PCM file.
 *
 * The cache lives next to the MP3 in `filesDir/chart-audio` and is named
 * `<sha256>.v1.<sampleRate>.s16le.stereo.pcm` with a small `.meta` sidecar. The MP3 itself keeps its
 * existing `<sha256>.<ext>` name, so `ChartAudioCacheIntegrity` and the cover-art / save-package code
 * are untouched.
 *
 * The PCM starts at the decoder's first output sample. That is deliberately not trimmed: MediaPlayer
 * plays the same MediaCodec output from sample 0, so the two paths share the same frame-zero
 * reference and `musicOffsetMs` keeps its meaning.
 *
 * The audio is kept at the track's native sample rate (44.1 kHz for most community MP3s); SFX assets
 * are resampled to that rate by [ChartSfxSampleBank], which is cheap because they are short.
 *
 * The whole `chart-audio` directory is bounded to [ChartAudioCacheLru.MAX_TRACKS] songs, so the MP3
 * and PCM files of a song are evicted together.
 */
object ChartPcmCache {
    data class CachedPcm(
        val file: File,
        val sampleRate: Int,
        val channels: Int,
        val frameCount: Long,
    )

    private const val TAG = "ChartPcmCache"
    private const val FORMAT_VERSION = 1
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val MAX_IDLE_LOOPS = 2_000
    private val decodeLocks = ConcurrentHashMap<String, Mutex>()

    fun cacheDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "chart-audio").apply { mkdirs() }

    /** Returns the valid PCM cache for [sourceSha256], or null when it has not been built yet. */
    fun cached(context: Context, sourceSha256: String): CachedPcm? {
        val directory = cacheDirectory(context)
        val prefix = "${sourceSha256.lowercase()}.v$FORMAT_VERSION."
        return directory.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".meta") }
            ?.firstNotNullOfOrNull { meta -> readValid(directory, meta) }
    }

    /**
     * Ensures a PCM cache exists for [source]. Blocking work runs on [Dispatchers.IO]; a partially
     * written cache is never visible because the file is renamed only after a successful decode.
     */
    suspend fun ensure(
        context: Context,
        source: File,
        sourceSha256: String,
        onProgress: (Float) -> Unit = {},
    ): CachedPcm {
        val sha = sourceSha256.lowercase()
        cached(context, sha)?.let { return it }
        return withContext(Dispatchers.IO) {
            // Serialize decodes of the same track: a fast back-and-forth between previews can
            // otherwise start two decoders writing the same temp file.
            val lock = decodeLocks.computeIfAbsent(sha) { Mutex() }
            lock.withLock {
                cached(context, sha)?.let { return@withLock it }
                val directory = cacheDirectory(context)
                val temporary = File(directory, "$sha.v$FORMAT_VERSION.tmp")
                var decoded: Decoded?
                try {
                    decoded = decode(source, temporary, onProgress) {
                        coroutineContext.ensureActive()
                    }
                val finalFile = File(
                    directory,
                    "$sha.v$FORMAT_VERSION.${decoded.sampleRate}.s16le.stereo.pcm",
                )
                if (finalFile.exists() && !finalFile.delete()) {
                    error("Unable to replace ${finalFile.name}")
                }
                if (!temporary.renameTo(finalFile)) {
                    // Rename can fail across file systems; fall back to copying.
                    temporary.inputStream().use { input ->
                        finalFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    temporary.delete()
                }
                writeMeta(directory, sha, decoded, source)
                // chart-audio/ keeps at most five songs; this track is the one just used.
                ChartAudioCacheLru.prune(directory, keepKeys = setOf(sha))
                coroutineContext.ensureActive()
                Log.i(
                    TAG,
                    "cached ${finalFile.name}: sr=${decoded.sampleRate} frames=${decoded.frameCount} " +
                        "bytes=${finalFile.length()}",
                )
                    CachedPcm(finalFile, decoded.sampleRate, 2, decoded.frameCount)
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
        }
    }

    private class Decoded(val sampleRate: Int, val frameCount: Long)

    private fun decode(
        source: File,
        destination: File,
        onProgress: (Float) -> Unit,
        isActive: () -> Unit,
    ): Decoded {
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
            var inputDone = false
            var outputDone = false
            var sampleRate = 0
            var channels = 0
            var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            var frameCount = 0L
            var idleLoops = 0
            BufferedOutputStream(FileOutputStream(destination), 1 shl 16).use { output ->
                var loopCount = 0
                while (!outputDone) {
                    if (++loopCount % 64 == 0) isActive()
                    if (!inputDone) {
                        val inputIndex = created.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val input = created.getInputBuffer(inputIndex)
                                ?: error("No input buffer")
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
                                android.media.AudioFormat.ENCODING_PCM_16BIT
                            }
                            idleLoops = 0
                        }
                        outputIndex >= 0 -> {
                            idleLoops = 0
                            val endOfStream =
                                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val codecConfig =
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !codecConfig) {
                                val buffer = created.getOutputBuffer(outputIndex)
                                    ?: error("No output buffer")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                require(sampleRate > 0 && channels > 0) {
                                    "Decoder produced PCM before its output format"
                                }
                                frameCount += writeStereo(output, buffer, encoding, channels)
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
            }
            require(sampleRate > 0 && frameCount > 0) { "Decoder produced no PCM" }
            onProgress(1f)
            return Decoded(sampleRate, frameCount)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Converts one decoder output buffer into interleaved stereo 16-bit and returns its frames. */
    private fun writeStereo(
        output: BufferedOutputStream,
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
    ): Long {
        val frames: Int
        val stereo: ShortArray
        if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val samples = floats.remaining()
            frames = samples / channels.coerceAtLeast(1)
            stereo = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) {
                    sum += floats.get(frame * channels + channel)
                }
                val sample = ((sum / channels) * 32767f).toInt().coerceIn(-32768, 32767)
                stereo[frame * 2] = sample.toShort()
                stereo[frame * 2 + 1] = sample.toShort()
            }
        } else {
            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            val samples = shorts.remaining()
            frames = samples / channels.coerceAtLeast(1)
            stereo = ShortArray(frames * 2)
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
        }
        val bytes = ByteBuffer.allocate(stereo.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(stereo)
        output.write(bytes.array())
        return frames.toLong()
    }

    private fun writeMeta(directory: File, sha: String, decoded: Decoded, source: File) {
        val meta = File(
            directory,
            "$sha.v$FORMAT_VERSION.${decoded.sampleRate}.s16le.stereo.meta",
        )
        val temporary = File(meta.absolutePath + ".tmp")
        temporary.writeText(
            buildString {
                appendLine("version=$FORMAT_VERSION")
                appendLine("sampleRate=${decoded.sampleRate}")
                appendLine("channels=2")
                appendLine("frames=${decoded.frameCount}")
                appendLine("sourceSha256=$sha")
                appendLine("sourceSize=${source.length()}")
            },
        )
        if (!temporary.renameTo(meta)) {
            meta.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private fun readValid(directory: File, meta: File): CachedPcm? {
        return runCatching {
            val values = meta.readLines()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
            if (values["version"]?.toIntOrNull() != FORMAT_VERSION) return null
            val sampleRate = values["sampleRate"]?.toIntOrNull() ?: return null
            val channels = values["channels"]?.toIntOrNull() ?: return null
            val frames = values["frames"]?.toLongOrNull() ?: return null
            if (sampleRate <= 0 || channels <= 0 || frames <= 0) return null
            val pcmName = meta.name.removeSuffix(".meta") + ".pcm"
            val pcm = File(directory, pcmName)
            if (!pcm.isFile) return null
            if (pcm.length() != frames * channels * 2) return null
            CachedPcm(pcm, sampleRate, channels, frames)
        }.getOrNull()
    }

}
