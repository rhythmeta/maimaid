package org.rhythmeta.maimaid.core.chart.audio

import android.content.res.AssetManager
import android.util.Log
import java.io.DataInputStream
import org.rhythmeta.maimaid.core.chart.ChartSfxKind

/**
 * Loads the chart's judge / answer WAV assets into mono PCM at the mixer's sample rate.
 *
 * The shipped assets are 48 kHz mono 16-bit, so the common path is a plain RIFF parse with no
 * conversion. Other rates are accepted with a linear interpolation fallback so a future asset swap
 * cannot silence the layer.
 */
class ChartSfxSampleBank private constructor(
    override val sampleRate: Int,
    private val samples: Map<ChartSfxKind, ShortArray>,
) : SfxSampleBank {
    override fun sample(kind: ChartSfxKind): ShortArray? = samples[kind]

    companion object {
        private const val TAG = "ChartSfxSampleBank"

        fun load(assets: AssetManager, sampleRate: Int = 48_000): ChartSfxSampleBank {
            val loaded = HashMap<ChartSfxKind, ShortArray>()
            for (kind in ChartSfxKind.entries) {
                try {
                    val parsed = assets.open("maimai/chart/${kind.assetName}").use(::parseWav)
                    loaded[kind] = if (parsed.sampleRate == sampleRate) {
                        parsed.samples
                    } else {
                        resampleLinear(parsed.samples, parsed.sampleRate, sampleRate)
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Unable to load ${kind.assetName}", error)
                }
            }
            return ChartSfxSampleBank(sampleRate, loaded)
        }

        private class Wav(val sampleRate: Int, val samples: ShortArray)

        private fun parseWav(input: java.io.InputStream): Wav {
            val stream = DataInputStream(input.buffered())
            val header = ByteArray(12)
            stream.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAVE file" }
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var pcm = ShortArray(0)
            while (true) {
                val chunkHeader = ByteArray(8)
                val read = stream.read(chunkHeader)
                if (read < 8) break
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = littleEndianInt(chunkHeader, 4)
                when (id) {
                    "fmt " -> {
                        val format = ByteArray(size)
                        stream.readFully(format)
                        val audioFormat = littleEndianShort(format, 0)
                        channels = littleEndianShort(format, 2)
                        sampleRate = littleEndianInt(format, 4)
                        bits = littleEndianShort(format, 14)
                        require(audioFormat == 1) { "unsupported WAV format $audioFormat" }
                        require(bits == 16) { "unsupported WAV bits $bits" }
                    }
                    "data" -> {
                        val data = ByteArray(size)
                        stream.readFully(data)
                        val frameCount = size / 2
                        val raw = ShortArray(frameCount)
                        for (index in 0 until frameCount) {
                            raw[index] = littleEndianShort(data, index * 2).toShort()
                        }
                        pcm = if (channels <= 1) {
                            raw
                        } else {
                            ShortArray(frameCount / channels) { frame ->
                                var sum = 0
                                for (channel in 0 until channels) {
                                    sum += raw[frame * channels + channel]
                                }
                                (sum / channels).toShort()
                            }
                        }
                    }
                    else -> skipFully(stream, size)
                }
            }
            require(sampleRate > 0 && pcm.isNotEmpty()) { "incomplete WAV" }
            return Wav(sampleRate, pcm)
        }

        private fun skipFully(stream: DataInputStream, size: Int) {
            var remaining = size
            while (remaining > 0) {
                val skipped = stream.skipBytes(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
        }

        private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

        private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        /** Linear fallback for assets that are not already at the mixer rate. */
        private fun resampleLinear(samples: ShortArray, from: Int, to: Int): ShortArray {
            if (from == to || samples.isEmpty()) return samples
            val outLength = (samples.size.toLong() * to / from).toInt().coerceAtLeast(1)
            val step = from.toDouble() / to
            return ShortArray(outLength) { index ->
                val position = index * step
                val left = position.toInt().coerceIn(0, samples.size - 1)
                val right = (left + 1).coerceAtMost(samples.size - 1)
                val fraction = position - left
                (samples[left] * (1 - fraction) + samples[right] * fraction).toInt().toShort()
            }
        }
    }
}
