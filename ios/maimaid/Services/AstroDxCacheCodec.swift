import CommonCrypto
import Foundation
import zlib

nonisolated enum AstroDxCacheError: Error, Equatable {
  case tooLarge
  case unsupportedVersion
  case invalid
}

nonisolated struct AstroDxScore: Sendable {
  let title: String
  let path: String
  let difficultyID: Int
  let totalNotes: Int
  let achievement: Double
  let dxScore: Int
  let fc: String?
}

nonisolated struct AstroDxParsedCache: Sendable {
  let scores: [AstroDxScore]
  let invalidCount: Int
}

nonisolated enum AstroDxCacheCodec {
  static let maximumInputBytes = 16 * 1024 * 1024
  private static let maximumJSONBytes = 64 * 1024 * 1024
  private static let key = Array("94625184621352165487465213218452".utf8)

  @concurrent
  static func parse(_ input: Data) async throws -> AstroDxParsedCache {
    let root = try decodeRoot(input)
    guard let metadata = root["level_metadata"] as? [String: Any] else {
      throw AstroDxCacheError.invalid
    }

    var scores: [AstroDxScore] = []
    var invalidCount = 0
    for (key, value) in metadata {
      guard let song = value as? [String: Any],
        let difficulties = song["difficulties"] as? [[String: Any]]
      else { throw AstroDxCacheError.invalid }

      for chart in difficulties {
        switch parseChart(chart, song: song, fallbackTitle: key) {
        case .unplayed:
          continue
        case .invalid:
          invalidCount += 1
        case .score(let score):
          scores.append(score)
        }
      }
    }
    return AstroDxParsedCache(scores: scores, invalidCount: invalidCount)
  }

  private enum ChartParseResult {
    case unplayed
    case invalid
    case score(AstroDxScore)
  }

  private static func parseChart(
    _ chart: [String: Any], song: [String: Any], fallbackTitle: String
  ) -> ChartParseResult {
    guard let stats = chart["stats"] as? [String: Any] else { return .unplayed }
    let playedKeys = ["achievementRate", "dxScore", "clearType", "completePlays", "clearedPlays"]
    guard playedKeys.contains(where: { number(stats[$0]).map { $0 != 0 } ?? false }) else {
      return .unplayed
    }
    guard let achievement = number(stats["achievementRate"]),
      let dxScore = integer(stats["dxScore"]),
      let totalNotes = integer(stats["totalNotesCount"]),
      let clearType = integer(stats["clearType"]),
      let difficultyID = integer(chart["id"]),
      (0...101).contains(achievement), (1...1_000_000).contains(totalNotes),
      (0...6).contains(clearType), (2...7).contains(difficultyID),
      dxScore >= 0, dxScore <= totalNotes * 3
    else { return .invalid }

    let fc: String?
    switch clearType {
    case 3: fc = "fc"
    case 4: fc = "fcp"
    case 5: fc = "ap"
    case 6: fc = "app"
    default: fc = nil
    }
    return .score(AstroDxScore(
      title: (song["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? fallbackTitle,
      path: (song["path"] as? String) ?? fallbackTitle,
      difficultyID: difficultyID,
      totalNotes: totalNotes,
      achievement: achievement,
      dxScore: dxScore,
      fc: fc
    ))
  }

  private static func decodeRoot(_ input: Data) throws -> [String: Any] {
    guard !input.isEmpty, input.count <= maximumInputBytes else { throw AstroDxCacheError.tooLarge }
    let text = (String(data: input, encoding: .utf8) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    let json: Data
    if text.hasPrefix("{") {
      json = Data(text.utf8)
    } else if input.starts(with: [0x1f, 0x8b]) {
      json = try inflate(input, windowBits: 15 + 16)
    } else if !text.isEmpty, text.unicodeScalars.allSatisfy({
      CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\r\n\t ")
        .contains($0)
    }), let encrypted = Data(base64Encoded: text.filter { !$0.isWhitespace }) {
      json = try decrypt(encrypted)
    } else {
      let hasZlibHeader = input.count >= 2
        && (input[input.startIndex] & 0x0F) == 8
        && ((Int(input[input.startIndex]) * 256 + Int(input[input.index(after: input.startIndex)])) % 31 == 0)
      json = try inflate(input, windowBits: hasZlibHeader ? 15 : -15)
    }

    guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any] else {
      throw AstroDxCacheError.invalid
    }
    guard integer(root["version"]) == 3 || integer(root["version"]) == 4 else {
      throw AstroDxCacheError.unsupportedVersion
    }
    guard root["level_metadata"] is [String: Any] else { throw AstroDxCacheError.invalid }
    return root
  }

  private static func decrypt(_ input: Data) throws -> Data {
    guard !input.isEmpty, input.count.isMultiple(of: kCCBlockSizeAES128) else {
      throw AstroDxCacheError.invalid
    }
    var output = [UInt8](repeating: 0, count: input.count + kCCBlockSizeAES128)
    var outputCount = 0
    let status = input.withUnsafeBytes { source in
      CCCrypt(
        CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES),
        CCOptions(kCCOptionPKCS7Padding | kCCOptionECBMode), key, key.count, nil,
        source.baseAddress, input.count, &output, output.count, &outputCount
      )
    }
    guard status == kCCSuccess else { throw AstroDxCacheError.invalid }
    return Data(output.prefix(outputCount))
  }

  private static func inflate(_ input: Data, windowBits: Int32) throws -> Data {
    var stream = z_stream()
    guard inflateInit2_(&stream, windowBits, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
      throw AstroDxCacheError.invalid
    }
    defer { inflateEnd(&stream) }
    return try input.withUnsafeBytes { source in
      stream.next_in = UnsafeMutablePointer(mutating: source.bindMemory(to: Bytef.self).baseAddress)
      stream.avail_in = uInt(source.count)
      var output = Data()
      while true {
        var buffer = [UInt8](repeating: 0, count: 64 * 1024)
        let status = buffer.withUnsafeMutableBytes { target in
          stream.next_out = target.bindMemory(to: Bytef.self).baseAddress
          stream.avail_out = uInt(target.count)
          return zlib.inflate(&stream, Z_NO_FLUSH)
        }
        output.append(contentsOf: buffer.prefix(buffer.count - Int(stream.avail_out)))
        guard output.count <= maximumJSONBytes else { throw AstroDxCacheError.tooLarge }
        if status == Z_STREAM_END {
          guard stream.avail_in == 0 else { throw AstroDxCacheError.invalid }
          return output
        }
        guard status == Z_OK, stream.avail_in > 0 || stream.avail_out == 0 else {
          throw AstroDxCacheError.invalid
        }
      }
    }
  }

  private static func number(_ value: Any?) -> Double? {
    guard let value, !(value is NSNull) else { return nil }
    if let number = value as? NSNumber { return number.doubleValue }
    if let string = value as? String { return Double(string) }
    return nil
  }

  private static func integer(_ value: Any?) -> Int? {
    guard let value = number(value), value.isFinite, value.rounded(.towardZero) == value else { return nil }
    return Int(exactly: value)
  }
}
