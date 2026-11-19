import Foundation

/// State of the chart music track as seen by the preview UI.
nonisolated enum ChartAudioState: Equatable, Sendable {
    case idle
    case preparing
    case ready(durationMillis: Int)
    case failed(message: String)
}

/// Streaming download progress for the chart track, shown on the stage in debug builds.
nonisolated struct TrackDownloadProgress: Equatable, Sendable {
    var receivedBytes: Int64
    var totalBytes: Int64
    var bytesPerSecond: Int64

    /// Completed percentage, 0...100.
    var percent: Int {
        guard totalBytes > 0 else { return 0 }
        return Int(min(100, max(0, receivedBytes * 100 / totalBytes)))
    }
}
