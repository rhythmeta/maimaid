import SwiftUI

struct BestTableEntryRow: View {
    let entry: RatingUtils.RatingEntry

    @Environment(\.colorScheme) private var colorScheme

    private var dxStars: Int {
        RatingUtils.dxStars(dxScore: entry.dxScore, maxDxScore: entry.maxDxScore)
    }

    var body: some View {
        HStack(spacing: 14) {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(ThemeUtils.colorForDifficulty(entry.diff, entry.type, colorScheme))
                    .frame(width: 4)
                    .padding(.vertical, 8)

                SongJacketView(
                    imageName: entry.imageName ?? "",
                    size: 56,
                    cornerRadius: 10,
                    useThumbnail: true
                )
            }

            VStack(alignment: .leading, spacing: 4) {
                MarqueeText(
                    text: entry.songTitle,
                    font: .system(size: 15, weight: .bold),
                    fontWeight: .bold,
                    color: .primary
                )
                .frame(height: 20)

                HStack(spacing: 6) {
                    let rank = RatingUtils.calculateRank(achievement: entry.achievement)
                    Text(rank)
                        .font(.system(size: 13, weight: .black, design: .rounded))
                        .foregroundStyle(RatingUtils.colorForRank(rank))

                    Text("\(entry.achievement, format: .number.precision(.fractionLength(4)))%")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(.secondary)

                    if entry.dxScore > 0 {
                        HStack(spacing: 2) {
                            Image(systemName: "star.fill")
                                .font(.system(size: 8))
                            Text("\(entry.dxScore)")
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                        }
                        .fixedSize()
                        .foregroundStyle(.yellow)
                    }
                }

                HStack(spacing: 4) {
                    ScoreTintBadge(
                        text: entry.type.uppercased(),
                        tint: entry.type.uppercased() == "DX" ? .orange : .blue
                    )

                    if dxStars > 0 {
                        ScoreTintBadge(text: "\(dxStars) ★", tint: .yellow)
                    }

                    if let fc = entry.fc, !fc.isEmpty {
                        ScoreTintBadge(text: ThemeUtils.normalizeFC(fc), tint: ThemeUtils.fcColor(fc))
                    }

                    if let fs = entry.fs, !fs.isEmpty {
                        ScoreTintBadge(text: ThemeUtils.normalizeFS(fs), tint: ThemeUtils.fsColor(fs))
                    }
                }
            }
            .frame(minHeight: 56, alignment: .leading)

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text("\(entry.rating)")
                    .font(.system(size: 18, weight: .black, design: .rounded))
                    .foregroundStyle(ThemeUtils.ratingGradient(entry.rating * 50))
                    .brightness(colorScheme == .light ? -0.28 : 0)
                Text("bestTable.base \(entry.level, specifier: "%.1f")")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            .fixedSize()
        }
        .padding(.vertical, 6)
    }
}
