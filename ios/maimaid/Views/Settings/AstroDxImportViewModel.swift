import Foundation
import Observation
import SwiftData

@Observable
@MainActor
final class AstroDxImportViewModel {
  struct SelectedCache {
    let data: Data
    let name: String
  }

  private(set) var selectedCache: SelectedCache?
  private(set) var isImporting = false
  private(set) var result: AstroDxImportResult?
  private(set) var errorMessage: String?

  func selectCache(from url: URL) {
    do {
      let didAccess = url.startAccessingSecurityScopedResource()
      defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
      let values = try url.resourceValues(forKeys: [.fileSizeKey])
      guard let size = values.fileSize, size > 0, size <= AstroDxCacheCodec.maximumInputBytes else {
        throw AstroDxCacheError.tooLarge
      }
      let data = try Data(contentsOf: url, options: .mappedIfSafe)
      selectedCache = SelectedCache(data: data, name: url.lastPathComponent)
      result = nil
      errorMessage = nil
    } catch {
      selectedCache = nil
      errorMessage = message(for: error)
    }
  }

  func importSelected(profileID: UUID, context: ModelContext) async {
    guard let selectedCache, !isImporting else { return }
    isImporting = true
    result = nil
    errorMessage = nil
    defer { isImporting = false }
    do {
      result = try await AstroDxImportService.run(selectedCache.data, profileID: profileID, context: context)
    } catch {
      errorMessage = message(for: error)
    }
  }

  private func message(for error: Error) -> String {
    if let error = error as? AstroDxCacheError {
      switch error {
      case .tooLarge: return String(localized: "import.astrodx.error.size")
      case .unsupportedVersion: return String(localized: "import.astrodx.error.version")
      case .invalid: return String(localized: "import.astrodx.error.invalid")
      }
    }
    if let error = error as? ScoreImportError, error.code == "profile_changed" {
      return String(localized: "import.astrodx.error.profile")
    }
    if let error = error as? ScoreImportError, error.code == "catalog_empty" {
      return String(localized: "import.astrodx.error.catalog")
    }
    return String(localized: "import.astrodx.error.invalid")
  }
}
