import SwiftUI

struct BestTableSummaryData {
    let b35: [RatingUtils.RatingEntry]
    let b15: [RatingUtils.RatingEntry]

    var total: Int {
        b35Sum + b15Sum
    }

    var b35Sum: Int {
        b35.reduce(0) { $0 + $1.rating }
    }

    var b15Sum: Int {
        b15.reduce(0) { $0 + $1.rating }
    }
}

struct BestTableSummaryCard: View {
    let data: BestTableSummaryData

    @Environment(\.colorScheme) private var colorScheme

    private let newAccent = Color(hex: "#FF6B6B")
    private let oldAccent = Color(hex: "#4ECDC4")
    private let starAccent = Color(hex: "#FFB300")

    private var totalStats: Best50SummaryStats? {
        Best50StatsCalculator.calculate(data.b15 + data.b35)
    }

    private var newStats: Best50SummaryStats? {
        Best50StatsCalculator.calculate(data.b15)
    }

    private var oldStats: Best50SummaryStats? {
        Best50StatsCalculator.calculate(data.b35)
    }

    private var newFraction: CGFloat {
        guard data.total > 0 else { return 0 }
        return CGFloat(data.b15Sum) / CGFloat(data.total)
    }

    private var oldFraction: CGFloat {
        data.total > 0 ? 1 - newFraction : 0
    }

    private var averageRating: Double {
        let count = data.b15.count + data.b35.count
        guard count > 0 else { return 0 }
        return Double(data.total) / Double(count)
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("bestTable.rating")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("\(data.total)")
                        .font(.system(size: 34, weight: .black, design: .rounded))
                        .foregroundStyle(ThemeUtils.ratingGradient(data.total))
                        .brightness(colorScheme == .light ? -0.28 : 0)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 3) {
                    Text(String(localized: "bestTable.old.count \(data.b35Sum)"))
                    Text(String(localized: "bestTable.new.count \(data.b15Sum)"))
                    Text(
                        String(
                            localized: "bestTable.averageRating.line \(averageRating.formatted(.number.precision(.fractionLength(1))))"
                        )
                    )
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            VStack(spacing: 6) {
                shareBar

                HStack {
                    Text(
                        verbatim: percentText(
                            label: String(localized: "bestTable.settings.new"),
                            percent: newFraction
                        )
                    )
                    .foregroundStyle(newAccent)
                    Spacer()
                    Text(
                        verbatim: percentText(
                            label: String(localized: "bestTable.settings.old"),
                            percent: 1 - newFraction
                        )
                    )
                    .foregroundStyle(oldAccent)
                }
                .font(.caption2.bold())
            }

            VStack(spacing: 6) {
                HStack(spacing: 8) {
                    Spacer()
                        .frame(width: 40)
                    Text("bestTable.stats.average")
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    Text("bestTable.stats.dx")
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    Text("bestTable.stats.stars")
                        .frame(width: 40, alignment: .trailing)
                }
                .font(.caption2.bold())
                .foregroundStyle(.secondary)
                .padding(.horizontal, 8)

                statsRow(
                    count: data.b15.count + data.b35.count,
                    stats: totalStats,
                    background: Color.primary.opacity(0.05)
                )
                statsRow(
                    count: data.b15.count,
                    stats: newStats,
                    background: newAccent.opacity(0.12)
                )
                statsRow(
                    count: data.b35.count,
                    stats: oldStats,
                    background: oldAccent.opacity(0.12)
                )
            }
        }
        .padding(18)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 18))
    }

    private var shareBar: some View {
        GeometryReader { proxy in
            HStack(spacing: 0) {
                if data.total > 0 {
                    if newFraction > 0 {
                        Rectangle()
                            .fill(newAccent)
                            .frame(width: proxy.size.width * newFraction)
                    }
                    if oldFraction > 0 {
                        Rectangle()
                            .fill(oldAccent)
                    }
                }
            }
            .frame(maxHeight: .infinity)
        }
        .frame(height: 6)
        .background(Color.primary.opacity(0.08), in: .capsule)
        .clipShape(.capsule)
    }

    private func percentText(label: String, percent: CGFloat) -> String {
        let value = Double(percent * 100).formatted(.number.precision(.fractionLength(1)))
        return "\(label) \(value)%"
    }

    private func statsRow(
        count: Int,
        stats: Best50SummaryStats?,
        background: Color
    ) -> some View {
        HStack(spacing: 8) {
            Text("B\(count)")
                .frame(width: 40, alignment: .leading)
            Text(achievementText(stats))
                .frame(maxWidth: .infinity, alignment: .trailing)
            Text(dxAchievementText(stats))
                .frame(maxWidth: .infinity, alignment: .trailing)
            Group {
                if let stats, stats.hasDxData {
                    ScoreTintBadge(
                        text: "\(stats.dxStars) ★",
                        tint: stats.dxStars > 0 ? starAccent : .secondary,
                        font: .caption2.bold()
                    )
                }
            }
            .frame(width: 40, alignment: .trailing)
        }
        .font(.caption.monospaced())
        .foregroundStyle(.secondary)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(background, in: .rect(cornerRadius: 8))
    }

    private func achievementText(_ stats: Best50SummaryStats?) -> String {
        guard let stats else { return "—" }
        return stats.averageAchievement.formatted(.number.precision(.fractionLength(4))) + "%"
    }

    private func dxAchievementText(_ stats: Best50SummaryStats?) -> String {
        guard let stats, stats.hasDxData else { return "—" }
        return stats.dxAchievement.formatted(.number.precision(.fractionLength(2))) + "%"
    }
}
