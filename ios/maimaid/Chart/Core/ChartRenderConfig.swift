import Foundation

/// Mirror transform applied to button and touch positions.
nonisolated enum MirrorMode: String, Codable, Sendable, CaseIterable, Identifiable {
    case none
    case horizontal
    case vertical
    case rotate180

    var id: String { rawValue }
}

/// Judgment line presentation, matching the reference engine's four designs.
nonisolated enum JudgmentLineDesign: String, Codable, Sendable, CaseIterable, Identifiable {
    case blind
    case noLine
    case simple
    case sensor

    var id: String { rawValue }
}

/// Renderer configuration mirroring the reference `RendererConfig` with the web app defaults.
nonisolated struct ChartRenderConfig: Sendable {
    /// Internal approach-time divisor: UI speed 6 maps to 3.6.
    var hiSpeed: Double = ChartConstants.hiSpeedDefault * ChartConstants.hiSpeedConversionFactor
    var alwaysKeepHiSpeed: Bool = false
    var slideDelay: Double = 0
    var playbackSpeed: Double = 1
    var mirrorMode: MirrorMode = .none
    var highlightExNotes: Bool = false
    var normalColorBreakSlide: Bool = false
    var pinkSlideStart: Bool = false
    var slideRotation: Bool = true
    var judgmentLineDesign: JudgmentLineDesign = .simple
    var showBpm: Bool = true
    var showNoteTotal: Bool = true
    var showBreakCount: Bool = true
    var showBreakIndex: Bool = false
    var rainbowBpm: Bool = false
    var ddrColorMode: Bool = false
    var ddrColorExtended: Bool = false
    var showFireworks: Bool = true
    var showHitEffect: Bool = true
    /// Localized in-canvas HUD copy.
    var hudComboLabel: String = "COMBO"
    var hudBreakNoExLabel: String = "NO EX"

    /// `setHiSpeed(3...9)` from the reference app, converted to the internal scale.
    static func fromUiHiSpeed(_ speed: Double) -> Double {
        min(9, max(3, speed)) * ChartConstants.hiSpeedConversionFactor
    }
}
