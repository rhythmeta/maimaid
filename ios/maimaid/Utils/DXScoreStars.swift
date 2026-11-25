import Foundation

enum DXScoreStars {
    private static let percentages = [0, 85, 90, 93, 95, 97]

    static func count(score: Int, maximum: Int) -> Int {
        guard maximum > 0 else { return 0 }
        return percentages.lastIndex { score >= minimumScore(percentage: $0, maximum: maximum) } ?? 0
    }

    static func progress(score: Int, maximum: Int) -> DXScoreStarProgress? {
        guard score >= 0, maximum > 0 else { return nil }
        let stars = count(score: score, maximum: maximum)
        let minimum = minimumScore(percentage: percentages[stars], maximum: maximum)
        let nextMinimum = stars < percentages.count - 1
            ? minimumScore(percentage: percentages[stars + 1], maximum: maximum)
            : nil
        return DXScoreStarProgress(
            stars: stars,
            pointsAboveMinimum: score - minimum,
            pointsToNextStar: nextMinimum.map { $0 - score }
        )
    }

    private static func minimumScore(percentage: Int, maximum: Int) -> Int {
        // Round up to a reachable integer score without overflowing the product.
        maximum / 100 * percentage + (maximum % 100 * percentage + 99) / 100
    }
}
