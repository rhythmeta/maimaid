package org.rhythmeta.maimaid.core.chart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Slide path symbol, matching simai's path notation. */
enum class SlidePathType(val notation: String) {
    LINE("-"),
    CLOCKWISE(">"),
    COUNTER_CLOCKWISE("<"),
    SHORT_ARC("^"),
    V_SHAPE("v"),
    SPIRAL_CLOCKWISE("p"),
    SPIRAL_CLOCKWISE_DOUBLE("pp"),
    SPIRAL_COUNTER_CLOCKWISE("q"),
    SPIRAL_COUNTER_CLOCKWISE_DOUBLE("qq"),
    S_CURVE("s"),
    Z_CURVE("z"),
    WIFI("w"),
    VIA("V"),
    ;

    companion object {
        fun fromSymbol(symbol: Char): SlidePathType? = when (symbol) {
            '-' -> LINE
            '>' -> CLOCKWISE
            '<' -> COUNTER_CLOCKWISE
            '^' -> SHORT_ARC
            'v' -> V_SHAPE
            'p' -> SPIRAL_CLOCKWISE
            'q' -> SPIRAL_COUNTER_CLOCKWISE
            's' -> S_CURVE
            'z' -> Z_CURVE
            'w' -> WIFI
            'V' -> VIA
            else -> null
        }
    }
}

/** Mirror transform applied to button and touch positions. */
@kotlinx.serialization.Serializable
enum class MirrorMode { NONE, HORIZONTAL, VERTICAL, ROTATE180 }

/** Judgment line presentation, matching the reference engine's four designs. */
@kotlinx.serialization.Serializable
enum class JudgmentLineDesign { BLIND, NO_LINE, SIMPLE, SENSOR }

/** A point in canvas pixels. */
data class ChartPoint(val x: Double, val y: Double)

/** One sampled point of a slide path length table. */
data class ArcLutPoint(val x: Double, val y: Double, val angle: Double, val s: Double)

/** A unit-circle guide arrow from the slide bar template. */
data class SlideBar(val x: Double, val y: Double, val r: Double)

/** A guide arrow already transformed into canvas space. */
data class SlideChainPoint(val x: Double, val y: Double, val angle: Double)

/**
 * One segment of a slide path. Geometry caches mirror the reference implementation and
 * are invalidated when the judgment ring radius or the mirror mode changes.
 */
class SlideSegment(
    val type: SlidePathType,
    val startPos: Int,
    val endPos: Int,
    val midPos: Int? = null,
) {
    var cachedLength: Double? = null
    var cachedLut: List<ArcLutPoint>? = null
    var cachedRadius: Float = -1f
    var cachedMirrorMode: MirrorMode? = null
    var cachedChain: List<SlideChainPoint>? = null
    var cachedChainRadius: Float = -1f
    var cachedChainMirror: MirrorMode? = null

    fun signatureKey(): String = "${type.name}:$startPos:$endPos:${midPos ?: ""}"

    override fun toString(): String = "${type.notation}$startPos-$endPos"
}

/** Base fields shared by every chart note. */
sealed class ChartNote {
    abstract val timing: Double
    abstract val timingMs: Double
    abstract val measure: Int
    abstract val positionInMeasure: Int
    abstract val scale: Double
    abstract val bpm: Double
    abstract val hasDelayMarker: Boolean
    abstract val hiSpeed: Double?
    abstract val sourceNoteIndex: Int?

    /** New note with the parsed lead-in already applied. */
    abstract fun withLeadIn(beatOffset: Double, msOffset: Double): ChartNote

    /** New note carrying the current hi-speed marker. */
    abstract fun withHiSpeed(value: Double?): ChartNote
}

/** Tap, break and same-time tap chord note with a numeric button position. */
data class TapNote(
    val position: Int,
    override val timing: Double,
    override val timingMs: Double,
    override val measure: Int,
    override val positionInMeasure: Int,
    override val scale: Double,
    override val bpm: Double,
    override val hasDelayMarker: Boolean,
    override val hiSpeed: Double? = null,
    override val sourceNoteIndex: Int? = null,
    val isBreak: Boolean = false,
    val isSameTime: Boolean = false,
    val isStar: Boolean = false,
    val isSpinningStar: Boolean = false,
    val isEx: Boolean = false,
) : ChartNote() {
    override fun withLeadIn(beatOffset: Double, msOffset: Double) = copy(
        timing = timing + beatOffset,
        timingMs = timingMs + msOffset,
    )

    override fun withHiSpeed(value: Double?) = copy(hiSpeed = value)
}

