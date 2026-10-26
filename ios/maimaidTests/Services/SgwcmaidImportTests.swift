import Foundation
import SwiftData
import Testing

@testable import maimaid

@MainActor
struct SgwcmaidImportTests {
  @Test func decodesMusicJSON() throws {
    let data = Data(
      #"""
      [{"userMusicDetailList":[
        {"musicId":10123,"level":3,"playCount":2,"achievement":1000739,
         "comboStatus":4,"syncStatus":5,"deluxscoreMax":2267,"scoreRank":12,
         "extNum1":0,"extNum2":0}
      ],"length":1}]
      """#.utf8)
    let payload = try ScoreImportPayload.decode(data, provider: .sgwcmaid)
    #expect(payload.fetchedCount == 1)
    let score = try #require(payload.scores.first)
    #expect(payload.scores.count == 1)
    #expect(score.songID == 10123)
    #expect(score.type == "dx")
    #expect(score.levelIndex == 3)
    #expect(abs(score.achievement - 100.0739) < 0.000001)
    #expect(score.dxScore == 2267)
    #expect(score.fc == "app")
    #expect(score.fs == "sync")
    #expect(score.playedAt == nil)
    #expect(score.difficulty == "master")
  }

  @Test func mapsMusicIDsToStandardAndDXCharts() throws {
    let data = Data(
      #"""
      [
        {"userMusicDetailList":[{"musicId":123,"level":3,"achievement":990000,"deluxscoreMax":100}]},
        {"userMusicDetailList":[{"musicId":10123,"level":3,"achievement":990000,"deluxscoreMax":100}]}
      ]
      """#.utf8)
    let payload = try ScoreImportPayload.decode(data, provider: .sgwcmaid)
    #expect(payload.scores.map(\.type) == ["standard", "dx"])
  }

  @Test func countsInvalidDetailsAsFetchedButSkipsThem() throws {
    let data = Data(
      #"""
      [
        {"userMusicDetailList":[
          {"musicId":10123,"level":3,"achievement":990000,"deluxscoreMax":100},
          {"musicId":10124,"level":5,"achievement":990000,"deluxscoreMax":100},
          {"musicId":10125,"level":3,"achievement":1010001,"deluxscoreMax":100},
          {"musicId":10126,"level":3,"achievement":990000,"deluxscoreMax":-1},
          {"musicId":"10127","level":3,"achievement":990000,"deluxscoreMax":100}
        ]},
        {"length":0}
      ]
      """#.utf8)
    let payload = try ScoreImportPayload.decode(data, provider: .sgwcmaid)
    #expect(payload.fetchedCount == 5)
    #expect(payload.scores.map(\.songID) == [10123])
  }

  @Test func rejectsPayloadsThatAreNotMusicArrays() throws {
    #expect(throws: ScoreImportError.self) {
      try ScoreImportPayload.decode(Data(#"{"records":[]}"#.utf8), provider: .sgwcmaid)
    }
  }

  @Test func replacesScoresVerbatimAndIsIdempotent() throws {
    let container = try makeContainer()
    let context = container.mainContext
    let profile = UserProfile(name: "CN", server: "cn", isActive: true)
    let other = UserProfile(name: "JP", server: "jp", isActive: false)
    context.insert(profile)
    context.insert(other)
    let sheet = Sheet(
      songIdentifier: "Test", type: "dx", difficulty: "master", level: "13", total: 100,
      songId: 10123)
    context.insert(sheet)
    let existing = Score(
      sheetId: "Test_dx_master", rate: 100.5, rank: "SSS+", dxScore: 300, fc: "app", fs: "fsdp",
      userProfileId: profile.id)
    let otherScore = Score(
      sheetId: "Test_dx_master", rate: 80, rank: "A", userProfileId: other.id)
    context.insert(existing)
    context.insert(otherScore)
    sheet.scores = [existing, otherScore]
    try context.save()

    let payload = try ScoreImportPayload.decode(
      Data(
        #"""
        [{"userMusicDetailList":[
          {"musicId":10123,"level":3,"achievement":990000,
           "comboStatus":1,"syncStatus":1,"deluxscoreMax":200}
        ]}]
        """#.utf8), provider: .sgwcmaid)
    let result = try LocalScoreImportService.apply(
      payload, provider: .sgwcmaid, profileID: profile.id, context: context)

    #expect(result.fetchedCount == 1)
    #expect(result.updatedCount == 1)
    #expect(result.skippedCount == 0)
    #expect(existing.rate == 99)
    #expect(existing.rank == "SS")
    #expect(existing.dxScore == 200)
    #expect(existing.fc == "fc")
    #expect(existing.fs == "sync")
    #expect(otherScore.rate == 80)
    #expect(otherScore.fc == nil)
    #expect(try context.fetchCount(FetchDescriptor<PlayRecord>()) == 0)
    #expect(profile.lastImportDateDF == nil)
    #expect(profile.lastImportDateLXNS == nil)

    let repeatResult = try LocalScoreImportService.apply(
      payload, provider: .sgwcmaid, profileID: profile.id, context: context)
    #expect(repeatResult.updatedCount == 0)
    #expect(try context.fetchCount(FetchDescriptor<Score>()) == 2)
  }

  @Test func skipsDXScoresAboveTheChartMaximum() throws {
    let container = try makeContainer()
    let context = container.mainContext
    let profile = UserProfile(name: "CN", server: "cn", isActive: true)
    context.insert(profile)
    let sheet = Sheet(
      songIdentifier: "Test", type: "dx", difficulty: "master", level: "13", total: 50,
      songId: 10123)
    context.insert(sheet)
    try context.save()

    let payload = try ScoreImportPayload.decode(
      Data(
        #"""
        [{"userMusicDetailList":[{"musicId":10123,"level":3,"achievement":990000,"deluxscoreMax":151}]}]
        """#.utf8), provider: .sgwcmaid)
    let result = try LocalScoreImportService.apply(
      payload, provider: .sgwcmaid, profileID: profile.id, context: context)
    #expect(result.updatedCount == 0)
    #expect(result.skippedCount == 1)
    #expect(try context.fetchCount(FetchDescriptor<Score>()) == 0)
  }

  @Test func protectsAndRestoresRequestBody() throws {
    let key = "0123456789abcdef0123456789abcdef"
    let iv = "1234567890abcdef"
    let plaintext = Data(#"{"userId":123,"nextIndex":0}"#.utf8)
    let ciphertext = try SgwcmaidCrypto.protect(plaintext, key: key, iv: iv)
    #expect(ciphertext.count % 16 == 0)
    #expect(try SgwcmaidCrypto.unprotect(ciphertext, key: key, iv: iv) == plaintext)
    #expect(try SgwcmaidCrypto.protect(plaintext, key: key, iv: iv) == ciphertext)
  }

  @Test func matchesAndroidDigestFormats() {
    #expect(
      SgwcmaidCrypto.md5Hex("GetUserMusicApiMaimaiChnsalt")
        == "3a67481cf9f9b6201d083693963bb78c")
    #expect(
      SgwcmaidCrypto.sha256HexUpper("abc")
        == "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD")
  }

  @Test func parsesContentLengthAndChunkedResponses() throws {
    let contentLength = Data(
      "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}".utf8)
    let parsedHead = try SgwcmaidHTTPResponseParser.parseHead(contentLength)
    let head = try #require(parsedHead)
    #expect(head.statusCode == 200)
    #expect(SgwcmaidHTTPResponseParser.framing(for: head) == .contentLength(2))
    #expect(head.values(forHeader: "content-type") == ["application/json"])

    let chunked = Data(
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\n"
        .utf8)
    let parsedChunkedHead = try SgwcmaidHTTPResponseParser.parseHead(chunked)
    let chunkedHead = try #require(parsedChunkedHead)
    #expect(SgwcmaidHTTPResponseParser.framing(for: chunkedHead) == .chunked)
    #expect(chunkedHead.values(forHeader: "set-cookie") == ["a=1", "b=2"])

    var body = Data("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n".utf8)
    #expect(try SgwcmaidHTTPResponseParser.chunkedBody(body, from: 0) == Data("Wikipedia".utf8))
    body.removeLast()
    #expect(try SgwcmaidHTTPResponseParser.chunkedBody(body, from: 0) == nil)
  }

  @Test func recognizesCabinetCodes() {
    let header = "SGWCMAID" + String(repeating: "A", count: 12)
    let code = header + "PAYLOAD"
    #expect(SgwcmaidCode.isRecognized(code))
    #expect(SgwcmaidCode.payload(of: code) == "PAYLOAD")
    #expect(!SgwcmaidCode.isRecognized("SGWCMAIDSHORT"))
    #expect(SgwcmaidCode.payload(of: "https://example.com") == nil)
  }

  @Test func requiresCompleteDeploymentConfiguration() {
    var config = SgwcmaidConfig(
      chimeURL: "https://chime.example", gameServer: "https://game.example",
      keychip: "A63E-01E13710000", aesKey: String(repeating: "k", count: 32),
      aesIV: String(repeating: "i", count: 16), apiSalt: "salt", chimeSecret: "secret",
      titleKey: "SDGB", placeID: 1971, regionID: 21)
    #expect(config.isComplete)
    // The catalog supplies the cabinet location, so the build-time place and
    // region are only needed by the legacy fallback.
    config.placeID = 0
    config.regionID = 0
    #expect(config.isComplete)
    config.aesIV = "short"
    #expect(!config.isComplete)
  }

  private func makeContainer() throws -> ModelContainer {
    ScoreService.shared.invalidateAllCaches()
    return try ModelContainer(
      for: Song.self, Sheet.self, Score.self, PlayRecord.self, UserProfile.self,
      configurations: ModelConfiguration(isStoredInMemoryOnly: true))
  }
}
