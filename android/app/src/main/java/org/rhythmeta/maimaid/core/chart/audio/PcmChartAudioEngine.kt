package org.rhythmeta.maimaid.core.chart.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.rhythmeta.maimaid.core.chart.ChartAudioEvent
import org.rhythmeta.maimaid.core.chart.ChartAudioState

/**
 * PCM music + SFX engine.
 *
 * One [AudioTrack] carries the whole mix: the decoded music stream and every SFX voice are rendered
 * by [ChartMixer] on a dedicated audio thread, so both paths share the same output latency and SFX
 * start on the exact sample frame of their event. The chart clock is extrapolated from
 * [AudioTrack.getTimestamp], which makes the visible chart follow the audible mix instead of the
 * coarsely reported MediaPlayer position.
 *
 * The UI thread only publishes commands (play/pause/seek/settings) and reads
 * [chartPositionMs]; it never touches voices or buffers.
 */
class PcmChartAudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val framesPerBuffer = FRAMES_PER_BUFFER

    @Volatile
    private var sampleRate = 48_000

    private val mutableState = MutableStateFlow<ChartAudioState>(ChartAudioState.Idle)
    val state: StateFlow<ChartAudioState> = mutableState.asStateFlow()

    private val mutableUsesPcm = MutableStateFlow(false)
    val usesPcm: StateFlow<Boolean> = mutableUsesPcm.asStateFlow()

    private val mutablePreparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = mutablePreparing.asStateFlow()

    private val mutableSeekCompletions = MutableStateFlow(0)
    val seekCompletions: StateFlow<Int> = mutableSeekCompletions.asStateFlow()

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    private var clock = ChartAudioClock(48_000)
    private val commands = ConcurrentLinkedQueue<Command>()
    private val wakeLock = Object()

    private var mixer: ChartMixer? = null
    private var source: MemoryPcmFrameSource? = null
    private var sampleBank: ChartSfxSampleBank? = null
    private var track: AudioTrack? = null
    private var mixThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var mixPlaying = false

    @Volatile
    private var trackFrameBase = 0L

    @Volatile
    private var latestSeekGeneration = 0L

    @Volatile
    private var pendingSpeed = 1f

    @Volatile
    private var appliedSpeed = 1f

    private var trackSpeedKnown = false

    @Volatile
    private var musicVolume = 1f

    @Volatile
    private var answerVolume = 1f

    @Volatile
    private var judgeVolume = 1f

    @Volatile
    private var sfxEnabled = true

    @Volatile
    private var leadInMs = 0.0

    @Volatile
    private var musicOffsetMs = 0

    @Volatile
    private var sfxOffsetMs = 0

    @Volatile
    private var chartDurationMs = 0.0

    private var rawEvents: List<ChartAudioEvent> = emptyList()
    @Volatile
    private var prepareGeneration = 0L
    private var preparedKey: String? = null
    private var timestampCounter = 0
    private var preparedAtUptime = 0L

    /**
     * Decodes [mp3File] into memory, loads the SFX bank and starts the mix thread.
     * Returns false when the track cannot be decoded/played; the chart then runs silently.
     */
    suspend fun prepare(mp3File: File, sha256: String): Boolean {
        val key = "${sha256.lowercase()}:${mp3File.lastModified()}:${mp3File.length()}"
        if (preparedKey == key && isReady) return true
        // A different track replaces the current one: tear the old mixer/AudioTrack down first so
        // it cannot keep playing (or leak) while the new track is being decoded.
        if (isReady || mixer != null) {
            withContext(Dispatchers.IO) { releaseInternal() }
        }
        val generation = ++prepareGeneration
        mutableState.value = ChartAudioState.Preparing
        mutablePreparing.value = true
        val startedAt = SystemClock.uptimeMillis()
        var createdTrack: AudioTrack? = null
        var createdMixer: ChartMixer? = null
        var memorySource: MemoryPcmFrameSource? = null
        return try {
            // Decoded straight into memory: nothing PCM-related ever reaches the disk cache.
            val decoded = ChartPcmDecoder.decode(mp3File)
            if (generation != prepareGeneration) return false
            val bank = withContext(Dispatchers.IO) {
                ChartSfxSampleBank.load(appContext.assets, decoded.sampleRate)
            }
            if (generation != prepareGeneration) return false
            val mapped = MemoryPcmFrameSource(decoded.samples)
            val createdMixerLocal = ChartMixer(decoded.sampleRate, bank)
            createdMixerLocal.setMusicSource(mapped)
            createdMixerLocal.musicVolume = musicVolume
            createdMixerLocal.answerVolume = answerVolume
            createdMixerLocal.judgeVolume = judgeVolume
            createdMixerLocal.sfxEnabled = sfxEnabled
            val createdTrackLocal = createAudioTrack(decoded.sampleRate)
            createdTrack = createdTrackLocal
            createdMixer = createdMixerLocal
            memorySource = mapped
            if (generation != prepareGeneration) {
                runCatching { createdTrackLocal.release() }
                createdMixerLocal.release()
                mapped.close()
                createdTrack = null
                createdMixer = null
                memorySource = null
                return false
            }
            sampleRate = decoded.sampleRate
            mixer = createdMixerLocal
            source = mapped
            sampleBank = bank
            track = createdTrackLocal
            startThread(createdMixerLocal, createdTrackLocal)
            preparedKey = key
            isReady = true
            mutableUsesPcm.value = true
            preparedAtUptime = startedAt
            applyConfigurationToMixer()
            mutableState.value = ChartAudioState.Ready(
                (mapped.frameCount * 1000L / decoded.sampleRate).toInt(),
            )
            true
        } catch (error: CancellationException) {
                throw error
        } catch (error: Throwable) {
            Log.w(TAG, "PCM preparation failed; the chart will play silently", error)
            if (generation == prepareGeneration) {
                releaseInternal()
                mutableState.value = ChartAudioState.Failed(error.message ?: "PCM preparation failed")
            }
            false
        } finally {
            if (createdTrack != null && track !== createdTrack) runCatching { createdTrack.release() }
            if (createdMixer != null && mixer !== createdMixer) createdMixer.release()
            if (source !== memorySource) memorySource?.close()
            if (generation == prepareGeneration) mutablePreparing.value = false
        }
    }

    fun configure(
        events: List<ChartAudioEvent>,
        leadInMs: Double,
        durationMs: Double,
        musicOffsetMs: Int,
        sfxOffsetMs: Int,
    ) {
        this.rawEvents = events
        this.leadInMs = leadInMs
        this.chartDurationMs = durationMs
        this.musicOffsetMs = musicOffsetMs
        this.sfxOffsetMs = sfxOffsetMs
        applyConfigurationToMixer()
    }

    fun play() {
        if (!isReady) return
        enqueue(Command(Type.PLAY))
    }

    fun pause() {
        if (!isReady) return
        enqueue(Command(Type.PAUSE))
    }

    /**
     * Seeks to a chart position. The mix thread flushes the track, resets the event cursor and
     * publishes [seekCompletions] once the new position is active.
     */
    fun seekTo(chartTimeMs: Double, playAfterSeek: Boolean = false) {
        if (!isReady) return
        val targetFrame = AudioTimeline.frameForMusicPositionMs(
            AudioTimeline.musicPositionMs(chartTimeMs, leadInMs, musicOffsetMs),
            sampleRate,
        ).coerceAtLeast(0L)
        latestSeekGeneration++
        enqueue(
            Command(
                type = Type.SEEK,
                longValue = targetFrame,
                generation = latestSeekGeneration,
                flag = playAfterSeek,
            ),
        )
    }

    /** Audible chart position in milliseconds, extrapolated from the last AudioTrack timestamp. */
    fun chartPositionMs(): Double {
        if (!isReady) return 0.0
        val frame = clock.positionFrameAt(System.nanoTime()).toLong().coerceAtLeast(0L)
        return AudioTimeline.chartTimeForFrame(frame, leadInMs, musicOffsetMs, sampleRate)
            .coerceIn(0.0, if (chartDurationMs > 0) chartDurationMs else Double.MAX_VALUE)
    }

    fun setMusicVolume(value: Float) {
        musicVolume = value.coerceIn(0f, 1f)
    }

    fun setAnswerVolume(value: Float) {
        answerVolume = value.coerceIn(0f, 1f)
    }

    fun setJudgeVolume(value: Float) {
        judgeVolume = value.coerceIn(0f, 1f)
    }

    fun setSfxEnabled(enabled: Boolean) {
        sfxEnabled = enabled
    }

    fun setPlaybackSpeed(value: Float) {
        val clamped = value.coerceIn(0.1f, 2f)
        if (clamped == pendingSpeed) return
        pendingSpeed = clamped
        enqueue(Command(Type.SPEED))
    }

    /** Rebuilds the event frames and keeps the current chart position. */
    fun setSfxOffsetMs(offsetMs: Int) {
        if (offsetMs == sfxOffsetMs) return
        sfxOffsetMs = offsetMs
        applyConfigurationToMixer()
    }

    /**
     * Changes the music offset and re-seeks so the chart position does not jump. The mapping and its
     * inverse live in [AudioTimeline], so a non-zero offset never doubles.
     */
    fun setMusicOffsetMs(offsetMs: Int) {
        if (offsetMs == musicOffsetMs) return
        val chartPosition = chartPositionMs()
        musicOffsetMs = offsetMs
        seekTo(chartPosition, playAfterSeek = mixPlaying)
        applyConfigurationToMixer()
    }

    fun stopSfx() {
        enqueue(Command(Type.STOP_VOICES))
    }

    data class EngineStats(
        val eventsDue: Long,
        val eventsMixed: Long,
        val eventsMuted: Long,
        val eventsWithoutSamples: Long,
        val maxFrameError: Int,
        val maxVoices: Int,
        val evictions: Int,
        val hardStops: Int,
        val underruns: Int,
        val trackState: Int,
        val speed: Float,
    )

    /** Snapshot for the acceptance harness and debug logging. */
    fun stats(): EngineStats {
        val mixer = mixer ?: return EngineStats(0, 0, 0, 0, 0, 0, 0, 0, -1, -1, appliedSpeed)
        val stats = mixer.stats
        val currentTrack = track
        return EngineStats(
            eventsDue = stats.eventsDue,
            eventsMixed = stats.eventsMixed,
            eventsMuted = stats.eventsMuted,
            eventsWithoutSamples = stats.eventsWithoutSamples,
            maxFrameError = stats.maxFrameError,
            maxVoices = stats.maxVoices,
            evictions = stats.voiceEvictions,
            hardStops = stats.voiceHardStops,
            underruns = runCatching { currentTrack?.underrunCount ?: -1 }.getOrDefault(-1),
            trackState = runCatching { currentTrack?.state ?: -1 }.getOrDefault(-1),
            speed = appliedSpeed,
        )
    }

    /** Debug summary for logcat / the acceptance harness. */
    fun debugSummary(): String {
        val mixer = mixer ?: return "pcm=not-ready"
        val stats = mixer.stats
        val underruns = runCatching { track?.underrunCount ?: -1 }.getOrDefault(-1)
        return "pcm sr=$sampleRate due=${stats.eventsDue} mixed=${stats.eventsMixed} " +
            "muted=${stats.eventsMuted} noSample=${stats.eventsWithoutSamples} " +
            "maxFrameError=${stats.maxFrameError} maxVoices=${stats.maxVoices} " +
            "evictions=${stats.voiceEvictions} hardStops=${stats.voiceHardStops} " +
            "underruns=$underruns"
    }

    fun release() {
        releaseInternal()
    }

    private fun applyConfigurationToMixer() {
        if (!isReady) return
        val events = ChartMixEvents.build(
            events = rawEvents,
            leadInMs = leadInMs,
            musicOffsetMs = musicOffsetMs,
            sfxOffsetMs = sfxOffsetMs.toDouble(),
            sampleRate = sampleRate,
        )
        enqueue(Command(Type.SET_EVENTS, events = events))
    }

    private fun enqueue(command: Command) {
        commands.add(command)
        synchronized(wakeLock) { wakeLock.notifyAll() }
    }

    private fun createAudioTrack(rate: Int): AudioTrack {
        val lowLatency = supportsPlaybackSpeed(rate)
        val minBytes = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBytes > 0) { "AudioTrack min buffer unavailable ($minBytes)" }
        val bufferBytes = maxOf(minBytes, framesPerBuffer * 2 * 2 * 4)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(
                if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                else AudioTrack.PERFORMANCE_MODE_NONE,
            )
            .build()
    }

    /**
     * Fast tracks cannot time-stretch: on several devices a 48 kHz low-latency track rejects
     * `setPlaybackParams(speed != 1)`. Probe once with a throw-away track before choosing the
     * performance mode for the real one; if speed is unsupported we accept the slightly higher
     * output latency (the chart clock follows AudioTrack timestamps either way) so 0.5x-2x and the
     * long-press boost keep working.
     */
    private fun supportsPlaybackSpeed(rate: Int): Boolean {
        val minBytes = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) return false
        val probe = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrNull() ?: return false
        return try {
            probe.setVolume(0f)
            probe.play()
            val buffer = ShortArray(256)
            probe.write(buffer, 0, buffer.size)
            probe.playbackParams = PlaybackParams().setSpeed(1.5f).setPitch(1f)
            true
        } catch (error: Throwable) {
            false
        } finally {
            runCatching { probe.pause() }
            runCatching { probe.release() }
        }
    }

    private fun startThread(mixer: ChartMixer, track: AudioTrack) {
        running = true
        clock = ChartAudioClock(sampleRate)
        clock.reset(0.0, System.nanoTime(), playing = false)
        mixThread = Thread({ mixLoop(mixer, track) }, "ChartAudioMix").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun mixLoop(mixer: ChartMixer, track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ShortArray(framesPerBuffer * 2)
        while (running) {
            drainCommands(mixer, track)
            if (!running) break
            if (!mixPlaying) {
                waitForWork()
                continue
            }
            mixer.musicVolume = musicVolume
            mixer.answerVolume = answerVolume
            mixer.judgeVolume = judgeVolume
            mixer.sfxEnabled = sfxEnabled
            mixer.render(buffer, framesPerBuffer)
            var written = 0
            while (written < buffer.size && running) {
                val count = runCatching {
                    track.write(buffer, written, buffer.size - written)
                }.getOrDefault(-1)
                if (count <= 0) break
                written += count
            }
            if (written < buffer.size && running) {
                writeFailureCount++
                if (writeFailureCount <= 5) {
                    Log.w(
                        TAG,
                        "AudioTrack write incomplete: wrote=$written/${buffer.size} " +
                            "state=${track.state} playState=${track.playState}",
                    )
                }
            } else {
                writeFailureCount = 0
            }
            timestampCounter++
            if (timestampCounter >= TIMESTAMP_POLL_BUFFERS) {
                timestampCounter = 0
                pollTimestamp(track)
            }
        }
    }

    private var writeFailureCount = 0

    private fun pollTimestamp(track: AudioTrack) {
        val timestamp = AudioTimestamp()
        if (!runCatching { track.getTimestamp(timestamp) }.getOrDefault(false)) return
        val frame = trackFrameBase + timestamp.framePosition
        clock.onTimestamp(frame, timestamp.nanoTime)
    }

    private fun waitForWork() {
        synchronized(wakeLock) {
            runCatching { wakeLock.wait(20) }
        }
    }

    private fun drainCommands(mixer: ChartMixer, track: AudioTrack) {
        while (true) {
            val command = commands.poll() ?: return
            when (command.type) {
                Type.PLAY -> {
                    if (!mixPlaying) {
                        runCatching { track.play() }.onFailure {
                            Log.w(TAG, "AudioTrack.play failed", it)
                        }
                        mixPlaying = true
                        isPlaying = true
                        clock.setPlaying(true, System.nanoTime())
                        mixer.restoreAtCurrentFrame()
                        syncPlaybackSpeed(track)
                    }
                }
                Type.PAUSE -> {
                    if (mixPlaying) {
                        runCatching { track.pause() }
                    }
                    mixPlaying = false
                    isPlaying = false
                    clock.setPlaying(false, System.nanoTime())
                    mixer.stopAllVoices()
                }
                Type.SEEK -> {
                    if (command.generation != latestSeekGeneration) {
                            continue
                    }
                    val wasPlaying = mixPlaying
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    mixer.seekToFrame(command.longValue)
                    trackFrameBase = command.longValue
                    clock.reset(
                        command.longValue.toDouble(),
                        System.nanoTime(),
                        playing = wasPlaying || command.flag,
                    )
                    trackSpeedKnown = false
                    if (command.flag || wasPlaying) {
                        runCatching { track.play() }
                        mixPlaying = true
                        isPlaying = true
                        syncPlaybackSpeed(track)
                    } else {
                        mixPlaying = false
                        isPlaying = false
                    }
                    mutableSeekCompletions.value = mutableSeekCompletions.value + 1
                }
                Type.SPEED -> syncPlaybackSpeed(track)
                Type.SET_EVENTS -> command.events?.let(mixer::setEvents)
                Type.STOP_VOICES -> mixer.stopAllVoices()
            }
        }
    }

    /**
     * Applies [pendingSpeed] to the AudioTrack. This must happen while the track is playing: many
     * devices reject `playbackParams` on a stopped/paused track (the previous implementation
     * swallowed that exception, leaving the music at 1x while the visual clock was sped up).
     * The visual clock only changes when the track actually accepted the new rate.
     */
    private fun syncPlaybackSpeed(track: AudioTrack) {
        if (!mixPlaying) return
        val target = pendingSpeed
        if (trackSpeedKnown && target == appliedSpeed) return
        val params = PlaybackParams().setSpeed(target).setPitch(1f)
        var failure: Throwable? = null
        var accepted = runCatching { track.playbackParams = params }
            .onFailure { failure = it }
            .isSuccess
        if (!accepted) {
            // Some devices only accept the change between pause/play; always resume afterwards so a
            // failed speed request cannot leave the mix silent.
            runCatching { track.pause() }
            accepted = runCatching { track.playbackParams = params }
                .onFailure { failure = it }
                .isSuccess
            runCatching { track.play() }
        }
        if (accepted) {
            appliedSpeed = target
            trackSpeedKnown = true
            clock.setSpeed(target, System.nanoTime())
        } else {
            Log.w(TAG, "AudioTrack rejected speed=$target, keeping $appliedSpeed", failure)
            pendingSpeed = appliedSpeed
        }
    }

    private fun releaseInternal() {
        running = false
        synchronized(wakeLock) { wakeLock.notifyAll() }
        mixThread?.let { thread ->
            runCatching { thread.join(1_000) }
        }
        mixThread = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        mixer?.release()
        mixer = null
        // Dropping the last reference frees the decoded track (tens of MB) with the preview.
        source = null
        sampleBank = null
        commands.clear()
        isReady = false
        isPlaying = false
        mixPlaying = false
        mutableUsesPcm.value = false
        mutableState.value = ChartAudioState.Idle
        preparedKey = null
    }

    private enum class Type { PLAY, PAUSE, SEEK, SPEED, SET_EVENTS, STOP_VOICES }

    private class Command(
        val type: Type,
        val longValue: Long = 0L,
        val floatValue: Float = 0f,
        val events: List<ChartMixEvent>? = null,
        val generation: Long = 0L,
        val flag: Boolean = false,
    )

    companion object {
        const val FRAMES_PER_BUFFER = 256
        private const val TIMESTAMP_POLL_BUFFERS = 8
        private const val TAG = "PcmChartAudioEngine"

    }
}
