import Foundation
import SwiftData
import SwiftProtobuf

extension CloudSnapshotStore {
  static func exportProfiles(_ profiles: [UserProfile]) -> [RHYProfile] {
    profiles.map { row in
      var profile = RHYProfile()
      profile.id = row.id.uuidString.lowercased()
      profile.name = row.name
      profile.server = row.server
      profile.avatar = row.avatarData ?? Data()
      profile.avatarURL = row.avatarUrl ?? ""
      profile.active = row.isActive
      profile.createdAt = timestamp(row.createdAt)
      profile.dfUsername = row.dfUsername
      profile.playerRating = Int32(row.playerRating)
      profile.plate = row.plate ?? ""
      profile.lastImportDf = timestamp(row.lastImportDateDF)
      profile.lastImportLxns = timestamp(row.lastImportDateLXNS)
      profile.b35Count = Int32(row.b35Count)
      profile.b15Count = Int32(row.b15Count)
      profile.b35RecLimit = Int32(row.b35RecLimit)
      profile.b15RecLimit = Int32(row.b15RecLimit)
      return profile
    }
  }
  static func exportCollections(context: ModelContext, result: inout RHYSnapshot) throws {
    result.collections = try context.fetch(FetchDescriptor<SongCollection>()).filter {
      $0.deletedAt == nil
    }.map {
      var value = RHYCollection()
      value.id = $0.id.uuidString.lowercased()
      value.name = $0.name
      value.sortIndex = Int32($0.sortIndex)
      value.createdAt = timestamp($0.createdAt)
      value.updatedAt = timestamp($0.updatedAt)
      return value
    }
    let collectionIDs = Set(result.collections.map(\.id))
    result.collectionItems = try context.fetch(FetchDescriptor<SongCollectionItem>()).filter {
      $0.deletedAt == nil && collectionIDs.contains($0.collectionId.uuidString.lowercased())
    }.map {
      var value = RHYCollectionItem()
      value.id = $0.id.uuidString.lowercased()
      value.collectionID = $0.collectionId.uuidString.lowercased()
      value.songID = $0.songId
      value.chartType = $0.chartType
      value.difficulty = $0.difficulty
      value.position = Int32($0.position)
      value.createdAt = timestamp($0.createdAt)
      value.updatedAt = timestamp($0.updatedAt)
      return value
    }
  }
  static func exportSettings(
    context: ModelContext, environment: Environment, result: inout RHYSnapshot
  ) throws {
    result.settings = try settingKeys.sorted().compactMap { key in
      guard let value = environment.defaults.object(forKey: key) else { return nil }
      var setting = RHYSetting()
      setting.key = "ios.maimaid.\(key)"
      setting.kind = "bytes"
      setting.bytesValue = try PropertyListSerialization.data(
        fromPropertyList: ["value": value], format: .binary, options: 0)
      return setting
    }
    if let config = try context.fetch(FetchDescriptor<SyncConfig>()).first {
      var theme = RHYSetting()
      theme.key = "ios.maimaid.theme"
      theme.kind = "int"
      theme.integerValue = Int64(config.themeRawValue)
      result.settings.append(theme)
      var autoUpload = RHYSetting()
      autoUpload.key = "ios.maimaid.autoUpload"
      autoUpload.kind = "bool"
      autoUpload.boolValue = config.isAutoUploadEnabled
      result.settings.append(autoUpload)
    }
    if FileManager.default.fileExists(atPath: environment.foreignURL.path) {
      result.settings += try RHYSnapshot(serializedBytes: Data(contentsOf: environment.foreignURL))
        .settings
    }
  }
  struct ScoreSource {
    let profile: UUID?
    let key: String
    let related: Sheet?
    let rate: Double
    let rank: String
    let dx: Int
    let fc: String?
    let fs: String?
    let date: Date
  }
  static func exportScore(_ source: ScoreSource, activeID: UUID, legacy: [String: Sheet]) throws -> RHYScore {
      guard
        let sheet = source.related
          ?? BackendSyncShared.resolveSheet(for: source.key, sheetMap: legacy)
      else {
        throw CloudBackupError.invalid(
          "A saved score has no matching chart. Update the song catalog before backing up.")
      }
      var value = RHYScore()
      value.profileID = (source.profile ?? activeID).uuidString.lowercased()
      value.chartKey = chartKey(sheet)
      value.achievement = source.rate
      value.rank = source.rank
      value.dxScore = Int32(source.dx)
      value.fc = source.fc ?? ""
      value.fs = source.fs ?? ""
      value.achievedAt = timestamp(source.date)
      return value
    }
}
