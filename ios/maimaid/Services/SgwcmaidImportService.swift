import Foundation
import SwiftData

/// Imports China-server scores from an `SGWCMAID` cabinet code.
///
/// The code is exchanged for a game session, `music.json` is fetched, and the
/// resulting payload is applied with replacement semantics. Nothing here
/// touches Diving Fish or LXNS tokens.
@MainActor
enum SgwcmaidImportService {
  /// Optional HTTP relay that returns the same `music.json` array. The direct
  /// chime client below is used when this is empty, matching Android's
  /// `ThirdPartyImportService.SGWCMAID_ENDPOINT`.
  static let relayEndpoint = ""

  static func run(code: String, profileID: UUID, context: ModelContext) async throws
    -> LocalScoreImportResult {
    let normalized = code.trimmingCharacters(in: .whitespacesAndNewlines)
    guard normalized.hasPrefix(SgwcmaidCode.prefix) else {
      throw ScoreImportError(code: "invalid_sgwcmaid")
    }
    let config = SgwcmaidConfig.current
    guard config.isComplete else {
      throw ScoreImportError(code: "sgwcmaid_not_configured")
    }
    let keychip = ChimeCredentialStore.shared.keychip(fallback: config.keychip)
    guard !keychip.isEmpty else {
      throw ScoreImportError(code: "chime_keychip_missing")
    }

    let data: Data
    if relayEndpoint.isEmpty {
      data = try await ChimeGetUserDataClient(config: config).fetchMusic(
        code: normalized, keychip: keychip)
    } else {
      data = try await fetchFromRelay(relayEndpoint, code: normalized)
    }

    try Task.checkCancellation()
    let payload = try ScoreImportPayload.decode(data, provider: .sgwcmaid)
    try Task.checkCancellation()
    return try LocalScoreImportService.apply(
      payload, provider: .sgwcmaid, profileID: profileID, context: context)
  }

  private static func fetchFromRelay(_ endpoint: String, code: String) async throws -> Data {
    guard let url = URL(string: endpoint) else {
      throw ScoreImportError(code: "sgwcmaid_not_configured")
    }
    let response: SgwcmaidHTTPResponse
    do {
      response = try await SgwcmaidHTTPTransport.shared.post(
        url: url,
        headers: ["Accept": "application/json", "Content-Type": "text/plain"],
        body: Data(code.utf8))
    } catch let error as SgwcmaidHTTPError {
      throw error == .timeout
        ? ScoreImportError(code: "chime_timeout")
        : ScoreImportError(code: "chime_network")
    }
    guard (200..<300).contains(response.statusCode) else {
      throw ScoreImportError(code: "HTTP \(response.statusCode)")
    }
    return response.body
  }
}
