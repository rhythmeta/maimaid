import Foundation

/// Persisted chart-preview settings, mirroring the reference app's speed / display / audio panels.
/// Speeds are stored in UI units (3–9) and converted for the renderer.
nonisolated struct ChartPreviewSettings: Codable, Equatable, Sendable {
    var hiSpeed: Double = 6.0
    var playbackSpeed: Double = 1.0
    /// Vertical flow of the rhythm lane, as a multiplier of its base points-per-ms scale.
    var rhythmSpeed: Double = 1.0
    var slideDelay: Double = 0.0
    var alwaysKeepHiSpeed: Bool = false
    var mirrorMode: MirrorMode = .none
    var judgmentLineDesign: JudgmentLineDesign = .simple
    var slideRotation: Bool = true
    var pinkSlideStart: Bool = false
    var highlightExNotes: Bool = false
    var normalColorBreakSlide: Bool = false
    var showFireworks: Bool = true
    var showHitEffect: Bool = true
    var showNoteTotal: Bool = true
    var showBreakCount: Bool = true
    var musicVolume: Float = 1
    var answerVolume: Float = 1
    var judgeVolume: Float = 1
    /// Positive values delay the music relative to the chart.
    var musicOffsetMs: Int = 0
    /// Positive values delay the judge/answer SFX relative to the chart.
    var sfxOffsetMs: Int = 0

    static let `default` = ChartPreviewSettings()

    func toRenderConfig(comboLabel: String, breakNoExLabel: String) -> ChartRenderConfig {
        var config = ChartRenderConfig()
        config.hiSpeed = ChartRenderConfig.fromUiHiSpeed(hiSpeed)
        config.alwaysKeepHiSpeed = alwaysKeepHiSpeed
        config.slideDelay = slideDelay
        config.playbackSpeed = playbackSpeed
        config.mirrorMode = mirrorMode
        config.highlightExNotes = highlightExNotes
        config.normalColorBreakSlide = normalColorBreakSlide
        config.pinkSlideStart = pinkSlideStart
        config.slideRotation = slideRotation
        config.judgmentLineDesign = judgmentLineDesign
        config.showBpm = true
        config.showNoteTotal = showNoteTotal
        config.showBreakCount = showBreakCount
        config.showBreakIndex = false
        config.rainbowBpm = false
        config.ddrColorMode = false
        config.ddrColorExtended = false
        config.showFireworks = showFireworks
        config.showHitEffect = showHitEffect
        config.hudComboLabel = comboLabel
        config.hudBreakNoExLabel = breakNoExLabel
        return config
    }
}

/// Stores the chart-preview settings as JSON in `UserDefaults`.
@MainActor
@Observable
final class ChartPreviewSettingsStore {
    static let shared = ChartPreviewSettingsStore()

    private static let storageKey = "chart.preview.settings"

    private(set) var settings: ChartPreviewSettings

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode(ChartPreviewSettings.self, from: data) {
            settings = decoded
        } else {
            settings = .default
        }
    }

    private let defaults: UserDefaults

    func update(_ transform: (inout ChartPreviewSettings) -> Void) {
        var next = settings
        transform(&next)
        guard next != settings else { return }
        settings = next
        persist()
    }

    func replace(with next: ChartPreviewSettings) {
        guard next != settings else { return }
        settings = next
        persist()
    }

    func reset() {
        settings = .default
        persist()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        defaults.set(data, forKey: Self.storageKey)
    }
}
