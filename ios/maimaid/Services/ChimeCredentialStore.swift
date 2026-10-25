import Foundation

/// Stores the cabinet keychip used by the SGWCMAID exchange.
///
/// Builds normally carry the keychip through `SGWCMAID_KEYCHIP`, but keeping a
/// local override matches the Android `ChimeCredentialStore` and lets a
/// developer test with another cabinet without rebuilding.
@MainActor
final class ChimeCredentialStore {
  static let shared = ChimeCredentialStore()

  private let defaults: UserDefaults
  private let key = "sgwcmaid.keychip"

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
  }

  /// Returns the stored keychip, falling back to the configured one.
  func keychip(fallback: String) -> String {
    let stored = (defaults.string(forKey: key) ?? "")
      .trimmingCharacters(in: .whitespacesAndNewlines)
    guard stored.isEmpty else { return stored }
    return fallback.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  func setKeychip(_ value: String) {
    defaults.set(
      value.trimmingCharacters(in: .whitespacesAndNewlines), forKey: key)
  }
}
