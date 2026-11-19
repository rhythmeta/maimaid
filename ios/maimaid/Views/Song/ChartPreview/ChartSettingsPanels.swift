import SwiftUI

// The three settings panels behind the chart-preview page. Each panel is a 1:1 control port of the
// Android reference (`ChartPreviewSettingsPanels.kt`): same ranges, same commit rounding, same
// defaults. Only the widgets changed — Miuix chips became native segmented pickers and switches.
// String keys use the feature's `chart.preview.settings.*` namespace; each one maps 1:1 to the
// reference panel's `chart_settings_*` resource.

/// Speed controls: hi-speed, playback speed, rhythm lane speed, slide delay and chart-speed lock.
///
/// The reference sliders are continuous (no step) and round only when the drag ends: rhythm lane
/// speed to two decimals, slide delay to one. Playback speed additionally sticks to exactly 1x.
struct ChartSpeedSettingsPanel: View {
    let settings: ChartPreviewSettings
    let onChange: (ChartPreviewSettings) -> Void

    @State private var isExpanded = false

    private var binder: ChartSettingsBinder {
        ChartSettingsBinder(settings: settings, onChange: onChange)
    }

    var body: some View {
        ChartSettingsCard(
            title: String(localized: "chart.preview.settings.speed"),
            systemImage: "speedometer",
            isExpanded: $isExpanded
        ) {
            ChartSettingsSlider(
                title: String(localized: "chart.preview.settings.hiSpeed"),
                value: settings.hiSpeed,
                range: 3...9,
                valueStyle: .fractionDigits(2)
            ) { newValue in
                binder.mutate { $0.hiSpeed = newValue }
            }

            ChartSettingsSlider(
                title: String(localized: "chart.preview.settings.playbackSpeed"),
                value: settings.playbackSpeed,
                range: 0.1...2,
                valueStyle: .multiplier(fractionDigits: 2),
                snapPoints: [1]
            ) { newValue in
                binder.mutate { $0.playbackSpeed = newValue }
            }

            ChartSettingsSlider(
                title: String(localized: "chart.preview.settings.rhythmSpeed"),
                value: settings.rhythmSpeed,
                range: 0.5...2,
                valueStyle: .multiplier(fractionDigits: 2),
                roundingDigits: 2
            ) { newValue in
                binder.mutate { $0.rhythmSpeed = newValue }
            }

            ChartSettingsSlider(
                title: String(localized: "chart.preview.settings.slideDelay"),
                value: settings.slideDelay,
                range: -1...1,
                valueStyle: .fractionDigits(1),
                roundingDigits: 1
            ) { newValue in
                binder.mutate { $0.slideDelay = newValue }
            }

            Toggle(
                String(localized: "chart.preview.settings.keepHiSpeed"),
                isOn: binder.binding(\.alwaysKeepHiSpeed)
            )
        }
    }
}

/// Display controls: mirror transform, judgment line design, render effects and in-canvas counters.
struct ChartDisplaySettingsPanel: View {
    let settings: ChartPreviewSettings
    let onChange: (ChartPreviewSettings) -> Void

    @State private var isExpanded = false

    private var binder: ChartSettingsBinder {
        ChartSettingsBinder(settings: settings, onChange: onChange)
    }

