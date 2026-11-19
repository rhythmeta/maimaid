import SwiftUI

/// Difficulty tabs for the previewed song. Only slots actually published in the maidata appear here.
struct ChartPreviewDifficultySelector: View {
    let options: [PreviewChartOption]
    let selectedKey: String
    let onSelect: (String) -> Void

    var body: some View {
        let multipleTypes = Set(options.map(\.displayType)).count > 1
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(options) { option in
                    Button {
                        onSelect(option.id)
                    } label: {
                        Text(label(for: option, multipleTypes: multipleTypes))
                            .font(.footnote.weight(.semibold))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .background(
                                option.id == selectedKey
                                    ? Color.accentColor.opacity(0.18)
                                    : Color(.secondarySystemBackground),
                                in: .capsule
                            )
                            .foregroundStyle(option.id == selectedKey ? Color.accentColor : .primary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(option.id == selectedKey ? [.isSelected] : [])
                }
            }
            .padding(.vertical, 2)
        }
        .scrollIndicators(.hidden)
    }

    private func label(for option: PreviewChartOption, multipleTypes: Bool) -> String {
        var text = ""
        if multipleTypes {
            switch option.sheet.type.lowercased() {
            case "dx": text = "DX "
            case let type where type.contains("utage"): text = "宴 "
            default: text = "STD "
            }
        }
        if option.sheet.type.localizedCaseInsensitiveContains("utage") {
            let difficulty = option.sheet.difficulty.trimmingCharacters(in: .whitespacesAndNewlines)
            text += difficulty.isEmpty ? "宴" : difficulty
        } else {
            text += option.shortLabel
        }
        return text
    }
}
