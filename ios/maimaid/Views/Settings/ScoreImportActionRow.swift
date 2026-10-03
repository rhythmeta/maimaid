import SwiftUI

struct ScoreImportActionRow: View {
  let title: LocalizedStringKey
  let symbol: String
  let tint: Color
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      HStack {
        ScoreImportIcon(symbol: symbol, tint: tint)
        Text(title)
          .foregroundStyle(.primary)
        Spacer()
        Image(systemName: "arrow.up.forward.app")
          .font(.subheadline.bold())
          .foregroundStyle(tint)
          .accessibilityHidden(true)
      }
      .contentShape(.rect)
    }
    .buttonStyle(.plain)
  }
}
