import Foundation

/// The public exit address the title server will see, plus what the provider reports.
nonisolated struct SdgaEgress: Sendable, Equatable {
  let address: String
  /// ISO-3166 alpha-2, when known.
  let countryCode: String?
  let isp: String?
}

/// Reads the exit address once per screen visit.
///
/// The international endpoints are region filtered, so seeing the address and its carrier
/// is the first thing to check when an import fails. This is informational: a datacenter
/// line has been observed to pass, so nothing here decides whether an import may run.
nonisolated enum SdgaEgressService {
  static func fetch() async -> SdgaEgress? {
    guard let url = URL(string: "https://ipwho.is/") else { return nil }
    var request = URLRequest(url: url)
    request.timeoutInterval = 6
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    guard let (data, _) = try? await URLSession.shared.data(for: request),
      let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      let address = root["ip"] as? String, !address.isEmpty
    else { return nil }
    let rawCountry = (root["country_code"] as? String)?.uppercased()
    let country = (rawCountry?.count == 2) ? rawCountry : nil
    let connection = root["connection"] as? [String: Any]
    let isp = (connection?["isp"] as? String) ?? (root["isp"] as? String)
    return SdgaEgress(address: address, countryCode: country, isp: isp)
  }
}
