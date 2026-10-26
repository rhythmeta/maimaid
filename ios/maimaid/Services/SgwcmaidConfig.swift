import Foundation

/// Deployment values for the SGWCMAID import.
///
/// The values are supplied through build settings in `Config/Base.xcconfig`
/// (overridable in the gitignored `Config/Secrets.xcconfig`) and exposed to the
/// app through `Info.plist`, so endpoints, keys, and salts never live in source
/// control. An unconfigured build reports `sgwcmaid_not_configured` instead of
/// silently importing nothing.
nonisolated struct SgwcmaidConfig: Sendable, Equatable {
  var chimeURL: String
  var gameServer: String
  var keychip: String
  var aesKey: String
  var aesIV: String
  var apiSalt: String
  var chimeSecret: String
  var titleKey: String
  var placeID: Int
  var regionID: Int

  static let empty = SgwcmaidConfig(
    chimeURL: "", gameServer: "", keychip: "", aesKey: "", aesIV: "", apiSalt: "",
    chimeSecret: "", titleKey: "", placeID: 0, regionID: 0)

  /// Mirrors the Android `SgwcmaidConfig.isComplete` contract.
  ///
  /// The keychip, place ID and region ID are deliberately excluded: the bundled
  /// catalog supplies the cabinet-specific values, and the build-time keychip
  /// only acts as a last-resort fallback.
  var isComplete: Bool {
    !chimeURL.isEmpty && !gameServer.isEmpty && aesKey.count == 32 && aesIV.count == 16
      && !apiSalt.isEmpty && !chimeSecret.isEmpty && !titleKey.isEmpty
  }

  static let current = SgwcmaidConfig(
    chimeURL: string(for: BundleInfoKeys.sgwcmaidChimeURL),
    gameServer: string(for: BundleInfoKeys.sgwcmaidGameServer),
    keychip: string(for: BundleInfoKeys.sgwcmaidKeychip),
    aesKey: string(for: BundleInfoKeys.sgwcmaidAESKey),
    aesIV: string(for: BundleInfoKeys.sgwcmaidAESIV),
    apiSalt: string(for: BundleInfoKeys.sgwcmaidAPISalt),
    chimeSecret: string(for: BundleInfoKeys.sgwcmaidChimeSecret),
    titleKey: string(for: BundleInfoKeys.sgwcmaidTitleKey),
    placeID: integer(for: BundleInfoKeys.sgwcmaidPlaceID),
    regionID: integer(for: BundleInfoKeys.sgwcmaidRegionID))

  private static func string(for key: String) -> String {
    AppInfo.configuredString(for: key) ?? ""
  }

  private static func integer(for key: String) -> Int {
    AppInfo.configuredString(for: key).flatMap(Int.init) ?? 0
  }
}
