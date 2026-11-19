import Foundation

/// Per-note derived render metadata: chord size, break ordering and source index.
nonisolated final class RenderNoteMeta {
    var simultaneousNoteCount: Int = 0
    var simultaneousSlideCount: Int = 0
    var simultaneousNonTouchCount: Int = 0
    var simultaneousTouchCount: Int = 0
    var noExBreakIndex: Int?
    var sourceNoteIndex: Int?
}

/// Sorted timings plus prefix-maximum end times for binary window culling.
nonisolated struct TimeWindowIndex {
    let timingMs: [Double]
    let prefixMaxEndMs: [Double]

    /// Returns the half-open range `[start, end)` of items visible right now.
    func range(nowMs: Double, lookAheadMs: Double) -> Range<Int> {
        let limit = nowMs + lookAheadMs
        var lo = 0
        var hi = timingMs.count
        while lo < hi {
            let mid = (lo + hi) >> 1
            if timingMs[mid] <= limit { lo = mid + 1 } else { hi = mid }
        }
        let end = lo
        lo = 0
        hi = end
        while lo < hi {
            let mid = (lo + hi) >> 1
            if prefixMaxEndMs[mid] < nowMs { lo = mid + 1 } else { hi = mid }
        }
        return lo..<end
    }

    static func build<T>(items: [T], timingOf: (T) -> Double, endOf: (T) -> Double) -> TimeWindowIndex {
        var timings = [Double](repeating: 0, count: items.count)
        var prefix = [Double](repeating: 0, count: items.count)
        var maxEnd = -Double.infinity
        for index in items.indices {
            let item = items[index]
            timings[index] = timingOf(item)
            let end = endOf(item)
            if end > maxEnd { maxEnd = end }
            prefix[index] = maxEnd
        }
        return TimeWindowIndex(timingMs: timings, prefixMaxEndMs: prefix)
    }
}

/// A group of hold heads and slide heads sharing one judgment timestamp.
nonisolated struct ApproachIndicatorGroup {
    var timingMs: Double
    var notes: [ApproachIndicatorNote]
}

/// A hold or slide head that carries an approach indicator.
nonisolated enum ApproachIndicatorNote {
    case hold(ChartNote)
    case slide(ChartNote)

    var note: ChartNote {
        switch self {
        case .hold(let note), .slide(let note): return note
        }
    }

    var position: Int { note.position }
    var timing: Double { note.timing }
    var timingMs: Double { note.timingMs }
    var hiSpeed: Double? { note.hiSpeed }
}

/// Tap / hold head / slide head sharing one z-layer.
nonisolated enum LayeredHead {
    case tap(ChartNote)
    case hold(ChartNote)
    case slideStart(ChartNote)

    var note: ChartNote {
        switch self {
        case .tap(let note), .hold(let note), .slideStart(let note): return note
        }
    }

    var timingMs: Double { note.timingMs }
}

