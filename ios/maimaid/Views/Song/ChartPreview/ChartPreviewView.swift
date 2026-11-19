// The preview page owns the stage, transport and panel composition; splitting it further would
// scatter the shared @State that drives them.
import SwiftUI
import UIKit

/// Full-page chart preview: arcade-faithful stage, transport, timelines and settings.
struct ChartPreviewView: View {
    let song: Song

    @State private var model: ChartPreviewViewModel
    @State private var scene: ChartSceneGraph?
    @State private var controller: ChartPlaybackController?
    @State private var densityData: ChartDensityData?
    @State private var rhythmData: ChartRhythmData?
    @State private var soundEnabled = true
    @State private var boostActive = false
    @State private var exportFile: ChartExportFile?
    @State private var isExporting = false
    @State private var exportError: String?

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss

    init(song: Song, preferredSheetKey: String? = nil) {
        self.song = song
        _model = State(initialValue: ChartPreviewViewModel(song: song, preferredSheetKey: preferredSheetKey))
    }

    var body: some View {
        content
            .navigationTitle("chart.preview.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 18))
                            .symbolRenderingMode(.hierarchical)
                    }
                    .accessibilityLabel(Text("chart.preview.close"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        exportChart()
                    } label: {
                        Label("chart.preview.action.save", systemImage: "square.and.arrow.up")
                    }
                    .disabled(!canExport || isExporting)
                }
            }
            // The preview is presented as a full-screen cover so the only way out is the back button:
            // the swipe-back gesture of a pushed page would trigger while dragging the seek bar near
            // the leading edge, and a pull-down dismissal is disabled for the same reason.
            .navigationBarBackButtonHidden(true)
            .interactiveDismissDisabled()
            .task {
                await model.load()
            }
            .onChange(of: model.chartRevision) { _, _ in
                syncScene()
            }
            .onChange(of: model.audioPending) { _, pending in
                guard let controller else { return }
                if pending {
                    controller.pause()
                } else {
                    controller.rewind()
                    controller.play()
                }
            }
            .onChange(of: model.settings) { _, settings in
                applySettings(settings)
            }
            .onChange(of: scenePhase) { _, phase in
                if phase != .active {
                    controller?.pause()
                }
            }
            .onAppear {
                // A chart preview is watched for minutes; the system idle timer must not cut it off.
                UIApplication.shared.isIdleTimerDisabled = true
            }
            .onDisappear {
                UIApplication.shared.isIdleTimerDisabled = false
                controller?.stop()
                model.release()
            }
            .sheet(item: $exportFile) { file in
                ActivityShareSheet(activityItems: [file.url])
                    .ignoresSafeArea()
            }
            .alert("chart.preview.save.failed", isPresented: exportErrorBinding) {
                Button("common.ok", role: .cancel) {}
            } message: {
                Text(exportError ?? "")
            }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            ChartPreviewFailureView(message: message) {
                Task { await model.load() }
            }
        case .ready:
            readyContent
        }
    }

    @ViewBuilder
    private var readyContent: some View {
        ScrollView {
            VStack(spacing: 12) {
                if model.options.count > 1 {
                    ChartPreviewDifficultySelector(
                        options: model.options,
                        selectedKey: model.selectedKey,
                        onSelect: { model.selectSheet($0) }
                    )
                }

                if let parseError = model.parseError {
                    Text(parseError)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding()
                } else if let scene, let controller {
                    stage(scene: scene, controller: controller)
                    ChartPreviewTransport(
                        controller: controller,
                        soundEnabled: soundEnabled,
                        enabled: !model.audioPending,
                        onTogglePlay: { togglePlayback() },
                        onToggleSound: {
                            soundEnabled.toggle()
                            model.audioEngine.setSfxEnabled(soundEnabled)
                        },
                        onPreviousMeasure: { jumpMeasure(-1) },
                        onNextMeasure: { jumpMeasure(1) }
                    )
                    timelineSection(scene: scene, controller: controller)
                }

                settingsSection
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 32)
        }
    }

    private func stage(scene: ChartSceneGraph, controller: ChartPlaybackController) -> some View {
        ChartStageView(
            scene: scene,
            renderConfig: renderConfig,
            controller: controller,
            coverImage: model.coverImage,
            sensorImage: model.sensorImage,
            speedBoostActive: boostActive
        )
        .aspectRatio(1, contentMode: .fit)
        .clipShape(.rect(cornerRadius: 20))
        .overlay {
            ChartPreviewStageOverlay(
                isWaitingForAudio: model.audioPending,
                isPlaying: controller.isPlaying
            )
        }
        .overlay(alignment: .bottomLeading) {
            #if DEBUG
            if let progress = model.downloadProgress {
                ChartMusicDownloadOverlay(progress: progress)
                    .padding(.leading, 12)
                    .padding(.bottom, 34)
            }
            #endif
        }
        .contentShape(.rect)
        .onTapGesture(count: 2) { togglePlayback() }
        .onLongPressGesture(minimumDuration: 0.2) {
            // Hold-to-boost is applied by the pressing state below; the action itself does nothing.
        } onPressingChanged: { isPressing in
            guard controller.isPlaying else {
                setBoost(false)
                return
            }
            setBoost(isPressing)
        }
        .accessibilityElement()
        .accessibilityLabel(Text("chart.preview.title"))
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(Text("chart.preview.playback.hint"))
    }

    @ViewBuilder
    private func timelineSection(scene: ChartSceneGraph, controller: ChartPlaybackController) -> some View {
        if let densityData, let rhythmData {
            ChartDensityTimeline(
                data: densityData,
                positionMs: controller.positionMs,
                isPlaying: controller.isPlaying,
                onScrubStart: { position in
                    controller.beginScrub()
                    controller.scrub(to: position)
                },
                onScrub: { position in controller.scrub(to: position) },
                onScrubEnd: { controller.endScrub() }
            )
            ChartRhythmTimeline(
                data: rhythmData,
                positionMs: controller.positionMs,
                // Keeping the chart flow speed also pins the lane's own wall-clock travel: with the
                // chart slowed down, the lane must widen its window instead of crawling.
                speedScale: rhythmSpeedScale,
                isPlaying: controller.isPlaying
            )
            ChartDensityLegend()
        }
    }

    private var settingsSection: some View {
        VStack(spacing: 12) {
            ChartSpeedSettingsPanel(settings: model.settings) { next in
                model.updateSettings { $0 = next }
            }
            ChartDisplaySettingsPanel(settings: model.settings) { next in
                model.updateSettings { $0 = next }
            }
            ChartAudioSettingsPanel(settings: model.settings) { next in
                model.updateSettings { $0 = next }
            }
        }
        .padding(.top, 4)
    }

    // MARK: - Derived state

    private var renderConfig: ChartRenderConfig {
        model.settings.toRenderConfig(
            comboLabel: String(localized: "chart.preview.hud.combo"),
            breakNoExLabel: String(localized: "chart.preview.hud.breakNoEx")
        )
    }

    private var rhythmSpeedScale: Double {
        let settings = model.settings
        guard settings.alwaysKeepHiSpeed else { return settings.rhythmSpeed }
        return settings.rhythmSpeed / max(0.1, settings.playbackSpeed)
    }

    private var canExport: Bool {
        model.state == .ready && model.chart != nil
    }

    private var exportErrorBinding: Binding<Bool> {
        Binding(
            get: { exportError != nil },
            set: { if !$0 { exportError = nil } }
        )
    }

    // MARK: - Actions

    private func syncScene() {
        controller?.stop()
        controller = nil
        scene = nil
        densityData = nil
        rhythmData = nil
        boostActive = false

        guard model.parseError == nil, let chart = model.chart else { return }
        let graph = ChartSceneGraph(chart: chart)
        scene = graph
        model.updateSceneDuration(graph.playbackDurationMs)

        let playback = ChartPlaybackController(durationMs: graph.playbackDurationMs, engine: model.audioEngine)
        playback.playbackSpeed = model.settings.playbackSpeed
        controller = playback
        densityData = ChartDensityData.make(notes: chart.notes, durationMs: graph.playbackDurationMs)
        rhythmData = ChartRhythmData.make(chart: chart, timeline: graph.timeline)
        model.applyStoredAudioSettings()
        model.audioEngine.setSfxEnabled(soundEnabled)
        if !model.audioPending {
            playback.rewind()
            playback.play()
        }
    }

    private func applySettings(_ settings: ChartPreviewSettings) {
        model.applyStoredAudioSettings()
        controller?.applyPlaybackSpeed(
            settingsSpeed: settings.playbackSpeed,
            boost: boostActive ? ChartPreviewTiming.boostSpeed : 1
        )
    }

    private func togglePlayback() {
        guard !model.audioPending, let controller else { return }
        setBoost(false)
        controller.toggle()
    }

    private func setBoost(_ active: Bool) {
        guard boostActive != active else { return }
        boostActive = active
        controller?.applyPlaybackSpeed(
            settingsSpeed: model.settings.playbackSpeed,
            boost: active ? ChartPreviewTiming.boostSpeed : 1
        )
    }

    private func jumpMeasure(_ delta: Int) {
        guard let scene, let controller else { return }
        let currentMs = controller.positionMs
        let currentBeat = scene.timeline.beatFromMs(currentMs)
        let measure = Int(floor(currentBeat / 4.0))
        let measureStartMs = scene.timeline.msFromBeat(Double(measure) * 4.0)
        let targetMeasure: Int
        if delta < 0 {
            targetMeasure = (currentMs - measureStartMs < 200) ? measure - 1 : measure
        } else {
            targetMeasure = measure + 1
        }
        let clampedMeasure = max(0, targetMeasure)
        let target = min(max(scene.timeline.msFromBeat(Double(clampedMeasure) * 4.0), 0), controller.durationMs)
        controller.seek(to: target)
    }

    private func exportChart() {
        guard !isExporting else { return }
        isExporting = true
        Task {
            defer { isExporting = false }
            do {
                let url = try await model.exportChartZip()
                exportFile = ChartExportFile(url: url)
            } catch {
                exportError = error.localizedDescription
            }
        }
    }
}

/// Identifiable wrapper so a file URL can drive `.sheet(item:)`.
private struct ChartExportFile: Identifiable {
    let id = UUID()
    let url: URL
}

/// Failure state with a retry action, matching the Android preview's error screen.
private struct ChartPreviewFailureView: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("chart.preview.title", systemImage: "music.note.list")
        } description: {
            Text(message)
        } actions: {
            Button("chart.preview.retry", systemImage: "arrow.clockwise", action: onRetry)
        }
    }
}
