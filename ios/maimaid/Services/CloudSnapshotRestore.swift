import Foundation
import SwiftData
import SwiftProtobuf

extension CloudSnapshotStore {
  static func validatedSettings(_ snapshot: RHYSnapshot) throws -> [String: Any] {
    var settings: [String: Any] = [:]
    for item in snapshot.settings where item.key.hasPrefix("ios.maimaid.") {
      let key = String(item.key.dropFirst("ios.maimaid.".count))
      if key == "autoUpload" {
        guard item.kind == "bool" else {
          throw CloudBackupError.invalid("Invalid auto-upload setting.")
        }
        continue
      }
      if key == "theme" {
        guard item.kind == "int", (0...2).contains(item.integerValue) else {
          throw CloudBackupError.invalid("Invalid theme setting.")
        }
        continue
      }
      guard settingKeys.contains(key), item.kind == "bytes",
        let value = try PropertyListSerialization.propertyList(from: item.bytesValue, format: nil)
          as? [String: Any], let value = value["value"]
      else { throw CloudBackupError.invalid("Invalid personal setting.") }
      settings[key] = value
    }
    return settings
  }
  static func replaceProfiles(
    _ snapshot: RHYSnapshot, context: ModelContext, sheets: [String: Sheet]
  ) throws {
    for profile in snapshot.profiles {
      guard let id = UUID(uuidString: profile.id) else {
        throw CloudBackupError.invalid("Invalid profile ID.")
      }
      context.insert(
        UserProfile(
          id: id, name: profile.name, server: profile.server,
          avatarData: profile.avatar.isEmpty ? nil : profile.avatar,
          avatarUrl: profile.avatarURL.isEmpty ? nil : profile.avatarURL, isActive: profile.active,
          createdAt: date(profile.createdAt), dfUsername: profile.dfUsername,
          playerRating: Int(profile.playerRating),
          plate: profile.plate.isEmpty ? nil : profile.plate,
          lastImportDateDF: profile.lastImportDf == 0 ? nil : date(profile.lastImportDf),
          lastImportDateLXNS: profile.lastImportLxns == 0 ? nil : date(profile.lastImportLxns),
          b35Count: Int(profile.b35Count), b15Count: Int(profile.b15Count),
          b35RecLimit: Int(profile.b35RecLimit),
          b15RecLimit: Int(profile.b15RecLimit)))
    }
  }
  static func replaceScores(_ snapshot: RHYSnapshot, context: ModelContext, sheets: [String: Sheet])
    throws {
    for result in snapshot.scores {
      guard let sheet = sheets[result.chartKey], let profile = UUID(uuidString: result.profileID)
      else {
        throw CloudBackupError.invalid("Invalid score reference.")
      }
      let score = Score(
        sheetId: BackendSyncShared.canonicalScoreSheetId(for: sheet), rate: result.achievement,
        rank: result.rank, dxScore: Int(result.dxScore), fc: result.fc.isEmpty ? nil : result.fc,
        fs: result.fs.isEmpty ? nil : result.fs, achievementDate: date(result.achievedAt),
        userProfileId: profile)
      score.sheet = sheet
      context.insert(score)
    }
  }
  static func replaceRecords(
    _ snapshot: RHYSnapshot, context: ModelContext, sheets: [String: Sheet]
  ) throws {
    for row in snapshot.playRecords {
      let result = row.result
      guard let sheet = sheets[result.chartKey], let id = UUID(uuidString: row.id),
        let profile = UUID(uuidString: result.profileID)
      else { throw CloudBackupError.invalid("Invalid play record reference.") }
      context.insert(
        PlayRecord(
          id: id, sheetId: BackendSyncShared.canonicalRecordSheetId(for: sheet),
          rate: result.achievement, rank: result.rank, dxScore: Int(result.dxScore),
          fc: result.fc.isEmpty ? nil : result.fc,
          fs: result.fs.isEmpty ? nil : result.fs, playDate: date(result.achievedAt),
          userProfileId: profile))
    }
  }
  static func replaceCollections(
    _ snapshot: RHYSnapshot, context: ModelContext, sheets: [String: Sheet]
  ) throws {
    for row in snapshot.collections {
      guard let id = UUID(uuidString: row.id) else {
        throw CloudBackupError.invalid("Invalid collection ID.")
      }
      context.insert(
        SongCollection(
          id: id, name: row.name, sortIndex: Int(row.sortIndex), createdAt: date(row.createdAt),
          updatedAt: date(row.updatedAt)))
    }
  }
  static func replaceCollectionItems(
    _ snapshot: RHYSnapshot, context: ModelContext, sheets: [String: Sheet]
  ) throws {
    for row in snapshot.collectionItems {
      guard let id = UUID(uuidString: row.id), let collection = UUID(uuidString: row.collectionID)
      else { throw CloudBackupError.invalid("Invalid collection item ID.") }
      context.insert(
        SongCollectionItem(
          id: id, collectionId: collection, songId: row.songID, chartType: row.chartType,
          difficulty: row.difficulty, position: Int(row.position), createdAt: date(row.createdAt),
          updatedAt: date(row.updatedAt)))
    }
  }
}
