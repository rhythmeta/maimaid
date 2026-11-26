import SwiftUI

/// The two credential fields plus the primary import action.
///
/// The layout mirrors `SgwcmaidImportInputSection`: one section, a bordered-prominent
/// full-width button, and a footer explaining the accepted formats.
struct SdgaImportInputSection: View {
  @Bindable var model: SdgaImportViewModel
  let isEnabled: Bool
  let startImport: () -> Void

  var body: some View {
    Section {
      TextField("import.sdga.card", text: $model.card, axis: .vertical)
        .textInputAutocapitalization(.characters)
        .autocorrectionDisabled()
        .lineLimit(1...2)
        .font(.body.monospaced())
        .disabled(model.isImporting)

      TextField(extraLabel, text: $model.extra)
        .textInputAutocapitalization(.characters)
        .autocorrectionDisabled()
        .font(.body.monospaced())
        .disabled(model.isImporting)

      Button(action: startImport) {
        HStack {
          Spacer()
          if model.isImporting {
            ProgressView()
          }
          Text(importTitle)
            .bold()
          Spacer()
        }
      }
      .buttonStyle(.borderedProminent)
      .controlSize(.large)
      .disabled(!isEnabled || model.isImporting || isCardEmpty)
      .listRowSeparator(.hidden)
    } header: {
      Text("import.sdga.credential")
    } footer: {
      Text("import.sdga.hint")
    }
  }

  /// The second field means PMm for an IDm and M1 UID for an access code.
  private var extraLabel: LocalizedStringKey {
    switch SdgaCredential.parse(model.card) {
    case .idm: "import.sdga.extraPMM"
    case .accessCode: "import.sdga.extraUID"
    default: "import.sdga.extraAny"
    }
  }

  private var isCardEmpty: Bool {
    model.card.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
  }

  private var importTitle: LocalizedStringKey {
    model.isImporting ? "import.status.syncing" : "import.sdga.action.import"
  }
}
