import Foundation
import Observation
import SwiftData

enum SgwcmaidImportOutcome {
  case imported
  case noChanges
}

@MainActor
@Observable
final class SgwcmaidImportViewModel {
  var code: String
  private(set) var isImporting = false
  private(set) var outcome: SgwcmaidImportOutcome?
  private(set) var errorMessage: String?
  private(set) var result: LocalScoreImportResult?
  /// The player remembered from the last successful QR import.
  private(set) var binding: SgwcmaidBinding?
  let provinces: [SgwcmaidProvince]
  private(set) var selectedProvinceID: Int?

  @ObservationIgnored private var operation: Task<Void, Never>?
  @ObservationIgnored private let keychipStore: SgwcmaidKeychipStore

  init(
    code: String = "", catalog: SgwcmaidKeychipCatalog = .bundled,
    keychipStore: SgwcmaidKeychipStore = .shared
  ) {
    self.code = code
    self.provinces = catalog.provinces
    self.keychipStore = keychipStore
    self.selectedProvinceID = keychipStore.selectedProvinceID
  }

  /// Loads the binding of the given profile, if any.
  func loadBinding(profileID: UUID?) {
    binding = profileID.flatMap { SgwcmaidBindingStore.shared.binding(for: $0) }
  }

  /// Refreshes the scores of the remembered player. This path never logs in,
  /// so it cannot trip the cabinet session lock.
  func quickImport(profileID: UUID, context: ModelContext) {
    guard !isImporting, let binding else { return }
    isImporting = true
    outcome = nil
    errorMessage = nil
    result = nil
    operation = Task { @MainActor in
      defer {
        self.isImporting = false
        self.operation = nil
        self.loadBinding(profileID: profileID)
      }
      do {
        let outcome = try await SgwcmaidImportService.runByBinding(
          userID: binding.userID, profileID: profileID, context: context)
        guard !Task.isCancelled else { return }
        self.result = outcome.result
        self.outcome = outcome.result.updatedCount > 0 ? .imported : .noChanges
      } catch is CancellationError {
        return
      } catch {
        guard !Task.isCancelled else { return }
        self.errorMessage = error.localizedDescription
      }
    }
  }

  func unbind(profileID: UUID) {
    guard !isImporting else { return }
    SgwcmaidBindingStore.shared.clear(for: profileID)
    loadBinding(profileID: profileID)
  }

  /// Stores the manual region choice. `nil` restores the nationwide pool.
  func selectProvince(_ id: Int?) {
    keychipStore.selectedProvinceID = id
    selectedProvinceID = id
  }

  func importScores(profileID: UUID, context: ModelContext) {
    guard !isImporting else { return }
    let normalized = code.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !normalized.isEmpty else { return }

    isImporting = true
    outcome = nil
    errorMessage = nil
    result = nil
    operation = Task { @MainActor in
      defer {
        self.isImporting = false
        self.operation = nil
        self.loadBinding(profileID: profileID)
      }
      do {
        let outcome = try await SgwcmaidImportService.run(
          code: normalized, profileID: profileID, context: context)
        guard !Task.isCancelled else { return }
        self.result = outcome.result
        self.outcome = outcome.result.updatedCount > 0 ? .imported : .noChanges
      } catch is CancellationError {
        return
      } catch {
        guard !Task.isCancelled else { return }
        self.errorMessage = error.localizedDescription
      }
    }
  }

  func cancel() {
    operation?.cancel()
    operation = nil
    isImporting = false
  }
}