    var body: some View {
        ChartSettingsCard(
            title: String(localized: "chart.preview.settings.display"),
            systemImage: "square.grid.2x2",
            isExpanded: $isExpanded
        ) {
            ChartSettingsSegmentedPicker(
                title: String(localized: "chart.preview.settings.mirror"),
                selection: binder.binding(\.mirrorMode),
                options: [
                    (label: String(localized: "chart.preview.settings.mirror.none"), value: MirrorMode.none),
                    (label: String(localized: "chart.preview.settings.mirror.horizontal"), value: .horizontal),
                    (label: String(localized: "chart.preview.settings.mirror.vertical"), value: .vertical),
                    (label: String(localized: "chart.preview.settings.mirror.rotate"), value: .rotate180)
                ]
            )

            ChartSettingsSegmentedPicker(
                title: String(localized: "chart.preview.settings.judgment"),
                selection: binder.binding(\.judgmentLineDesign),
                options: [
                    (label: String(localized: "chart.preview.settings.judgment.hidden"), value: .blind),
                    (label: String(localized: "chart.preview.settings.judgment.noLine"), value: .noLine),
                    (label: String(localized: "chart.preview.settings.judgment.simple"), value: .simple),
                    (label: String(localized: "chart.preview.settings.judgment.sensor"), value: .sensor)
                ]
            )

            ChartSettingsGroup(title: String(localized: "chart.preview.settings.effects")) {
                Toggle(
                    String(localized: "chart.preview.settings.slideRotation"),
                    isOn: binder.binding(\.slideRotation)
                )
                Toggle(
                    String(localized: "chart.preview.settings.pinkSlide"),
                    isOn: binder.binding(\.pinkSlideStart)
                )
                Toggle(
                    String(localized: "chart.preview.settings.highlightEx"),
                    isOn: binder.binding(\.highlightExNotes)
                )
                Toggle(
                    String(localized: "chart.preview.settings.breakSlideNormal"),
                    isOn: binder.binding(\.normalColorBreakSlide)
                )
                Toggle(
                    String(localized: "chart.preview.settings.fireworks"),
                    isOn: binder.binding(\.showFireworks)
                )
                Toggle(
                    String(localized: "chart.preview.settings.hitEffect"),
                    isOn: binder.binding(\.showHitEffect)
                )
            }

            ChartSettingsGroup(title: String(localized: "chart.preview.settings.counts")) {
                Toggle(
                    String(localized: "chart.preview.settings.noteTotal"),
                    isOn: binder.binding(\.showNoteTotal)
                )
                Toggle(
                    String(localized: "chart.preview.settings.breakCount"),
                    isOn: binder.binding(\.showBreakCount)
                )
            }
        }
    }
}

/// Audio controls: three volumes, the two millisecond offsets, and the panel reset.
struct ChartAudioSettingsPanel: View {
    let settings: ChartPreviewSettings
    let onChange: (ChartPreviewSettings) -> Void

    @State private var isExpanded = false

    private var binder: ChartSettingsBinder {
        ChartSettingsBinder(settings: settings, onChange: onChange)
    }

    var body: some View {
        ChartSettingsCard(
            title: String(localized: "chart.preview.settings.audio"),
            systemImage: "speaker.wave.3.fill",
            isExpanded: $isExpanded
        ) {
            ChartSettingsSlider(
                title: String(localized: "chart.preview.settings.musicVolume"),
                value: Double(settings.musicVolume),
                range: 0...1,
                valueStyle: .percent
            ) { newValue in
                binder.mutate { $0.musicVolume = Float(newValue) }
            }

            ChartSettingsSlider(
                title: String(localized: "chart.preview.settings.answerVolume"),
                value: Double(settings.answerVolume),
                range: 0...1,
                valueStyle: .percent
            ) { newValue in
                binder.mutate { $0.answerVolume = Float(newValue) }
            }

            ChartSettingsSlider(
                title: String(localized: "chart.preview.settings.judgeVolume"),
                value: Double(settings.judgeVolume),
                range: 0...1,
                valueStyle: .percent
            ) { newValue in
                binder.mutate { $0.judgeVolume = Float(newValue) }
            }

            ChartSettingsOffsetRow(
                title: String(localized: "chart.preview.settings.musicOffset"),
                value: settings.musicOffsetMs
            ) { newValue in
                binder.mutate { $0.musicOffsetMs = newValue }
            }

            ChartSettingsOffsetRow(
                title: String(localized: "chart.preview.settings.sfxOffset"),
                value: settings.sfxOffsetMs
            ) { newValue in
                binder.mutate { $0.sfxOffsetMs = newValue }
            }

            Button {
                onChange(.default)
            } label: {
                Label(String(localized: "chart.preview.settings.resetAll"), systemImage: "arrow.counterclockwise")
                    .font(.body)
            }
            .buttonStyle(.borderless)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
