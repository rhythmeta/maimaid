import CryptoKit
import Foundation

struct ImportedScore: Sendable {
  let songID: Int
  let title: String
  let type: String
  let levelIndex: Int
  let achievement: Double
  let dxScore: Int
  let fc: String?
  let fs: String?
  let playedAt: Date?

  var difficulty: String? {
    let values = ["basic", "advanced", "expert", "master", "remaster"]
    return values.indices.contains(levelIndex) ? values[levelIndex] : nil
  }

  func recordID(profileID: UUID) -> UUID? {
    guard let playedAt else { return nil }
    let key = [
      "lxns", profileID.uuidString.lowercased(), String(songID), type, String(levelIndex),
      String(Int64((playedAt.timeIntervalSince1970 * 1000).rounded())),
      String(Int64((achievement * 10000).rounded())), String(dxScore), fc ?? "", fs ?? ""
    ]
    .joined(separator: "|")
    var bytes = Array(SHA256.hash(data: Data(key.utf8)).prefix(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x50
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    return UUID(
      uuid: (
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
      ))
  }
}
