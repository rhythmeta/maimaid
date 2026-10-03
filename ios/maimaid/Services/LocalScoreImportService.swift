import Foundation
import SwiftData

@MainActor
enum LocalScoreImportService {
  static func run(provider: ScoreImportProvider, profileID: UUID, context: ModelContext)
    async throws -> LocalScoreImportResult {
    let token = try await ScoreImportAPI.shared.accessToken(
      provider: provider, profileID: profileID)
    let address =
      provider == .lxns
      ? "https://maimai.lxns.net/api/v0/user/maimai/player/scores"
      : "https://www.diving-fish.com/api/maimaidxprober/player/records"
    let data = try await ScoreImportAPI.shared.request(address, token: token)
    var payload = try ScoreImportPayload.decode(data, provider: provider)
    if provider == .lxns {
      do {
        let playerData = try await ScoreImportAPI.shared.request(
          "https://maimai.lxns.net/api/v0/user/maimai/player", token: token)
        let root = try JSONSerialization.jsonObject(with: playerData) as? [String: Any]
        if root?["success"] as? Bool == true, let player = root?["data"] as? [String: Any] {
          payload.name = player["name"] as? String
          payload.rating = player["rating"] as? Int
          payload.plate = (player["trophy"] as? [String: Any])?["name"] as? String
        }
      } catch is CancellationError { throw CancellationError() } catch {
        try Task.checkCancellation()
      }
    }
    try Task.checkCancellation()
    return try apply(payload, provider: provider, profileID: profileID, context: context)
  }

  static func apply(
    _ payload: ScoreImportPayload, provider: ScoreImportProvider, profileID: UUID,
    context: ModelContext
  ) throws -> LocalScoreImportResult {
    let active = try context.fetch(
      FetchDescriptor<UserProfile>(predicate: #Predicate { $0.isActive }))
    guard let profile = active.first, profile.id == profileID else {
      throw ScoreImportError(code: "profile_changed")
    }
    let sheets = try context.fetch(FetchDescriptor<Sheet>())
    guard !sheets.isEmpty else { throw ScoreImportError(code: "catalog_empty") }
    let songs = try context.fetch(FetchDescriptor<Song>())
    let matcher = ScoreImportSheetMatcher(
      sheets: sheets,
      songs: Dictionary(
        songs.map { ($0.songIdentifier, $0) }, uniquingKeysWith: { first, _ in first }))
    ScoreService.shared.invalidateAllCaches()
    do {
      let result = try merge(
        payload, provider: provider, profileID: profileID, matcher: matcher, context: context)
      if let rating = payload.rating { profile.playerRating = rating }
      if let name = payload.name, !name.isEmpty { profile.name = name }
      if let plate = payload.plate { profile.plate = plate }
      if provider == .lxns {
        profile.lastImportDateLXNS = Date()
      } else {
        profile.lastImportDateDF = Date()
      }
      try context.save()
      ScoreService.shared.notifyScoresChanged(for: profileID)
      return result
    } catch {
      context.rollback()
      ScoreService.shared.invalidateAllCaches()
      throw error
    }
  }

  private static func merge(
    _ payload: ScoreImportPayload, provider: ScoreImportProvider, profileID: UUID,
    matcher: ScoreImportSheetMatcher, context: ModelContext
  ) throws -> LocalScoreImportResult {
    let histories = try context.fetch(
      FetchDescriptor<PlayRecord>(predicate: #Predicate { $0.userProfileId == profileID }))
    var ids = Set(histories.map(\.id))
    var fingerprints = Set(
      histories.map {
        historyKey(
          sheetID: $0.sheetId, date: $0.playDate,
          values: ScoreValues(rate: $0.rate, dx: $0.dxScore, fc: $0.fc, fs: $0.fs))
      })
    var updated = 0
    var matched = 0
    for item in payload.scores {
      guard let sheet = matcher.match(item),
        item.dxScore <= (sheet.total ?? 0) * 3 || (sheet.total ?? 0) == 0
      else { continue }
      matched += 1
      let previous = ScoreService.shared.score(for: sheet, context: context)
      let before = previous.map {
        ScoreValues(rate: $0.rate, dx: $0.dxScore, fc: $0.fc, fs: $0.fs)
      }
      let saved = ScoreService.shared.saveScore(
        sheet: sheet, rate: item.achievement, rank: "",
        dxScore: item.dxScore, fc: item.fc, fs: item.fs, achievementDate: item.playedAt ?? Date(),
        context: context)
      var changed =
        before != ScoreValues(rate: saved.rate, dx: saved.dxScore, fc: saved.fc, fs: saved.fs)
      // Best-score snapshots without a play timestamp must never create play history.
      if provider == .lxns, let date = item.playedAt,
        let id = item.recordID(profileID: profileID), !ids.contains(id) {
        let key = historyKey(
          sheetID: "\(sheet.songIdentifier)-\(sheet.type)-\(sheet.difficulty)",
          date: date,
          values: ScoreValues(rate: item.achievement, dx: item.dxScore, fc: item.fc, fs: item.fs))
        if fingerprints.insert(key).inserted {
          _ = ScoreService.shared.recordPlay(
            id: id, sheet: sheet, rate: item.achievement,
            rank: OtogameImportPolicy.calculatedRank(for: item.achievement),
            dxScore: item.dxScore,
            fc: item.fc, fs: item.fs, playDate: date, context: context)
          changed = true
        }
        ids.insert(id)
      }
      if changed { updated += 1 }
    }
    return LocalScoreImportResult(
      fetchedCount: payload.fetchedCount, updatedCount: updated,
      skippedCount: payload.fetchedCount - matched)
  }

  private static func historyKey(
    sheetID: String, date: Date, values: ScoreValues
  ) -> String {
    [
      sheetID.replacing("_", with: "-"),
      String(Int64((date.timeIntervalSince1970 * 1000).rounded())),
      String(Int64((values.rate * 10000).rounded())), String(values.dx),
      ThemeUtils.canonicalFC(values.fc) ?? "", values.fs ?? ""
    ]
    .joined(separator: "|")
  }

  private struct ScoreValues: Equatable {
    let rate: Double
    let dx: Int
    let fc: String?
    let fs: String?
  }
}
