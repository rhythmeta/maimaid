import Foundation

enum ScoreImportProvider: String, CaseIterable, Hashable, Sendable {
  case divingFish, lxns
  /// China-server imports do not use an OAuth token; they exchange a cabinet
  /// QR code. The case lives here so score payload decoding and merging can be
  /// shared with the other providers.
  case sgwcmaid

  var clientID: String {
    switch self {
    case .divingFish: "5b79b87f22855b80ee35243eeec07916"
    case .lxns: LxnsOAuthConfiguration.clientId
    case .sgwcmaid: ""
    }
  }

  var tokenURL: String {
    switch self {
    case .divingFish: "https://auth.diving-fish.com/oauth/token"
    case .lxns: "https://maimai.lxns.net/api/v0/oauth/token"
    case .sgwcmaid: ""
    }
  }

  /// SGWCMAID imports replace the stored score with the cabinet's copy instead
  /// of keeping the better of the two values.
  var replacesExistingScores: Bool { self == .sgwcmaid }
}
