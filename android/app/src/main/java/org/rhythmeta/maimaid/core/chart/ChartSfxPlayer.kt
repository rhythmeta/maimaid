package org.rhythmeta.maimaid.core.chart

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log

/** The engine's judge / answer sound set. */
enum class ChartSfxKind(val assetName: String) {
    ANSWER("answer.wav"),
    TAP("tap.wav"),
    TOUCH("touch.wav"),
    BREAK("break.wav"),
    SLIDE("slide.wav"),
    CHEER("cheer.wav"),
    EX("ex.wav"),
    FIREWORK("firework.wav"),
    BREAK_SLIDE("break_slide.wav"),
    BREAK_SLIDE_CHEER("break_slide_cheer.wav"),
    TOUCH_HOLD("touch_hold.wav"),
}

/**
 * SoundPool-backed playback of the chart's answer and judge layers.
 *
 * Answer sounds overlap freely, while the judge layers that the reference engine treats as
 * monophonic (break, ex, slide, firework, cheer, break-slide, touch-hold) stop their previous
 * instance when a new one starts. Tap and touch sounds stay polyphonic.
 */
class ChartSfxPlayer(context: Context) : ChartSfxOutput {
    private val assets = context.applicationContext.assets
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val soundIds = HashMap<ChartSfxKind, Int>()
    private val monoStreams = HashMap<ChartSfxKind, Int>()
    private val recentStreams = ArrayDeque<Int>()
    private val touchHoldStreams = HashMap<Int, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val pendingTasks = HashMap<Int, Runnable>()
    private var loadedCount = 0
    private val totalSounds = ChartSfxKind.entries.size

    override var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) stopAll()
        }
    var answerVolume: Float = 1f
    var judgeVolume: Float = 1f

    override val isReady: Boolean get() = loadedCount >= totalSounds

    init {
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) loadedCount++
        }
        for (kind in ChartSfxKind.entries) {
            runCatching {
                assets.openFd("maimai/chart/${kind.assetName}").use { descriptor ->
                    soundIds[kind] = soundPool.load(descriptor, 1)
                }
            }.onFailure { error ->
                Log.w(TAG, "Unable to load ${kind.assetName}", error)
            }
        }
    }

    override fun playEvent(event: ChartAudioEvent, eventKey: Int): Boolean {
        if (!enabled || !isReady) return false
        if (event.playsAnswerSound) play(ChartSfxKind.ANSWER, answerVolume, mono = false)
        if (event.hasTapJudgeSound || event.hasHoldEndSound) {
            play(ChartSfxKind.TAP, judgeVolume, mono = false)
        }
        if (event.hasTouchSound || event.hasTouchHoldEndSound) {
            play(ChartSfxKind.TOUCH, judgeVolume, mono = false)
        }
        if (event.hasBreakJudgeSound) {
            play(ChartSfxKind.BREAK, judgeVolume, mono = true)
            play(ChartSfxKind.CHEER, judgeVolume, mono = true)
        }
        if (event.hasExJudgeSound) play(ChartSfxKind.EX, judgeVolume, mono = true)
        if (event.hasSlideSound) play(ChartSfxKind.SLIDE, judgeVolume, mono = true)
        if (event.hasBreakSlideSound) {
            play(ChartSfxKind.BREAK_SLIDE, judgeVolume, mono = true)
        }
        if (event.hasBreakSlideCheerSound) {
            play(ChartSfxKind.BREAK_SLIDE_CHEER, judgeVolume, mono = true)
        }
        if (event.hasFireworkSound || event.hasTouchHoldEndFireworkSound) {
            play(ChartSfxKind.FIREWORK, judgeVolume, mono = true)
        }
        if (event.hasTouchHoldSound) startTouchHoldLoop(eventKey)
        return true
    }

    override fun scheduleEvent(event: ChartAudioEvent, eventKey: Int, delayMs: Long): Boolean {
        if (!enabled || !isReady) return false
        pendingTasks.remove(eventKey)?.let(handler::removeCallbacks)
        if (delayMs <= 0L) return playEvent(event, eventKey)
        val task = Runnable {
            pendingTasks.remove(eventKey)
            playEvent(event, eventKey)
        }
        pendingTasks[eventKey] = task
        handler.postDelayed(task, delayMs)
        return true
    }

    /** Compatibility entry point for callers that do not need per-event hold ownership. */
    fun playEvent(event: ChartAudioEvent): Boolean = playEvent(event, DEFAULT_EVENT_KEY)

    private fun play(kind: ChartSfxKind, volume: Float, mono: Boolean) {
        if (!isReady) return
        val soundId = soundIds[kind] ?: return
        if (mono) {
            monoStreams[kind]?.let { stream ->
                runCatching { soundPool.stop(stream) }
            }
        }
        val streamId = runCatching {
            soundPool.play(soundId, volume, volume, 1, 0, 1f)
        }.getOrDefault(0)
        if (streamId != 0) {
            rememberStream(streamId)
            if (mono) monoStreams[kind] = streamId
        }
    }

    private fun startTouchHoldLoop(eventKey: Int) {
        if (!isReady) return
        val soundId = soundIds[ChartSfxKind.TOUCH_HOLD] ?: return
        stopTouchHoldLoop(eventKey)
        val streamId = runCatching {
            soundPool.play(soundId, judgeVolume, judgeVolume, 1, -1, 1f)
        }.getOrDefault(0)
        if (streamId != 0) {
            rememberStream(streamId)
            touchHoldStreams[eventKey] = streamId
        }
    }

    override fun stopTouchHoldLoop(eventKey: Int) {
        val stream = touchHoldStreams.remove(eventKey) ?: return
        runCatching { soundPool.stop(stream) }
    }

    /** Stops all active Touch Hold loops, used when pausing or leaving the preview. */
    fun stopTouchHoldLoop() {
        touchHoldStreams.keys.toList().forEach(::stopTouchHoldLoop)
    }

    private fun rememberStream(streamId: Int) {
        if (recentStreams.size >= MAX_TRACKED_STREAMS) recentStreams.removeFirst()
        recentStreams.addLast(streamId)
    }

    /** Stops every sound; used on seek, pause and when leaving the page. */
    fun stopAll() {
        pendingTasks.values.forEach(handler::removeCallbacks)
        pendingTasks.clear()
        while (recentStreams.isNotEmpty()) {
            runCatching { soundPool.stop(recentStreams.removeLast()) }
        }
        monoStreams.clear()
        touchHoldStreams.clear()
    }

    fun release() {
        stopAll()
        handler.removeCallbacksAndMessages(null)
        runCatching { soundPool.release() }
    }

    private companion object {
        const val TAG = "ChartSfxPlayer"
        const val DEFAULT_EVENT_KEY = -1
        const val MAX_STREAMS = 32
        const val MAX_TRACKED_STREAMS = 64
    }
}
