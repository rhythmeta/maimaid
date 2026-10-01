import Foundation
import Observation
import SwiftData
import SwiftProtobuf

struct CloudBackup: Decodable, Identifiable {
  let id: String
  let game: String
  let formatVersion: Int
  let size: Int
  let uncompressedSize: Int
  let sha256: String
  let deviceName: String
  let profileCount: Int
  let committedAt: String
  let downloadUrl: String
}
private struct CloudBackupList: Decodable { let backups: [CloudBackup] }
private struct CloudBackupUpload: Decodable {
  let id: String
  let uploadUrl: String
  let headers: [String: String]
}
private struct CloudBackupMetadata: Encodable {
  let formatVersion = 1
  let size: Int
  let uncompressedSize: Int
  let sha256: String
  let deviceName: String
  let clientVersion: String
  let profileCount: Int
}
private struct CloudBackupAck: Decodable {}

@MainActor
@Observable
final class CloudBackupService {
  static let shared = CloudBackupService()
  private(set) var backups: [CloudBackup] = []
  private(set) var isBusy = false
  private let base = "maimaid/v1/backups"
  func reload() async throws {
    let payload: CloudBackupList = try await BackendAPIClient.request(
      path: base, authentication: .required)
    backups = payload.backups
  }
  func backup(context: ModelContext) async throws {
    guard !isBusy else { return }
    isBusy = true
    defer { isBusy = false }
    try CloudSnapshotStore.recover(context: context)
    let snapshot = try CloudSnapshotStore.export(context: context)
    let rawSize = try snapshot.serializedData().count
    let bytes = try await CloudBackupCodec.encodeAsync(snapshot)
    let metadata = CloudBackupMetadata(
      size: bytes.count, uncompressedSize: rawSize, sha256: CloudBackupCodec.sha256(bytes),
      deviceName: "iOS", clientVersion: AppInfo.shortVersion ?? "1",
      profileCount: snapshot.profiles.count)
    let upload: CloudBackupUpload = try await BackendAPIClient.request(
      path: base, method: "POST", body: metadata, authentication: .required)
    guard let url = URL(string: upload.uploadUrl), url.scheme == "https" else {
      throw CloudBackupError.invalid("Invalid upload URL.")
    }
    var request = URLRequest(url: url)
    request.httpMethod = "PUT"
    request.timeoutInterval = 120
    for (key, value) in upload.headers { request.setValue(value, forHTTPHeaderField: key) }
    let (_, response) = try await URLSession.shared.upload(for: request, from: bytes)
    guard let response = response as? HTTPURLResponse, (200...299).contains(response.statusCode)
    else { throw CloudBackupError.invalid("Backup upload failed.") }
    let _: CloudBackupAck = try await BackendAPIClient.request(
      path: "\(base)/\(upload.id)/commit", method: "POST", authentication: .required)
    try await reload()
  }
  func restore(_ backup: CloudBackup, context: ModelContext) async throws {
    guard !isBusy else { return }
    isBusy = true
    defer { isBusy = false }
    guard backup.game == "maimaid", backup.formatVersion == 1,
      (1...CloudBackupCodec.maxCompressed).contains(backup.size),
      let url = URL(string: backup.downloadUrl), url.scheme == "https"
    else { throw CloudBackupError.invalid("Unsupported backup.") }
    var request = URLRequest(url: url)
    request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
    request.timeoutInterval = 120
    let (file, response) = try await URLSession.shared.download(for: request)
    defer { try? FileManager.default.removeItem(at: file) }
    guard let response = response as? HTTPURLResponse, response.statusCode == 200,
      try file.resourceValues(forKeys: [.fileSizeKey]).fileSize == backup.size
    else { throw CloudBackupError.invalid("Backup download size does not match.") }
    let bytes = try Data(contentsOf: file)
    guard CloudBackupCodec.sha256(bytes) == backup.sha256 else {
      throw CloudBackupError.invalid("Backup checksum does not match.")
    }
    let snapshot = try await CloudBackupCodec.decodeAsync(
      bytes, expectedSize: backup.uncompressedSize)
    try CloudSnapshotStore.restore(snapshot, context: context)
  }
}
