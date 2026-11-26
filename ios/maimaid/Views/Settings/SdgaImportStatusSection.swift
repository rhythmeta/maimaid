import SwiftUI

struct SdgaImportStatusSection: View {
  let model: SdgaImportViewModel

  var body: some View {
    Section("import.status.header") {
      VStack(alignment: .leading, spacing: 6) {
        // No loading row here: the import button already shows the spinner.
        if let message = model.errorMessage {
          Label {
            Text(message)
              .fixedSize(horizontal: false, vertical: true)
              .textSelection(.enabled)
          } icon: {
            Image(systemName: "xmark.circle.fill")
          }
          .foregroundStyle(.red)
        } else if let result = model.result {
          Label {
            Text(result.updatedCount > 0
              ? "import.sdga.result.imported"
              : "import.sdga.result.noChanges")
          } icon: {
            Image(systemName: "checkmark.circle.fill")
          }
          .foregroundStyle(.cyan)
          .onAppear { _ = result }
        }

        if let result = model.result {
          Text(
            "import.local.result \(result.fetchedCount) \(result.updatedCount) \(result.skippedCount)"
          )
          .font(.caption)
          .foregroundStyle(.secondary)
        }
      }
      .padding(.vertical, 4)
    }
  }
}
