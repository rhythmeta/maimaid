package org.rhythmeta.maimaid.core.chart.audio

import org.rhythmeta.maimaid.core.chart.ChartAudioEvent
import org.rhythmeta.maimaid.core.chart.ChartSfxKind

/**
 * One SFX event converted to mixer frames. Immutable and shared between the UI thread (which builds
 * it) and the mix thread (which only reads it).
 *
 * @param frame mixer frame at which the event must start
 * @param playsAnswer whether the answer layer plays
 * @param judgeKinds judge-layer sounds started by this event, in the reference engine's order
 * @param touchHoldEndFrame end frame of the touch-hold loop, or -1 when this event starts no loop
 */
class ChartMixEvent(
    val frame: Long,
    val playsAnswer: Boolean,
    val judgeKinds: IntArray,
    val touchHoldEndFrame: Long,
) {
    val hasTouchHoldLoop: Boolean get() = touchHoldEndFrame >= 0L
}

/** Builds the immutable, frame-addressed SFX event list for the mixer. */
object ChartMixEvents {
    fun build(
        events: List<ChartAudioEvent>,
        leadInMs: Double,
        musicOffsetMs: Int,
        sfxOffsetMs: Double,
        sampleRate: Int,
    ): List<ChartMixEvent> {
        val result = ArrayList<ChartMixEvent>(events.size)
        for (event in events) {
            val frame = AudioTimeline.frameForSfx(
                event.timeMs,
                leadInMs,
                musicOffsetMs,
                sfxOffsetMs,
                sampleRate,
            )
            val kinds = judgeKinds(event)
            val holdEnd = if (event.hasTouchHoldSound) {
                AudioTimeline.frameForSfx(
                    event.timeMs + event.touchHoldDurationMs,
                    leadInMs,
                    musicOffsetMs,
                    sfxOffsetMs,
                    sampleRate,
                )
            } else {
                -1L
            }
            result += ChartMixEvent(frame, event.playsAnswerSound, kinds, holdEnd)
        }
        return result
    }

    /** Mirrors the SoundPool implementation's `playEvent` layer selection. */
    fun judgeKinds(event: ChartAudioEvent): IntArray {
        val kinds = ArrayList<ChartSfxKind>(4)
        if (event.hasTapJudgeSound || event.hasHoldEndSound) kinds += ChartSfxKind.TAP
        if (event.hasTouchSound || event.hasTouchHoldEndSound) kinds += ChartSfxKind.TOUCH
        if (event.hasBreakJudgeSound) {
            kinds += ChartSfxKind.BREAK
            kinds += ChartSfxKind.CHEER
        }
        if (event.hasExJudgeSound) kinds += ChartSfxKind.EX
        if (event.hasSlideSound) kinds += ChartSfxKind.SLIDE
        if (event.hasBreakSlideSound) kinds += ChartSfxKind.BREAK_SLIDE
        if (event.hasBreakSlideCheerSound) kinds += ChartSfxKind.BREAK_SLIDE_CHEER
        if (event.hasFireworkSound || event.hasTouchHoldEndFireworkSound) {
            kinds += ChartSfxKind.FIREWORK
        }
        if (event.hasTouchHoldSound) kinds += ChartSfxKind.TOUCH_HOLD
        return kinds.map { it.ordinal }.toIntArray()
    }
}
