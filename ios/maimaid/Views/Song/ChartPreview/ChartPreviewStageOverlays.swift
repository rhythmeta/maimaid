import SwiftUI

/// Stage overlay: the audio-loading spinner while a track downloads or decodes, and the play badge
/// shown while the chart is paused.
struct ChartPreviewStageOverlay: View {
    let isWaitingForAudio: Bool
    let isPlaying: Bool

    var body: some View {
        if isWaitingForAudio {
            VStack(spacing: 10) {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.white)
                    .frame(width: 64, height: 64)
                    .background(.black.opacity(0.45), in: .circle)
                Text("chart.preview.audioLoading")
                    .font(.footnote)
                    .foregroundStyle(.white)
            }
            .allowsHitTesting(false)
        } else if !isPlaying {
            Image(systemName: "play.fill")
                .font(.title)
                .foregroundStyle(.white)
                .frame(width: 64, height: 64)
                .background(.black.opacity(0.45), in: .circle)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

/// Debug overlay for the track download: percentage on the first line and the transfer rate below,
/// plain white text so the stage stays visible.
struct ChartMusicDownloadOverlay: View {
    let progress: TrackDownloadProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: "\(progress.percent)%")
            Text(verbatim: "\(formattedRate)/s")
        }
        .font(.caption2.weight(.bold))
        .foregroundStyle(.white)
        .allowsHitTesting(false)
    }

    private var formattedRate: String {
        Int64(max(progress.bytesPerSecond, 0)).formatted(.byteCount(style: .file))
    }
}
