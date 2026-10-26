import CryptoKit
import Foundation
import Security

/// Encrypts the SGWCMAID binding before it enters a cloud backup.
///
/// The snapshot is stored on the backend, so the player id is never written in
/// the clear. Unlike the local Keychain binding, the backup must be readable
/// after a reinstall or on another device, so the key is derived from the
/// signed-in backend account instead: only the same account can decrypt its own
/// snapshot. This keeps the id opaque inside the stored object; it is not a
/// defence against the account owner or the backend that already knows that
/// account id.
///
/// Layout: `version(1) | iv(12) | AES-256-GCM ciphertext + tag(16)`.
/// The derivation must stay byte-identical to the Android `SgwcmaidBackupCipher`.
nonisolated enum SgwcmaidBackupCipher {
  private static let version: UInt8 = 1
  private static let ivBytes = 12
  private static let tagBytes = 16
  private static let keyBytes = 32
  private static let salt = Data("maimaid.backup.sgwcmaid.v1".utf8)
  private static let info = Data("aes-256-gcm".utf8)

  static func encrypt(_ binding: SgwcmaidBinding, accountID: String) throws -> Data {
    let plain = try JSONEncoder().encode(binding)
    var iv = Data(count: ivBytes)
    let status = iv.withUnsafeMutableBytes {
      SecRandomCopyBytes(kSecRandomDefault, ivBytes, $0.baseAddress!)
    }
    guard status == errSecSuccess else {
      throw CloudBackupError.invalid("Cannot generate an encryption nonce.")
    }
    let sealed = try AES.GCM.seal(
      plain, using: key(accountID: accountID),
      nonce: try AES.GCM.Nonce(data: iv))
    var blob = Data([version])
    blob.append(iv)
    blob.append(sealed.ciphertext)
    blob.append(sealed.tag)
    return blob
  }

  /// Returns nil for a foreign account, an older format or a corrupted blob.
  static func decrypt(_ blob: Data, accountID: String) -> SgwcmaidBinding? {
    guard blob.count > 1 + ivBytes + tagBytes, blob.first == version else { return nil }
    let iv = blob.subdata(in: 1..<(1 + ivBytes))
    let ciphertext = blob.subdata(in: (1 + ivBytes)..<(blob.count - tagBytes))
    let tag = blob.subdata(in: (blob.count - tagBytes)..<blob.count)
    guard let sealed = try? AES.GCM.SealedBox(
      nonce: try AES.GCM.Nonce(data: iv), ciphertext: ciphertext, tag: tag),
      let plain = try? AES.GCM.open(sealed, using: key(accountID: accountID)),
      let binding = try? JSONDecoder().decode(SgwcmaidBinding.self, from: plain),
      binding.userID > 0
    else { return nil }
    return binding
  }

  /// RFC 5869 HKDF-SHA256; kept explicit so both platforms agree on every byte.
  static func hkdf(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
    HKDF<SHA256>.deriveKey(
      inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info,
      outputByteCount: length
    ).withUnsafeBytes { Data($0) }
  }

  static func key(accountID: String) -> SymmetricKey {
    SymmetricKey(
      data: hkdf(ikm: Data(accountID.utf8), salt: salt, info: info, length: keyBytes))
  }
}
