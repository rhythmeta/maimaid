import Foundation

extension MaimaiDataFetcher {
    /// `12 MB / 180 MB` for the current run, or nil when the manifest published no sizes.
    ///
    /// The numerator is clamped to the total because retried downloads can report the same bytes
    /// twice, and a resumed run can find files already on disk.
    var byteProgressText: String? {
        guard let total = totalDownloadBytes else { return nil }
        let downloaded = min(max(downloadedBytes, 0), total)
        let downloadedText = downloaded.formatted(.byteCount(style: .file))
        let totalText = total.formatted(.byteCount(style: .file))
        return String(localized: "data.sync.byteProgress \(downloadedText) \(totalText)")
    }
}
