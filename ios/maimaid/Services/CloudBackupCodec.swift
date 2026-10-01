import CryptoKit
import Foundation
import SwiftProtobuf
import zlib

nonisolated enum CloudBackupError: LocalizedError {
  case invalid(String)
  var errorDescription: String? {
    if case .invalid(let message) = self { return message }
    return nil
  }
}

nonisolated enum CloudBackupCodec {
  @concurrent static func encodeAsync(_ snapshot: RHYSnapshot) async throws -> Data {
    try encode(snapshot)
  }
  @concurrent static func decodeAsync(_ bytes: Data, expectedSize: Int) async throws -> RHYSnapshot {
    try decode(bytes, expectedSize: expectedSize)
  }
  static let maxCompressed = 64 * 1024 * 1024
  static let maxRaw = 512 * 1024 * 1024
  static func sha256(_ bytes: Data) -> String {
    SHA256.hash(data: bytes).map { ($0 < 16 ? "0" : "") + String($0, radix: 16) }.joined()
  }
  static func encode(_ snapshot: RHYSnapshot) throws -> Data {
    try validate(snapshot)
    let raw = try snapshot.serializedData()
    guard raw.count <= maxRaw else { throw CloudBackupError.invalid("Backup is too large.") }
    let data = try gzip(raw, compress: true)
    guard data.count <= maxCompressed else {
      throw CloudBackupError.invalid("Backup is too large.")
    }
    return data
  }
  static func decode(_ bytes: Data, expectedSize: Int? = nil) throws -> RHYSnapshot {
    guard bytes.count <= maxCompressed else {
      throw CloudBackupError.invalid("Backup is too large.")
    }
    let raw = try gzip(bytes, compress: false)
    if let expectedSize, raw.count != expectedSize {
      throw CloudBackupError.invalid("Backup size does not match.")
    }
    let snapshot = try RHYSnapshot(serializedBytes: raw)
    try validate(snapshot)
    return snapshot
  }
  static func validate(_ snapshot: RHYSnapshot) throws {
    func check(_ condition: Bool, _ message: String = "Invalid backup records.") throws {
      if !condition { throw CloudBackupError.invalid(message) }
    }
    func ids(_ values: [String]) throws {
      try check(Set(values).count == values.count)
      try check(values.allSatisfy { UUID(uuidString: $0) != nil })
    }
    try check(
      snapshot.magic == "RHYTHMETA_BACKUP" && snapshot.formatVersion == 1
        && snapshot.game == "maimaid", "Unsupported backup format or game.")
    try check(
      (1...10000).contains(snapshot.profiles.count) && snapshot.scores.count <= 1_000_000
        && snapshot.playRecords.count <= 2_000_000)
    try ids(snapshot.profiles.map(\.id))
    try check(snapshot.profiles.filter(\.active).count == 1)
    try check(
      snapshot.profiles.allSatisfy { $0.avatar.count <= 16 * 1024 * 1024 && $0.name.count <= 200 })
    let profiles = Set(snapshot.profiles.map(\.id))
    func result(_ value: RHYScore) throws {
      try check(
        profiles.contains(value.profileID) && !value.chartKey.isEmpty
          && value.chartKey.count <= 1024)
      try check(
        value.achievement.isFinite && (0...101).contains(value.achievement) && value.dxScore >= 0)
    }
    try snapshot.scores.forEach(result)
    try check(
      Set(snapshot.scores.map { "\($0.profileID)|\($0.chartKey)" }).count == snapshot.scores.count)
    try ids(snapshot.playRecords.map(\.id))
    try snapshot.playRecords.forEach {
      try check($0.hasResult)
      try result($0.result)
    }
    try ids(snapshot.collections.map(\.id))
    try ids(snapshot.collectionItems.map(\.id))
    let collections = Set(snapshot.collections.map(\.id))
    try check(
      snapshot.collectionItems.allSatisfy {
        collections.contains($0.collectionID) && !$0.songID.isEmpty
      })
    try check(
      Set(
        snapshot.collectionItems.map {
          "\($0.collectionID)|\($0.songID)|\($0.chartType)|\($0.difficulty)"
        }
      ).count == snapshot.collectionItems.count)
    try check(
      snapshot.settings.count <= 1000
        && Set(snapshot.settings.map(\.key)).count == snapshot.settings.count)
  }
  private static func gzip(_ input: Data, compress: Bool) throws -> Data {
    var stream = z_stream()
    let size = Int32(MemoryLayout<z_stream>.size)
    let result =
      compress
      ? deflateInit2_(
        &stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 31, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, size)
      : inflateInit2_(&stream, 31, ZLIB_VERSION, size)
    guard result == Z_OK else { throw CloudBackupError.invalid("Cannot initialize gzip.") }
    defer { if compress { deflateEnd(&stream) } else { inflateEnd(&stream) } }
    return try input.withUnsafeBytes { buffer in
      stream.next_in = UnsafeMutablePointer(mutating: buffer.bindMemory(to: Bytef.self).baseAddress)
      stream.avail_in = uInt(buffer.count)
      var output = Data()
      while true {
        var bytes = [UInt8](repeating: 0, count: 65536)
        let status = bytes.withUnsafeMutableBytes { target in
          stream.next_out = target.bindMemory(to: Bytef.self).baseAddress
          stream.avail_out = uInt(target.count)
          return compress ? deflate(&stream, Z_FINISH) : inflate(&stream, Z_NO_FLUSH)
        }
        output.append(contentsOf: bytes.prefix(bytes.count - Int(stream.avail_out)))
        guard output.count <= (compress ? maxCompressed : maxRaw) else {
          throw CloudBackupError.invalid("Backup is too large.")
        }
        if status == Z_STREAM_END {
          guard stream.avail_in == 0 else {
            throw CloudBackupError.invalid("Unexpected trailing backup data.")
          }
          return output
        }
        guard status == Z_OK, stream.avail_in > 0 || stream.avail_out == 0 else {
          throw CloudBackupError.invalid("Invalid gzip backup.")
        }
      }
    }
  }
}
