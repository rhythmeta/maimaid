import Foundation
import OSLog

/// One arcade from the build-time places database.
///
/// The file is a flat object keyed by keychip; `place_id` is hexadecimal and `region0` is
/// decimal, exactly as the reference deployment publishes them.
nonisolated struct SdgaPlaceRecord: Decodable, Sendable {
  let placeName: String
  let placeNickname: String
  let placeId: String
  let region0: String
  let regionName0: String
  let regionName1: String
  let regionName2: String

  enum CodingKeys: String, CodingKey {
    case placeName = "place_name"
    case placeNickname = "place_nickname"
    case placeId = "place_id"
    case region0
    case regionName0 = "region_name0"
    case regionName1 = "region_name1"
    case regionName2 = "region_name2"
  }
}

/// The cabinet credentials handed to AiMeDB and the title server for one attempt.
nonisolated struct SdgaCabinet: Sendable, Equatable {
  let keychip: String
  let placeID: Int
  let regionID: Int
}

nonisolated struct SdgaPlace: Sendable, Identifiable, Equatable {
  let placeID: Int
  let name: String
  let nickname: String
  /// ISO-3166 alpha-3 of the country/territory (`SGP`, `HKG`, …).
  let country: String
  let region: String
  let city: String
  let cabinets: [SdgaCabinet]

  var id: Int { placeID }
  var displayName: String { name.isEmpty ? nickname : name }
}

/// Loads the build-time places database from the app bundle.
///
/// A missing or malformed resource degrades to an empty catalog, so the import falls back
/// to the build-time cabinet instead of failing at startup.
nonisolated struct SdgaPlacesCatalog: Sendable {
  let places: [SdgaPlace]

  static let empty = SdgaPlacesCatalog(places: [])

  var isEmpty: Bool { places.isEmpty }

  var countries: [String] {
    Array(Set(places.map(\.country).filter { !$0.isEmpty })).sorted()
  }

  func places(in country: String?) -> [SdgaPlace] {
    guard let country, !country.isEmpty else { return places }
    return places.filter { $0.country == country }
  }

  func cabinets(country: String?, placeID: Int?) -> [SdgaCabinet] {
    places(in: country)
      .filter { placeID == nil || $0.placeID == placeID }
      .flatMap(\.cabinets)
  }

  static let bundled: SdgaPlacesCatalog = load()

  static func load(bundle: Bundle = .main) -> SdgaPlacesCatalog {
    guard let url = bundle.url(forResource: "sdga_places", withExtension: "json") else {
      Logger(subsystem: "org.rhythmeta.maimaid", category: "Sdga")
        .notice("sdga_places.json is not bundled; the catalog is empty")
      return .empty
    }
    do {
      let data = try Data(contentsOf: url)
      return parse(data)
    } catch {
      Logger(subsystem: "org.rhythmeta.maimaid", category: "Sdga")
        .error("failed to read sdga_places.json: \(String(describing: error), privacy: .public)")
      return .empty
    }
  }

  static func parse(_ data: Data) -> SdgaPlacesCatalog {
    guard let records = try? JSONDecoder().decode([String: SdgaPlaceRecord].self, from: data)
    else { return .empty }
    var grouped: [Int: [SdgaCabinet]] = [:]
    var meta: [Int: SdgaPlaceRecord] = [:]
    for (keychip, record) in records {
      guard let placeID = Int(record.placeId, radix: 16) else { continue }
      let normalized = keychip.filter { $0.isLetter || $0.isNumber }
      guard normalized.count >= 8 else { continue }
      meta[placeID] = meta[placeID] ?? record
      grouped[placeID, default: []].append(
        SdgaCabinet(
          keychip: normalized, placeID: placeID,
          regionID: Int(record.region0) ?? 0))
    }
    let places = grouped.compactMap { placeID, cabinets -> SdgaPlace? in
      guard let record = meta[placeID] else { return nil }
      return SdgaPlace(
        placeID: placeID,
        name: record.placeName.isEmpty ? record.placeNickname : record.placeName,
        nickname: record.placeNickname,
        country: record.regionName1.uppercased(),
        region: record.regionName0,
        city: record.regionName2,
        cabinets: cabinets)
    }
    .sorted { ($0.country, $0.displayName, $0.placeID) < ($1.country, $1.displayName, $1.placeID) }
    return SdgaPlacesCatalog(places: places)
  }
}
