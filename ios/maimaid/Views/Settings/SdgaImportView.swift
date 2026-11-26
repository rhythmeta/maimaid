import SwiftData
import SwiftUI

/// International-server (`SDGA`) score import.
///
/// Same page structure as `SgwcmaidImportView`: summary header, the profile being written,
/// the cabinet region, the credential, and the shared status block.
struct SdgaImportView: View {
  @Environment(\.modelContext) private var modelContext
  @Query(filter: #Predicate<UserProfile> { $0.isActive }) private var profiles: [UserProfile]
  @State private var model = SdgaImportViewModel()
  @State private var egress: SdgaEgress?
  @State private var egressLoading = false

  var body: some View {
    List {
      Section {
        ImportPageHeader(
          symbol: "globe.asia.australia.fill",
          title: "import.sdga.title",
          summary: "import.sdga.summary")
      }
      .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
      .listSectionSeparator(.hidden)

      ImportProfilePreview(profile: profiles.first)

      SdgaEgressSection(
        egress: egress,
        isLoading: egressLoading,
        refresh: { Task { await loadEgress() } })

      SdgaRegionSection(model: model)

      if let saved = model.saved {
        SdgaSavedCredentialSection(
          saved: saved,
          isImporting: model.isImporting,
          isEnabled: !profiles.isEmpty,
          quickImport: quickImport,
          forget: forget)
      }

      SdgaImportInputSection(
        model: model,
        isEnabled: !profiles.isEmpty,
        startImport: startImport)

      if model.result != nil || model.errorMessage != nil {
        SdgaImportStatusSection(model: model)
      }
    }
    .listStyle(.insetGrouped)
    .scrollDismissesKeyboard(.interactively)
    .disabled(profiles.isEmpty)
    .navigationTitle("import.sdga.title")
    .navigationBarTitleDisplayMode(.inline)
    .task(id: profiles.first?.id) { model.loadSaved(profileID: profiles.first?.id) }
    .task { await loadEgress() }
    .onDisappear { model.cancel() }
  }

  private func quickImport() {
    guard let profile = profiles.first else { return }
    model.quickImport(profileID: profile.id, context: modelContext)
  }

  private func forget() {
    guard let profile = profiles.first else { return }
    model.forget(profileID: profile.id)
  }

  private func startImport() {
    guard let profile = profiles.first else { return }
    model.importScores(profileID: profile.id, context: modelContext)
  }

  private func loadEgress() async {
    egressLoading = true
    egress = await SdgaEgressService.fetch()
    egressLoading = false
  }
}
