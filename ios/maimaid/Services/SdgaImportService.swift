import SwiftData
import Foundation

/// One international import together with the identity it proved.
nonisolated struct SdgaImportRun: Sendable {
  let result: LocalScoreImportResult
  let userID: Int
  let playerName: String
}

/// Imports international-server (`SDGA`) scores from a card credential.
///
/// The pipeline is: credential → AiMeDB (card → account) → title-server login → paginated
/// `GetUserMusicApi` → the shared score import. The last step is the same code the China
/// cabinet import uses: the response is `userMusicList[].userMusicDetailList[]`, so decoding
/// and applying are region independent.
@MainActor
enum SdgaImportService {
  /// Cabinets are tried in this order: the known-good build-time cabinet first, then the
  /// pool rotated by the cursor. Dead keychips are common in the catalog, so a transport
  /// failure is retried once on the next cabinet.
  nonisolated static func plan(
    catalog: SdgaPlacesCatalog, country: String?, placeID: Int?, cursor: Int,
    fallback: SdgaCabinet?
  ) -> [SdgaCabinet] {
    let pool = catalog.cabinets(country: country, placeID: placeID)
      .reduce(into: [SdgaCabinet]()) { unique, cabinet in
        if !unique.contains(where: { $0.keychip == cabinet.keychip }) { unique.append(cabinet) }
      }
    let offset = pool.isEmpty ? 0 : ((cursor % pool.count) + pool.count) % pool.count
    let rotated = Array(pool.dropFirst(offset) + pool.prefix(offset))
      .filter { $0.keychip != fallback?.keychip }
    guard let fallback else { return Array(rotated.prefix(4)) }
    let explicit = !(country ?? "").isEmpty || placeID != nil
    let ordered = explicit
      ? Array(rotated.prefix(1)) + [fallback] + rotated.dropFirst()
      : [fallback] + rotated
    return Array(ordered.prefix(4))
  }

  static func credential(for profileID: UUID) -> SdgaSavedCredential? {
    SdgaCredentialStore.shared.credential(for: profileID)
  }

  static func forget(profileID: UUID) {
    SdgaCredentialStore.shared.clear(for: profileID)
  }

  /// Re-imports with the credential remembered from the last success.
  static func runSaved(profileID: UUID, context: ModelContext) async throws -> SdgaImportRun {
    guard let saved = credential(for: profileID) else {
      throw ScoreImportError(code: "sdga_no_saved_credential")
    }
    let raw = saved.extra.isEmpty ? saved.card : "\(saved.card) \(saved.extra)"
    return try await run(rawCredential: raw, profileID: profileID, context: context)
  }

  static func run(
    rawCredential: String, profileID: UUID, context: ModelContext
  ) async throws -> SdgaImportRun {
    let config = SdgaConfig.current
    guard config.isComplete else { throw ScoreImportError(code: "sdga_not_configured") }
    guard let credential = SdgaCredential.parse(rawCredential) else {
      throw ScoreImportError(code: "sdga_invalid_credential")
    }
    // Validate the PMm before opening a socket so a malformed credential costs no cabinet.
    if case .idm(let idm, let pmm) = credential {
      _ = try SdgaCredential.pmm(for: idm, provided: pmm)
    }

    let fallback: SdgaCabinet? =
      if !config.keychip.isEmpty, config.placeID > 0, config.regionID > 0 {
        SdgaCabinet(
          keychip: config.keychip.filter { $0.isLetter || $0.isNumber },
          placeID: config.placeID, regionID: config.regionID)
      } else {
        nil
      }
    let cabinetList = plan(
      catalog: .bundled,
      country: SdgaPlaceStore.shared.country,
      placeID: SdgaPlaceStore.shared.placeID,
      cursor: SdgaPlaceStore.shared.cursor,
      fallback: fallback)
    guard !cabinetList.isEmpty else { throw ScoreImportError(code: "sdga_no_cabinet") }

    let aime = SdgaAimeDBClient(config: config)
    let game = SdgaGameClient(config: config)
    var attempted = 0
    var transportFailures = 0
    var lastError: Error?

    for cabinet in cabinetList {
      try Task.checkCancellation()
      attempted += 1
      let account: SdgaAimeAccount
      do {
        account = try await aime.resolve(credential, cabinet: cabinet)
      } catch let error as ScoreImportError {
        let transport = error.code == "sdga_network" || error.code == "sdga_no_response"
        transportFailures += 1
        guard transport, transportFailures < 2 else { throw error }
        lastError = error
        continue
      }
      SdgaPlaceStore.shared.cursor = attempted
      let fetched = try await game.fetchMusic(account: account, cabinet: cabinet)
      try Task.checkCancellation()
      // This payload carries every badge, so nothing has to be preserved.
      let payload = try ScoreImportPayload.decode(fetched.music, provider: .sgwcmaid)
      try Task.checkCancellation()
      let result = try LocalScoreImportService.apply(
        payload, provider: .sgwcmaid, profileID: profileID, context: context)
      SdgaCredentialStore.shared.save(
        SdgaSavedCredential(
          card: credential.card, extra: credential.extra ?? "",
          playerName: fetched.playerName),
        for: profileID)
      return SdgaImportRun(
        result: result, userID: fetched.userID, playerName: fetched.playerName)
    }
    throw lastError ?? ScoreImportError(code: "sdga_no_cabinet")
  }
}
