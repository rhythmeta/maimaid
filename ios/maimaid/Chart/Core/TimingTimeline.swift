import Foundation

/// Beat ↔ millisecond conversion with BPM changes integrated into cumulative timestamps.
/// Mirrors the reference `TimingTimeline`; every renderer path reuses this instead of scanning
/// BPM events itself.
nonisolated struct TimingTimeline {
    private struct BpmPoint {
        var timing: Double
        var bpm: Double
        var cumulativeMs: Double
    }

    private let defaultBpm: Double
    private let bpmPoints: [BpmPoint]
    private let divisorEvents: [DivisorEvent]

    init(defaultBpm: Double, bpmEvents: [BpmEvent]? = nil, divisorEvents: [DivisorEvent]? = nil) {
        self.defaultBpm = defaultBpm
        self.bpmPoints = Self.createBpmPoints(defaultBpm: defaultBpm, events: bpmEvents)
        self.divisorEvents = (divisorEvents ?? []).sorted { $0.timing < $1.timing }
    }

    static func fromChart(_ chart: Chart) -> TimingTimeline {
        TimingTimeline(defaultBpm: chart.bpm, bpmEvents: chart.bpmEvents, divisorEvents: chart.divisorEvents)
    }

    func msFromBeat(_ beat: Double) -> Double {
        let index = lastIndex(bpmPoints.count) { bpmPoints[$0].timing <= beat }
        guard index >= 0 else { return 60_000.0 * beat / defaultBpm }
        let point = bpmPoints[index]
        return point.cumulativeMs + (beat - point.timing) * 60_000.0 / point.bpm
    }

    func beatFromMs(_ ms: Double) -> Double {
        let index = lastIndex(bpmPoints.count) { bpmPoints[$0].cumulativeMs <= ms }
        guard index >= 0 else { return ms * defaultBpm / 60_000.0 }
        let point = bpmPoints[index]
        return point.timing + (ms - point.cumulativeMs) * point.bpm / 60_000.0
    }

    func bpmAtBeat(_ beat: Double) -> Double {
        let index = lastIndex(bpmPoints.count) { bpmPoints[$0].timing <= beat }
        return index < 0 ? defaultBpm : bpmPoints[index].bpm
    }

    func divisorAtBeat(_ beat: Double) -> Double {
        let index = lastIndex(divisorEvents.count) { divisorEvents[$0].timing <= beat }
        return index < 0 ? 4.0 : divisorEvents[index].divisor
    }

    private func lastIndex(_ size: Int, where predicate: (Int) -> Bool) -> Int {
        var lo = 0
        var hi = size - 1
        var result = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if predicate(mid) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    private static func createBpmPoints(defaultBpm: Double, events: [BpmEvent]?) -> [BpmPoint] {
        guard let events, !events.isEmpty else { return [] }
        var points: [BpmPoint] = []
        var currentBpm = defaultBpm
        var lastBeat = 0.0
        var cumulativeMs = 0.0
        for event in events.sorted(by: { $0.timing < $1.timing }) {
            cumulativeMs += (event.timing - lastBeat) * 60_000.0 / currentBpm
            points.append(BpmPoint(timing: event.timing, bpm: event.bpm, cumulativeMs: cumulativeMs))
            lastBeat = event.timing
            currentBpm = event.bpm
        }
        return points
    }
}
