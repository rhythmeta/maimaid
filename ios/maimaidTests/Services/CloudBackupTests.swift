import Foundation
import SwiftData
import Testing

@testable import maimaid

struct CloudBackupCodecTests {
  @Test func decodesAndroidWireFixtureAndRoundTrips() throws {
    let value = try CloudBackupCodec.decode(BackupFixture.compressed)
    #expect(value.profiles.first?.name == "Test 玩家")
    #expect(value.scores.first?.chartKey == "4:Test|dx|master")
    #expect(value.scores.first?.achievement == 100.1234)
    #expect(try CloudBackupCodec.decode(CloudBackupCodec.encode(value)) == value)
  }
  @Test func rejectsTruncationWrongGameAndDanglingProfile() throws {
    #expect(throws: (any Error).self) {
      try CloudBackupCodec.decode(BackupFixture.compressed.dropLast(5))
    }
    var value = try CloudBackupCodec.decode(BackupFixture.compressed)
    value.game = "chunithmd"
    #expect(throws: (any Error).self) { try CloudBackupCodec.validate(value) }
    value.game = "maimaid"
    value.scores[0].profileID = UUID().uuidString
    #expect(throws: (any Error).self) { try CloudBackupCodec.validate(value) }
  }
}

@MainActor
struct CloudSnapshotStoreTests {
  @Test func replacementPreservesCatalogAndReplacesPersonalRowsWithSameIDs() throws {
    let id = UUID().uuidString
    let directory = URL.temporaryDirectory.appending(path: id)
    let defaults = try #require(UserDefaults(suiteName: id))
    defer {
      defaults.removePersistentDomain(forName: id)
      try? FileManager.default.removeItem(at: directory)
    }
    let environment = CloudSnapshotStore.Environment(
      directory: directory, defaults: defaults, notifyChanges: false)
    let container = try makeContainer()
    let context = container.mainContext
    let song = Song(
      songIdentifier: "Test", category: "test", title: "Test", artist: "Test", imageName: "",
      sortOrder: 0, isNew: false, isLocked: false)
    let sheet = Sheet(songIdentifier: "Test", type: "dx", difficulty: "master", level: "14")
    song.sheets = [sheet]
    context.insert(song)
    let fixture = try CloudBackupCodec.decode(BackupFixture.compressed)
    let profileID = try #require(UUID(uuidString: fixture.profiles[0].id))
    context.insert(UserProfile(id: profileID, name: "Before", server: "jp", isActive: true))
    context.insert(Score(sheetId: "Test_dx_master", rate: 80, rank: "a", userProfileId: profileID))
    context.insert(SongCollection(name: "Old collection"))
    context.insert(SyncConfig(isAutoUploadEnabled: true))
    try context.save()
    defaults.set(false, forKey: "songs.sortAscending")
    try CloudSnapshotStore.restore(fixture, context: context, environment: environment)
    let restored = try CloudSnapshotStore.export(context: context, environment: environment)
    #expect(restored.settings.first { $0.key == "ios.maimaid.autoUpload" }?.boolValue == false)
    #expect(restored.profiles.count == 1)
    #expect(restored.profiles[0].name == "Test 玩家")
    #expect(restored.scores.count == 1)
    #expect(restored.scores[0].achievement == 100.1234)
    #expect(restored.collections.isEmpty)
    #expect(
      restored.settings.contains {
        $0.key == "android.maimaid.catalog_sort_ascending" && $0.boolValue
      })
    #expect(try context.fetchCount(FetchDescriptor<Song>()) == 1)
    #expect(try context.fetchCount(FetchDescriptor<Sheet>()) == 1)
    #expect(defaults.object(forKey: "songs.sortAscending") == nil)
    #expect(FileManager.default.fileExists(atPath: environment.journalURL.path) == false)
    // Simulate process death after a partial replacement: the durable journal must win.
    try Data([1]).write(to: environment.journalURL)
    try CloudSnapshotStore.recover(context: context, environment: environment)
    let recovered = try CloudSnapshotStore.export(context: context, environment: environment)
    #expect(recovered.settings.first { $0.key == "ios.maimaid.autoUpload" }?.boolValue == true)
    #expect(recovered.profiles[0].name == "Before")
    #expect(recovered.scores[0].achievement == 80)
    #expect(recovered.collections.count == 1)
    #expect(defaults.bool(forKey: "songs.sortAscending") == false)
    #expect(FileManager.default.fileExists(atPath: environment.journalURL.path) == false)
  }
  private func makeContainer() throws -> ModelContainer {
    let schema = Schema([
      Song.self, Sheet.self, Score.self, PlayRecord.self, UserProfile.self, SongCollection.self,
      SongCollectionItem.self, SyncConfig.self
    ])
    return try ModelContainer(
      for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
  }
}
