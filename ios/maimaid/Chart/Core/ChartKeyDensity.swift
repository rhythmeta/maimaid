import Foundation

/// Keys-per-second readout for the preview HUD.
///
/// Only note heads count as a key press: holds and touch holds contribute their start, slides their
/// head (headless slides still need one input), and chords contribute one key per note. Hold and
/// touch-hold releases are not extra presses.
nonisolated enum ChartKeyDensity {
    static let defaultWindowMs = 1_000.0

    /// Sorted press times (ms) for `notes`, ready for `keysPerSecond`.
    static func pressTimes(_ notes: [ChartNote]) -> [Double] {
        notes
            .filter { note in
                note.isTap || note.isHoldStart || note.isSlide || note.isTouch || note.isTouchHoldStart
            }
            .map(\.timingMs)
            .sorted()
    }

    /// Presses inside the trailing `windowMs` ending at `currentTimeMs`.
    static func keysPerSecond(_ times: [Double], currentTimeMs: Double,
                              windowMs: Double = defaultWindowMs) -> Int {
        guard !times.isEmpty else { return 0 }
        let from = upperBound(times, currentTimeMs - windowMs)
        let to = upperBound(times, currentTimeMs)
        return max(0, to - from)
    }

    /// First index whose value is greater than `value`.
    private static func upperBound(_ times: [Double], _ value: Double) -> Int {
        var low = 0
        var high = times.count
        while low < high {
            let mid = (low + high) >> 1
            if times[mid] <= value { low = mid + 1 } else { high = mid }
        }
        return low
    }
}
