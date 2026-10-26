import Foundation
import Testing

@testable import maimaid

@MainActor
struct SgwcmaidRivalMusicTests {
  @Test func hoistsSongIDsAndMergesPagedRows() throws {
    let songs: [[String: Any]] = [
      [
        "musicId": 17,
        "userRivalMusicDetailList": [
          ["level": 2, "achievement": 977859, "deluxscoreMax": 717],
          ["level": 4, "achievement": 1002678, "deluxscoreMax": 1389],
        ],
      ],
      // A second page may repeat the same song; its charts must merge, not duplicate.
      [
        "musicId": 17,
        "userRivalMusicDetailList": [["level": 3, "achievement": 990000, "deluxscoreMax": 100]],
      ],
      [
        "musicId": 111365,
        "userRivalMusicDetailList": [["level": 10, "achievement": 2018433, "deluxscoreMax": 5093]],
      ],
      ["musicId": 18, "userRivalMusicDetailList": [[String: Any]]()],
      ["musicId": 19, "userRivalMusicDetailList": [["level": 3, "deluxscoreMax": 10]]],
    ]

    let rows = SgwcmaidRivalMusic.musicJSON(from: songs).map { $0 }

    #expect(rows.count == 2)
    #expect(rows.map { $0["musicId"] as? Int } == [17, 111365])
    let first = try #require(rows.first?["userMusicDetailList"] as? [[String: Any]])
    #expect(first.count == 3)
    #expect(first.compactMap { $0["musicId"] as? Int } == [17, 17, 17])
    #expect(first.compactMap { $0["level"] as? Int } == [2, 4, 3])
  }

  @Test func decodesQuickImportWithoutBadges() throws {
    let songs: [[String: Any]] = [
      [
        "musicId": 10123,
        "userRivalMusicDetailList": [["level": 3, "achievement": 1000739, "deluxscoreMax": 2267]],
      ]
    ]
    let data = try JSONSerialization.data(withJSONObject: SgwcmaidRivalMusic.musicJSON(from: songs))

    let payload = try ScoreImportPayload.decode(
      data, provider: .sgwcmaid, keepUnknownJudgements: true)

    #expect(payload.keepUnknownJudgements)
    #expect(payload.fetchedCount == 1)
    let score = try #require(payload.scores.first)
    #expect(score.songID == 10123)
    #expect(score.type == "dx")
    #expect(score.levelIndex == 3)
    #expect(abs(score.achievement - 100.0739) < 0.000001)
    #expect(score.dxScore == 2267)
    // The rival payload has no combo/sync status, so the badge stays unknown
    // instead of being replaced by a fabricated value.
    #expect(score.fc == nil)
    #expect(score.fs == nil)
  }

  @Test func cabinetImportsKeepReportingBadges() throws {
    let data = Data(
      #"""
      [{"userMusicDetailList":[{"musicId":10123,"level":3,"achievement":1000739,
        "comboStatus":4,"syncStatus":5,"deluxscoreMax":2267}]}]
      """#.utf8)
    let payload = try ScoreImportPayload.decode(data, provider: .sgwcmaid)
    #expect(!payload.keepUnknownJudgements)
    #expect(payload.scores.first?.fc == "app")
    #expect(payload.scores.first?.fs == "sync")
  }
}
