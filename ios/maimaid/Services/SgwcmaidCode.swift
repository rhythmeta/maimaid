import Foundation

/// Format rules for the QR payload printed on a China-server cabinet.
///
/// A payload is the literal `SGWCMAID` prefix followed by a header and the
/// per-cabinet code. Only the characters after the 20th are forwarded to the
/// chime service, mirroring the Android implementation.
nonisolated enum SgwcmaidCode {
  static let prefix = "SGWCMAID"
  static let headerLength = 20

  static func isRecognized(_ value: String) -> Bool {
    value.hasPrefix(prefix) && value.count > headerLength
  }

  static func payload(of value: String) -> String? {
    guard isRecognized(value) else { return nil }
    return String(value.dropFirst(headerLength))
  }
}
