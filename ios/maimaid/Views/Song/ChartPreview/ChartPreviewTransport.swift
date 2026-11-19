import SwiftUI

/// Play/pause, measure stepping and the SFX mute toggle below the stage.
struct ChartPreviewTransport: View {
    let controller: ChartPlaybackController
    let soundEnabled: Bool
    let enabled: Bool
    let onTogglePlay: () -> Void
    let onToggleSound: () -> Void
    let onPreviousMeasure: () -> Void
    let onNextMeasure: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            Button("chart.preview.previousMeasure", systemImage: "chevron.left", action: onPreviousMeasure)
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .font(.title3)

            Button(action: onTogglePlay) {
                Image(systemName: controller.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title2)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.borderless)
            .disabled(!enabled)
            .accessibilityLabel(Text(controller.isPlaying ? "chart.preview.pause" : "chart.preview.play"))

            Button("chart.preview.nextMeasure", systemImage: "chevron.right", action: onNextMeasure)
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .font(.title3)

            Spacer(minLength: 8)

            Button(action: onToggleSound) {
                Image(systemName: soundEnabled ? "speaker.wave.2.fill" : "speaker.slash.fill")
                    .font(.body)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(Text(soundEnabled ? "chart.preview.soundOff" : "chart.preview.soundOn"))
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 4)
    }
}
