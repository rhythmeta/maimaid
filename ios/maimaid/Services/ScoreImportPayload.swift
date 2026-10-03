import Foundation

struct ScoreImportPayload {
  let scores: [ImportedScore]
  let fetchedCount: Int
  var rating: Int?
  var name: String?
  var plate: String?

  static func decode(_ data: Data, provider: ScoreImportProvider) throws -> Self {
    guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw ScoreImportError(code: "invalid_response")
    }
    if provider == .lxns, root["success"] as? Bool != true {
      throw ScoreImportError(code: "invalid_response")
    }
    guard let rows = root[provider == .lxns ? "data" : "records"] as? [[String: Any]] else {
      throw ScoreImportError(code: "invalid_response")
    }
    let scores = rows.compactMap { row -> ImportedScore? in
      guard let achievement = (row["achievements"] as? NSNumber)?.doubleValue,
        achievement.isFinite, (0...101).contains(achievement),
        let rawType = row["type"] as? String,
        let level = row["level_index"] as? Int
      else { return nil }
      let type = chartType(rawType)
      guard ["standard", "dx", "utage"].contains(type), (0...4).contains(level) || type == "utage"
      else { return nil }
      let rawID = (row[provider == .lxns ? "id" : "song_id"] as? Int) ?? 0
      let songID =
        provider == .lxns && type == "dx" && rawID > 0 && rawID < 10000 ? rawID + 10000 : rawID
      let dx = row["dx_score"] as? Int ?? row["dxScore"] as? Int ?? 0
      guard dx >= 0 else { return nil }
      var playedAt: Date?
      if provider == .lxns, let rawDate = row["play_time"] as? String {
        playedAt =
          (try? Date(rawDate, strategy: .iso8601))
          ?? (try? Date(
            rawDate,
            strategy: .iso8601.year().month().day().time(includingFractionalSeconds: true).timeZone(
              separator: .colon)))
        if let date = playedAt, date.timeIntervalSince1970 <= 0 { playedAt = nil }
      }
      return ImportedScore(
        songID: songID,
        title: row[provider == .lxns ? "song_name" : "title"] as? String ?? "",
        type: type, levelIndex: level, achievement: achievement, dxScore: dx,
        fc: ThemeUtils.canonicalFC(row["fc"] as? String), fs: canonicalFS(row["fs"] as? String),
        playedAt: playedAt)
    }
    return Self(
      scores: scores, fetchedCount: rows.count, rating: root["rating"] as? Int,
      name: root["nickname"] as? String, plate: root["plate"] as? String)
  }

  static func chartType(_ type: String) -> String {
    switch type.lowercased() {
    case "sd", "std": "standard"
    default: type.lowercased()
    }
  }
  private static func canonicalFS(_ value: String?) -> String? {
    switch value?.lowercased() {
    case nil, "": nil
    case "s": "sync"
    case "fs+": "fsp"
    case "fdx": "fsd"
    case "fdx+": "fsdp"
    default: value?.lowercased()
    }
  }
}
