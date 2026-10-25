import SwiftUI

struct SgwcmaidImportSummarySection: View {
  var body: some View {
    Section {
      HStack(spacing: 16) {
        Image(systemName: "qrcode")
          .font(.largeTitle)
          .foregroundStyle(.tint)
          .frame(width: 48)
          .accessibilityHidden(true)
        VStack(alignment: .leading, spacing: 4) {
          Text("import.sgwcmaid.summary.title")
            .bold()
          Text("import.sgwcmaid.summary.description")
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
      }
      .padding(.vertical, 4)
    }
    .listRowSeparator(.hidden)
  }
}
