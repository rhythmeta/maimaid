import SwiftData
import SwiftUI

struct LocalScoreImportView: View {
  @Environment(\.modelContext) private var modelContext
  @Environment(\.openURL) private var openURL
  @Query(filter: #Predicate<UserProfile> { $0.isActive }) private var profiles: [UserProfile]
  @State private var model: LocalScoreImportViewModel

  init(provider: ScoreImportProvider) {
    _model = State(initialValue: LocalScoreImportViewModel(provider: provider))
  }

  var body: some View {
    List {
      Section {
        ImportPageHeader(
          symbol: model.provider == .lxns ? "snowflake" : "fish.fill",
          title: model.provider == .lxns ? "import.lxns.summary.title" : "import.df.summary.title",
          summary: model.provider == .lxns ? "import.lxns.summary.description" : "import.df.summary.description")
      }
      .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
      .listSectionSeparator(.hidden)
      ImportProfilePreview(profile: profiles.first)
      if model.provider == .divingFish {
        DivingFishImportSections(
          model: model, authorize: authorize, quickImport: quickImport, disconnect: disconnect
        )
      } else {
        LxnsImportSections(
          model: model, authorize: authorize, quickImport: quickImport,
          disconnect: disconnect, exchange: exchange
        )
      }
      if model.isBusy || !model.status.isEmpty {
        ScoreImportStatusSection(model: model)
      }
    }
    .listStyle(.insetGrouped)
    .scrollDismissesKeyboard(.interactively)
    .disabled(profiles.isEmpty)
    .navigationTitle(model.provider == .lxns ? "import.lxns.title" : "import.df.title")
    .navigationBarTitleDisplayMode(.inline)
    .onChange(of: profiles.first?.id, initial: true) { _, id in model.reload(profileID: id) }
    .onDisappear { model.cancel() }
  }

  private func authorize() {
    guard let profile = profiles.first else { return }
    model.authorize(profileID: profile.id, context: modelContext) { openURL($0) }
  }

  private func quickImport() {
    guard let profile = profiles.first else { return }
    model.quickImport(profileID: profile.id, context: modelContext)
  }

  private func disconnect() {
    guard let profile = profiles.first else { return }
    model.disconnect(profileID: profile.id)
  }

  private func exchange() {
    guard let profile = profiles.first else { return }
    model.exchange(profileID: profile.id, context: modelContext)
  }
}
