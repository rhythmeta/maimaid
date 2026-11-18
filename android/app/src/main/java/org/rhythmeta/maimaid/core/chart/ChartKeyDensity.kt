package org.rhythmeta.maimaid.core.chart

/**
 * Keys-per-second readout for the preview HUD.
 *
 * Only note heads count as a key press: holds and touch holds contribute their start, slides their
 * head (headless slides still need one input), and chords contribute one key per note. Hold and
 * touch-hold releases are not extra presses.
 */
object ChartKeyDensity {
    const val DEFAULT_WINDOW_MS = 1_000.0

    /** Sorted press times (ms) for [notes], ready for [keysPerSecond]. */
    fun pressTimes(notes: List<ChartNote>): DoubleArray =
        notes.asSequence()
            .filter { note ->
                note is TapNote || note is HoldStartNote || note is SlideNote ||
                    note is TouchNote || note is TouchHoldStartNote
            }
            .map { it.timingMs }
            .sorted()
            .toList()
            .toDoubleArray()

    /** Presses inside the trailing [windowMs] ending at [currentTimeMs]. */
    fun keysPerSecond(
        times: DoubleArray,
        currentTimeMs: Double,
        windowMs: Double = DEFAULT_WINDOW_MS,
    ): Int {
        if (times.isEmpty()) return 0
        val from = upperBound(times, currentTimeMs - windowMs)
        val to = upperBound(times, currentTimeMs)
        return (to - from).coerceAtLeast(0)
    }

    /** First index whose value is greater than [value]. */
    private fun upperBound(times: DoubleArray, value: Double): Int {
        var low = 0
        var high = times.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (times[mid] <= value) low = mid + 1 else high = mid
        }
        return low
    }
}