/** Hold head note. */
data class HoldStartNote(
    val position: Int,
    override val timing: Double,
    override val timingMs: Double,
    override val measure: Int,
    override val positionInMeasure: Int,
    override val scale: Double,
    override val bpm: Double,
    override val hasDelayMarker: Boolean,
    override val hiSpeed: Double? = null,
    override val sourceNoteIndex: Int? = null,
    val duration: Double,
    val isSameTime: Boolean = false,
    val isEx: Boolean = false,
    val isBreakHold: Boolean = false,
) : ChartNote() {
    override fun withLeadIn(beatOffset: Double, msOffset: Double) = copy(
        timing = timing + beatOffset,
        timingMs = timingMs + msOffset,
    )

    override fun withHiSpeed(value: Double?) = copy(hiSpeed = value)
}

/** Hold tail note. */
data class HoldEndNote(
    val position: Int,
    override val timing: Double,
    override val timingMs: Double,
    override val measure: Int,
    override val positionInMeasure: Int,
    override val scale: Double,
    override val bpm: Double,
    override val hasDelayMarker: Boolean,
    override val hiSpeed: Double? = null,
    override val sourceNoteIndex: Int? = null,
    val holdStartTiming: Double,
    val isSameTime: Boolean = false,
    val isEx: Boolean = false,
    val isBreakHold: Boolean = false,
) : ChartNote() {
    override fun withLeadIn(beatOffset: Double, msOffset: Double) = copy(
        timing = timing + beatOffset,
        timingMs = timingMs + msOffset,
        holdStartTiming = holdStartTiming + beatOffset,
    )

    override fun withHiSpeed(value: Double?) = copy(hiSpeed = value)
}

/** Slide note, including every concurrent path. */
data class SlideNote(
    val position: Int,
    override val timing: Double,
    override val timingMs: Double,
    override val measure: Int,
    override val positionInMeasure: Int,
    override val scale: Double,
    override val bpm: Double,
    override val hasDelayMarker: Boolean,
    override val hiSpeed: Double? = null,
    override val sourceNoteIndex: Int? = null,
    val isHeadless: Boolean = false,
    val headlessMode: String? = null,
    val hasTapHead: Boolean = false,
    val isStartBreak: Boolean = false,
    val allSlideBreaks: List<Boolean> = emptyList(),
    val isEx: Boolean = false,
    val duration: Double,
    val durationMs: Double,
    val delayMs: Double,
    val slideSegments: List<SlideSegment>,
    val allSlideSegments: List<List<SlideSegment>>,
    val allDurations: List<Double>,
    val allDurationMs: List<Double>,
    val allDelayMs: List<Double>,
    val allCustomLengths: List<Double?>,
    val isSplitSlide: Boolean,
    val customLength: Double?,
) : ChartNote() {
    override fun withLeadIn(beatOffset: Double, msOffset: Double) = copy(
        timing = timing + beatOffset,
        timingMs = timingMs + msOffset,
    )

    override fun withHiSpeed(value: Double?) = copy(hiSpeed = value)
}

/** Touch note. */
data class TouchNote(
    val position: String,
    override val timing: Double,
    override val timingMs: Double,
    override val measure: Int,
    override val positionInMeasure: Int,
    override val scale: Double,
    override val bpm: Double,
    override val hasDelayMarker: Boolean,
    override val hiSpeed: Double? = null,
    override val sourceNoteIndex: Int? = null,
    val hasFirework: Boolean = false,
) : ChartNote() {
    override fun withLeadIn(beatOffset: Double, msOffset: Double) = copy(
        timing = timing + beatOffset,
        timingMs = timingMs + msOffset,
    )

    override fun withHiSpeed(value: Double?) = copy(hiSpeed = value)
}

