import SwiftUI

struct ConstantTablePreviewView: View {
    let sections: [ConstantTableExportView.ExportSection]
    let includesScores: Bool
    let songMap: [String: Song]

    @Environment(\.colorScheme) private var colorScheme

    private let columns = Array(
        repeating: GridItem(.flexible(), spacing: 8),
        count: 5
    )

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(sections.enumerated(), id: \.element.id) { index, section in
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(section.levelLabel)
                            .font(.headline.weight(.black))
                            .foregroundStyle(levelColor(for: section.levelLabel, index: index))

                        Spacer()

                        Text(
                            "\(section.entries.count.formatted()) "
                                + String(localized: "scoreQuery.export.charts")
                        )
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(section.entries) { entry in
                            NavigationLink(destination: songDetailDestination(entry: entry)) {
                                chartCell(entry)
                            }
                            .buttonStyle(.plain)
                            .frame(maxWidth: .infinity)
                        }
                    }
                    .padding(10)
                    .background(
                        index.isMultiple(of: 2)
                            ? Color.primary.opacity(0.045)
                            : Color.primary.opacity(0.025),
                        in: RoundedRectangle(cornerRadius: 14)
                    )
                }
            }
        }
        .padding(.top, 16)
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private func songDetailDestination(entry: ConstantTableExportView.Entry) -> some View {
        if let song = songMap[entry.songIdentifier] {
            SongDetailView(song: song, preferredType: entry.type)
        } else {
            ContentUnavailableView("scoreQuery.songNotFound", systemImage: "music.note")
        }
    }

    private func chartCell(_ entry: ConstantTableExportView.Entry) -> some View {
        ZStack(alignment: .bottomTrailing) {
            SongJacketView(
                imageName: entry.imageName,
                size: 64,
                cornerRadius: 9,
                useThumbnail: true
            )
            .overlay {
                RoundedRectangle(cornerRadius: 9)
                    .stroke(
                        ThemeUtils.colorForDifficulty(entry.difficulty, entry.type, colorScheme),
                        lineWidth: 1.5
                    )
            }

            if includesScores {
                VStack(alignment: .trailing, spacing: 1) {
                    badge(text: entry.rank, color: entry.rank.map(RatingUtils.colorForRank))
                    badge(text: entry.fc.map(ThemeUtils.normalizeFC), color: entry.fc.map(ThemeUtils.fcColor))
                    badge(text: entry.fs.map(ThemeUtils.normalizeFS), color: entry.fs.map(ThemeUtils.fsColor))
                }
                .padding(2)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(entry.songTitle))
    }

    @ViewBuilder
    private func badge(text: String?, color: Color?) -> some View {
        if let text, !text.isEmpty, let color {
            Text(text)
                .font(.system(size: 8, weight: .black, design: .rounded))
                .foregroundStyle(.white)
                .padding(.horizontal, 3)
                .padding(.vertical, 1)
                .background(color, in: RoundedRectangle(cornerRadius: 3))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
    }

    private func levelColor(for label: String, index: Int) -> Color {
        let tenths = Int(((Double(label) ?? 0) * 10).rounded()) % 10

        switch tenths {
        case 0, 5:
            return Color(hex: "#D34A63")
        case 1, 6:
            return Color(hex: "#4D78FF")
        case 2, 7:
            return Color(hex: "#3F9B74")
        case 3, 8:
            return Color(hex: "#B45BFF")
        default:
            return index.isMultiple(of: 2)
                ? Color(hex: "#C84A7B")
                : Color(hex: "#5489FF")
        }
    }
}
