import CommonCrypto
import CryptoKit
import Foundation
import zlib

nonisolated enum SgwcmaidCryptoError: Error, Equatable {
  case invalidKey
  case compression
  case encryption
}

/// Request/response protection for the China-server game API.
///
/// The wire format is a zlib stream (raw deflate plus the zlib header and
/// Adler-32 trailer) encrypted with AES-256-CBC and PKCS#7 padding. The iOS and
/// Android clients must produce the same bytes, so both use the default zlib
/// level and the deployment-supplied key and IV.
nonisolated enum SgwcmaidCrypto {
  private static let maximumRawBytes = 64 * 1024 * 1024

  static func protect(_ plaintext: Data, key: String, iv: String) throws -> Data {
    let compressed = try zlib(plaintext, compress: true)
    return try aesCBC(compressed, key: key, iv: iv, encrypt: true)
  }

  static func unprotect(_ ciphertext: Data, key: String, iv: String) throws -> Data {
    let compressed = try aesCBC(ciphertext, key: key, iv: iv, encrypt: false)
    return try zlib(compressed, compress: false)
  }

  static func md5Hex(_ value: String) -> String {
    hex(Insecure.MD5.hash(data: Data(value.utf8)))
  }

  static func sha256HexUpper(_ value: String) -> String {
    hex(SHA256.hash(data: Data(value.utf8))).uppercased()
  }

  private static func hex<Digest: Sequence>(_ digest: Digest) -> String
  where Digest.Element == UInt8 {
    digest.map { byte in
      let value = String(byte, radix: 16)
      return value.count == 1 ? "0" + value : value
    }.joined()
  }

  private static func aesCBC(_ input: Data, key: String, iv: String, encrypt: Bool) throws -> Data {
    let keyBytes = Array(key.utf8)
    let ivBytes = Array(iv.utf8)
    guard [16, 24, 32].contains(keyBytes.count), ivBytes.count == 16 else {
      throw SgwcmaidCryptoError.invalidKey
    }
    guard !input.isEmpty else { throw SgwcmaidCryptoError.encryption }

    var output = [UInt8](repeating: 0, count: input.count + kCCBlockSizeAES128)
    var moved = 0
    let status = input.withUnsafeBytes { inputBuffer in
      CCCrypt(
        CCOperation(encrypt ? kCCEncrypt : kCCDecrypt),
        CCAlgorithm(kCCAlgorithmAES),
        CCOptions(kCCOptionPKCS7Padding),
        keyBytes, keyBytes.count,
        ivBytes,
        inputBuffer.baseAddress, input.count,
        &output, output.count,
        &moved)
    }
    guard status == CCCryptorStatus(kCCSuccess) else {
      throw SgwcmaidCryptoError.encryption
    }
    return Data(output.prefix(moved))
  }

  private static func zlib(_ input: Data, compress: Bool) throws -> Data {
    guard !input.isEmpty else { throw SgwcmaidCryptoError.compression }

    var stream = z_stream()
    let streamSize = Int32(MemoryLayout<z_stream>.size)
    let result =
      compress
      ? deflateInit2_(
        &stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION,
        streamSize)
      : inflateInit2_(&stream, 15, ZLIB_VERSION, streamSize)
    guard result == Z_OK else { throw SgwcmaidCryptoError.compression }
    defer { if compress { deflateEnd(&stream) } else { inflateEnd(&stream) } }

    return try input.withUnsafeBytes { buffer in
      stream.next_in = UnsafeMutablePointer(mutating: buffer.bindMemory(to: Bytef.self).baseAddress)
      stream.avail_in = uInt(buffer.count)
      var output = Data()
      while true {
        var bytes = [UInt8](repeating: 0, count: 64 * 1024)
        let status = bytes.withUnsafeMutableBytes { target in
          stream.next_out = target.bindMemory(to: Bytef.self).baseAddress
          stream.avail_out = uInt(target.count)
          return compress ? deflate(&stream, Z_FINISH) : inflate(&stream, Z_NO_FLUSH)
        }
        output.append(contentsOf: bytes.prefix(bytes.count - Int(stream.avail_out)))
        guard output.count <= maximumRawBytes else { throw SgwcmaidCryptoError.compression }
        if status == Z_STREAM_END {
          return output
        }
        guard status == Z_OK, stream.avail_in > 0 || stream.avail_out == 0 else {
          throw SgwcmaidCryptoError.compression
        }
      }
    }
  }
}
