import Foundation
import CommonCrypto
import SwiftData
import Testing
import zlib

@testable import maimaid

@MainActor
struct AstroDxImportTests {
  @Test func parsesScoresByDifficultyIDAndClearType() async throws {
    let data = Data(cacheJSON.utf8)
    let parsed = try await AstroDxCacheCodec.parse(data)
    #expect(parsed.invalidCount == 0)
    #expect(parsed.scores.count == 2)
    #expect(parsed.scores.map(\.difficultyID) == [5, 6])
    #expect(parsed.scores.map(\.fc) == ["fcp", "app"])
  }

  @Test func decodesEncryptedV3AndRawDeflateV4Caches() async throws {
    let json = Data(cacheJSON.utf8)
    let encrypted = try encryptAES256ECB(json)
    let encryptedCache = try await AstroDxCacheCodec.parse(Data(encrypted.base64EncodedString().utf8))
    #expect(encryptedCache.scores.count == 2)

    let v4JSON = cacheJSON.replacingOccurrences(of: "\"version\": 3", with: "\"version\": 4")
    let deflated = try rawDeflate(Data(v4JSON.utf8))
    let deflatedCache = try await AstroDxCacheCodec.parse(deflated)
    #expect(deflatedCache.scores.count == 2)
  }

  @Test func importsUniqueMatchingChartsAndPreservesBetterScores() async throws {
    let container = try makeContainer()
    let context = container.mainContext
    let profile = UserProfile(name: "Player", server: "jp", isActive: true)
    let otherProfile = UserProfile(name: "Other", server: "jp")
    context.insert(profile)
    context.insert(otherProfile)
    let song = Song(
      songIdentifier: "astro-song", category: "maimai", title: "Astro Song", artist: "Artist",
      imageName: "", sortOrder: 1, isNew: false, isLocked: false)
    context.insert(song)
    let higherDifficultySheet = Sheet(
      songIdentifier: song.songIdentifier, type: "dx", difficulty: "master", level: "13",
      total: 100, songId: 10001)
    let extraDifficultySheet = Sheet(
      songIdentifier: song.songIdentifier, type: "dx", difficulty: "remaster", level: "13+",
      total: 110, songId: 10001)
    context.insert(higherDifficultySheet)
    context.insert(extraDifficultySheet)
    song.sheets = [higherDifficultySheet, extraDifficultySheet]
    let old = Score(
      sheetId: "astro-song_dx_master", rate: 100.5, rank: "SSS+", dxScore: 250,
      userProfileId: profile.id)
    let otherScore = Score(
      sheetId: "astro-song_dx_master", rate: 90, rank: "AA", userProfileId: otherProfile.id)
    context.insert(old)
    context.insert(otherScore)
    higherDifficultySheet.scores = [old, otherScore]
    try context.save()

    let result = try await AstroDxImportService.run(Data(cacheJSON.utf8), profileID: profile.id, context: context)
    #expect(result.found == 2)
    #expect(result.matched == 2)
    #expect(result.updated == 2)
    #expect(result.skipped == 0)
    #expect(old.rate == 100.5)
    #expect(old.dxScore == 250)
    #expect(old.fc == "fcp")
    #expect(otherScore.rate == 90)
    #expect(try context.fetchCount(FetchDescriptor<PlayRecord>()) == 0)
    #expect(try context.fetchCount(FetchDescriptor<Score>()) == 3)
    let repeated = try await AstroDxImportService.run(Data(cacheJSON.utf8), profileID: profile.id, context: context)
    #expect(repeated.updated == 0)
  }

  @Test func rejectsUnsupportedVersionsAndOversizedData() async {
    let unsupported = Data(#"{"version":2,"level_metadata":{}}"#.utf8)
    await #expect(throws: AstroDxCacheError.unsupportedVersion) {
      try await AstroDxCacheCodec.parse(unsupported)
    }
    let oversized = Data(repeating: 0, count: AstroDxCacheCodec.maximumInputBytes + 1)
    await #expect(throws: AstroDxCacheError.tooLarge) {
      try await AstroDxCacheCodec.parse(oversized)
    }
  }

  private var cacheJSON: String {
    #"""
    {
      "version": 3,
      "level_metadata": {
        "Astro Song": {
          "title": "Astro Song",
          "path": "Astro Song [dx]",
          "difficulties": [
            {"id": 5, "stats": {"clearType": 4, "achievementRate": 99.5, "dxScore": 250, "totalNotesCount": 100}},
            {"id": 6, "stats": {"clearType": 6, "achievementRate": 98.0, "dxScore": 300, "totalNotesCount": 110}}
          ]
        }
      }
    }
    """#
  }

  private func makeContainer() throws -> ModelContainer {
    ScoreService.shared.invalidateAllCaches()
    return try ModelContainer(
      for: Song.self, Sheet.self, Score.self, PlayRecord.self, UserProfile.self,
      configurations: ModelConfiguration(isStoredInMemoryOnly: true))
  }

  private func encryptAES256ECB(_ input: Data) throws -> Data {
    let key = Array("94625184621352165487465213218452".utf8)
    var output = [UInt8](repeating: 0, count: input.count + kCCBlockSizeAES128)
    var outputCount = 0
    let status = input.withUnsafeBytes { source in
      CCCrypt(
        CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES),
        CCOptions(kCCOptionPKCS7Padding | kCCOptionECBMode), key, key.count, nil,
        source.baseAddress, input.count, &output, output.count, &outputCount)
    }
    guard status == kCCSuccess else { throw AstroDxCacheError.invalid }
    return Data(output.prefix(outputCount))
  }

  private func rawDeflate(_ input: Data) throws -> Data {
    var stream = z_stream()
    guard deflateInit2_(
      &stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY,
      ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
      throw AstroDxCacheError.invalid
    }
    defer { deflateEnd(&stream) }
    return try input.withUnsafeBytes { source in
      stream.next_in = UnsafeMutablePointer(mutating: source.bindMemory(to: Bytef.self).baseAddress)
      stream.avail_in = uInt(source.count)
      var output = Data()
      while true {
        var buffer = [UInt8](repeating: 0, count: 4096)
        let status = buffer.withUnsafeMutableBytes { target in
          stream.next_out = target.bindMemory(to: Bytef.self).baseAddress
          stream.avail_out = uInt(target.count)
          return deflate(&stream, Z_FINISH)
        }
        output.append(contentsOf: buffer.prefix(buffer.count - Int(stream.avail_out)))
        if status == Z_STREAM_END { return output }
        guard status == Z_OK else { throw AstroDxCacheError.invalid }
      }
    }
  }
}
