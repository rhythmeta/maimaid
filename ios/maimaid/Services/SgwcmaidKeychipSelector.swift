import Foundation

/// The ordered cabinet candidates for one import.
///
/// `preferred` holds the selected region in latency order; `fallback` holds every
/// other region. Both lists are rotated by the persisted cursor so consecutive
/// imports spread across cabinets instead of always hitting the fastest one.
nonisolated struct SgwcmaidKeychipPlan: Sendable, Equatable {
  let preferred: [SgwcmaidKeychip]
  let fallback: [SgwcmaidKeychip]

  var isEmpty: Bool { preferred.isEmpty && fallback.isEmpty }

  func attempts(
    preferredLimit: Int = SgwcmaidKeychipSelector.preferredAttemptLimit,
    fallbackLimit: Int = SgwcmaidKeychipSelector.fallbackAttemptLimit
  ) -> [SgwcmaidKeychip] {
    Array(preferred.prefix(max(0, preferredLimit)))
      + Array(fallback.prefix(max(0, fallbackLimit)))
  }
}

/// Pure selection policy shared by the import service and its tests.
nonisolated enum SgwcmaidKeychipSelector {
  /// A province can contain hundreds of cabinets, so cap the retries. The
  /// fallback budget is separate to guarantee a nationwide attempt even when
  /// every cabinet in the selected province fails.
  static let preferredAttemptLimit = 4
  static let fallbackAttemptLimit = 4

  static func plan(
    catalog: SgwcmaidKeychipCatalog, provinceID: Int?, cursor: Int
  ) -> SgwcmaidKeychipPlan {
    let preferred = provinceID.map { catalog.keychips(inProvince: $0) } ?? []
    let fallback: [SgwcmaidKeychip] =
      if let provinceID {
        catalog.keychips.filter { $0.provinceID != provinceID }
      } else {
        catalog.keychips
      }
    return SgwcmaidKeychipPlan(
      preferred: rotatedByLatency(preferred, cursor: cursor),
      fallback: rotatedByLatency(fallback, cursor: cursor))
  }

  /// Sorts the pool by measured latency (then keychip for stability) and
  /// rotates it by the cursor. The cursor advances after a successful import,
  /// giving round-robin load balancing inside each latency bucket.
  private static func rotatedByLatency(
    _ keychips: [SgwcmaidKeychip], cursor: Int
  ) -> [SgwcmaidKeychip] {
    let sorted = keychips.sorted { lhs, rhs in
      if lhs.latencyMS != rhs.latencyMS { return lhs.latencyMS < rhs.latencyMS }
      return lhs.keychip < rhs.keychip
    }
    guard !sorted.isEmpty else { return [] }
    let offset = ((cursor % sorted.count) + sorted.count) % sorted.count
    return Array(sorted[offset...]) + Array(sorted[..<offset])
  }
}

/// Decides whether a failed chime exchange is worth retrying with the next
/// cabinet.
///
/// A code that is invalid or already used cannot be rescued by another
/// keychip. Transport failures, HTTP errors, and chime database errors can be
/// cabinet-specific and are retried.
nonisolated enum SgwcmaidKeychipFailure {
  static func isRetryable(_ code: String) -> Bool {
    switch code {
    case "invalid_sgwcmaid", "sgwcmaid_not_configured",
      "chime_code_invalid_or_expired", "chime_code_already_used",
      "chime_keychip_missing":
      return false
    default:
      return code.hasPrefix("chime_")
    }
  }
}