/** Touch hold head. */
data class TouchHoldStartNote(
    val position: String,
    override val timing: Double,
    override val timingMs: Double,
    override val measure: Int,
    override val positionInMeasure: Int,
    override val scale: Double,
    override val bpm: Double,
    override val hasDelayMarker: Boolean,
    override val hiSpeed: Double? = null,
    override val sourceNoteIndex: Int? = null,
    val duration: Double,
    val durationMs: Double,
    val hasFirework: Boolean = false,
) : ChartNote() {
    override fun withLeadIn(beatOffset: Double, msOffset: Double) = copy(
        timing = timing + beatOffset,
        timingMs = timingMs + msOffset,
    )

    override fun withHiSpeed(value: Double?) = copy(hiSpeed = value)
}

/** Touch hold tail. */
data class TouchHoldEndNote(
    val position: String,
    override val timing: Double,
    override val timingMs: Double,
    override val measure: Int,
    override val positionInMeasure: Int,
    override val scale: Double,
    override val bpm: Double,
    override val hasDelayMarker: Boolean,
    override val hiSpeed: Double? = null,
    override val sourceNoteIndex: Int? = null,
    val holdStartTiming: Double,
    val hasFirework: Boolean = false,
) : ChartNote() {
    override fun withLeadIn(beatOffset: Double, msOffset: Double) = copy(
        timing = timing + beatOffset,
        timingMs = timingMs + msOffset,
        holdStartTiming = holdStartTiming + beatOffset,
    )

    override fun withHiSpeed(value: Double?) = copy(hiSpeed = value)
}

/** BPM change event. */
data class BpmEvent(val timing: Double, val bpm: Double)

/** Time-signature divisor change event. */
data class DivisorEvent(val timing: Double, val divisor: Double)

/** Fully parsed chart. */
data class Chart(
    val title: String,
    val artist: String,
    val designer: String,
    val bpm: Double,
    val level: Map<Int, String>,
    val designers: Map<Int, String>,
    val difficulty: Int?,
    val availableDifficulties: Set<Int>,
    val measures: Int,
    val notes: List<ChartNote>,
    val bpmEvents: List<BpmEvent>,
    val divisorEvents: List<DivisorEvent>,
    val firstMs: Double?,
) {
    /** Total playable length in ms, including hold tails and slide paths. */
    val endTimeMs: Double = run {
        var maxTimingMs = 0.0
        for (note in notes) {
            val end = when (note) {
                is HoldStartNote -> note.timingMs + 60_000.0 * note.duration / note.bpm
                is TouchHoldStartNote -> note.timingMs + note.durationMs
                is SlideNote -> {
                    var pathEnd = note.timingMs
                    val count = note.allSlideSegments.size.coerceAtLeast(1)
                    for (index in 0 until count) {
                        val delay = note.allDelayMs.getOrNull(index) ?: note.delayMs
                        val duration = note.allDurationMs.getOrNull(index) ?: note.durationMs
                        val candidate = note.timingMs + delay + duration
                        if (candidate > pathEnd) pathEnd = candidate
                    }
                    pathEnd
                }
                else -> note.timingMs
            }
            if (end > maxTimingMs) maxTimingMs = end
        }
        maxTimingMs
    }

    val leadInMs: Double get() = 60_000.0 * 4.0 / bpm
}

/** Convenience predicates mirroring the reference engine's type guards. */
val ChartNote.isTap: Boolean get() = this is TapNote
val ChartNote.isHoldStart: Boolean get() = this is HoldStartNote
val ChartNote.isHoldEnd: Boolean get() = this is HoldEndNote
val ChartNote.isSlide: Boolean get() = this is SlideNote
val ChartNote.isTouch: Boolean get() = this is TouchNote
val ChartNote.isTouchHoldStart: Boolean get() = this is TouchHoldStartNote

/** Button position on the judgment ring for a 1-based index (already mirrored). */
fun ringPoint(centerX: Double, centerY: Double, radius: Double, angle: Double): ChartPoint =
    ChartPoint(centerX + cos(angle) * radius, centerY + sin(angle) * radius)

/** Note position in one frame: canvas coordinates, approach scale and visibility. */
data class NoteRenderPosition(
    val x: Double,
    val y: Double,
    val scale: Double,
    val visible: Boolean,
) {
    companion object {
        val INVISIBLE = NoteRenderPosition(0.0, 0.0, 0.0, false)
    }
}

/** Full turn in radians, kept for readability of angle math. */
const val TWO_PI: Double = PI * 2
