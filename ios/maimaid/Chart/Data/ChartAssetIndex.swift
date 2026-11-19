import Foundation

/// The independently published chart preview lookup table (`/chart-assets/index.json`).
nonisolated struct ChartAssetIndex: Decodable, Sendable {
    struct Source: Decodable, Sendable {
        var name: String?
        var catalogUrl: String?
        var attribution: String?
        var metadataLicense: String?
        var mediaRights: String?
    }

    struct Entry: Decodable, Sendable {
        var id: Int
        var title: String
        var files: Files
    }

    struct Files: Decodable, Sendable {
        var maidata: File?
        var audio: File?
    }

    struct File: Decodable, Sendable, Equatable {
        var path: String
        var sourceUrl: String?
        var size: Int64
        var sha256: String
    }

    var schemaVersion: Int
    var generatedAt: String?
    var source: Source?
    var entries: [Entry]

    func entry(for id: Int) -> Entry? {
        entries.first { $0.id == id }
    }
}
