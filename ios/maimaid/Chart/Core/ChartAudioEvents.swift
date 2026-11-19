import Foundation

/// One aggregated sound event. Notes sharing the same millisecond merge into a single event so a
/// tap and a hold release at the same time produce one tap sound, matching the reference engine's
/// `prepareAudioEvents`.
nonisolated final class ChartAudioEvent {
    let timeMs: Double
    var hasBaseSound = false
    var hasTapJudgeSound = false
    var hasBreakJudgeSound = false
    var hasExJudgeSound = false
    var hasSlideSound = false
    var hasBreakSlideSound = false
    var hasBreakSlideCheerSound = false
    var hasTouchSound = false
    var hasFireworkSound = false
    var hasTouchHoldSound = false
    var hasHoldEndSound = false
    var hasTouchHoldEndSound = false
    var hasTouchHoldEndFireworkSound = false
    var touchHoldDurationMs = 0.0

    init(timeMs: Double) {
        self.timeMs = timeMs
    }

    /// Answer layer plays for every audible event; touch/judge-only layers do not count.
    var playsAnswerSound: Bool {
        hasBaseSound || hasTouchSound || hasFireworkSound || hasHoldEndSound
            || hasTouchHoldEndSound || hasTouchHoldEndFireworkSound
    }

}

/// Builds the per-millisecond sound event list for a parsed chart.
nonisolated enum ChartAudioEvents {
    private struct SlidePath {
        var delayMs: Double
        var durationMs: Double
        var isBreak: Bool
    }

    private static func slidePaths(_ note: ChartNote) -> [SlidePath] {
        let fallbackDelay = note.delayMs
        let count = max(note.allDelayMs.count, max(note.allSlideBreaks.count, 1))
        return (0..<count).map { index in
            SlidePath(
                delayMs: index < note.allDelayMs.count ? note.allDelayMs[index] : fallbackDelay,
                durationMs: index < note.allDurationMs.count ? note.allDurationMs[index] : note.durationMs,
                isBreak: index < note.allSlideBreaks.count ? note.allSlideBreaks[index] : false
            )
        }
    }

    static func prepare(_ notes: [ChartNote]) -> [ChartAudioEvent] {
        guard !notes.isEmpty else { return [] }
        var events: [Int64: ChartAudioEvent] = [:]
        var order: [Int64] = []

        func eventAt(_ timeMs: Double) -> ChartAudioEvent {
            let key = Int64((timeMs * 1000.0).rounded())
            if let existing = events[key] { return existing }
            let created = ChartAudioEvent(timeMs: timeMs)
            events[key] = created
            order.append(key)
            return created
        }

        func markHeadJudge(_ event: ChartAudioEvent, isBreak: Bool, isEx: Bool) {
            if isBreak {
                event.hasBreakJudgeSound = true
            } else if isEx {
                event.hasExJudgeSound = true
            } else {
                event.hasTapJudgeSound = true
            }
        }

        for note in notes {
            let event = eventAt(note.timingMs)
            switch note.kind {
            case .slide:
                if !note.isHeadless {
                    event.hasBaseSound = true
                    markHeadJudge(event, isBreak: note.isStartBreak, isEx: note.isEx)
                }
                for path in slidePaths(note) {
                    let launch = eventAt(note.timingMs + path.delayMs)
                    launch.hasSlideSound = true
                    if path.isBreak {
                        launch.hasBreakSlideSound = true
                        eventAt(note.timingMs + path.delayMs + path.durationMs).hasBreakSlideCheerSound = true
                    }
                }
            case .tap:
                event.hasBaseSound = true
                if note.isBreak {
                    event.hasBreakJudgeSound = true
                } else {
                    markHeadJudge(event, isBreak: false, isEx: note.isEx)
                }
            case .holdStart:
                event.hasBaseSound = true
                markHeadJudge(event, isBreak: note.isBreakHold, isEx: note.isEx)
            case .touch:
                if note.hasFirework { event.hasFireworkSound = true } else { event.hasTouchSound = true }
            case .touchHoldStart:
                event.hasTouchSound = true
                event.hasTouchHoldSound = true
                event.touchHoldDurationMs = max(event.touchHoldDurationMs, note.durationMs)
            case .holdEnd:
                event.hasHoldEndSound = true
            case .touchHoldEnd:
                if note.hasFirework {
                    event.hasTouchHoldEndFireworkSound = true
                } else {
                    event.hasTouchHoldEndSound = true
                }
            }
        }

        return order.compactMap { events[$0] }.sorted { $0.timeMs < $1.timeMs }
    }
}
