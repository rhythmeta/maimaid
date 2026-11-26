import Foundation

/// Deployment values for the international-server (SDGA) import.
///
/// Like `SgwcmaidConfig`, everything comes from build settings through `Info.plist`, so
/// endpoints and keys never live in source control. An unconfigured build reports
/// `sdga_not_configured` instead of importing nothing.
nonisolated struct SdgaConfig: Sendable, Equatable {
  var titleServerURL: String
  var aesKey: String
  var aesIV: String
  var obfuscateParam: String
  var commonKey: String
  var aimeHost: String
  var aimePort: Int
  var gameVersion: String
  var gameID: String
  var keychip: String
  var placeID: Int
  var regionID: Int

  static let empty = SdgaConfig(
    titleServerURL: "", aesKey: "", aesIV: "", obfuscateParam: "", commonKey: "",
    aimeHost: "", aimePort: 22345, gameVersion: "1.65", gameID: "SDGA",
    keychip: "", placeID: 0, regionID: 0)

  /// The cabinet values are excluded: the bundled places catalog normally supplies them.
  var isComplete: Bool {
    !titleServerURL.isEmpty && aesKey.count == 32 && aesIV.count == 16
      && !obfuscateParam.isEmpty && commonKey.count == 16 && !aimeHost.isEmpty
  }

  /// The servlet base always ends with `/`; each API appends only its own hash.
  var servletBase: String {
    titleServerURL.hasSuffix("/") ? titleServerURL : titleServerURL + "/"
  }

  static let current = SdgaConfig(
    titleServerURL: AppInfo.configuredString(for: "SDGA_TITLE_SERVER") ?? "",
    aesKey: AppInfo.configuredString(for: "SDGA_AES_KEY") ?? "",
    aesIV: AppInfo.configuredString(for: "SDGA_AES_IV") ?? "",
    obfuscateParam: AppInfo.configuredString(for: "SDGA_OBFUSCATE_PARAM") ?? "",
    commonKey: AppInfo.configuredString(for: "SDGA_COMMON_KEY") ?? "",
    aimeHost: AppInfo.configuredString(for: "SDGA_AIME_HOST") ?? "",
    aimePort: AppInfo.configuredString(for: "SDGA_AIME_PORT").flatMap(Int.init) ?? 22345,
    gameVersion: AppInfo.configuredString(for: "SDGA_GAME_VERSION") ?? "1.65",
    gameID: AppInfo.configuredString(for: "SDGA_GAME_ID") ?? "SDGA",
    keychip: AppInfo.configuredString(for: "SDGA_KEYCHIP") ?? "",
    placeID: AppInfo.configuredString(for: "SDGA_PLACE_ID").flatMap(Int.init) ?? 0,
    regionID: AppInfo.configuredString(for: "SDGA_REGION_ID").flatMap(Int.init) ?? 0)
}
