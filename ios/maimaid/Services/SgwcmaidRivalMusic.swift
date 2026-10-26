import Foundation

/// Converts a `GetUserRivalMusicApi` response into the cabinet `music.json` shape
/// that `ScoreImportPayload` already decodes.
///
/// The quick import reads the player's own best scores through the rival
/// endpoint, which can be queried without opening a game session. Its payload
/// groups charts per song and omits `musicId` on each chart row, so the
/// conversion hoists the song id into every row and merges repeated songs from
/// paged answers. The cabinet-only fields (play count, combo/sync status, score
/// rank) stay absent; the import policy treats them as unknown instead of
/// clearing what the profile already has.
nonisolated enum SgwcmaidRivalMusic {
  static func musicJSON(from songs: [[String: Any]]) -> [[String: Any]] {
    var rowsBySong: [Int: [[String: Any]]] = [:]
    var order: [Int] = []
    for song in songs {
      guard let musicID = SgwcmaidJSON.contentInt(song["musicId"]),
        let details = song["userRivalMusicDetailList"] as? [[String: Any]]
      else { continue }
      if rowsBySong[musicID] == nil { order.append(musicID) }
      rowsBySong[musicID, default: []].append(contentsOf: details)
    }
    return order.compactMap { musicID in
      let rows = (rowsBySong[musicID] ?? []).compactMap { detail -> [String: Any]? in
        guard let level = SgwcmaidJSON.contentInt(detail["level"]),
          let achievement = detail["achievement"] as? NSNumber
        else { return nil }
        var row: [String: Any] = ["musicId": musicID, "level": level, "achievement": achievement]
        if let dx = SgwcmaidJSON.contentInt(detail["deluxscoreMax"]) { row["deluxscoreMax"] = dx }
        return row
      }
      guard !rows.isEmpty else { return nil }
      return ["musicId": musicID, "userMusicDetailList": rows]
    }
  }
}
