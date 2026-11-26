import Foundation

/// A login credential accepted by the international AiMeDB.
///
/// `IDm [PMm]` for FeliCa cards, `AccessCode [M1 UID]` for 20-digit cards. The cleaned
/// lengths never overlap, so one pass tells them apart.
nonisolated enum SdgaCredential: Sendable, Equatable {
  case idm(String, pmm: String?)
  case accessCode(String, uid: String?)

  static let defaultPMM = "00F1000000014300"
  private static let aimeCardPrefix = "012E"

  /// The two fields joined the way the protocol expects.
  var requestValue: String {
    switch self {
    case .idm(let idm, let pmm): pmm.map { "\(idm) \($0)" } ?? idm
    case .accessCode(let code, let uid): uid.map { "\(code) \($0)" } ?? code
    }
  }

  /// Card part only, for the first text field.
  var card: String {
    switch self {
    case .idm(let value, _), .accessCode(let value, _): value
    }
  }

  /// Optional part only, for the second text field.
  var extra: String? {
    switch self {
    case .idm(_, let pmm): pmm
    case .accessCode(_, let uid): uid
    }
  }

  /// Non-`012E` FeliCa media each carry their own PMm.
  static func needsPMM(_ idm: String) -> Bool {
    !clean(idm).uppercased().hasPrefix(aimeCardPrefix)
  }

  /// The PMm actually sent: the card's own, the Aime default, or an error.
  static func pmm(for idm: String, provided: String?) throws -> String {
    if let provided, !provided.isEmpty { return provided }
    guard !needsPMM(idm) else { throw ScoreImportError(code: "sdga_pmm_required") }
    return defaultPMM
  }

  static func clean(_ value: String) -> String {
    value.filter { !$0.isWhitespace && !":,;/|-".contains($0) }
  }

  static func parse(_ raw: String) -> SdgaCredential? {
    let text = clean(raw)
    if let match = text.wholeMatch(of: /([0-9A-Fa-f]{16})([0-9A-Fa-f]{16})?/) {
      return .idm(
        String(match.1).uppercased(),
        pmm: match.2.map { String($0).uppercased() })
    }
    if let match = text.wholeMatch(of: /([0-9]{20})([0-9A-Fa-f]{8})?/) {
      return .accessCode(
        String(match.1),
        uid: match.2.map { String($0).uppercased() })
    }
    return nil
  }
}
