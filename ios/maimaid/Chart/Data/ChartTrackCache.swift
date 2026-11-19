import Foundation

/// Bounds `Caches/chart-audio` to at most `maxTracks` downloaded songs.
///
/// Only the MP3s are cached on disk nowadays (PCM is decoded into memory), but the eviction still
/// works on "song groups": every file whose name starts with the audio sha256 — `<sha>.mp3` plus any
/// leftovers from older versions — is deleted together, so a re-download can never mix files from two
/// different generations of the same song.
nonisolated enum ChartAudioCacheLru {
    static let maxTracks = 5

    /// One song's files plus the newest modification date among them (the LRU sort key).
    private struct Group {
        let key: String
        let names: [String]
        let modified: Date
    }

    /// Deletes the least recently used song groups until at most `maxTracks` remain.
    /// `keepKeys` (sha256 strings) are never deleted; pass the track that is being prepared now.
    static func prune(directory: URL, keepKeys: Set<String> = [], maxTracks: Int = maxTracks) {
        guard maxTracks > 0 else { return }
        let fileManager = FileManager.default
        guard let names = try? fileManager.contentsOfDirectory(atPath: directory.path) else { return }
        var groups: [String: [String]] = [:]
        for name in names {
            guard let key = groupKey(of: name), isRegularFile(directory.appending(path: name)) else { continue }
            groups[key, default: []].append(name)
        }
        guard groups.count > maxTracks else { return }
        let keep = Set(keepKeys.map { $0.lowercased() })
        // Oldest group first. The tie-break on the key keeps the eviction deterministic when several
        // files share a timestamp (the reference implementation relies on its stable sort instead).
        var ordered: [Group] = []
        ordered.reserveCapacity(groups.count)
        for (key, groupNames) in groups {
            ordered.append(
                Group(
                    key: key,
                    names: groupNames,
                    modified: latestModification(in: directory, names: groupNames)
                )
            )
        }
        ordered.sort { lhs, rhs in
            if lhs.modified == rhs.modified { return lhs.key < rhs.key }
            return lhs.modified < rhs.modified
        }
        var remaining = ordered.count
        for group in ordered {
            if remaining <= maxTracks { break }
            if keep.contains(group.key) { continue }
            for name in group.names {
                try? fileManager.removeItem(at: directory.appending(path: name))
            }
            remaining -= 1
        }
    }

    /// Deletes PCM files written by older versions of the app. Decoded audio now lives in memory, so
    /// these are pure waste (tens of MB per track) and must not occupy the cache directory.
    @discardableResult
    static func purgeLegacyPcmFiles(directory: URL) -> Int {
        let fileManager = FileManager.default
        guard let names = try? fileManager.contentsOfDirectory(atPath: directory.path) else { return 0 }
        var removed = 0
        for name in names where name.hasSuffix(".pcm") || name.hasSuffix(".meta") {
            let url = directory.appending(path: name)
            guard isRegularFile(url) else { continue }
            do {
                try fileManager.removeItem(at: url)
                removed += 1
            } catch {
                continue
            }
        }
        return removed
    }

    /// Every cache file starts with the 64-char sha256 followed by '.', so the first segment is the
    /// song identity. Files without a prefix (e.g. dot-files) are ignored.
    private static func groupKey(of name: String) -> String? {
        let key = String(name.prefix { $0 != "." }).lowercased()
        return key.isEmpty ? nil : key
    }

    private static func isRegularFile(_ url: URL) -> Bool {
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else { return false }
        return !isDirectory.boolValue
    }

    private static func latestModification(in directory: URL, names: [String]) -> Date {
        var latest = Date.distantPast
        for name in names {
            let url = directory.appending(path: name)
            guard let values = try? url.resourceValues(forKeys: [.contentModificationDateKey]),
                  let date = values.contentModificationDate
            else { continue }
            if date > latest { latest = date }
        }
        return latest
    }
}

/// Verified on-disk cache for chart tracks. Only the downloaded MP3 is cached, at
/// `Caches/chart-audio/<sha256>.<ext>`; the decoded PCM is kept in memory by the mixer and never
/// written here.
///
/// The directory holds at most `ChartAudioCacheLru.maxTracks` songs; preparing a new one evicts the
/// least recently used one. Every hit is re-verified against the published size and SHA-256 so a
/// truncated download can never reach the decoder.
@MainActor
final class ChartTrackCache {
    static let shared = ChartTrackCache()

    let directory: URL

    init(directory: URL? = nil) {
        let resolved = directory ?? URL.cachesDirectory.appending(path: "chart-audio", directoryHint: .isDirectory)
        self.directory = resolved
        try? FileManager.default.createDirectory(at: resolved, withIntermediateDirectories: true)
    }

    /// Cached, integrity-relevant file for an audio asset, or nil when it is not downloaded yet.
    ///
    /// This is the cheap probe used for cover art and for deciding whether a download is needed; the
    /// digest is only verified by `trackFile(for:onProgress:)`.
    func cachedFile(for asset: ChartAssetIndex.File) -> URL? {
        let file = fileURL(for: asset)
        guard let values = try? file.resourceValues(forKeys: [.fileSizeKey]),
              let size = values.fileSize, size > 0
        else { return nil }
        return file
    }

