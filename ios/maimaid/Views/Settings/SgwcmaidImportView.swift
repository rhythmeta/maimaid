import SwiftData
import SwiftUI

struct SgwcmaidImportView: View {
  @Environment(\.modelContext) private var modelContext
  @Query(filter: #Predicate<UserProfile> { $0.isActive }) private var profiles: [UserProfile]
  @State private var model: SgwcmaidImportViewModel

  init(initialCode: String = "") {
    _model = State(initialValue: SgwcmaidImportViewModel(code: initialCode))
  }

  var body: some View {
    List {
      SgwcmaidImportSummarySection()
      if model.binding != nil {
        SgwcmaidBoundPlayerSection(
          model: model, isEnabled: !profiles.isEmpty, quickImport: quickImport, unbind: unbind)
      }
      SgwcmaidRegionSection(model: model)
      SgwcmaidImportInputSection(
        model: model, isEnabled: !profiles.isEmpty, startImport: startImport)
      if model.isImporting || model.outcome != nil || model.errorMessage != nil {
        SgwcmaidImportStatusSection(model: model)
      }
    }
    .listStyle(.insetGrouped)
    .scrollDismissesKeyboard(.interactively)
    .disabled(profiles.isEmpty)
    .navigationTitle("import.sgwcmaid.title")
    .navigationBarTitleDisplayMode(.inline)
    .task(id: profiles.first?.id) { model.loadBinding(profileID: profiles.first?.id) }
    .onDisappear { model.cancel() }
  }

  private func quickImport() {
    guard let profile = profiles.first else { return }
    model.quickImport(profileID: profile.id, context: modelContext)
  }

  private func unbind() {
    guard let profile = profiles.first else { return }
    model.unbind(profileID: profile.id)
  }

  private func startImport() {
    guard let profile = profiles.first else { return }
    model.importScores(profileID: profile.id, context: modelContext)
  }
}
