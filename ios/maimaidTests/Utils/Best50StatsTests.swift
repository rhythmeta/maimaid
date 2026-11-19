import Foundation
import Testing
@testable import maimaid

@MainActor
struct Best50StatsTests {
    @Test("Averages every entry but only counts recorded DX scores")
    func averagesEveryEntryButOnlyRecordedDxScores() {
        let stats = Best50StatsCalculator.calculate([
            entry(achievement: 100.0, dxScore: 3_000, maxDxScore: 3_000),
            entry(achievement: 99.0, dxScore: 2_400, maxDxScore: 3_000),
            entry(achievement: 97.0, dxScore: 0, maxDxScore: 3_000)
        ])

        #expect(stats != nil)
        #expect(abs((stats?.averageAchievement ?? 0) - (100.0 + 99.0 + 97.0) / 3) < 0.0001)
        #expect(abs((stats?.dxAchievement ?? 0) - 90.0) < 0.0001)
        #expect(stats?.dxStars == RatingUtils.dxStars(rate: 0.9))
        #expect(stats?.hasDxData == true)
    }

    @Test("Missing DX scores do not drag the average down")
    func missingDxScoresAreIgnored() {
        let stats = Best50StatsCalculator.calculate([
            entry(achievement: 100.0, dxScore: 2_910, maxDxScore: 3_000),
            entry(achievement: 100.0, dxScore: 0, maxDxScore: 3_000)
        ])

        #expect(abs((stats?.dxAchievement ?? 0) - 97.0) < 0.0001)
        #expect(stats?.dxStars == 5)
        #expect(stats?.hasDxData == true)
    }

    @Test("Buckets without DX data report no stars")
    func noDxData() {
        let stats = Best50StatsCalculator.calculate([
            entry(achievement: 100.0, dxScore: 0, maxDxScore: 3_000)
        ])

        #expect(stats?.hasDxData == false)
        #expect(stats?.dxAchievement == 0)
        #expect(stats?.dxStars == 0)
    }

    @Test("Empty buckets have no stats")
    func emptyBucket() {
        #expect(Best50StatsCalculator.calculate([]) == nil)
    }

    private func entry(
        achievement: Double,
        dxScore: Int,
        maxDxScore: Int
    ) -> RatingUtils.RatingEntry {
        RatingUtils.RatingEntry(
            songId: 1,
            songIdentifier: "song",
            songTitle: "Song",
            imageName: "song.png",
            achievement: achievement,
            rating: 300,
            level: 14.0,
            diff: "master",
            type: "dx",
            dxScore: dxScore,
            maxDxScore: maxDxScore,
            fc: nil,
            fs: nil
        )
    }
}
