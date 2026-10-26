import Foundation

/// Persists the manually selected import region and the load-balancing cursor.
///
/// The selection is intentionally local-only: it is never derived from the
/// device location and no location permission is requested.
@MainActor
final class SgwcmaidKeychipStore {
  static let shared = SgwcmaidKeychipStore()

  private let defaults: UserDefaults
  private enum Keys {
    static let provinceID = "sgwcmaid.keychip.provinceID"
    static let cursor = "sgwcmaid.keychip.cursor"
  }

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
  }

  /// `nil` means automatic: the nationwide pool is used directly.
  var selectedProvinceID: Int? {
    get {
      let value = defaults.integer(forKey: Keys.provinceID)
      return value > 0 ? value : nil
    }
    set {
      if let newValue, newValue > 0 {
        defaults.set(newValue, forKey: Keys.provinceID)
      } else {
        defaults.removeObject(forKey: Keys.provinceID)
      }
    }
  }

  var cursor: Int { defaults.integer(forKey: Keys.cursor) }

  /// Advances the round-robin cursor so the next import starts after the
  /// cabinet this import settled on. Failed candidates are skipped until the
  /// cursor wraps around the pool.
  func advanceCursor(by count: Int) {
    let next = (cursor + max(0, count)) % 1_000_000
    defaults.set(next, forKey: Keys.cursor)
  }
}
