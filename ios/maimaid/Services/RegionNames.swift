import Foundation

/// Turns the region codes used by the deployments into readable names.
///
/// The places catalog publishes ISO-3166 alpha-3 (`SGP`, `HKG`, …) while IP geolocation
/// answers with alpha-2; both are rendered with the system's localized country names.
nonisolated enum RegionNames {
  private static let iso3ToIso2: [String: String] = [
    "AUS": "AU", "CAN": "CA", "CHN": "CN", "GBR": "GB", "HKG": "HK", "IDN": "ID",
    "JPN": "JP", "KOR": "KR", "MAC": "MO", "MMR": "MM", "MYS": "MY", "NZL": "NZ",
    "PHL": "PH", "SGP": "SG", "THA": "TH", "TWN": "TW", "USA": "US", "VNM": "VN",
  ]

  static func iso2(_ code: String?) -> String? {
    let normalized = (code ?? "").trimmingCharacters(in: .whitespaces).uppercased()
    return normalized.count == 2 ? normalized : iso3ToIso2[normalized]
  }

  static func displayName(_ code: String?) -> String {
    let raw = (code ?? "").trimmingCharacters(in: .whitespaces)
    guard let region = iso2(raw),
      let name = Locale.current.localizedString(forRegionCode: region),
      !name.isEmpty
    else { return raw }
    return name
  }
}
