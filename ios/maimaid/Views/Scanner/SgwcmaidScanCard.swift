import SwiftUI

struct SgwcmaidScanCard: View {
  let code: String

  var body: some View {
    HStack(spacing: 12) {
      Image(systemName: "qrcode")
        .font(.title3)
        .foregroundStyle(.tint)
        .accessibilityHidden(true)
      VStack(alignment: .leading, spacing: 2) {
        Text("scanner.sgwcmaid.card.title")
          .font(.subheadline.bold())
        Text(code)
          .font(.caption.monospaced())
          .foregroundStyle(.secondary)
          .lineLimit(1)
      }
      Spacer(minLength: 8)
      Image(systemName: "chevron.right")
        .font(.caption.bold())
        .foregroundStyle(.tertiary)
        .accessibilityHidden(true)
    }
    .padding(.horizontal, 16)
    .padding(.vertical, 10)
    .background(.ultraThinMaterial, in: .rect(cornerRadius: 16))
    .overlay {
      RoundedRectangle(cornerRadius: 16)
        .strokeBorder(.tint.opacity(0.35))
    }
    .contentShape(.rect(cornerRadius: 16))
    .padding(.horizontal, 20)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(Text("scanner.sgwcmaid.card.title"))
    .accessibilityValue(Text(code))
  }
}
