import Foundation

enum ScoreImportProvider: String, CaseIterable, Hashable, Sendable {
  case divingFish, lxns

  var clientID: String {
    switch self {
    case .divingFish: "5b79b87f22855b80ee35243eeec07916"
    case .lxns: LxnsOAuthConfiguration.clientId
    }
  }
  var tokenURL: String {
    switch self {
    case .divingFish: "https://auth.diving-fish.com/oauth/token"
    case .lxns: "https://maimai.lxns.net/api/v0/oauth/token"
    }
  }
}
