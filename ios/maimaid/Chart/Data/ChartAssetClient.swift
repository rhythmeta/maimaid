import CryptoKit
import Foundation

/// A chart asset downloaded from the published static bundle.
nonisolated struct ChartAssetDownloadError: LocalizedError {
    var message: String
    var errorDescription: String? { message }

    static func sizeMismatch(expected: Int64, actual: Int64) -> ChartAssetDownloadError {
        ChartAssetDownloadError(message: "Chart asset size mismatch: expected \(expected), got \(actual)")
    }

    static func digestMismatch() -> ChartAssetDownloadError {
        ChartAssetDownloadError(message: "Chart asset digest mismatch")
    }
}

/// Client for the chart preview assets published next to the static catalog:
/// `/chart-assets/index.json` plus content-addressed files under `/chart-assets/files/`.
@MainActor
enum ChartAssetClient {
    static func indexURL() -> URL? {
        BackendConfig.staticAssetsEndpoint("chart-assets/index.json")
    }

    /// Fetches and decodes the published chart-asset index.
    static func fetchIndex() async throws -> ChartAssetIndex {
        guard let url = indexURL() else { throw ChartAssetDownloadError(message: "Static asset host is not configured") }
        let (data, response) = try await URLSession.shared.data(from: url)
        try validate(response)
        return try JSONDecoder().decode(ChartAssetIndex.self, from: data)
    }

    /// Downloads a maidata text file and verifies its published size and digest.
    static func downloadMaidata(_ asset: ChartAssetIndex.File) async throws -> String {
        let data = try await downloadData(asset)
        guard let text = String(data: data, encoding: .utf8) else {
            throw ChartAssetDownloadError(message: "Chart maidata is not valid UTF-8")
        }
        return text
    }

    /// Downloads an audio asset into `destination`, verifying its digest while streaming.
    static func downloadAudio(
        _ asset: ChartAssetIndex.File,
        to destination: URL,
        onProgress: (@Sendable (Int64, Int64) -> Void)? = nil
    ) async throws -> URL {
        guard let url = fileURL(for: asset) else {
            throw ChartAssetDownloadError(message: "Static asset host is not configured")
        }
        let fileManager = FileManager.default
        try fileManager.createDirectory(
            at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        var request = URLRequest(url: url)
        request.timeoutInterval = 60

        let delegate = ChartAssetProgressDelegate(onProgress: onProgress)
        let (temporaryURL, response) = try await URLSession.shared.download(for: request, delegate: delegate)
        try validate(response)
        let size = (try? FileManager.default.attributesOfItem(atPath: temporaryURL.path)[.size] as? Int64) ?? 0
        guard size == asset.size else { throw ChartAssetDownloadError.sizeMismatch(expected: asset.size, actual: size) }
        let digest = try sha256Hex(ofFile: temporaryURL)
        guard digest.caseInsensitiveCompare(asset.sha256) == .orderedSame else {
            try? fileManager.removeItem(at: temporaryURL)
            throw ChartAssetDownloadError.digestMismatch()
        }
        if fileManager.fileExists(atPath: destination.path) {
            try? fileManager.removeItem(at: destination)
        }
        try fileManager.moveItem(at: temporaryURL, to: destination)
        return destination
    }

    /// Downloads a small asset into memory and verifies its published size and digest.
    static func downloadData(_ asset: ChartAssetIndex.File) async throws -> Data {
        guard let url = fileURL(for: asset) else {
            throw ChartAssetDownloadError(message: "Static asset host is not configured")
        }
        let (data, response) = try await URLSession.shared.data(from: url)
        try validate(response)
        guard Int64(data.count) == asset.size else {
            throw ChartAssetDownloadError.sizeMismatch(expected: asset.size, actual: Int64(data.count))
        }
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        guard digest.caseInsensitiveCompare(asset.sha256) == .orderedSame else {
            throw ChartAssetDownloadError.digestMismatch()
        }
        return data
    }

    // MARK: - Helpers

    private static func fileURL(for asset: ChartAssetIndex.File) -> URL? {
        guard asset.path.hasPrefix("/chart-assets/files/"),
              !asset.path.contains(".."),
              let baseURL = BackendConfig.staticAssetsBaseURL
        else { return nil }
        return URL(string: asset.path, relativeTo: baseURL)?.absoluteURL
    }

    private static func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else {
            throw ChartAssetDownloadError(message: "Chart asset response is not HTTP")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw ChartAssetDownloadError(message: "Chart asset request failed with \(http.statusCode)")
        }
    }

    static func sha256Hex(ofFile url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while autoreleasepool(invoking: {
            let chunk = try? handle.read(upToCount: 1 << 16)
            guard let chunk, !chunk.isEmpty else { return false }
            hasher.update(data: chunk)
            return true
        }) {}
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Bridges `URLSession` download progress into a plain closure.
nonisolated private final class ChartAssetProgressDelegate: NSObject, URLSessionDownloadDelegate,
    @unchecked Sendable {
    private let onProgress: (@Sendable (Int64, Int64) -> Void)?

    init(onProgress: (@Sendable (Int64, Int64) -> Void)?) {
        self.onProgress = onProgress
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        onProgress?(totalBytesWritten, totalBytesExpectedToWrite)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {}
}