/// A chart indexed for the render loop. Building this is the only place that walks every note:
/// category lists, chord metadata, break indices, completion counters and time windows are all
/// derived once per chart, exactly like the reference `prepareRenderNotes`.
nonisolated final class PreparedChart {
    let chart: Chart
    let slides: [ChartNote]
    let touches: [ChartNote]
    let fireworkTouches: [ChartNote]
    let holdEffectNotes: [ChartNote]
    let touchHoldNotes: [ChartNote]
    let holdEndMap: [String: ChartNote]
    let meta: [Int: RenderNoteMeta]
    let approachGroups: [ApproachIndicatorGroup]
    let layeredHeads: [LayeredHead]
    let hitEffectNotes: [ChartNote]
    let noteCompletionTimes: [Double]
    let breakCompletionTimes: [Double]
    let breakNoExCompletionTimes: [Double]
    let slideIndex: TimeWindowIndex
    let touchIndex: TimeWindowIndex
    let layeredIndex: TimeWindowIndex
    let approachIndex: TimeWindowIndex
    let holdEffectIndex: TimeWindowIndex
    let touchHoldIndex: TimeWindowIndex
    let minHiSpeed: Double

    private init(chart: Chart, slides: [ChartNote], touches: [ChartNote], fireworkTouches: [ChartNote],
                 holdEffectNotes: [ChartNote], touchHoldNotes: [ChartNote],
                 holdEndMap: [String: ChartNote], meta: [Int: RenderNoteMeta],
                 approachGroups: [ApproachIndicatorGroup], layeredHeads: [LayeredHead],
                 hitEffectNotes: [ChartNote], noteCompletionTimes: [Double],
                 breakCompletionTimes: [Double], breakNoExCompletionTimes: [Double],
                 slideIndex: TimeWindowIndex, touchIndex: TimeWindowIndex,
                 layeredIndex: TimeWindowIndex, approachIndex: TimeWindowIndex,
                 holdEffectIndex: TimeWindowIndex, touchHoldIndex: TimeWindowIndex,
                 minHiSpeed: Double) {
        self.chart = chart
        self.slides = slides
        self.touches = touches
        self.fireworkTouches = fireworkTouches
        self.holdEffectNotes = holdEffectNotes
        self.touchHoldNotes = touchHoldNotes
        self.holdEndMap = holdEndMap
        self.meta = meta
        self.approachGroups = approachGroups
        self.layeredHeads = layeredHeads
        self.hitEffectNotes = hitEffectNotes
        self.noteCompletionTimes = noteCompletionTimes
        self.breakCompletionTimes = breakCompletionTimes
        self.breakNoExCompletionTimes = breakNoExCompletionTimes
        self.slideIndex = slideIndex
        self.touchIndex = touchIndex
        self.layeredIndex = layeredIndex
        self.approachIndex = approachIndex
        self.holdEffectIndex = holdEffectIndex
        self.touchHoldIndex = touchHoldIndex
        self.minHiSpeed = minHiSpeed
    }

    static var emptyMeta: RenderNoteMeta { RenderNoteMeta() }

    func metaOf(_ note: ChartNote) -> RenderNoteMeta {
        meta[note.id] ?? Self.emptyMeta
    }

    func countCompleted(_ sortedTimes: [Double], _ currentTimeMs: Double) -> Int {
        var lo = 0
        var hi = sortedTimes.count
        while lo < hi {
            let mid = (lo + hi) >> 1
            if sortedTimes[mid] <= currentTimeMs { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    private static func timingKey(_ ms: Double) -> Int64 {
        Int64((ms * 1000.0).rounded())
    }

    private static func slideVisibleEndMs(_ note: ChartNote) -> Double {
        let pathCount = max(note.allSlideSegments.count, 1)
        var end = note.timingMs
        for index in 0..<pathCount {
            let delay = index < note.allDelayMs.count ? note.allDelayMs[index] : note.delayMs
            let duration = index < note.allDurationMs.count ? note.allDurationMs[index] : note.durationMs
            let pathEnd = note.timingMs + delay + duration
            if pathEnd > end { end = pathEnd }
        }
        return end
    }

    private static func layeredHeadEndMs(_ item: LayeredHead) -> Double {
        switch item {
        case .hold(let note):
            return note.timingMs + note.holdLengthMs
        default:
            return item.note.timingMs + ChartConstants.noteVisibilityAfterMs
        }
    }

    private static func touchVisibleEndMs(_ note: ChartNote) -> Double {
        let holdMs = note.kind == .touchHoldStart ? note.durationMs : 0
        return note.timingMs + holdMs + 50.0
    }

    static func build(chart: Chart) -> PreparedChart {
        let notes = chart.notes
        var slides: [ChartNote] = []
        var touches: [ChartNote] = []
        var fireworkTouches: [ChartNote] = []
        var holds: [ChartNote] = []
        var taps: [ChartNote] = []
        var holdEndMap: [String: ChartNote] = [:]
        var meta: [Int: RenderNoteMeta] = [:]
        var hitEffectNotes: [ChartNote] = []
        var noteCompletionTimes: [Double] = []
        var breakCompletionTimes: [Double] = []
        var breakNoExCompletionTimes: [Double] = []

        func metaFor(_ note: ChartNote) -> RenderNoteMeta {
            if let existing = meta[note.id] { return existing }
            let created = RenderNoteMeta()
            meta[note.id] = created
            return created
        }

        for note in notes.reversed() {
            switch note.kind {
            case .slide: slides.append(note)
            case .touch, .touchHoldStart: touches.append(note)
            case .holdStart: holds.append(note)
            case .tap: taps.append(note)
            default: break
            }
        }

        for (note, sourceIndex) in prepareTouchSourceIndices(notes) {
            metaFor(note).sourceNoteIndex = sourceIndex
        }

        calculateSimultaneousCounts(notes, metaFor: metaFor)
        assignBreakIndices(notes, metaFor: metaFor)

        for note in notes {
            if note.kind == .tap || note.kind == .holdEnd
                || (note.kind == .slide && !note.isHeadless)
                || note.kind == .touch || note.kind == .touchHoldEnd {
                noteCompletionTimes.append(note.timingMs)
            }

            if note.kind == .holdEnd {
                holdEndMap["\(note.position):\(note.holdStartTiming)"] = note
            }

            let hasFirework = (note.kind == .touch || note.kind == .touchHoldStart) ? note.hasFirework : false
            if hasFirework { fireworkTouches.append(note) }

            if note.kind == .slide {
                let pathCount = max(note.allSlideSegments.count, 1)
                for pathIndex in 0..<pathCount {
                    let delay = pathIndex < note.allDelayMs.count ? note.allDelayMs[pathIndex] : 0
                    let duration = pathIndex < note.allDurationMs.count ? note.allDurationMs[pathIndex] : 0
                    noteCompletionTimes.append(note.timingMs + delay + duration)
                }
            }

            if note.isBreakNote {
                breakCompletionTimes.append(note.timingMs)
                // The reference reads `isEx` off every break note, so EX holds and EX slides are
                // excluded from the "no EX" counter just like EX taps.
                if !note.isEx {
                    breakNoExCompletionTimes.append(note.timingMs)
                }
            }

            if note.kind == .slide {
                for (pathIndex, pathBreak) in note.allSlideBreaks.enumerated() where pathBreak {
                    let delay = pathIndex < note.allDelayMs.count ? note.allDelayMs[pathIndex] : 0
                    let duration = pathIndex < note.allDurationMs.count ? note.allDurationMs[pathIndex] : 0
                    breakCompletionTimes.append(note.timingMs + delay + duration)
                }
            }

            let isButtonNote = note.kind == .tap || note.kind == .holdEnd
                || note.kind == .holdStart || note.kind == .slide
            if isButtonNote && note.kind != .holdStart && !(note.kind == .slide && note.isHeadless) {
                hitEffectNotes.append(note)
            }
        }

        hitEffectNotes = stableSorted(hitEffectNotes) { $0.timingMs < $1.timingMs }
        noteCompletionTimes.sort()
        breakCompletionTimes.sort()
        breakNoExCompletionTimes.sort()

        var layeredHeads: [LayeredHead] = []
        for tap in taps { layeredHeads.append(.tap(tap)) }
        for hold in holds { layeredHeads.append(.hold(hold)) }
        for slide in slides where !slide.isHeadless { layeredHeads.append(.slideStart(slide)) }
        layeredHeads = stableSorted(layeredHeads) { $0.timingMs > $1.timingMs }

        var approachGroups = prepareApproachGroups(holds: holds, slides: slides)

        let sortedSlides = stableSorted(slides) { $0.timingMs < $1.timingMs }
        let sortedTouches = stableSorted(touches) { $0.timingMs < $1.timingMs }
        let sortedHeads = layeredHeads.reversed()
        approachGroups = stableSorted(approachGroups) { $0.timingMs < $1.timingMs }
        fireworkTouches = stableSorted(fireworkTouches) { fireworkTriggerMs($0) < fireworkTriggerMs($1) }

        let holdEffectNotes = stableSorted(holds) { $0.timingMs < $1.timingMs }
        let touchHoldNotes = sortedTouches.filter { $0.kind == .touchHoldStart }

        var minHiSpeed = 1.0
        for note in notes {
            guard let hiSpeed = note.hiSpeed else { continue }
            let magnitude = abs(hiSpeed)
            if magnitude > 0 && magnitude < minHiSpeed { minHiSpeed = magnitude }
        }

        return PreparedChart(
            chart: chart,
            slides: sortedSlides,
            touches: sortedTouches,
            fireworkTouches: fireworkTouches,
            holdEffectNotes: holdEffectNotes,
            touchHoldNotes: touchHoldNotes,
            holdEndMap: holdEndMap,
            meta: meta,
            approachGroups: approachGroups,
            layeredHeads: Array(sortedHeads),
            hitEffectNotes: hitEffectNotes,
            noteCompletionTimes: noteCompletionTimes,
            breakCompletionTimes: breakCompletionTimes,
            breakNoExCompletionTimes: breakNoExCompletionTimes,
            slideIndex: TimeWindowIndex.build(items: sortedSlides, timingOf: { $0.timingMs },
                                              endOf: slideVisibleEndMs),
            touchIndex: TimeWindowIndex.build(items: sortedTouches, timingOf: { $0.timingMs },
                                              endOf: touchVisibleEndMs),
            layeredIndex: TimeWindowIndex.build(items: Array(sortedHeads), timingOf: { $0.timingMs },
                                                endOf: layeredHeadEndMs),
            approachIndex: TimeWindowIndex.build(items: approachGroups, timingOf: { $0.timingMs },
                                                 endOf: { $0.timingMs }),
            holdEffectIndex: TimeWindowIndex.build(items: holdEffectNotes, timingOf: { $0.timingMs },
                                                   endOf: { hold in
                let end = holdEndMap["\(hold.position):\(hold.timing)"]
                return end?.timingMs ?? hold.timingMs
            }),
            touchHoldIndex: TimeWindowIndex.build(items: touchHoldNotes, timingOf: { $0.timingMs },
                                                  endOf: {
                $0.timingMs + $0.durationMs + ChartConstants.noteHitEffectDurationMs
            }),
            minHiSpeed: minHiSpeed
        )
    }

    private static func calculateSimultaneousCounts(_ notes: [ChartNote],
                                                    metaFor: (ChartNote) -> RenderNoteMeta) {
        var order: [Int64] = []
        var byTiming: [Int64: [ChartNote]] = [:]
        for note in notes {
            // Note ends are release events, not extra hits: a tap that lands on the same
            // millisecond as a hold/touch-hold release must not read as a simultaneous pair.
            if note.kind == .holdEnd || note.kind == .touchHoldEnd || note.hasDelayMarker { continue }
            let key = timingKey(note.timingMs)
            if byTiming[key] == nil {
                byTiming[key] = []
                order.append(key)
            }
            byTiming[key]?.append(note)
        }

        for key in order {
            guard let group = byTiming[key] else { continue }
            let tapCount = group.count { !($0.kind == .tap && $0.isBreak) }
            let breakCount = group.count {
                ($0.kind == .tap && $0.isBreak) || ($0.kind == .slide && $0.isStartBreak)
                    || ($0.kind == .holdStart && $0.isBreakHold)
            }
            let slideCount = group.count { $0.kind == .slide }
            let nonTouchCount = group.count {
                $0.kind != .touch && $0.kind != .touchHoldStart && !($0.kind == .tap && $0.isBreak)
            }

            var touchByPos: [String: Int] = [:]
            for note in group {
                guard note.kind == .touch || note.kind == .touchHoldStart else { continue }
                touchByPos[note.touchPosition, default: 0] += 1
            }

            for note in group {
                let isSimultaneous = tapCount >= 2 || (breakCount >= 1 && tapCount >= 1)
                let noteMeta = metaFor(note)
                noteMeta.simultaneousNoteCount = isSimultaneous ? max(2, tapCount) : tapCount
                noteMeta.simultaneousSlideCount = slideCount
                noteMeta.simultaneousNonTouchCount = nonTouchCount
                if note.kind == .touch || note.kind == .touchHoldStart {
                    noteMeta.simultaneousTouchCount = touchByPos[note.touchPosition] ?? 1
                }
            }
        }
    }

    private static func assignBreakIndices(_ notes: [ChartNote], metaFor: (ChartNote) -> RenderNoteMeta) {
        let breakNotes = stableSorted(notes.filter { $0.isBreakNote && !$0.isEx }) {
            $0.timingMs < $1.timingMs
        }
        for (index, note) in breakNotes.enumerated() {
            metaFor(note).noExBreakIndex = index + 1
        }
    }

    private static func prepareApproachGroups(holds: [ChartNote],
                                              slides: [ChartNote]) -> [ApproachIndicatorGroup] {
        var order: [Int64] = []
        var byTiming: [Int64: [ApproachIndicatorNote]] = [:]
        func insert(_ note: ChartNote, _ item: ApproachIndicatorNote) {
            let key = timingKey(note.timingMs)
            if byTiming[key] == nil {
                byTiming[key] = []
                order.append(key)
            }
            byTiming[key]?.append(item)
        }
        for hold in holds { insert(hold, .hold(hold)) }
        for slide in slides where !slide.isHeadless { insert(slide, .slide(slide)) }
        return order.compactMap { key in
            guard let notes = byTiming[key], let first = notes.first else { return nil }
            return ApproachIndicatorGroup(timingMs: first.timingMs, notes: notes)
        }
    }

    /// Touch z-order source indices: synthetic hold tails do not consume a slot.
    static func prepareTouchSourceIndices(_ notes: [ChartNote]) -> [(ChartNote, Int)] {
        var result: [(ChartNote, Int)] = []
        var reserved = Set<Int>()
        for note in notes {
            if let index = note.sourceNoteIndex { reserved.insert(index) }
        }

        var index = 0
        for note in notes {
            if note.kind == .holdEnd || note.kind == .touchHoldEnd { continue }
            let isTouch = note.kind == .touch || note.kind == .touchHoldStart
            if let sourceIndex = note.sourceNoteIndex {
                if isTouch { result.append((note, sourceIndex)) }
                index = max(index, sourceIndex + 1)
            } else {
                while reserved.contains(index) { index += 1 }
                if isTouch { result.append((note, index)) }
                index += 1
            }
        }
        return result
    }

    static func fireworkTriggerMs(_ note: ChartNote) -> Double {
        note.kind == .touchHoldStart ? note.timingMs + note.durationMs : note.timingMs
    }
}


/// Stable sort helper: Swift's `sorted` is not guaranteed stable, while the reference
/// implementation relies on Kotlin's stable `sortedBy` for tie ordering.
nonisolated func stableSorted<T>(_ items: [T], by areInIncreasingOrder: (T, T) -> Bool) -> [T] {
    items.enumerated()
        .sorted { lhs, rhs in
            let l = lhs.element
            let r = rhs.element
            if areInIncreasingOrder(l, r) { return true }
            if areInIncreasingOrder(r, l) { return false }
            return lhs.offset < rhs.offset
        }
        .map(\.element)
}
