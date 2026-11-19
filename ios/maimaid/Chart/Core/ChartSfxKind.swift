import Foundation

/// The engine's judge / answer sound set.
///
/// `mono` mirrors the reference engine: these layers are monophonic and a new instance terminates
/// the previous one. The mixer honours the flag with a short fade instead of a hard cut.
nonisolated enum ChartSfxKind: String, CaseIterable, Sendable {
    case answer
    case tap
    case touch
    case breakSound
    case slide
    case cheer
    case ex
    case firework
    case breakSlide
    case breakSlideCheer
    case touchHold

    /// File name inside the bundled `maimai/chart` sound directory.
    var assetName: String {
        switch self {
        case .answer: return "answer.wav"
        case .tap: return "tap.wav"
        case .touch: return "touch.wav"
        case .breakSound: return "break.wav"
        case .slide: return "slide.wav"
        case .cheer: return "cheer.wav"
        case .ex: return "ex.wav"
        case .firework: return "firework.wav"
        case .breakSlide: return "break_slide.wav"
        case .breakSlideCheer: return "break_slide_cheer.wav"
        case .touchHold: return "touch_hold.wav"
        }
    }

    /// Whether a new instance terminates the previous one.
    var isMono: Bool {
        switch self {
        case .answer, .tap, .touch: return false
        case .breakSound, .slide, .cheer, .ex, .firework, .breakSlide, .breakSlideCheer, .touchHold:
            return true
        }
    }

    /// Stable numeric identity used by the frame-addressed mix events.
    var ordinal: Int {
        switch self {
        case .answer: return 0
        case .tap: return 1
        case .touch: return 2
        case .breakSound: return 3
        case .slide: return 4
        case .cheer: return 5
        case .ex: return 6
        case .firework: return 7
        case .breakSlide: return 8
        case .breakSlideCheer: return 9
        case .touchHold: return 10
        }
    }

    static func kind(forOrdinal ordinal: Int) -> ChartSfxKind? {
        allCases.first { $0.ordinal == ordinal }
    }
}
