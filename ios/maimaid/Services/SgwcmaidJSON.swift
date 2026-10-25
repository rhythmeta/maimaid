import Foundation

/// Type-checking helpers for the loosely typed `music.json` payloads.
///
/// `JSONSerialization` bridges JSON booleans to `NSNumber` as well, so the
/// numeric accessors explicitly reject booleans to mirror Kotlin's
/// `JsonPrimitive.intOrNull`/`doubleOrNull` behaviour.
nonisolated enum SgwcmaidJSON {
  static func int(_ value: Any?) -> Int? {
    guard let number = value as? NSNumber else { return nil }
    guard CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    return number.intValue
  }

  static func double(_ value: Any?) -> Double? {
    guard let number = value as? NSNumber else { return nil }
    guard CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    return number.doubleValue
  }

  /// Lenient variant for protocol fields that Android reads through
  /// `JsonPrimitive.contentOrNull` (so numeric strings are accepted).
  static func contentInt(_ value: Any?) -> Int? {
    if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() {
      return number.intValue
    }
    if let string = value as? String {
      return Int(string.trimmingCharacters(in: .whitespacesAndNewlines))
    }
    return nil
  }

  static func bool(_ value: Any?) -> Bool? {
    if let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() {
      return number.boolValue
    }
    if let value = value as? Bool { return value }
    return nil
  }
}
