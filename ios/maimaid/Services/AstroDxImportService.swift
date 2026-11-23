import Foundation
import SwiftData

struct AstroDxImportResult: Sendable {
  let found: Int
  let matched: Int
  let updated: Int
  let skipped: Int
}

@MainActor
enum AstroDxImportService {
  static func run(_ data: Data, profileID: UUID, context: ModelContext) async throws -> AstroDxImportResult {
    let parsed = try await AstroDxCacheCodec.parse(data)
    return try apply(parsed, profileID: profileID, context: context)
  }

  static func apply(
    _ parsed: AstroDxParsedCache, profileID: UUID, context: ModelContext
  ) throws -> AstroDxImportResult {
    let activeProfiles = try context.fetch(FetchDescriptor<UserProfile>(predicate: #Predicate { $0.isActive }))
    guard activeProfiles.first?.id == profileID else { throw ScoreImportError(code: "profile_changed") }
    let sheets = try context.fetch(FetchDescriptor<Sheet>())
    guard !sheets.isEmpty else { throw ScoreImportError(code: "catalog_empty") }
    let songs = try context.fetch(FetchDescriptor<Song>())
    let matcher = AstroDxSheetMatcher(sheets: sheets, songs: songs)

    ScoreService.shared.invalidateAllCaches()
    do {
      var matched = 0
      var updated = 0
      for item in parsed.scores {
        guard let sheet = matcher.match(item) else { continue }
        matched += 1
        let previous = ScoreService.shared.score(for: sheet, context: context)
        let before = previous.map(ScoreValues.init)
        let saved = ScoreService.shared.saveScore(
          sheet: sheet, rate: item.achievement, rank: "", dxScore: item.dxScore,
          fc: item.fc, achievementDate: .now, context: context
        )
        if before != ScoreValues(saved) { updated += 1 }
      }
      try context.save()
      ScoreService.shared.notifyScoresChanged(for: profileID)
      let found = parsed.scores.count + parsed.invalidCount
      return AstroDxImportResult(
        found: found, matched: matched, updated: updated,
        skipped: found - matched
      )
    } catch {
      context.rollback()
      ScoreService.shared.invalidateAllCaches()
      throw error
    }
  }
}

private struct ScoreValues: Equatable {
  let rate: Double
  let dxScore: Int
  let fc: String?

  init(_ score: Score) {
    rate = score.rate
    dxScore = score.dxScore
    fc = score.fc
  }
}

private struct AstroDxSheetMatcher {
  private let songsByTitle: [String: [Song]]
  private let sheetsBySong: [String: [Sheet]]

  init(sheets: [Sheet], songs: [Song]) {
    songsByTitle = Dictionary(grouping: songs, by: { Self.normalize($0.title) })
    sheetsBySong = Dictionary(grouping: sheets, by: \.songIdentifier)
  }

  func match(_ score: AstroDxScore) -> Sheet? {
    guard let difficulty = Self.difficulties[score.difficultyID] else { return nil }
    let normalizedTitle = Self.normalize(score.title)
    let normalizedPath = Self.normalize(score.path)
    let searchText = "\(normalizedTitle) \(normalizedPath)"
    let hints = Set((Self.typeTag?.matches(in: searchText) ?? []).compactMap { match -> String? in
      guard let range = Range(match.range(at: 1), in: searchText) else { return nil }
      return String(searchText[range]).lowercased() == "dx" ? "dx" : "standard"
    })
    guard hints.count <= 1 else { return nil }
    let title = (Self.typeTag?.replacingOccurrences(in: normalizedTitle, with: "") ?? normalizedTitle)
      .trimmingCharacters(in: .whitespaces)
    let utageTag: String?
    if score.difficultyID == 7, let match = Self.utagePrefix?.firstMatch(in: title),
      let range = Range(match.range(at: 1), in: title) {
      utageTag = String(title[range])
    } else {
      utageTag = nil
    }
    let cleanedTitle = utageTag == nil ? title : (Self.utagePrefix?.replacingOccurrences(in: title, with: "") ?? title)
    let songs = songsByTitle[cleanedTitle].orEmpty
    let candidates = songs.flatMap { sheetsBySong[$0.songIdentifier].orEmpty }.filter { sheet in
      guard sheet.total == score.totalNotes else { return false }
      if score.difficultyID == 7 {
        return sheet.type.lowercased() == "utage"
          && (utageTag == nil || Self.normalizeUtage(sheet.difficulty) == utageTag)
      }
      let type = sheet.type.lowercased()
      return ["dx", "std", "standard"].contains(type)
        && sheet.difficulty.lowercased() == difficulty
        && (hints.isEmpty || hints.contains(type == "dx" ? "dx" : "standard"))
    }
    return candidates.count == 1 ? candidates.first : nil
  }

  private static let difficulties = [2: "basic", 3: "advanced", 4: "expert", 5: "master", 6: "remaster", 7: "utage"]
  private static let typeTag = try? NSRegularExpression(pattern: #"\[(dx|st|std)]"#, options: [.caseInsensitive])
  private static let utagePrefix = try? NSRegularExpression(pattern: #"^(?:\[|【)([宴光星協耐蛸習蔵回覚逆速遅舞狂跳撫滑割弾技音傾？疑嘘])(?:]|】)"#)

  private static func normalize(_ value: String) -> String {
    value.precomposedStringWithCompatibilityMapping.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
  }

  private static func normalizeUtage(_ value: String) -> String {
    normalize(value).filter(\.isLetter).precomposedStringWithCompatibilityMapping
  }
}

private extension NSRegularExpression {
  func matches(in value: String) -> [NSTextCheckingResult] {
    let range = NSRange(value.startIndex..., in: value)
    return matches(in: value, range: range)
  }

  func firstMatch(in value: String) -> NSTextCheckingResult? {
    firstMatch(in: value, range: NSRange(value.startIndex..., in: value))
  }

  func replacingOccurrences(in value: String, with replacement: String) -> String {
    stringByReplacingMatches(in: value, range: NSRange(value.startIndex..., in: value), withTemplate: replacement)
  }
}

private extension Optional where Wrapped == [Song] {
  var orEmpty: [Song] { self ?? [] }
}

private extension Optional where Wrapped == [Sheet] {
  var orEmpty: [Sheet] { self ?? [] }
}
