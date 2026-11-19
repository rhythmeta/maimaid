import Foundation

/// BPM change event.
nonisolated struct BpmEvent: Sendable {
    var timing: Double
    var bpm: Double
}

/// Time-signature divisor change event.
nonisolated struct DivisorEvent: Sendable {
    var timing: Double
    var divisor: Double
}

/// Fully parsed chart.
nonisolated struct Chart {
    var title: String
    var artist: String
    var designer: String
    var bpm: Double
    var level: [Int: String]
    var designers: [Int: String]
    var difficulty: Int?
    var availableDifficulties: Set<Int>
    var measures: Int
    var notes: [ChartNote]
    var bpmEvents: [BpmEvent]
    var divisorEvents: [DivisorEvent]
    var firstMs: Double?
    /// Designer of the parsed difficulty, when the maidata carries one.
    var sheetDesigner: String?

    init(title: String = "", artist: String = "", designer: String = "", bpm: Double = 0,
         level: [Int: String] = [:], designers: [Int: String] = [:], difficulty: Int? = nil,
         availableDifficulties: Set<Int> = [], measures: Int = 0, notes: [ChartNote] = [],
         bpmEvents: [BpmEvent] = [], divisorEvents: [DivisorEvent] = [], firstMs: Double? = nil,
         sheetDesigner: String? = nil) {
        self.title = title
        self.artist = artist
        self.designer = designer
        self.bpm = bpm
        self.level = level
        self.designers = designers
        self.difficulty = difficulty
        self.availableDifficulties = availableDifficulties
        self.measures = measures
        self.notes = notes
        self.bpmEvents = bpmEvents
        self.divisorEvents = divisorEvents
        self.firstMs = firstMs
        self.sheetDesigner = sheetDesigner
    }

    /// Total playable length in ms, including hold tails and slide paths.
    var endTimeMs: Double {
        var maxTimingMs = 0.0
        for note in notes {
            let end: Double
            switch note.kind {
            case .holdStart:
                end = note.timingMs + note.holdLengthMs
            case .touchHoldStart:
                end = note.timingMs + note.durationMs
            case .slide:
                var pathEnd = note.timingMs
                let count = max(note.allSlideSegments.count, 1)
                for index in 0..<count {
                    let delay = index < note.allDelayMs.count ? note.allDelayMs[index] : note.delayMs
                    let duration = index < note.allDurationMs.count ? note.allDurationMs[index] : note.durationMs
                    let candidate = note.timingMs + delay + duration
                    if candidate > pathEnd { pathEnd = candidate }
                }
                end = pathEnd
            default:
                end = note.timingMs
            }
            if end > maxTimingMs { maxTimingMs = end }
        }
        return maxTimingMs
    }

    /// One measure of lead-in at the chart's initial BPM.
    var leadInMs: Double { 60_000.0 * 4.0 / bpm }
}

/// Button position on the judgment ring for an angle.
nonisolated func ringPoint(centerX: Double, centerY: Double, radius: Double, angle: Double) -> ChartPoint {
    ChartPoint(centerX + cos(angle) * radius, centerY + sin(angle) * radius)
}

/// Full turn in radians, kept for readability of angle math.
nonisolated let twoPi = Double.pi * 2
