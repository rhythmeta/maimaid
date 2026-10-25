import SwiftUI

struct SgwcmaidImportInputSection: View {
  @Bindable var model: SgwcmaidImportViewModel
  let isEnabled: Bool
  let startImport: () -> Void

  var body: some View {
    Section {
      TextField("import.sgwcmaid.code.placeholder", text: $model.code, axis: .vertical)
        .textInputAutocapitalization(.characters)
        .autocorrectionDisabled()
        .lineLimit(3...6)
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
      .disabled(!isEnabled || model.isImporting || isCodeEmpty)
      .listRowSeparator(.hidden)
    } header: {
      Text("import.sgwcmaid.input.header")
    } footer: {
      Text("import.sgwcmaid.input.footer")
    }
  }

  private var isCodeEmpty: Bool {
    model.code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
  }

  private var importTitle: LocalizedStringKey {
    model.isImporting ? "import.status.syncing" : "import.sgwcmaid.action.import"
  }
}
