import CryptoKit
import Foundation
import Security

struct PendingAppLogin {
  let state: String
  let verifier: String
  let createdAt: Date

  var challenge: String { Self.encode(Data(SHA256.hash(data: Data(verifier.utf8)))) }

  static func create() -> Self? {
    var bytes = [UInt8](repeating: 0, count: 64)
    guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
      return nil
    }
    return Self(
      state: encode(Data(bytes.prefix(32))), verifier: encode(Data(bytes.suffix(32))),
      createdAt: Date())
  }

  private static func encode(_ data: Data) -> String {
    data.base64EncodedString().replacing("+", with: "-").replacing("/", with: "_").replacing(
      "=", with: "")
  }
}
