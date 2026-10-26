import Foundation
import OSLog

/// The cabinet pool injected into the app at build time.
///
/// The catalog is static data, not a credential: it is loaded from the app
/// bundle and never fetched at runtime, so region-based selection works
/// offline and without a location permission.
nonisolated struct SgwcmaidKeychipCatalog: Decodable, Sendable {
  let version: Int
  let source: String
  let provinces: [SgwcmaidProvince]
  let keychips: [SgwcmaidKeychip]

  static let empty = SgwcmaidKeychipCatalog(
    version: 0, source: "", provinces: [], keychips: [])

  var isEmpty: Bool { keychips.isEmpty }

  /// Loaded once on first use. A missing or corrupt resource degrades to the
  /// legacy single-keychip fallback instead of blocking the import.
  static let bundled: SgwcmaidKeychipCatalog = load()

  static func load(bundle: Bundle = .main) -> SgwcmaidKeychipCatalog {
    guard let url = bundle.url(forResource: "keychips", withExtension: "json") else {
      Logger(subsystem: "org.rhythmeta.maimaid", category: "Sgwcmaid")
        .notice("keychips.json is not bundled; using the legacy keychip only")
      return .empty
    }
    do {
      let data = try Data(contentsOf: url)
      return try JSONDecoder().decode(Self.self, from: data)
    } catch {
      Logger(subsystem: "org.rhythmeta.maimaid", category: "Sgwcmaid")
        .error("failed to decode keychips.json: \(String(describing: error), privacy: .public)")
      return .empty
    }
  }

  func province(id: Int) -> SgwcmaidProvince? {
    provinces.first { $0.id == id }
  }

  func keychips(inProvince id: Int) -> [SgwcmaidKeychip] {
    keychips.filter { $0.provinceID == id }
  }
}