    /// Returns the verified local file, downloading it when missing. `onProgress` is called on the
    /// main actor with streaming progress (received/total bytes + a short-window speed estimate).
    func trackFile(
        for asset: ChartAssetIndex.File,
        onProgress: @escaping @MainActor (TrackDownloadProgress) -> Void
    ) async throws -> URL {
        let destination = fileURL(for: asset)
        if await matches(destination, asset: asset) {
            markRecent(destination)
            prune(keeping: asset.sha256)
            return destination
        }
        try Task.checkCancellation()
        let file = try await download(asset, onProgress: onProgress)
        markRecent(file)
        prune(keeping: asset.sha256)
        return file
    }

    // MARK: - Download

    /// Downloads and verifies `asset`, streaming progress to the UI. Exposed for callers that already
    /// know the file is missing (the ZIP export re-uses the same path).
    func download(
        _ asset: ChartAssetIndex.File,
        onProgress: @escaping @MainActor (TrackDownloadProgress) -> Void
    ) async throws -> URL {
        let destination = fileURL(for: asset)
        let reporter = ChartTrackProgressReporter(handler: onProgress)
        let progress: @Sendable (Int64, Int64) -> Void = { received, total in
            Task { @MainActor in
                reporter.update(receivedBytes: received, totalBytes: total)
            }
        }
        do {
            let url = try await ChartAssetClient.downloadAudio(asset, to: destination, onProgress: progress)
            // `downloadAudio` already checked size + SHA-256 while streaming, so the file on disk is
            // byte-identical to the published asset.
            return url
        } catch let error as URLError where error.code == .cancelled {
            // The view model cancels a superseded preparation; surface that as a cancellation instead
            // of a download failure so the UI does not report an error.
            throw CancellationError()
        }
    }

    // MARK: - Integrity

    /// Size + SHA-256 verification (port of `ChartAudioCacheIntegrity`).
    func matches(_ url: URL, asset: ChartAssetIndex.File) async -> Bool {
        guard let values = try? url.resourceValues(forKeys: [.fileSizeKey]),
              let size = values.fileSize, Int64(size) == asset.size
        else { return false }
        guard let digest = try? ChartAssetClient.sha256Hex(ofFile: url) else { return false }
        return digest.caseInsensitiveCompare(asset.sha256) == .orderedSame
    }

    // MARK: - Helpers

    private func fileURL(for asset: ChartAssetIndex.File) -> URL {
        let pathExtension = (asset.path as NSString).pathExtension
        let suffix = pathExtension.isEmpty ? "mp3" : pathExtension
        return directory.appending(
            path: "\(asset.sha256.lowercased()).\(suffix)",
            directoryHint: .notDirectory
        )
    }

    /// Marks the song as used so the LRU keeps the track that is playing now.
    private func markRecent(_ url: URL) {
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
    }

    /// Keeps the cache directory inside its track budget. Legacy PCM files are dropped first; nothing
    /// writes them any more, so they are pure waste.
    private func prune(keeping key: String) {
        ChartAudioCacheLru.purgeLegacyPcmFiles(directory: directory)
        ChartAudioCacheLru.prune(
            directory: directory,
            keepKeys: [key.lowercased()],
            maxTracks: ChartAudioCacheLru.maxTracks
        )
    }
}

/// Turns the raw byte counters of `URLSession` into the UI's `TrackDownloadProgress`, including the
/// short-window speed estimate. Main-actor isolated so the reported numbers stay ordered.
@MainActor
private final class ChartTrackProgressReporter {
    private let handler: @MainActor (TrackDownloadProgress) -> Void

    private var windowStart = ContinuousClock.now
    private var windowBytes: Int64 = 0
    private var lastReceived: Int64 = 0
    private var lastReport = ContinuousClock.now
    private var speed: Int64 = 0

    init(handler: @escaping @MainActor (TrackDownloadProgress) -> Void) {
        self.handler = handler
    }

    func update(receivedBytes: Int64, totalBytes: Int64) {
        guard receivedBytes >= lastReceived else { return }
        let now = ContinuousClock.now
        windowBytes += receivedBytes - lastReceived
        lastReceived = receivedBytes
        let elapsed = now - windowStart
        if elapsed >= .milliseconds(250) {
            let seconds = Double(elapsed.components.seconds)
                + Double(elapsed.components.attoseconds) / 1e18
            if seconds > 0 {
                speed = Int64(Double(windowBytes) / seconds)
            }
            windowBytes = 0
            windowStart = now
        }
        if now - lastReport < .milliseconds(80) && receivedBytes != totalBytes { return }
        lastReport = now
        handler(
            TrackDownloadProgress(
                receivedBytes: receivedBytes,
                totalBytes: totalBytes,
                bytesPerSecond: speed
            )
        )
    }
}
