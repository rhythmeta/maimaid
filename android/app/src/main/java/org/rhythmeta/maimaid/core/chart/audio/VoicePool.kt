package org.rhythmeta.maimaid.core.chart.audio

/**
 * Fixed-capacity SFX voice pool. It never refuses a new voice: when [maxVoices] voices are already
 * sounding, the oldest one is faded out over [evictFadeFrames]; extra slots ([reserve]) hold the
 * fading voices so the new event can start immediately. Only when even the reserve is exhausted is
 * the oldest fading voice cut, which keeps the promise "a new event always plays" under any density.
 *
 * The pool also implements the reference engine's monophonic layers: starting a voice in a mono group
 * fades out the previous voice of the same group instead of cutting it.
 */
internal class VoicePool(
    private val maxVoices: Int,
    private val reserve: Int,
    private val evictFadeFrames: Int,
) {
    private val capacity = maxVoices + reserve
    private val samples = arrayOfNulls<ShortArray>(capacity)
    private val samplePosition = IntArray(capacity)
    private val startFrame = LongArray(capacity)
    private val sequence = LongArray(capacity)
    private val layer = IntArray(capacity)
    private val monoGroup = IntArray(capacity)
    private val looping = BooleanArray(capacity)
    private val loopEndFrame = LongArray(capacity)
    private val fadeLeft = IntArray(capacity)
    private val fadeTotal = IntArray(capacity)
    private val fadeDelay = IntArray(capacity)
    private val active = BooleanArray(capacity)
    private val bufferOffset = IntArray(capacity)

    private var activeCount = 0
    private var nextSequence = 0L
    private var evictionCount = 0
    private var hardStopCount = 0
    private var peakVoices = 0

    val audibleVoices: Int get() = activeCount
    val evictions: Int get() = evictionCount
    val hardStops: Int get() = hardStopCount
    val peak: Int get() = peakVoices

    fun start(
        sample: ShortArray,
        layerIndex: Int,
        monoGroupIndex: Int,
        frame: Long,
        offsetInBuffer: Int,
        isLooping: Boolean,
        loopEnd: Long,
        monoFadeFrames: Int,
    ): Boolean {
        if (monoGroupIndex >= 0) {
            for (index in 0 until capacity) {
                if (active[index] && monoGroup[index] == monoGroupIndex && fadeTotal[index] == 0) {
                    beginFade(index, monoFadeFrames, offsetInBuffer)
                }
            }
        }
        // Keep the number of sounding voices at maxVoices even when free reserve slots exist.
        if (activeCount >= maxVoices) evictOldestActive(offsetInBuffer)
        var slot = freeSlot()
        if (slot < 0) {
            val evicted = evictOldestActive(offsetInBuffer)
            if (evicted) slot = freeSlot()
        }
        if (slot < 0) {
            hardStopOldestFading()
            slot = freeSlot()
        }
        if (slot < 0) {
            hardStopOldestActive()
            slot = freeSlot()
        }
        if (slot < 0) return false

        samples[slot] = sample
        samplePosition[slot] = 0
        startFrame[slot] = frame
        sequence[slot] = nextSequence++
        layer[slot] = layerIndex
        monoGroup[slot] = monoGroupIndex
        looping[slot] = isLooping
        loopEndFrame[slot] = loopEnd
        fadeLeft[slot] = 0
        fadeTotal[slot] = 0
        fadeDelay[slot] = 0
        bufferOffset[slot] = offsetInBuffer
        active[slot] = true
        activeCount++
        if (activeCount > peakVoices) peakVoices = activeCount
        return true
    }

    /** Starts a fade-out over [frames] samples. No-op when the voice is already fading faster. */
    fun beginFade(index: Int, frames: Int, delaySamples: Int = 0) {
        if (!active[index]) return
        if (frames <= 0) {
            hardStop(index)
            return
        }
        if (fadeTotal[index] != 0 && fadeTotal[index] <= frames) return
        fadeTotal[index] = frames
        fadeLeft[index] = frames
        fadeDelay[index] = delaySamples.coerceAtLeast(0)
    }

    /** Stops loops whose scheduled end has been reached; called once per mix buffer. */
    fun updateLoops(bufferStartFrame: Long, fadeFrames: Int) {
        for (index in 0 until capacity) {
            if (!active[index] || !looping[index]) continue
            if (bufferStartFrame >= loopEndFrame[index]) {
                looping[index] = false
                beginFade(index, fadeFrames, 0)
            }
        }
    }

    fun stopAll() {
        for (index in 0 until capacity) {
            active[index] = false
            samples[index] = null
            fadeLeft[index] = 0
            fadeTotal[index] = 0
            fadeDelay[index] = 0
            bufferOffset[index] = 0
        }
        activeCount = 0
    }

    fun render(
        mix: FloatArray,
        frames: Int,
        bufferStartFrame: Long,
        layerVolumes: FloatArray,
        voiceBaseGain: Float,
    ) {
        for (index in 0 until capacity) {
            if (!active[index]) continue
            val sample = samples[index] ?: continue
            val gain = voiceBaseGain * layerVolumes[layer[index].coerceIn(0, layerVolumes.size - 1)]
            var position = samplePosition[index]
            var offset = bufferOffset[index].coerceIn(0, frames)
            bufferOffset[index] = 0
            var sampleIndex = offset
            while (sampleIndex < frames) {
                val fading = fadeTotal[index] > 0 && fadeDelay[index] == 0
                val fade = if (fading) {
                    fadeLeft[index].toFloat() / fadeTotal[index].toFloat()
                } else {
                    1f
                }
                val value = sample[position] / 32768f * gain * fade
                mix[sampleIndex * 2] += value
                mix[sampleIndex * 2 + 1] += value
                position++
                if (position >= sample.size) {
                    if (looping[index] && bufferStartFrame + sampleIndex < loopEndFrame[index]) {
                        position = 0
                    } else {
                        deactivate(index)
                        break
                    }
                }
                if (fadeTotal[index] > 0) {
                    if (fadeDelay[index] > 0) {
                        fadeDelay[index]--
                    } else {
                        fadeLeft[index]--
                        if (fadeLeft[index] <= 0) {
                            deactivate(index)
                            break
                        }
                    }
                }
                sampleIndex++
            }
            if (active[index]) samplePosition[index] = position
        }
    }

    private fun deactivate(index: Int) {
        if (!active[index]) return
        active[index] = false
        samples[index] = null
        fadeLeft[index] = 0
        fadeTotal[index] = 0
        fadeDelay[index] = 0
        bufferOffset[index] = 0
        activeCount--
    }

    private fun freeSlot(): Int {
        for (index in 0 until capacity) if (!active[index]) return index
        return -1
    }

    private fun evictOldestActive(delaySamples: Int = 0): Boolean {
        var oldest = -1
        for (index in 0 until capacity) {
            if (!active[index] || fadeTotal[index] != 0) continue
            if (oldest < 0 || sequence[index] < sequence[oldest]) oldest = index
        }
        if (oldest < 0) return false
        beginFade(oldest, evictFadeFrames, delaySamples)
        evictionCount++
        return true
    }

    private fun hardStopOldestFading() {
        var oldest = -1
        for (index in 0 until capacity) {
            if (!active[index] || fadeTotal[index] == 0) continue
            if (oldest < 0 || sequence[index] < sequence[oldest]) oldest = index
        }
        if (oldest >= 0) {
            hardStopCount++
            deactivate(oldest)
        }
    }

    private fun hardStopOldestActive() {
        var oldest = -1
        for (index in 0 until capacity) {
            if (!active[index]) continue
            if (oldest < 0 || sequence[index] < sequence[oldest]) oldest = index
        }
        if (oldest >= 0) {
            hardStopCount++
            deactivate(oldest)
        }
    }

    private fun hardStop(index: Int) {
        hardStopCount++
        deactivate(index)
    }
}
