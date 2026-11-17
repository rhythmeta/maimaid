package org.rhythmeta.maimaid.core.chart

import kotlin.math.abs
import kotlin.math.roundToLong

/** Per-note derived render metadata: chord size, break ordering and source index. */
class RenderNoteMeta {
    var simultaneousNoteCount: Int = 0
    var simultaneousSlideCount: Int = 0
    var simultaneousNonTouchCount: Int = 0
    var simultaneousTouchCount: Int = 0
    var noExBreakIndex: Int? = null
    var sourceNoteIndex: Int? = null
}

/** Sorted timings plus prefix-maximum end times for binary window culling. */
class TimeWindowIndex(
    val timingMs: DoubleArray,
    val prefixMaxEndMs: DoubleArray,
) {
    /** Returns the half-open index range `[start, endExclusive)` of notes visible right now. */
    fun range(nowMs: Double, lookAheadMs: Double): IntRange {
        val limit = nowMs + lookAheadMs
        var lo = 0
        var hi = timingMs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timingMs[mid] <= limit) lo = mid + 1 else hi = mid
        }
        val end = lo
        lo = 0
        hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (prefixMaxEndMs[mid] < nowMs) lo = mid + 1 else hi = mid
        }
        return lo until end
    }

    companion object {
        val EMPTY = TimeWindowIndex(DoubleArray(0), DoubleArray(0))

        fun <T> build(
            items: List<T>,
            timingOf: (T) -> Double,
            endOf: (T) -> Double,
        ): TimeWindowIndex {
            val timings = DoubleArray(items.size)
            val prefix = DoubleArray(items.size)
            var maxEnd = Double.NEGATIVE_INFINITY
            for (index in items.indices) {
                val item = items[index]
                timings[index] = timingOf(item)
                val end = endOf(item)
                if (end > maxEnd) maxEnd = end
                prefix[index] = maxEnd
            }
            return TimeWindowIndex(timings, prefix)
        }
    }
}

/** A group of hold heads and slide heads sharing one judgment timestamp. */
class ApproachIndicatorGroup(
    val timingMs: Double,
    val notes: List<ApproachIndicatorNote>,
)

sealed class ApproachIndicatorNote {
    abstract val note: ChartNote
    abstract val position: Int
    abstract val timing: Double
    abstract val timingMs: Double
    abstract val hiSpeed: Double?

    class Hold(override val note: HoldStartNote) : ApproachIndicatorNote() {
        override val position get() = note.position
        override val timing get() = note.timing
        override val timingMs get() = note.timingMs
        override val hiSpeed get() = note.hiSpeed
    }

    class Slide(override val note: SlideNote) : ApproachIndicatorNote() {
        override val position get() = note.position
        override val timing get() = note.timing
        override val timingMs get() = note.timingMs
        override val hiSpeed get() = note.hiSpeed
    }
}

/** Tap / hold head / slide head sharing one z-layer. */
sealed class LayeredHead {
    abstract val note: ChartNote
    abstract val timingMs: Double

    class Tap(override val note: TapNote) : LayeredHead() {
        override val timingMs get() = note.timingMs
    }

    class Hold(override val note: HoldStartNote) : LayeredHead() {
        override val timingMs get() = note.timingMs
    }

    class SlideStart(override val note: SlideNote) : LayeredHead() {
        override val timingMs get() = note.timingMs
    }
}

/**
 * A chart indexed for the render loop. Building this is the only place that walks every note:
 * category lists, chord metadata, break indices, completion counters and time windows are all
 * derived once per chart, exactly like the reference `prepareRenderNotes`.
 */
