import Foundation

/// One China-server cabinet from the bundled keychip catalog.
///
/// The keychip identifies the cabinet to the chime service while the province
/// and store IDs are the `regionId`/`placeId` pair the game API expects for the
/// same cabinet.
nonisolated struct SgwcmaidKeychip: Decodable, Sendable, Hashable, Identifiable {
  let keychip: String
  let provinceID: Int
  let storeID: Int
  let latencyMS: Int

  var id: String { keychip }

  enum CodingKeys: String, CodingKey {
    case keychip
    case provinceID = "provinceId"
    case storeID = "storeId"
    case latencyMS = "latencyMs"
  }
}

/// A selectable region. The catalog only carries provinces, with the four
/// direct-controlled municipalities represented as their own entries.
nonisolated struct SgwcmaidProvince: Decodable, Sendable, Hashable, Identifiable {
  let id: Int
  let name: String
  let count: Int
}

/// The cabinet credentials handed to the game API for one import attempt.
nonisolated struct SgwcmaidCabinet: Sendable, Equatable {
  let keychip: String
  let placeID: Int
  let regionID: Int

  init(keychip: String, placeID: Int, regionID: Int) {
    self.keychip = keychip
    self.placeID = placeID
    self.regionID = regionID
  }

  init(keychip: SgwcmaidKeychip) {
    self.init(keychip: keychip.keychip, placeID: keychip.storeID, regionID: keychip.provinceID)
  }
}
