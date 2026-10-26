import Foundation
import Security

/// The China-server player remembered for a local profile.
///
/// Only [playerName] is ever shown in the UI; [userID] stays inside the import
/// pipeline and is persisted in the Keychain.
nonisolated struct SgwcmaidBinding: Codable, Equatable, Sendable {
  let userID: Int
  let playerName: String

  enum CodingKeys: String, CodingKey {
    case userID = "userId"
    case playerName
  }
}

/// Remembers the SGWCMAID player of each profile so its scores can be refreshed
/// without scanning another cabinet QR code.
///
/// Mirrors the Android `SgwcmaidBindingStore`: the payload is stored in the
/// Keychain (`WhenUnlockedThisDeviceOnly`, never synced) and bound to the
/// profile UUID.
@MainActor
final class SgwcmaidBindingStore {
  static let shared = SgwcmaidBindingStore()

  private static let service = "in.shikoch.maimaid.sgwcmaid.binding"

  private init() {}

  func binding(for profileID: UUID) -> SgwcmaidBinding? {
    var query = keychainQuery(for: profileID)
    query[kSecReturnData] = kCFBooleanTrue
    query[kSecMatchLimit] = kSecMatchLimitOne
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
      let data = item as? Data,
      let binding = try? JSONDecoder().decode(SgwcmaidBinding.self, from: data),
      binding.userID > 0
    else { return nil }
    return binding
  }

  func save(_ binding: SgwcmaidBinding, for profileID: UUID) {
    guard binding.userID > 0, let data = try? JSONEncoder().encode(binding) else { return }
    let query = keychainQuery(for: profileID)
    let update: [CFString: Any] = [
      kSecValueData: data,
      kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
    ]
    let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
    guard status == errSecItemNotFound else { return }
    var insert = query
    insert[kSecValueData] = data
    insert[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    insert[kSecAttrSynchronizable] = kCFBooleanFalse as Any
    SecItemAdd(insert as CFDictionary, nil)
  }

  func clear(for profileID: UUID) {
    SecItemDelete(keychainQuery(for: profileID) as CFDictionary)
  }

  private func keychainQuery(for profileID: UUID) -> [CFString: Any] {
    [
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: Self.service,
      kSecAttrAccount: profileID.uuidString.lowercased(),
      kSecAttrSynchronizable: kCFBooleanFalse as Any,
    ]
  }
}