class PreparedChart private constructor(
    val chart: Chart,
    val slides: List<SlideNote>,
    val touches: List<ChartNote>,
    val fireworkTouches: List<ChartNote>,
    val holds: List<HoldStartNote>,
    val holdEffectNotes: List<HoldStartNote>,
    val touchHoldNotes: List<TouchHoldStartNote>,
    val holdEndMap: Map<String, HoldEndNote>,
    val meta: Map<ChartNote, RenderNoteMeta>,
    val approachGroups: List<ApproachIndicatorGroup>,
    val layeredHeads: List<LayeredHead>,
    val hitEffectNotes: List<ChartNote>,
    val noteCompletionTimes: DoubleArray,
    val breakCompletionTimes: DoubleArray,
    val breakNoExCompletionTimes: DoubleArray,
    val slideIndex: TimeWindowIndex,
    val touchIndex: TimeWindowIndex,
    val layeredIndex: TimeWindowIndex,
    val approachIndex: TimeWindowIndex,
    val holdEffectIndex: TimeWindowIndex,
    val touchHoldIndex: TimeWindowIndex,
    val minHiSpeed: Double,
) {
    fun metaOf(note: ChartNote): RenderNoteMeta = meta[note] ?: EMPTY_META

    fun holdEndKey(position: Int, holdStartTiming: Double): String = "$position:$holdStartTiming"

    fun countCompleted(sortedTimes: DoubleArray, currentTimeMs: Double): Int {
        var lo = 0
        var hi = sortedTimes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedTimes[mid] <= currentTimeMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY_META = RenderNoteMeta()

        private fun timingKey(ms: Double): Long = (ms * 1000.0).roundToLong()

        private fun slideVisibleEndMs(note: SlideNote): Double {
            val pathCount = note.allSlideSegments.size.coerceAtLeast(1)
            var end = note.timingMs
            for (index in 0 until pathCount) {
                val delay = note.allDelayMs.getOrNull(index) ?: note.delayMs
                val duration = note.allDurationMs.getOrNull(index) ?: note.durationMs
                val pathEnd = note.timingMs + delay + duration
                if (pathEnd > end) end = pathEnd
            }
            return end
        }

        private fun layeredHeadEndMs(item: LayeredHead): Double = when (item) {
            is LayeredHead.Hold -> item.note.timingMs + 60_000.0 * item.note.duration / item.note.bpm
            else -> item.note.timingMs + ChartConstants.NOTE_VISIBILITY_AFTER_MS
        }

        private fun touchVisibleEndMs(note: ChartNote): Double {
            val holdMs = if (note is TouchHoldStartNote) note.durationMs else 0.0
            return note.timingMs + holdMs + 50.0
        }

        fun build(chart: Chart): PreparedChart {
            val notes = chart.notes
            val slides = mutableListOf<SlideNote>()
            val touches = mutableListOf<ChartNote>()
            val fireworkTouches = mutableListOf<ChartNote>()
            val holds = mutableListOf<HoldStartNote>()
            val taps = mutableListOf<TapNote>()
            val holdEndMap = mutableMapOf<String, HoldEndNote>()
            val meta = mutableMapOf<ChartNote, RenderNoteMeta>()
            val hitEffectNotes = mutableListOf<ChartNote>()
            val noteCompletionTimes = mutableListOf<Double>()
            val breakCompletionTimes = mutableListOf<Double>()
            val breakNoExCompletionTimes = mutableListOf<Double>()

            fun metaFor(note: ChartNote): RenderNoteMeta =
                meta.getOrPut(note) { RenderNoteMeta() }

            for (index in notes.indices.reversed()) {
                when (val note = notes[index]) {
                    is SlideNote -> slides += note
                    is TouchNote, is TouchHoldStartNote -> touches += note
                    is HoldStartNote -> holds += note
                    is TapNote -> taps += note
                    else -> Unit
                }
            }

            for ((note, sourceIndex) in prepareTouchSourceIndices(notes)) {
                metaFor(note).sourceNoteIndex = sourceIndex
            }

            calculateSimultaneousCounts(notes, ::metaFor)
            assignBreakIndices(notes, ::metaFor)

            for (note in notes) {
                if (note is TapNote || note is HoldEndNote ||
                    (note is SlideNote && !note.isHeadless) ||
                    note is TouchNote || note is TouchHoldEndNote
                ) {
                    noteCompletionTimes += note.timingMs
                }

                if (note is HoldEndNote) {
                    holdEndMap["${note.position}:${note.holdStartTiming}"] = note
                }

                val hasFirework = when (note) {
                    is TouchNote -> note.hasFirework
                    is TouchHoldStartNote -> note.hasFirework
                    else -> false
                }
                if (hasFirework) fireworkTouches += note

                if (note is SlideNote) {
                    val pathCount = note.allSlideSegments.size.coerceAtLeast(1)
                    for (pathIndex in 0 until pathCount) {
                        val delay = note.allDelayMs.getOrNull(pathIndex) ?: 0.0
                        val duration = note.allDurationMs.getOrNull(pathIndex) ?: 0.0
                        noteCompletionTimes += note.timingMs + delay + duration
                    }
                }

                val isBreak = when (note) {
                    is TapNote -> note.isBreak
                    is SlideNote -> note.isStartBreak
                    is HoldStartNote -> note.isBreakHold
                    else -> false
                }
                if (isBreak) {
                    breakCompletionTimes += note.timingMs
                    // The reference reads `isEx` off every break note, so EX holds and EX
                    // slides are excluded from the "no EX" counter just like EX taps.
                    val isEx = when (note) {
                        is TapNote -> note.isEx
                        is SlideNote -> note.isEx
                        is HoldStartNote -> note.isEx
                        is HoldEndNote -> note.isEx
                        else -> false
                    }
                    if (!isEx) {
                        breakNoExCompletionTimes += note.timingMs
                    }
                }

                if (note is SlideNote) {
                    note.allSlideBreaks.forEachIndexed { pathIndex, pathBreak ->
                        if (pathBreak) {
                            val delay = note.allDelayMs.getOrNull(pathIndex) ?: 0.0
                            val duration = note.allDurationMs.getOrNull(pathIndex) ?: 0.0
                            breakCompletionTimes += note.timingMs + delay + duration
                        }
                    }
                }

                val isButtonNote = note is TapNote || note is HoldEndNote ||
                    note is HoldStartNote || note is SlideNote
                if (isButtonNote && note !is HoldStartNote &&
                    !(note is SlideNote && note.isHeadless)
                ) {
                    hitEffectNotes += note
                }
            }

            hitEffectNotes.sortBy { it.timingMs }
            noteCompletionTimes.sort()
            breakCompletionTimes.sort()
            breakNoExCompletionTimes.sort()

            val layeredHeads = mutableListOf<LayeredHead>()
            taps.forEach { layeredHeads += LayeredHead.Tap(it) }
            holds.forEach { layeredHeads += LayeredHead.Hold(it) }
            slides.filterNot { it.isHeadless }.forEach { layeredHeads += LayeredHead.SlideStart(it) }
            layeredHeads.sortWith(compareByDescending { it.timingMs })

            val approachGroups = prepareApproachGroups(holds, slides)

            val sortedSlides = slides.sortedByDescending { it.timingMs }.reversed()
            val sortedTouches = touches.sortedByDescending { it.timingMs }.reversed()
            val sortedHeads = layeredHeads.reversed()
            approachGroups.sortBy { it.timingMs }
            fireworkTouches.sortBy { fireworkTriggerMs(it) }

            val holdEffectNotes = holds.sortedBy { it.timingMs }
            val touchHoldNotes = sortedTouches.filterIsInstance<TouchHoldStartNote>()

            var minHiSpeed = 1.0
            for (note in notes) {
                val hiSpeed = note.hiSpeed ?: continue
                val magnitude = abs(hiSpeed)
                if (magnitude > 0.0 && magnitude < minHiSpeed) minHiSpeed = magnitude
            }

            return PreparedChart(
                chart = chart,
                slides = sortedSlides,
                touches = sortedTouches,
                fireworkTouches = fireworkTouches,
                holds = holds,
                holdEffectNotes = holdEffectNotes,
                touchHoldNotes = touchHoldNotes,
                holdEndMap = holdEndMap,
                meta = meta,
                approachGroups = approachGroups,
                layeredHeads = sortedHeads,
                hitEffectNotes = hitEffectNotes,
                noteCompletionTimes = noteCompletionTimes.toDoubleArray(),
                breakCompletionTimes = breakCompletionTimes.toDoubleArray(),
                breakNoExCompletionTimes = breakNoExCompletionTimes.toDoubleArray(),
                slideIndex = TimeWindowIndex.build(sortedSlides, { it.timingMs }, ::slideVisibleEndMs),
                touchIndex = TimeWindowIndex.build(sortedTouches, { it.timingMs }, ::touchVisibleEndMs),
                layeredIndex = TimeWindowIndex.build(
                    sortedHeads,
                    { it.timingMs },
                    ::layeredHeadEndMs,
                ),
                approachIndex = TimeWindowIndex.build(
                    approachGroups,
                    { it.timingMs },
                    { it.timingMs },
                ),
                holdEffectIndex = TimeWindowIndex.build(
                    holdEffectNotes,
                    { it.timingMs },
                    { hold ->
                        val end = holdEndMap["${hold.position}:${hold.timing}"]
                        end?.timingMs ?: hold.timingMs
                    },
                ),
                touchHoldIndex = TimeWindowIndex.build(
                    touchHoldNotes,
                    { it.timingMs },
                    { it.timingMs + it.durationMs + ChartConstants.NOTE_HIT_EFFECT_DURATION_MS },
                ),
                minHiSpeed = minHiSpeed,
            )
        }

        private fun calculateSimultaneousCounts(
            notes: List<ChartNote>,
            metaFor: (ChartNote) -> RenderNoteMeta,
        ) {
            val byTiming = LinkedHashMap<Long, MutableList<ChartNote>>()
            for (note in notes) {
                if (note is HoldEndNote || note.hasDelayMarker) continue
                byTiming.getOrPut(timingKey(note.timingMs)) { mutableListOf() } += note
            }

            for (group in byTiming.values) {
                val tapCount = group.count { note ->
                    !(note is TapNote && note.isBreak)
                }
                val breakCount = group.count { note ->
                    (note is TapNote && note.isBreak) ||
                        (note is SlideNote && note.isStartBreak) ||
                        (note is HoldStartNote && note.isBreakHold)
                }
                val slideCount = group.count { it is SlideNote }
                val nonTouchCount = group.count { note ->
                    note !is TouchNote && note !is TouchHoldStartNote &&
                        !(note is TapNote && note.isBreak)
                }

                val touchByPos = HashMap<String, Int>()
                for (note in group) {
                    val position = when (note) {
                        is TouchNote -> note.position
                        is TouchHoldStartNote -> note.position
                        else -> null
                    } ?: continue
                    touchByPos[position] = (touchByPos[position] ?: 0) + 1
                }

                for (note in group) {
                    val isSimultaneous = tapCount >= 2 || (breakCount >= 1 && tapCount >= 1)
                    val meta = metaFor(note)
                    meta.simultaneousNoteCount =
                        if (isSimultaneous) maxOf(2, tapCount) else tapCount
                    meta.simultaneousSlideCount = slideCount
                    meta.simultaneousNonTouchCount = nonTouchCount
                    when (note) {
                        is TouchNote -> meta.simultaneousTouchCount =
                            touchByPos[note.position] ?: 1
                        is TouchHoldStartNote -> meta.simultaneousTouchCount =
                            touchByPos[note.position] ?: 1
                        else -> Unit
                    }
                }
            }
        }

        private fun assignBreakIndices(
            notes: List<ChartNote>,
            metaFor: (ChartNote) -> RenderNoteMeta,
        ) {
            val breakNotes = notes.filter { note ->
                val isBreak = when (note) {
                    is TapNote -> note.isBreak
                    is SlideNote -> note.isStartBreak
                    is HoldStartNote -> note.isBreakHold
                    else -> false
                }
                val isEx = when (note) {
                    is TapNote -> note.isEx
                    is SlideNote -> note.isEx
                    is HoldStartNote -> note.isEx
                    else -> false
                }
                isBreak && !isEx
            }.sortedBy { it.timingMs }

            breakNotes.forEachIndexed { index, note ->
                metaFor(note).noExBreakIndex = index + 1
            }
        }

        private fun prepareApproachGroups(
            holds: List<HoldStartNote>,
            slides: List<SlideNote>,
        ): MutableList<ApproachIndicatorGroup> {
            val byTiming = LinkedHashMap<Long, MutableList<ApproachIndicatorNote>>()
            for (hold in holds) {
                byTiming.getOrPut(timingKey(hold.timingMs)) { mutableListOf() } +=
                    ApproachIndicatorNote.Hold(hold)
            }
            for (slide in slides) {
                if (slide.isHeadless) continue
                byTiming.getOrPut(timingKey(slide.timingMs)) { mutableListOf() } +=
                    ApproachIndicatorNote.Slide(slide)
            }
            return byTiming.values.map { notes ->
                ApproachIndicatorGroup(notes.first().timingMs, notes)
            }.toMutableList()
        }

        /** Touch z-order source indices: synthetic hold tails do not consume a slot. */
        fun prepareTouchSourceIndices(notes: List<ChartNote>): List<Pair<ChartNote, Int>> {
            val result = mutableListOf<Pair<ChartNote, Int>>()
            val reserved = HashSet<Int>()
            for (note in notes) note.sourceNoteIndex?.let { reserved += it }

            var index = 0
            for (note in notes) {
                if (note is HoldEndNote || note is TouchHoldEndNote) continue
                val isTouch = note is TouchNote || note is TouchHoldStartNote
                val sourceIndex = note.sourceNoteIndex
                if (sourceIndex != null) {
                    if (isTouch) result += note to sourceIndex
                    index = maxOf(index, sourceIndex + 1)
                } else {
                    while (reserved.contains(index)) index++
                    if (isTouch) result += note to index
                    index++
                }
            }
            return result
        }

        fun fireworkTriggerMs(note: ChartNote): Double = when (note) {
            is TouchHoldStartNote -> note.timingMs + note.durationMs
            else -> note.timingMs
        }
    }
}
