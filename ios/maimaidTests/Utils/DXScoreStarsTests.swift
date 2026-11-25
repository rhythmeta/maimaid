import Testing
@testable import maimaid

@MainActor
struct DXScoreStarsTests {
    @Test("Star progress shows the margin above and gap below the thresholds")
    func progressBetweenThresholds() {
        #expect(DXScoreStars.progress(score: 1_850, maximum: 2_000) == DXScoreStarProgress(
            stars: 2, pointsAboveMinimum: 50, pointsToNextStar: 10
        ))
        #expect(DXScoreStars.progress(score: 1_000, maximum: 2_000) == DXScoreStarProgress(
            stars: 0, pointsAboveMinimum: 1_000, pointsToNextStar: 700
        ))
        #expect(DXScoreStars.progress(score: 2_000, maximum: 2_000) == DXScoreStarProgress(
            stars: 5, pointsAboveMinimum: 60, pointsToNextStar: nil
        ))
    }

    @Test("Star thresholds round up to whole scores", arguments: [
        (0, 0), (1, 1_701), (2, 1_801), (3, 1_861), (4, 1_901), (5, 1_941)
    ])
    func roundedThresholds(stars: Int, minimum: Int) throws {
        let nextGaps: [Int?] = [1_701, 100, 60, 40, 40, nil]
        #expect(DXScoreStars.progress(score: minimum, maximum: 2_001) == DXScoreStarProgress(
            stars: stars, pointsAboveMinimum: 0, pointsToNextStar: nextGaps[stars]
        ))
        if stars > 0 {
            let below = try #require(DXScoreStars.progress(score: minimum - 1, maximum: 2_001))
            #expect(below.stars == stars - 1)
            #expect(below.pointsToNextStar == 1)
        }
    }

    @Test("Progress requires a valid maximum and nonnegative score", arguments: [(1_000, 0), (1_000, -1), (-1, 2_000)])
    func invalidScores(score: Int, maximum: Int) {
        #expect(DXScoreStars.progress(score: score, maximum: maximum) == nil)
    }

    @Test("Threshold arithmetic does not overflow")
    func largeMaximum() {
        let maximum = Int(Int32.max)
        #expect(DXScoreStars.progress(score: maximum, maximum: maximum) == DXScoreStarProgress(
            stars: 5, pointsAboveMinimum: 64_424_509, pointsToNextStar: nil
        ))
    }
}
