import Foundation
import Observation
import SwiftData

@MainActor
@Observable
final class SdgaImportViewModel {
  var card: String
  var extra: String
  private(set) var isImporting = false
  private(set) var result: LocalScoreImportResult?
  private(set) var errorMessage: String?
  private(set) var saved: SdgaSavedCredential?
  private(set) var country: String?
  private(set) var placeID: Int?

  let catalog: SdgaPlacesCatalog
  @ObservationIgnored private var operation: Task<Void, Never>?
  @ObservationIgnored private let store: SdgaPlaceStore

  init(card: String = "", extra: String = "", catalog: SdgaPlacesCatalog = .bundled) {
    self.card = card
    self.extra = extra
    self.catalog = catalog
    self.store = .shared
    self.country = store.country
    self.placeID = store.placeID
  }

  var countries: [String] { catalog.countries }

  var places: [SdgaPlace] {
    catalog.places(in: country).sorted { $0.displayName < $1.displayName }
  }

  /// Second field label follows whatever the first field looks like.
  var extraLabelKey: String {
    switch SdgaCredential.parse(card) {
    case .idm: "import.sdga.extraPMM"
    case .accessCode: "import.sdga.extraUID"
    default: "import.sdga.extraAny"
    }
  }

  func loadSaved(profileID: UUID?) {
    saved = profileID.flatMap { SdgaImportService.credential(for: $0) }
  }

  func selectCountry(_ value: String?) {
    country = value
    placeID = nil
    store.country = value
    store.placeID = nil
  }

  func selectPlace(_ value: Int?) {
    placeID = value
    store.placeID = value
  }

  func importScores(profileID: UUID, context: ModelContext) {
    let raw = extra.isEmpty ? card : "\(card) \(extra)"
    run(profileID: profileID, context: context) {
      try await SdgaImportService.run(rawCredential: raw, profileID: profileID, context: context)
    }
  }

  func quickImport(profileID: UUID, context: ModelContext) {
    guard saved != nil else { return }
    run(profileID: profileID, context: context) {
      try await SdgaImportService.runSaved(profileID: profileID, context: context)
    }
  }

  func forget(profileID: UUID) {
    guard !isImporting else { return }
    SdgaImportService.forget(profileID: profileID)
    loadSaved(profileID: profileID)
  }

  func cancel() {
    operation?.cancel()
    operation = nil
    isImporting = false
  }

  private func run(
    profileID: UUID, context: ModelContext,
    body: @escaping () async throws -> SdgaImportRun
  ) {
    guard !isImporting, !card.trimmingCharacters(in: .whitespaces).isEmpty else { return }
    isImporting = true
    result = nil
    errorMessage = nil
    operation = Task { @MainActor in
      defer {
        isImporting = false
        operation = nil
        loadSaved(profileID: profileID)
      }
      do {
        let run = try await body()
        guard !Task.isCancelled else { return }
        result = run.result
      } catch is CancellationError {
        return
      } catch {
        guard !Task.isCancelled else { return }
        errorMessage = Self.message(for: error)
      }
    }
  }

  /// Turns protocol codes into wording the UI can show.
  private static func message(for error: Error) -> String {
    guard let error = error as? ScoreImportError else { return error.localizedDescription }
    return switch error.code {
    case "sdga_not_configured": String(localized: "import.sdga.error.notConfigured")
    case "sdga_invalid_credential": String(localized: "import.sdga.error.invalidCredential")
    case "sdga_pmm_required": String(localized: "import.sdga.error.pmmRequired")
    case "sdga_no_access_code": String(localized: "import.sdga.error.noAccessCode")
    case "sdga_no_account", "sdga_no_account_no_uid": String(localized: "import.sdga.error.noAccount")
    case "sdga_locked": String(localized: "import.sdga.error.locked")
    case "sdga_network", "sdga_no_response", "sdga_timeout":
      String(localized: "import.sdga.error.network")
    case "sdga_no_cabinet": String(localized: "import.sdga.error.noCabinet")
    case "sdga_no_saved_credential": String(localized: "import.sdga.error.noSaved")
    case "game_empty_response": String(localized: "import.sdga.error.emptyResponse")
    case "game_account_already_logged_in", "game_login_100":
      String(localized: "import.sgwcmaid.gameLoginBusy")
    default: error.localizedDescription
    }
  }
}
