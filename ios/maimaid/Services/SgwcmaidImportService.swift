import Foundation
import SwiftData

/// One SGWCMAID import together with the identity it proved.
///
/// `userID` is present only for the direct chime path; the relay returns scores
/// without an identity.
nonisolated struct SgwcmaidImportRun: Sendable {
  let result: LocalScoreImportResult
  let userID: Int?
  let playerName: String
}

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
    -> SgwcmaidImportRun {
    let normalized = code.trimmingCharacters(in: .whitespacesAndNewlines)
    guard normalized.hasPrefix(SgwcmaidCode.prefix) else {
      throw ScoreImportError(code: "invalid_sgwcmaid")
    }
    let config = SgwcmaidConfig.current
    guard config.isComplete else {
      throw ScoreImportError(code: "sgwcmaid_not_configured")
    }

    let fetched: (data: Data, userID: Int?, playerName: String)
    if relayEndpoint.isEmpty {
      let music = try await fetchMusicUsingKeychips(code: normalized, config: config)
      fetched = (music.data, music.userID, music.playerName)
    } else {
      fetched = (try await fetchFromRelay(relayEndpoint, code: normalized), nil, "")
    }

    // The QR exchange consumed a one-time code, so remember the player identity
    // before the (fallible) apply; a local failure must not force another scan.
    if let userID = fetched.userID, userID > 0 {
      SgwcmaidBindingStore.shared.save(
        SgwcmaidBinding(userID: userID, playerName: fetched.playerName), for: profileID)
    }
    try Task.checkCancellation()
    let payload = try ScoreImportPayload.decode(fetched.data, provider: .sgwcmaid)
    try Task.checkCancellation()
    let result = try LocalScoreImportService.apply(
      payload, provider: .sgwcmaid, profileID: profileID, context: context)
    return SgwcmaidImportRun(
      result: result, userID: fetched.userID, playerName: fetched.playerName)
  }

  /// Refreshes the scores of a remembered player without scanning another code.
  ///
  /// The rival endpoint answers for the player id alone, so this path never logs
  /// in and cannot trip the cabinet session lock.
  static func runByBinding(userID: Int, profileID: UUID, context: ModelContext) async throws
    -> SgwcmaidImportRun {
    let fetched = try await ChimeGetUserDataClient(config: .current).fetchOwnMusic(userID: userID)
    SgwcmaidBindingStore.shared.save(
      SgwcmaidBinding(userID: userID, playerName: fetched.playerName), for: profileID)
    try Task.checkCancellation()
    // This payload only carries the achievement and the best DX score, so the
    // combo/sync badges already stored for the profile are kept.
    let payload = try ScoreImportPayload.decode(
      fetched.data, provider: .sgwcmaid, keepUnknownJudgements: true)
    try Task.checkCancellation()
    let result = try LocalScoreImportService.apply(
      payload, provider: .sgwcmaid, profileID: profileID, context: context)
    return SgwcmaidImportRun(
      result: result, userID: fetched.userID, playerName: fetched.playerName)
  }

  /// Tries cabinets in the selected region first, then the nationwide pool, and
  /// finally the build-time keychip. Only the chime exchange is retried: once a
  /// session exists the QR code has been consumed, so the game fetch must not be
  /// replayed on another cabinet.
  private static func fetchMusicUsingKeychips(code: String, config: SgwcmaidConfig) async throws
    -> SgwcmaidFetchedMusic {
    let store = SgwcmaidKeychipStore.shared
    let plan = SgwcmaidKeychipSelector.plan(
      catalog: .bundled, provinceID: store.selectedProvinceID, cursor: store.cursor)
    let candidates = plan.attempts()
    let client = ChimeGetUserDataClient(config: config)
    var lastError: ScoreImportError?
    var attempted = 0

    for keychip in candidates {
      try Task.checkCancellation()
      attempted += 1
      let cabinet = SgwcmaidCabinet(keychip: keychip)
      let session: SgwcmaidChimeSession
      do {
        session = try await client.resolveSession(code: code, cabinet: cabinet)
      } catch let error as ScoreImportError {
        guard SgwcmaidKeychipFailure.isRetryable(error.code) else { throw error }
        lastError = error
        continue
      }
      // The code is consumed from here on. Move the cursor before the game
      // fetch so a retried import starts with the next cabinet, and never
      // replay the fetch on another cabinet.
      store.advanceCursor(by: attempted)
      return try await client.fetchMusic(session: session, cabinet: cabinet)
    }

    if let legacy = legacyCabinet(config: config, avoiding: candidates) {
      let session = try await client.resolveSession(code: code, cabinet: legacy)
      store.advanceCursor(by: attempted + 1)
      return try await client.fetchMusic(session: session, cabinet: legacy)
    }

    throw lastError ?? ScoreImportError(code: "chime_keychip_missing")
  }

  /// The build-time keychip (or a stored developer override) is kept as an
  /// emergency fallback so an older deployment keeps working when the bundled
  /// catalog cannot serve an import.
  private static func legacyCabinet(
    config: SgwcmaidConfig, avoiding candidates: [SgwcmaidKeychip]
  ) -> SgwcmaidCabinet? {
    let keychip = ChimeCredentialStore.shared.keychip(fallback: config.keychip)
    guard !keychip.isEmpty, config.placeID > 0, config.regionID > 0,
      !candidates.contains(where: { $0.keychip == keychip })
    else { return nil }
    return SgwcmaidCabinet(
      keychip: keychip, placeID: config.placeID, regionID: config.regionID)
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
