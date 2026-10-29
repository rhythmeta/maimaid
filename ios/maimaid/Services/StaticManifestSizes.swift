import Foundation

/// Byte sizes published by the static-asset build in `manifest.json`.
///
/// Measured from the bytes stored in the published tree, so they describe a whole first-run
/// download: the JSON bundle plus every cover and preset-avatar image. Covers are fetched through
/// the CDN image transform, so the bytes a device receives differ slightly from these.
struct StaticManifestSizes: Decodable {
    let bundleBytes: Int64
    let imageBytes: Int64
    let totalBytes: Int64

    private enum CodingKeys: String, CodingKey {
        case bundleBytes
        case imageBytes
        case totalBytes
    }

    // Every field defaults to 0 so a partially populated block degrades to "no size" instead of
    // failing the whole manifest decode.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        bundleBytes = try container.decodeIfPresent(Int64.self, forKey: .bundleBytes) ?? 0
        imageBytes = try container.decodeIfPresent(Int64.self, forKey: .imageBytes) ?? 0
        totalBytes = try container.decodeIfPresent(Int64.self, forKey: .totalBytes) ?? 0
    }
}
