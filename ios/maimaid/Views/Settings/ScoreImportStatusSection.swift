import SwiftUI

struct ScoreImportStatusSection: View {
  let model: LocalScoreImportViewModel

  var body: some View {
    Section("import.status.header") {
      VStack(alignment: .leading) {
        if model.isBusy {
          ProgressView(
            model.userCode.isEmpty ? "import.status.syncing" : "import.df.oauth.waiting"
          )
          .foregroundStyle(.secondary)
        }
        if !model.userCode.isEmpty {
          Text(model.userCode)
            .font(.title.monospaced())
            .textSelection(.enabled)
        }
        if !model.status.isEmpty {
          Label {
            Text(model.status)
              .fixedSize(horizontal: false, vertical: true)
              .textSelection(.enabled)
          } icon: {
            Image(systemName: model.hasError ? "xmark.circle.fill" : "checkmark.circle.fill")
          }
          .foregroundStyle(model.hasError ? .red : .cyan)
        }
      }
      .padding(.vertical, 4)
      if model.isBusy {
        Button("userProfile.cancel", systemImage: "xmark", role: .cancel, action: model.cancel)
      }
    }
  }
}
