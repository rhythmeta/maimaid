package org.rhythmeta.maimaid.core.chart

/**
 * Beat ↔ millisecond conversion with BPM changes integrated into cumulative timestamps.
 * Mirrors the reference `TimingTimeline`; every renderer path reuses this instead of scanning
 * BPM events itself.
 */
class TimingTimeline(
    private val defaultBpm: Double,
    bpmEvents: List<BpmEvent>? = null,
    divisorEvents: List<DivisorEvent>? = null,
) {
    private class BpmPoint(val timing: Double, val bpm: Double, val cumulativeMs: Double)

    private val bpmPoints: List<BpmPoint> = createBpmPoints(defaultBpm, bpmEvents)
    private val divisorEvents: List<DivisorEvent> = divisorEvents.orEmpty().sortedBy { it.timing }

    companion object {
        fun fromChart(chart: Chart): TimingTimeline =
            TimingTimeline(chart.bpm, chart.bpmEvents, chart.divisorEvents)
    }

    fun msFromBeat(beat: Double): Double {
        val index = findLastLe(bpmPoints.size) { bpmPoints[it].timing <= beat }
        if (index < 0) return 60_000.0 * beat / defaultBpm
        val point = bpmPoints[index]
        return point.cumulativeMs + (beat - point.timing) * 60_000.0 / point.bpm
    }

    fun beatFromMs(ms: Double): Double {
        val index = findLastLe(bpmPoints.size) { bpmPoints[it].cumulativeMs <= ms }
        if (index < 0) return ms * defaultBpm / 60_000.0
        val point = bpmPoints[index]
        return point.timing + (ms - point.cumulativeMs) * point.bpm / 60_000.0
    }

    fun bpmAtBeat(beat: Double): Double {
        val index = findLastLe(bpmPoints.size) { bpmPoints[it].timing <= beat }
        return if (index < 0) defaultBpm else bpmPoints[index].bpm
    }

    fun divisorAtBeat(beat: Double): Double {
        val index = findLastLe(divisorEvents.size) { divisorEvents[it].timing <= beat }
        return if (index < 0) 4.0 else divisorEvents[index].divisor
    }

    private inline fun findLastLe(size: Int, predicate: (Int) -> Boolean): Int {
        var lo = 0
        var hi = size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (predicate(mid)) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    private fun createBpmPoints(
        defaultBpm: Double,
        events: List<BpmEvent>?,
    ): List<BpmPoint> {
        if (events.isNullOrEmpty()) return emptyList()
        val points = mutableListOf<BpmPoint>()
        var currentBpm = defaultBpm
        var lastBeat = 0.0
        var cumulativeMs = 0.0
        for (event in events.sortedBy { it.timing }) {
            cumulativeMs += (event.timing - lastBeat) * 60_000.0 / currentBpm
            points += BpmPoint(event.timing, event.bpm, cumulativeMs)
            lastBeat = event.timing
            currentBpm = event.bpm
        }
        return points
    }
}
