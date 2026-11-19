import Foundation

/// Category of a parsed chart note. Mirrors the reference engine's note classes.
nonisolated enum ChartNoteKind: String, Sendable, CaseIterable {
    case tap
    case holdStart
    case holdEnd
    case slide
    case touch
    case touchHoldStart
    case touchHoldEnd
}

/// One parsed note of a simai chart.
///
/// The reference implementation models every note class separately, but every renderer only needs a
/// handful of shared fields plus a category switch. This flat shape keeps the render loop free of
/// existential casts while preserving all payload fields of each note class.
nonisolated struct ChartNote: Identifiable {
    /// Index of this note in `Chart.notes`, used as the render-metadata key.
    var id: Int = 0
    var kind: ChartNoteKind

    // Shared by every note class.
    var timing: Double
    var timingMs: Double
    var measure: Int
    var positionInMeasure: Int
    var scale: Double
    var bpm: Double
    var hasDelayMarker: Bool
    var hiSpeed: Double?
    var sourceNoteIndex: Int?

    /// 1-based button position for tap, hold and slide notes; 0 for touch notes.
    var position: Int = 0
    /// Sensor name (`A1`…`E8`, `C`) for touch notes; empty for button notes.
    var touchPosition: String = ""

    // Tap flags.
    var isSameTime: Bool = false
    var isBreak: Bool = false
    var isStar: Bool = false
    var isSpinningStar: Bool = false
    var isEx: Bool = false

    // Hold fields (`duration` is in beats for button holds, `holdStartTiming` only on ends).
    var duration: Double = 0
    var holdStartTiming: Double = 0
    var isBreakHold: Bool = false

    // Touch fields.
    var durationMs: Double = 0
    var hasFirework: Bool = false

    // Slide fields.
    var isHeadless: Bool = false
    var headlessMode: String?
    var hasTapHead: Bool = false
    var isStartBreak: Bool = false
    var allSlideBreaks: [Bool] = []
    var delayMs: Double = 0
    var slideSegments: [SlideSegment] = []
    var allSlideSegments: [[SlideSegment]] = []
    var allDurations: [Double] = []
    var allDurationMs: [Double] = []
    var allDelayMs: [Double] = []
    var allCustomLengths: [Double?] = []
    var isSplitSlide: Bool = false
    var customLength: Double?

    init(kind: ChartNoteKind, timing: Double, timingMs: Double, measure: Int, positionInMeasure: Int,
         scale: Double, bpm: Double, hasDelayMarker: Bool) {
        self.kind = kind
        self.timing = timing
        self.timingMs = timingMs
        self.measure = measure
        self.positionInMeasure = positionInMeasure
        self.scale = scale
        self.bpm = bpm
        self.hasDelayMarker = hasDelayMarker
    }

    /// New note with the parsed lead-in already applied.
    func withLeadIn(beatOffset: Double, msOffset: Double) -> ChartNote {
        var note = self
        note.timing += beatOffset
        note.timingMs += msOffset
        if kind == .holdEnd || kind == .touchHoldEnd {
            note.holdStartTiming += beatOffset
        }
        return note
    }

    /// New note carrying the current hi-speed marker.
    func withHiSpeed(_ value: Double?) -> ChartNote {
        var note = self
        note.hiSpeed = value
        return note
    }

    /// Button position or touch sensor name, whichever this note uses.
    var displayPosition: String {
        switch kind {
        case .touch, .touchHoldStart, .touchHoldEnd:
            return touchPosition
        default:
            return String(position)
        }
    }
}

// MARK: - Type guards mirroring the reference engine

nonisolated extension ChartNote {
    var isTap: Bool { kind == .tap }
    var isHoldStart: Bool { kind == .holdStart }
    var isHoldEnd: Bool { kind == .holdEnd }
    var isSlide: Bool { kind == .slide }
    var isTouch: Bool { kind == .touch }
    var isTouchHoldStart: Bool { kind == .touchHoldStart }
    var isTouchHoldEnd: Bool { kind == .touchHoldEnd }
    var isTouchHold: Bool { kind == .touchHoldStart || kind == .touchHoldEnd }

    /// Hold length in milliseconds for a button hold start.
    var holdLengthMs: Double { 60_000.0 * duration / bpm }

    /// Timestamp of the hold release for a button hold start.
    var holdEndMs: Double { timingMs + holdLengthMs }

    /// Whether this note counts as a break for the break counters.
    var isBreakNote: Bool {
        switch kind {
        case .tap: return isBreak
        case .slide: return isStartBreak
        case .holdStart, .holdEnd: return isBreakHold
        default: return false
        }
    }
}
