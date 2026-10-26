import Foundation
import Testing

@testable import maimaid

@MainActor
struct SgwcmaidBackupCipherTests {
  private let binding = SgwcmaidBinding(userID: 10_834_470, playerName: "Ｋｅｒｉｔｉａｌ")

  @Test func roundTripRestoresTheBinding() throws {
    let blob = try SgwcmaidBackupCipher.encrypt(binding, accountID: "account-one")
    #expect(SgwcmaidBackupCipher.decrypt(blob, accountID: "account-one") == binding)
  }

  @Test func anotherAccountCannotDecryptTheBlob() throws {
    let blob = try SgwcmaidBackupCipher.encrypt(binding, accountID: "account-one")
    #expect(SgwcmaidBackupCipher.decrypt(blob, accountID: "account-two") == nil)
  }

  @Test func tamperedOrTruncatedBlobsAreRejected() throws {
    let blob = try SgwcmaidBackupCipher.encrypt(binding, accountID: "account-one")
    var tampered = blob
    tampered[tampered.count - 1] ^= 0x01
    #expect(SgwcmaidBackupCipher.decrypt(tampered, accountID: "account-one") == nil)
    #expect(SgwcmaidBackupCipher.decrypt(blob.prefix(8), accountID: "account-one") == nil)
    var wrongVersion = blob
    wrongVersion[0] = 9
    #expect(SgwcmaidBackupCipher.decrypt(wrongVersion, accountID: "account-one") == nil)
  }

  @Test func bindingBlobsAreNeverWrittenInTheClear() throws {
    let blob = try SgwcmaidBackupCipher.encrypt(binding, accountID: "account-one")
    let text = String(decoding: blob, as: UTF8.self)
    #expect(!text.contains("10834470"))
    #expect(!text.contains("playerName"))
  }

  /// RFC 5869, appendix A.1: both platforms must derive the same key material.
  @Test func hkdfMatchesRFC5869TestVector() {
    let ikm = Data(repeating: 0x0b, count: 22)
    let salt = Data((0...12).map { UInt8($0) })
    let info = Data([0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9])
    let expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
      + "34007208d5b887185865"
    let actual = SgwcmaidBackupCipher.hkdf(ikm: ikm, salt: salt, info: info, length: 42)
    #expect(actual.hexString == expected)
  }
}

extension Data {
  fileprivate var hexString: String {
    map { String(format: "%02x", $0) }.joined()
  }
}
