package org.rhythmeta.maimaid.core.chart.audio

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh
import org.rhythmeta.maimaid.core.chart.ChartSfxKind

/**
 * Pure, sample-accurate mixer for one music PCM stream plus the chart's SFX voices.
 *
 * The mix timeline is the music PCM frame position. Events are addressed in frames
 * (see [ChartMixEvents]) and are started at their exact sample offset inside a mix buffer, so
 * high-density sections cannot bunch several hits into one UI frame. The class has no Android
 * dependencies and is covered by JVM tests.
 */
class ChartMixer(
    private val sampleRate: Int,
    private val bank: SfxSampleBank,
    maxVoices: Int = DEFAULT_MAX_VOICES,
    reserve: Int = DEFAULT_FADE_RESERVE,
    monoFadeMs: Double = DEFAULT_MONO_FADE_MS,
    evictFadeMs: Double = DEFAULT_EVICT_FADE_MS,
    maxFramesPerBuffer: Int = DEFAULT_MAX_FRAMES_PER_BUFFER,
) {
    private val monoFadeFrames = (monoFadeMs * sampleRate / 1000.0).toInt().coerceAtLeast(1)
    private val voicePool = VoicePool(
        maxVoices = maxVoices.coerceAtLeast(1),
        reserve = reserve.coerceAtLeast(2),
        evictFadeFrames = (evictFadeMs * sampleRate / 1000.0).toInt().coerceAtLeast(1),
    )
    private val maxFrames = maxFramesPerBuffer.coerceAtLeast(64)
    private val musicBuffer = ShortArray(maxFrames * 2)
    private val mixBuffer = FloatArray(maxFrames * 2)
    private val layerVolumes = FloatArray(2)

    private var musicSource: PcmFrameSource? = null
    private var events: List<ChartMixEvent> = emptyList()
    private var touchHoldEvents: List<ChartMixEvent> = emptyList()
    private var eventCursor = 0

    var currentFrame: Long = 0L
        private set

    var musicVolume: Float = 1f
    var answerVolume: Float = 1f
    var judgeVolume: Float = 1f
    var sfxEnabled: Boolean = true

    val stats = Stats()

    class Stats {
        var eventsDue: Long = 0
        var eventsMixed: Long = 0
        var eventsMuted: Long = 0
        var eventsWithoutSamples: Long = 0
        var maxFrameError: Int = 0
        var seeks: Long = 0
        var maxVoices: Int = 0
        var voiceEvictions: Int = 0
        var voiceHardStops: Int = 0
        var mixedFrames: Long = 0
        var starvedBuffers: Long = 0

        fun reset() {
            eventsDue = 0
            eventsMixed = 0
            eventsMuted = 0
            eventsWithoutSamples = 0
            maxFrameError = 0
            seeks = 0
            maxVoices = 0
            voiceEvictions = 0
            voiceHardStops = 0
            mixedFrames = 0
            starvedBuffers = 0
        }
    }

    fun setMusicSource(source: PcmFrameSource?) {
        musicSource = source
    }

    /**
     * Publishes a new event list (chart load, offset change). The cursor is re-anchored at the
     * current frame so past events are not replayed; a touch-hold that covers the current frame is
     * restored as a looping voice.
     */
    fun setEvents(newEvents: List<ChartMixEvent>) {
        events = newEvents.sortedBy { it.frame }
        touchHoldEvents = events.filter { it.hasTouchHoldLoop }
        reanchorCursor()
        restoreTouchHold()
    }

    fun seekToFrame(frame: Long) {
        currentFrame = frame.coerceAtLeast(0L)
        voicePool.stopAll()
        reanchorCursor()
        restoreTouchHold()
        stats.seeks++
    }

    /** Stops every voice (pause, mute transition, page leave). */
    fun stopAllVoices() {
        voicePool.stopAll()
    }

    /** Re-anchors after a pause: restores a touch-hold that covers the current frame. */
    fun restoreAtCurrentFrame() {
        restoreTouchHold()
    }

    /**
     * Renders [frames] frames of interleaved stereo into [out] and advances the timeline.
     * Returns the music frame of the start of the rendered buffer.
     */
    fun render(out: ShortArray, frames: Int): Long {
        require(frames in 1..maxFrames) { "frames=$frames outside 1..$maxFrames" }
        require(out.size >= frames * 2) { "output buffer too small" }
        val bufferStart = currentFrame

        mixBuffer.fill(0f, 0, frames * 2)

        voicePool.updateLoops(bufferStart, monoFadeFrames)
        triggerEvents(bufferStart, frames)

        layerVolumes[0] = answerVolume
        layerVolumes[1] = judgeVolume
        val baseGain = 1f / sqrt(voicePool.audibleVoices.coerceAtLeast(1).toFloat())
        voicePool.render(mixBuffer, frames, bufferStart, layerVolumes, baseGain)

        addMusic(bufferStart, frames)

        writeOutput(out, frames)
        currentFrame += frames
        stats.mixedFrames += frames
        if (voicePool.audibleVoices > stats.maxVoices) stats.maxVoices = voicePool.audibleVoices
        stats.voiceEvictions = voicePool.evictions
        stats.voiceHardStops = voicePool.hardStops
        return bufferStart
    }

    fun release() {
        voicePool.stopAll()
        musicSource?.close()
        musicSource = null
    }

    private fun triggerEvents(bufferStart: Long, frames: Int) {
        val bufferEnd = bufferStart + frames
        while (eventCursor < events.size && events[eventCursor].frame < bufferEnd) {
            val event = events[eventCursor]
            eventCursor++
            stats.eventsDue++
            if (!sfxEnabled) {
                stats.eventsMuted++
                continue
            }
            val offset = (event.frame - bufferStart).coerceIn(0L, (frames - 1).toLong()).toInt()
            val error = (bufferStart + offset) - event.frame
            if (abs(error) > abs(stats.maxFrameError)) stats.maxFrameError = error.toInt()
            var started = false
            if (event.playsAnswer) {
                val sample = bank.sample(ChartSfxKind.ANSWER)
                if (sample != null) {
                    started = voicePool.start(
                        sample = sample,
                        layerIndex = 0,
                        monoGroupIndex = -1,
                        frame = event.frame,
                        offsetInBuffer = offset,
                        isLooping = false,
                        loopEnd = -1L,
                        monoFadeFrames = monoFadeFrames,
                    ) || started
                } else {
                    stats.eventsWithoutSamples++
                }
            }
            for (kindOrdinal in event.judgeKinds) {
                val kind = ChartSfxKind.entries.getOrNull(kindOrdinal) ?: continue
                val sample = bank.sample(kind) ?: run {
                    stats.eventsWithoutSamples++
                    continue
                }
                val looping = kind == ChartSfxKind.TOUCH_HOLD && event.hasTouchHoldLoop
                started = voicePool.start(
                    sample = sample,
                    layerIndex = 1,
                    monoGroupIndex = if (kind.mono) kindOrdinal else -1,
                    frame = event.frame,
                    offsetInBuffer = offset,
                    isLooping = looping,
                    loopEnd = if (looping) event.touchHoldEndFrame else -1L,
                    monoFadeFrames = monoFadeFrames,
                ) || started
            }
            if (started) stats.eventsMixed++
        }
    }

    private fun addMusic(bufferStart: Long, frames: Int) {
        val source = musicSource ?: return
        val read = source.readFrames(bufferStart, musicBuffer, 0, frames)
        if (read <= 0) {
            // The decoder had not caught up yet (streaming decode); count it so a silent gap is
            // detectable instead of being invisible.
            if (!source.isComplete) stats.starvedBuffers++
            return
        }
        val volume = musicVolume
        var index = 0
        while (index < read * 2) {
            mixBuffer[index] += musicBuffer[index] / 32768f * volume
            index++
        }
    }

    private fun writeOutput(out: ShortArray, frames: Int) {
        var index = 0
        while (index < frames * 2) {
            val limited = softLimit(mixBuffer[index])
            out[index] = (limited * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            index++
        }
    }

    private fun reanchorCursor() {
        eventCursor = lowerBound(currentFrame)
    }

    private fun restoreTouchHold() {
        var hold: ChartMixEvent? = null
        for (event in touchHoldEvents) {
            if (event.frame > currentFrame) break
            if (currentFrame < event.touchHoldEndFrame) hold = event
        }
        val event = hold ?: return
        val kindOrdinal = ChartSfxKind.TOUCH_HOLD.ordinal
        val sample = bank.sample(ChartSfxKind.TOUCH_HOLD) ?: return
        voicePool.start(
            sample = sample,
            layerIndex = 1,
            monoGroupIndex = kindOrdinal,
            frame = currentFrame,
            offsetInBuffer = 0,
            isLooping = true,
            loopEnd = event.touchHoldEndFrame,
            monoFadeFrames = monoFadeFrames,
        )
    }

    private fun lowerBound(frame: Long): Int {
        var low = 0
        var high = events.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (events[mid].frame < frame) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Soft knee limiter: transparent below 0.7, then bends smoothly towards 1.0 so summing many
     * voices can never clip past full scale.
     */
    private fun softLimit(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= SOFT_KNEE) return value
        val limited = SOFT_KNEE + (1f - SOFT_KNEE) *
            tanh((magnitude - SOFT_KNEE) / (1f - SOFT_KNEE))
        return if (value < 0f) -limited else limited
    }

    companion object {
        const val DEFAULT_MAX_VOICES = 32
        const val DEFAULT_FADE_RESERVE = 8
        const val DEFAULT_MONO_FADE_MS = 15.0
        const val DEFAULT_EVICT_FADE_MS = 8.0
        const val DEFAULT_MAX_FRAMES_PER_BUFFER = 2048
        private const val SOFT_KNEE = 0.7f
    }
}
