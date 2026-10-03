import Foundation
import SwiftData
import Testing

@testable import maimaid

@MainActor
struct LocalScoreImportTests {
  @Test func acceptsFlatAndLegacyTokens() throws {
    for json in [
      #"{"access_token":"a","refresh_token":"r"}"#,
      #"{"data":{"access_token":"a","refresh_token":"r"}}"#
    ] {
      let token = try JSONDecoder().decode(ScoreImportToken.self, from: Data(json.utf8))
      #expect(token.accessToken == "a")
      #expect(token.refreshToken == "r")
    }
  }

  @Test func preservesBestsProfilesAndDoesNotInventDivingFishHistory() throws {
    let container = try makeContainer()
    let context = container.mainContext
    let profile = UserProfile(name: "One", server: "cn", isActive: true)
    let other = UserProfile(name: "Two", server: "cn", isActive: false)
    context.insert(profile)
    context.insert(other)
    let sheet = Sheet(
      songIdentifier: "Test", type: "dx", difficulty: "master", level: "13", songId: 10123)
    context.insert(sheet)
    let old = Score(
      sheetId: "Test_dx_master", rate: 100.5, rank: "SSS+", dxScore: 100, fc: "fc",
      userProfileId: profile.id)
    let otherScore = Score(sheetId: "Test_dx_master", rate: 88, rank: "A", userProfileId: other.id)
    context.insert(old)
    context.insert(otherScore)
    sheet.scores = [old, otherScore]
    try context.save()
    let payload = try ScoreImportPayload.decode(
      Data(
        #"""
        {
          "records": [
            {
              "song_id": 10123,
              "title": "Test",
              "type": "DX",
              "level_index": 3,
              "achievements": 99,
              "dxScore": 110,
              "fc": "app"
            }
          ]
        }
        """#.utf8), provider: .divingFish)
    let result = try LocalScoreImportService.apply(
      payload, provider: .divingFish, profileID: profile.id, context: context)
    #expect(result.updatedCount == 1)
    #expect(old.rate == 100.5)
    #expect(old.dxScore == 110)
    #expect(old.fc == "app")
    #expect(otherScore.rate == 88)
    #expect(ScoreService.shared.playHistory(for: sheet, context: context).isEmpty)
    #expect(try context.fetchCount(FetchDescriptor<PlayRecord>()) == 0)
    let repeatResult = try LocalScoreImportService.apply(
      payload, provider: .divingFish, profileID: profile.id, context: context)
    #expect(repeatResult.updatedCount == 0)
    #expect(throws: ScoreImportError.self) {
      try LocalScoreImportService.apply(
        payload, provider: .divingFish, profileID: other.id, context: context)
    }
  }

  @Test func lxnsMapsDxIDsAndDeduplicatesTimestampedHistory() throws {
    let container = try makeContainer()
    let context = container.mainContext
    let profile = UserProfile(name: "One", server: "jp", isActive: true)
    context.insert(profile)
    context.insert(
      Sheet(songIdentifier: "Test", type: "dx", difficulty: "master", level: "13", songId: 10123))
    try context.save()
    let payload = try ScoreImportPayload.decode(
      Data(
        #"""
        {
          "success": true,
          "data": [
            {
              "id": 123,
              "song_name": "Test",
              "type": "dx",
              "level_index": 3,
              "achievements": 100.1,
              "dx_score": 99,
              "play_time": "2026-10-01T12:34:56.123Z"
            },
            {
              "id": 123,
              "type": "dx",
              "level_index": 9,
              "achievements": 90
            }
          ]
        }
        """#.utf8), provider: .lxns)
    #expect(payload.scores.count == 1)
    let item = try #require(payload.scores.first)
    #expect(item.songID == 10123)
    #expect(item.playedAt != nil)
    #expect(item.recordID(profileID: profile.id) != item.recordID(profileID: UUID()))
    for _ in 0..<2 {
      _ = try LocalScoreImportService.apply(
        payload, provider: .lxns, profileID: profile.id, context: context)
    }
    #expect(try context.fetchCount(FetchDescriptor<PlayRecord>()) == 1)
    #expect(try context.fetchCount(FetchDescriptor<Score>()) == 1)
  }

  @Test func missingTimestampsAndAmbiguousChartsAreSkippedSafely() throws {
    let payload = try ScoreImportPayload.decode(
      Data(
        #"""
        {
          "success": true,
          "data": [
            {
              "id": 100123,
              "type": "utage",
              "level_index": 5,
              "achievements": 90,
              "play_time": null
            }
          ]
        }
        """#.utf8), provider: .lxns)
    let item = try #require(payload.scores.first)
    #expect(item.songID == 100123)
    #expect(item.recordID(profileID: UUID()) == nil)
    let sheets = ["協", "耐"].map {
      Sheet(songIdentifier: "Test", type: "utage", difficulty: $0, level: "?", songId: 100123)
    }
    #expect(ScoreImportSheetMatcher(sheets: sheets, songs: [:]).match(item) == nil)
  }

  private func makeContainer() throws -> ModelContainer {
    ScoreService.shared.invalidateAllCaches()
    return try ModelContainer(
      for: Song.self, Sheet.self, Score.self, PlayRecord.self, UserProfile.self,
      configurations: ModelConfiguration(isStoredInMemoryOnly: true))
  }
}
