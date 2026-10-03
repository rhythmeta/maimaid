import SwiftUI

struct SongDetailHistorySortOption: View {
    let title: LocalizedStringKey
    let selected: Bool
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.caption)
                .foregroundStyle(selected ? tint : .secondary)
                .padding(.horizontal, 7)
                .padding(.vertical, 5)
                .background(selected ? tint.opacity(0.12) : .clear, in: .rect(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
