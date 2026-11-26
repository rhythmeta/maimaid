import Foundation
import Security

/// The card credential a profile last imported with.
///
/// The international card number is fixed, so remembering it turns the next import into one
/// tap. It is a card credential rather than a session token, so it lives in the Keychain
/// (`WhenUnlockedThisDeviceOnly`, never synced) and is bound to the profile UUID.
nonisolated struct SdgaSavedCredential: Codable, Equatable, Sendable {
  let card: String
  let extra: String
  let playerName: String

  /// Display form that keeps only the head and tail of the card number.
  var masked: String {
    card.count <= 8 ? card : "\(card.prefix(4))…\(card.suffix(4))"
  }
}

@MainActor
final class SdgaCredentialStore {
  static let shared = SdgaCredentialStore()

  private static let service = "in.shikoch.maimaid.sdga.credential"

  private init() {}

  func credential(for profileID: UUID) -> SdgaSavedCredential? {
    var query = keychainQuery(for: profileID)
    query[kSecReturnData] = kCFBooleanTrue
    query[kSecMatchLimit] = kSecMatchLimitOne
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
      let data = item as? Data,
      let saved = try? JSONDecoder().decode(SdgaSavedCredential.self, from: data),
      !saved.card.isEmpty
    else { return nil }
    return saved
  }

  func save(_ credential: SdgaSavedCredential, for profileID: UUID) {
    guard !credential.card.isEmpty, let data = try? JSONEncoder().encode(credential) else {
      return
    }
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

/// The manually selected import region/arcade and the rotation cursor.
@MainActor
final class SdgaPlaceStore {
  static let shared = SdgaPlaceStore()

  private let defaults = UserDefaults.standard

  var country: String? {
    get { defaults.string(forKey: "sdga.import.country")?.nonEmpty }
    set { defaults.set(newValue, forKey: "sdga.import.country") }
  }

  var placeID: Int? {
    get {
      let value = defaults.integer(forKey: "sdga.import.place")
      return value > 0 ? value : nil
    }
    set { defaults.set(newValue ?? 0, forKey: "sdga.import.place") }
  }

  var cursor: Int {
    get { defaults.integer(forKey: "sdga.import.cursor") }
    set { defaults.set(newValue % 1_000_000, forKey: "sdga.import.cursor") }
  }
}

extension String {
  fileprivate var nonEmpty: String? { isEmpty ? nil : self }
}
