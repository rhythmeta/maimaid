import SwiftUI

struct SgwcmaidImportStatusSection: View {
  let model: SgwcmaidImportViewModel

  var body: some View {
    Section("import.status.header") {
      VStack(alignment: .leading, spacing: 6) {
        if model.isImporting {
          ProgressView("import.sgwcmaid.status.fetching")
            .foregroundStyle(.secondary)
        } else if let message = model.errorMessage {
          Label {
            Text(message)
              .fixedSize(horizontal: false, vertical: true)
              .textSelection(.enabled)
          } icon: {
            Image(systemName: "xmark.circle.fill")
          }
          .foregroundStyle(.red)
        } else if let outcome = model.outcome {
          Label {
            Text(resultKey(for: outcome))
          } icon: {
            Image(systemName: "checkmark.circle.fill")
          }
          .foregroundStyle(.cyan)
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

  private func resultKey(for outcome: SgwcmaidImportOutcome) -> LocalizedStringKey {
    switch outcome {
    case .imported: "import.sgwcmaid.result.imported"
    case .noChanges: "import.sgwcmaid.result.noChanges"
    }
  }
}
