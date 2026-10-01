import Foundation
import SwiftData
import SwiftProtobuf

@MainActor
enum CloudSnapshotStore {
  static let settingKeys: Set<String> = [
    "useFitDiff", "best50.constantMode", "showScannerBoundingBox", "scoreQuery.displayMode",
    "scoreQuery.gridColumns",
    "scoreQuery.sortMode", "scoreQuery.sortAscending", "songs.sortOption", "songs.sortAscending",
    "songs.gridColumns",
    "collections.sortOption", "collections.sortAscending", "filter.hideDeletedSongs",
    "filter.showOnlyPlayableSongs"
  ]
  struct Environment {
    let directory: URL
    let defaults: UserDefaults
    var notifyChanges = true
    static var live: Environment {
      Environment(
        directory: URL.applicationSupportDirectory.appending(
          path: "RhythmetaBackups", directoryHint: .isDirectory), defaults: .standard)
    }
    var rollbackURL: URL { directory.appending(path: "before-restore.pb.gz") }
    var journalURL: URL { directory.appending(path: "restore-pending") }
    var foreignURL: URL { directory.appending(path: "foreign-settings.pb") }
    func prepareDirectory() throws {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      var url = directory
      var values = URLResourceValues()
      values.isExcludedFromBackup = true
      try url.setResourceValues(values)
    }
  }
  static func timestamp(_ date: Date?) -> Int64 {
    date.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) } ?? 0
  }
  static func date(_ value: Int64) -> Date {
    Date(timeIntervalSince1970: Double(value) / 1000)
  }
  static func chartKey(_ sheet: Sheet) -> String {
    "\(sheet.songIdentifier.utf16.count):\(sheet.songIdentifier)"
      + "|\(sheet.type.lowercased())|\(sheet.difficulty.lowercased())"
  }
  static func export(context: ModelContext, environment: Environment = .live) throws -> RHYSnapshot {
    try context.save()
    let profiles = try context.fetch(FetchDescriptor<UserProfile>())
    guard let active = profiles.first(where: \.isActive) else {
      throw CloudBackupError.invalid("Create an active profile before backing up.")
    }
    let sheets = try context.fetch(FetchDescriptor<Sheet>())
    let legacy = BackendSyncShared.buildSheetMap(for: sheets, separators: ["_", "-"])
    var result = RHYSnapshot()
    result.magic = "RHYTHMETA_BACKUP"
    result.formatVersion = 1
    result.game = "maimaid"
    result.createdAt = timestamp(.now)
    result.clientVersion = AppInfo.shortVersion ?? "1"
    result.profiles = exportProfiles(profiles)
    result.scores = try context.fetch(FetchDescriptor<Score>()).map {
      try exportScore(
        ScoreSource(
          profile: $0.userProfileId, key: $0.sheetId, related: $0.sheet, rate: $0.rate,
          rank: $0.rank,
          dx: $0.dxScore, fc: $0.fc, fs: $0.fs, date: $0.achievementDate), activeID: active.id, legacy: legacy)
    }
    result.playRecords = try context.fetch(FetchDescriptor<PlayRecord>()).map { row in
      var value = RHYPlayRecord()
      value.id = row.id.uuidString.lowercased()
      value.result = try exportScore(
        ScoreSource(
          profile: row.userProfileId, key: row.sheetId, related: nil, rate: row.rate,
          rank: row.rank,
          dx: row.dxScore, fc: row.fc, fs: row.fs, date: row.playDate), activeID: active.id, legacy: legacy)
      return value
    }
    try exportCollections(context: context, result: &result)
    result.favoriteSongIds = try context.fetch(FetchDescriptor<Song>()).filter(\.isFavorite).map(
      \.songIdentifier)
    try exportSettings(context: context, environment: environment, result: &result)
    try CloudBackupCodec.validate(result)
    return result
  }
  static func validateCatalog(_ snapshot: RHYSnapshot, context: ModelContext) throws {
    let keys = Set(try context.fetch(FetchDescriptor<Sheet>()).map(chartKey))
    let songs = Set(try context.fetch(FetchDescriptor<Song>()).map(\.songIdentifier))
    guard snapshot.favoriteSongIds.allSatisfy(songs.contains) else {
      throw CloudBackupError.invalid(
        "Some favorite songs are missing. Update the catalog before restoring.")
    }
    guard snapshot.scores.allSatisfy({ keys.contains($0.chartKey) }),
      snapshot.playRecords.allSatisfy({ keys.contains($0.result.chartKey) })
    else {
      throw CloudBackupError.invalid(
        "Some charts are missing. Update the catalog before restoring.")
    }
  }
  static func recover(context: ModelContext, environment: Environment = .live) throws {
    guard FileManager.default.fileExists(atPath: environment.journalURL.path) else { return }
    try replace(
      CloudBackupCodec.decode(Data(contentsOf: environment.rollbackURL)), context: context,
      environment: environment)
    try FileManager.default.removeItem(at: environment.journalURL)
  }
  static func restore(
    _ snapshot: RHYSnapshot, context: ModelContext, environment: Environment = .live
  ) throws {
    try recover(context: context, environment: environment)
    try CloudBackupCodec.validate(snapshot)
    try validateCatalog(snapshot, context: context)
    let previous = try export(context: context, environment: environment)
    try environment.prepareDirectory()
    try CloudBackupCodec.encode(previous).write(
      to: environment.rollbackURL, options: [.atomic, .completeFileProtection])
    try Data([1]).write(to: environment.journalURL, options: [.atomic, .completeFileProtection])
    do {
      try replace(snapshot, context: context, environment: environment)
      try FileManager.default.removeItem(at: environment.journalURL)
    } catch {
      context.rollback()
      try replace(previous, context: context, environment: environment)
      try FileManager.default.removeItem(at: environment.journalURL)
      throw error
    }
  }
  private static func replace(
    _ snapshot: RHYSnapshot, context: ModelContext, environment: Environment
  ) throws {
    try CloudBackupCodec.validate(snapshot)
    try validateCatalog(snapshot, context: context)
    let allSheets = try context.fetch(FetchDescriptor<Sheet>())
    var sheets: [String: Sheet] = [:]
    for sheet in allSheets { sheets[chartKey(sheet)] = sheet }
    let settings = try validatedSettings(snapshot)
    // Individual model deletes and inserts are committed in one save transaction.
    try context.fetch(FetchDescriptor<Score>()).forEach(context.delete)
    try context.fetch(FetchDescriptor<PlayRecord>()).forEach(context.delete)
    try context.fetch(FetchDescriptor<SongCollectionItem>()).forEach(context.delete)
    try context.fetch(FetchDescriptor<SongCollection>()).forEach(context.delete)
    try context.fetch(FetchDescriptor<UserProfile>()).forEach(context.delete)
    try replaceProfiles(snapshot, context: context, sheets: sheets)
    try replaceScores(snapshot, context: context, sheets: sheets)
    try replaceRecords(snapshot, context: context, sheets: sheets)
    try replaceCollections(snapshot, context: context, sheets: sheets)
    try replaceCollectionItems(snapshot, context: context, sheets: sheets)
    let favorites = Set(snapshot.favoriteSongIds)
    for song in try context.fetch(FetchDescriptor<Song>()) {
      song.isFavorite = favorites.contains(song.songIdentifier)
    }
    for config in try context.fetch(FetchDescriptor<SyncConfig>()) {
      config.themeRawValue = Int(
        snapshot.settings.first { $0.key == "ios.maimaid.theme" }?.integerValue ?? 0)
      config.isAutoUploadEnabled =
        snapshot.settings.first { $0.key == "ios.maimaid.autoUpload" }?.boolValue ?? false
      config.lastSyncRevision = "0"
      config.clearRemoteProfileVersions()
      config.resetDataSyncState()
    }
    try context.save()
    for key in settingKeys {
      if let value = settings[key] {
        environment.defaults.set(value, forKey: key)
      } else {
        environment.defaults.removeObject(forKey: key)
      }
    }
    var foreign = RHYSnapshot()
    foreign.settings = snapshot.settings.filter { !$0.key.hasPrefix("ios.maimaid.") }
    try environment.prepareDirectory()
    try foreign.serializedData().write(
      to: environment.foreignURL, options: [.atomic, .completeFileProtection])
    if environment.notifyChanges {
      ScoreService.shared.invalidateAllCaches()
      ScoreService.shared.notifyActiveProfileChanged()
    }
  }
}
