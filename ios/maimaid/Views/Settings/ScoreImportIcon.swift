import SwiftUI

struct ScoreImportIcon: View {
  let symbol: String
  let tint: Color
  @ScaledMetric(relativeTo: .body) private var size = 32

  var body: some View {
    Image(systemName: symbol)
      .font(.subheadline.bold())
      .foregroundStyle(.white)
      .frame(width: size, height: size)
      .background(tint.gradient, in: .rect(cornerRadius: 10))
      .accessibilityHidden(true)
  }
}
