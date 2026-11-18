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

/** Output contract used by the scheduler. Kept small so timing can be tested without Android. */
interface ChartSfxOutput {
    val enabled: Boolean
    val isReady: Boolean

    /** Returns false when the event must remain pending for a later call. */
    fun playEvent(event: ChartAudioEvent, eventKey: Int): Boolean

    /** Schedules an event relative to the current audio-master tick. */
    fun scheduleEvent(event: ChartAudioEvent, eventKey: Int, delayMs: Long): Boolean =
        playEvent(event, eventKey)

    fun stopTouchHoldLoop(eventKey: Int)
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

/**
 * Walks the sound events together with the visual clock. Trigger times are shifted by the
 * reference engine's -50 ms answer-sound offset.
 */
class ChartSoundScheduler(
    private val events: List<ChartAudioEvent>,
    private val baseOffsetMs: Double = ANSWER_SOUND_BASE_OFFSET_MS,
) {
    /** User-configured SFX offset in milliseconds; positive delays the sounds. */
    var userOffsetMs: Double = 0.0
        private set

    private val offsetMs: Double get() = baseOffsetMs + userOffsetMs

    /** Changes the user offset and re-anchors the cursor so past events are not replayed. */
    fun setUserOffset(valueMs: Double, currentTimeMs: Double) {
        if (userOffsetMs == valueMs) return
        userOffsetMs = valueMs
        reset(currentTimeMs)
    }

    private var nextIndex = 0
    private val activeTouchHoldEnds = HashMap<Int, Double>()

    fun reset(currentTimeMs: Double) {
        val firstDue = lowerBound(currentTimeMs + offsetMs)
        // A seek can land inside a Touch Hold. Keep its start event in the next window so the
        // continuous layer is restored instead of remaining silent until the next note.
        nextIndex = if (firstDue > 0) {
            val previous = events[firstDue - 1]
            if (previous.hasTouchHoldSound &&
                previous.timeMs + previous.touchHoldDurationMs > currentTimeMs
            ) {
                firstDue - 1
            } else {
                firstDue
            }
        } else {
            firstDue
        }
        activeTouchHoldEnds.clear()
    }

    /** Plays every event that has become audible since the last call. */
    fun advance(
        currentTimeMs: Double,
        player: ChartSfxOutput,
        lookAheadMs: Double = 0.0,
    ) {
        // A disabled output still consumes events that have already passed. Re-enabling sound
        // must not replay notes that were muted while the chart was running.
        if (!player.enabled) {
            while (nextIndex < events.size &&
                events[nextIndex].timeMs + offsetMs <= currentTimeMs
            ) {
                nextIndex++
            }
            activeTouchHoldEnds.clear()
            return
        }
        // SoundPool loads asynchronously. Do not consume events while it is warming up: the
        // reference scheduler leaves them pending and catches up on the first ready tick.
        if (!player.isReady) return

        val horizonMs = currentTimeMs + lookAheadMs.coerceAtLeast(0.0)

        while (nextIndex < events.size) {
            val event = events[nextIndex]
            val audibleTimeMs = event.timeMs + offsetMs
            if (audibleTimeMs > horizonMs) break
            val delayMs = (audibleTimeMs - currentTimeMs).coerceAtLeast(0.0).toLong()
            if (!player.scheduleEvent(event, nextIndex, delayMs)) {
                // A transient output failure must not make the event disappear.
                return
            }
            if (event.hasTouchHoldSound) {
                activeTouchHoldEnds[nextIndex] = event.timeMs + event.touchHoldDurationMs
            }
            nextIndex++
        }

        val finished = activeTouchHoldEnds.entries
            .filter { (_, endMs) -> endMs <= currentTimeMs }
            .map { it.key }
        for (eventKey in finished) {
            player.stopTouchHoldLoop(eventKey)
            activeTouchHoldEnds.remove(eventKey)
        }
    }

    private fun lowerBound(targetTimeMs: Double): Int {
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].timeMs + offsetMs < targetTimeMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val ANSWER_SOUND_BASE_OFFSET_MS = -50.0
		}
}
