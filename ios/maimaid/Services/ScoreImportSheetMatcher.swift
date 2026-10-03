import Foundation

struct ScoreImportSheetMatcher {
  private var byID: [String: [Sheet]] = [:]
  private var byTitle: [String: [Sheet]] = [:]

  init(sheets: [Sheet], songs: [String: Song]) {
    for sheet in sheets {
      let type = ScoreImportPayload.chartType(sheet.type)
      let suffix = "|\(type)|\(type == "utage" ? "utage" : sheet.difficulty.lowercased())"
      let id =
        sheet.songId > 0 ? sheet.songId : Int(sheet.songIdentifier) ?? sheet.song?.songId ?? 0
      if id > 0 { byID["\(id)" + suffix, default: []].append(sheet) }
      let title = Self.normalize(sheet.song?.title ?? songs[sheet.songIdentifier]?.title ?? "")
      if !title.isEmpty { byTitle[title + suffix, default: []].append(sheet) }
    }
  }

  func match(_ score: ImportedScore) -> Sheet? {
    let suffix = "|\(score.type)|\(score.type == "utage" ? "utage" : score.difficulty ?? "")"
    if score.songID > 0, let matches = byID["\(score.songID)" + suffix] {
      return matches.count == 1 ? matches.first : nil
    }
    let title = Self.normalize(score.title)
    guard !title.isEmpty, let matches = byTitle[title + suffix] else { return nil }
    return matches.count == 1 ? matches.first : nil
  }

  private static func normalize(_ value: String) -> String {
    value.precomposedStringWithCompatibilityMapping.trimmingCharacters(in: .whitespacesAndNewlines)
      .lowercased()
  }
}
