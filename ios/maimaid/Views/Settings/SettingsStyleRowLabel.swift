import SwiftUI

/// The icon + title pair every Settings row uses, so import sections can pick it up
/// without duplicating the colours.
struct SettingsStyleRowLabel: View {
  let icon: String
  let color: Color
  let title: LocalizedStringKey

  var body: some View {
    HStack(spacing: 12) {
      Image(systemName: icon)
        .font(.system(size: 14))
        .foregroundStyle(.white)
        .frame(width: 28, height: 28)
        .background(color, in: RoundedRectangle(cornerRadius: 6))

      Text(title)
        .font(.system(size: 16))
    }
  }
}
