import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct AstroDxImportView: View {
  @Environment(\.modelContext) private var modelContext
  @Query(filter: #Predicate<UserProfile> { $0.isActive }) private var profiles: [UserProfile]
  @State private var model = AstroDxImportViewModel()
  @State private var showingFileImporter = false

  private var profile: UserProfile? { profiles.first }

  var body: some View {
    List {
      Section {
        ImportPageHeader(
          symbol: "externaldrive.fill",
          title: "import.astrodx.summary.title",
          summary: "import.astrodx.summary.description")
      }
      .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
      .listSectionSeparator(.hidden)

      ImportProfilePreview(profile: profile)

      Section {
        Button {
          showingFileImporter = true
        } label: {
          Label("import.astrodx.choose", systemImage: "folder")
        }
        .disabled(model.isImporting)

        if let selectedCache = model.selectedCache {
          Label(selectedCache.name, systemImage: "doc.fill")
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .lineLimit(1)
        }
      } header: {
        Text("import.astrodx.step1")
      } footer: {
        Text("import.astrodx.ios.note")
      }

      Section {
        Button {
          guard let profile else { return }
          Task { await model.importSelected(profileID: profile.id, context: modelContext) }
        } label: {
          HStack {
            Spacer()
            if model.isImporting { ProgressView().tint(.white) }
            Text(model.isImporting ? "import.astrodx.importing" : "import.astrodx.action")
              .bold()
            Spacer()
          }
          .contentShape(.rect)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(model.selectedCache == nil || model.isImporting || profile == nil)

        if let errorMessage = model.errorMessage {
          Label(errorMessage, systemImage: "exclamationmark.circle.fill")
            .foregroundStyle(.red)
            .font(.footnote)
        }
        if let result = model.result {
          VStack(alignment: .leading, spacing: 4) {
            Text(result.found == 0 ? "import.astrodx.empty" : "import.astrodx.complete")
              .foregroundStyle(.green)
            Text("import.astrodx.counts \(result.found) \(result.matched) \(result.updated) \(result.skipped)")
              .font(.caption)
              .foregroundStyle(.secondary)
          }
        }
      } header: {
        Text("import.astrodx.step2")
      } footer: {
        Text("import.astrodx.result.note")
      }
    }
    .listStyle(.insetGrouped)
    .navigationTitle("import.astrodx.title")
    .navigationBarTitleDisplayMode(.inline)
    .fileImporter(
      isPresented: $showingFileImporter,
      allowedContentTypes: [.data],
      allowsMultipleSelection: false
    ) { outcome in
      switch outcome {
      case .success(let urls):
        if let url = urls.first { model.selectCache(from: url) }
      case .failure:
        break
      }
    }
  }
}
