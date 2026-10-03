import Foundation

enum CollectionSharingService {
    nonisolated static func isCollectionLink(_ url: URL) -> Bool {
        SongCollectionCodec.extractToken(from: url.absoluteString) != nil
    }

    static func resolveImport(_ value: String) throws -> SongCollectionExport {
        try SongCollectionCodec.decode(value)
    }
}
