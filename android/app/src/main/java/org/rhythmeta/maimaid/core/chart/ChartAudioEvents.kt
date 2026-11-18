package org.rhythmeta.maimaid.core.chart

import kotlin.math.roundToLong

/**
 * One aggregated sound event. Notes sharing the same millisecond merge into a single event so a
 * tap and a hold release at the same time produce one tap sound, matching the reference engine's
 * `prepareAudioEvents`.
 */
class ChartAudioEvent(val timeMs: Double) {
    var hasBaseSound: Boolean = false
    var hasTapJudgeSound: Boolean = false
    var hasBreakJudgeSound: Boolean = false
    var hasExJudgeSound: Boolean = false
    var hasSlideSound: Boolean = false
    var hasBreakSlideSound: Boolean = false
    var hasBreakSlideCheerSound: Boolean = false
    var hasTouchSound: Boolean = false
    var hasFireworkSound: Boolean = false
    var hasTouchHoldSound: Boolean = false
    var hasHoldEndSound: Boolean = false
    var hasTouchHoldEndSound: Boolean = false
    var hasTouchHoldEndFireworkSound: Boolean = false
    var touchHoldDurationMs: Double = 0.0

    /** Answer layer plays for every audible event; touch/judge-only layers do not count. */
    val playsAnswerSound: Boolean
        get() = hasBaseSound || hasTouchSound || hasFireworkSound || hasHoldEndSound ||
            hasTouchHoldEndSound || hasTouchHoldEndFireworkSound

}

/** Builds the per-millisecond sound event list for a parsed chart. */
object ChartAudioEvents {
    private fun slidePaths(note: SlideNote): List<Triple<Double, Double, Boolean>> {
        val fallbackDelay = note.delayMs
        val count = maxOf(note.allDelayMs.size, note.allSlideBreaks.size, 1)
        return (0 until count).map { index ->
            Triple(
                note.allDelayMs.getOrNull(index) ?: fallbackDelay,
                note.allDurationMs.getOrNull(index) ?: note.durationMs,
                note.allSlideBreaks.getOrNull(index) ?: false,
            )
        }
    }

    fun prepare(notes: List<ChartNote>): List<ChartAudioEvent> {
        if (notes.isEmpty()) return emptyList()
        val events = HashMap<Long, ChartAudioEvent>()

        fun eventAt(timeMs: Double): ChartAudioEvent {
            val key = (timeMs * 1000.0).roundToLong()
            return events.getOrPut(key) { ChartAudioEvent(timeMs) }
        }

        fun markHeadJudge(event: ChartAudioEvent, isBreak: Boolean, isEx: Boolean) {
            when {
                isBreak -> event.hasBreakJudgeSound = true
                isEx -> event.hasExJudgeSound = true
                else -> event.hasTapJudgeSound = true
            }
        }

        for (note in notes) {
            val event = eventAt(note.timingMs)
            when (note) {
                is SlideNote -> {
                    if (!note.isHeadless) {
                        event.hasBaseSound = true
                        markHeadJudge(event, note.isStartBreak, note.isEx)
                    }
                    for ((delayMs, durationMs, isBreak) in slidePaths(note)) {
                        val launch = eventAt(note.timingMs + delayMs)
                        launch.hasSlideSound = true
                        if (isBreak) {
                            launch.hasBreakSlideSound = true
                            eventAt(note.timingMs + delayMs + durationMs)
                                .hasBreakSlideCheerSound = true
                        }
                    }
                }
                is TapNote -> {
                    event.hasBaseSound = true
                    if (note.isBreak) {
                        event.hasBreakJudgeSound = true
                    } else {
                        markHeadJudge(event, false, note.isEx)
                    }
                }
                is HoldStartNote -> {
                    event.hasBaseSound = true
                    markHeadJudge(event, note.isBreakHold, note.isEx)
                }
                is TouchNote -> {
                    if (note.hasFirework) event.hasFireworkSound = true else event.hasTouchSound = true
                }
                is TouchHoldStartNote -> {
                    event.hasTouchSound = true
                    event.hasTouchHoldSound = true
                    event.touchHoldDurationMs = maxOf(event.touchHoldDurationMs, note.durationMs)
                }
                is HoldEndNote -> event.hasHoldEndSound = true
                is TouchHoldEndNote -> {
                    if (note.hasFirework) {
                        event.hasTouchHoldEndFireworkSound = true
                    } else {
                        event.hasTouchHoldEndSound = true
                    }
                }
            }
        }

        return events.values.sortedBy { it.timeMs }
    }
}
