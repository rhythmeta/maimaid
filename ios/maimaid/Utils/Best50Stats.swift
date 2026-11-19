import Foundation

/// Aggregate achievement / DX-score metrics for a Best 50 bucket.
struct Best50SummaryStats: Equatable, Sendable {
    let averageAchievement: Double
    /// Average DX-score rate in percent (0...100).
    let dxAchievement: Double
    let dxStars: Int
    let hasDxData: Bool
}

enum Best50StatsCalculator {
    /// Entries without a recorded DX score are excluded from the DX average entirely
    /// instead of counting as zero.
    static func calculate(_ entries: [RatingUtils.RatingEntry]) -> Best50SummaryStats? {
        guard !entries.isEmpty else { return nil }

        let dxEntries = entries.filter { $0.dxScore > 0 && $0.maxDxScore > 0 }
        let averageDx = dxEntries.isEmpty
            ? 0
            : dxEntries.reduce(0.0) { partial, entry in
                partial + Double(entry.dxScore) / Double(entry.maxDxScore)
            } / Double(dxEntries.count)

        return Best50SummaryStats(
            averageAchievement: entries.reduce(0.0) { $0 + $1.achievement } / Double(entries.count),
            dxAchievement: averageDx * 100,
            dxStars: dxEntries.isEmpty ? 0 : RatingUtils.dxStars(rate: averageDx),
            hasDxData: !dxEntries.isEmpty
        )
    }
}

extension RatingUtils {
    /// In-game DX star count for a chart's DX score.
    static func dxStars(dxScore: Int, maxDxScore: Int) -> Int {
        guard maxDxScore > 0 else { return 0 }
        return dxStars(rate: Double(dxScore) / Double(maxDxScore))
    }

    /// The same DX star thresholds for an already averaged achievement rate (0...1).
    static func dxStars(rate: Double) -> Int {
        switch rate {
        case 0.97...: return 5
        case 0.95...: return 4
        case 0.93...: return 3
        case 0.90...: return 2
        case 0.85...: return 1
        default: return 0
        }
    }
}
