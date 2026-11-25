import Foundation
import Testing
@testable import maimaid

@MainActor
struct BestTableFilterSettingsTests {
    @Test("No star selection allows every star count", arguments: [0, 2_550, 2_700, 2_790, 2_850, 2_910])
    func emptySelection(dxScore: Int) {
        let settings = BestTableFilterSettings()

        #expect(settings.isEmpty)
        #expect(settings.matches(entry: entry(dxScore: dxScore), songCategory: nil))
    }

    @Test("Star selections match exact counts", arguments: [
        (0, 0), (2_549, 0), (2_550, 1), (2_699, 1),
        (2_700, 2), (2_789, 2), (2_790, 3), (2_849, 3),
        (2_850, 4), (2_909, 4), (2_910, 5), (3_000, 5)
    ])
    func exactStarCounts(dxScore: Int, expectedStars: Int) {
        let entry = entry(dxScore: dxScore)

        for stars in 0...5 {
            let settings = BestTableFilterSettings(selectedDXStars: [stars])
            #expect(settings.isEmpty == false)
            #expect(settings.matches(entry: entry, songCategory: nil) == (stars == expectedStars))
        }
    }

    @Test("Zero stars include missing DX data", arguments: [(0, 3_000), (3_000, 0)])
    func missingDXData(dxScore: Int, maxDxScore: Int) {
        let entry = entry(dxScore: dxScore, maxDxScore: maxDxScore)
        let zeroStars = BestTableFilterSettings(selectedDXStars: [0])
        let positiveStars = BestTableFilterSettings(selectedDXStars: [1, 2, 3, 4, 5])

        #expect(zeroStars.matches(entry: entry, songCategory: nil))
        #expect(positiveStars.matches(entry: entry, songCategory: nil) == false)
    }

    @Test("Multiple stars combine with the other filter dimensions")
    func combinedFilters() {
        let settings = BestTableFilterSettings(
            selectedDifficulties: ["master"],
            selectedCategories: ["maimai"],
            selectedTypes: ["dx"],
            selectedRanks: ["SSS+"],
            selectedDXStars: [0, 5],
            selectedFC: ["AP+"],
            selectedFS: ["FDX+"]
        )

        #expect(settings.matches(entry: entry(dxScore: 0), songCategory: "maimai"))
        #expect(settings.matches(entry: entry(dxScore: 2_910), songCategory: "maimai"))
        #expect(settings.matches(entry: entry(dxScore: 2_550), songCategory: "maimai") == false)
        #expect(settings.matches(entry: entry(dxScore: 2_910, achievement: 99), songCategory: "maimai") == false)
        #expect(settings.matches(entry: entry(dxScore: 2_910), songCategory: "GAME & VARIETY") == false)
    }

    @Test("Legacy saved filters retain their selections")
    func legacySettings() throws {
        let data = Data("""
        {
            "selectedDifficulties": ["master"],
            "selectedCategories": ["maimai"],
            "selectedTypes": ["dx"],
            "selectedRanks": ["SSS+"],
            "selectedFC": ["AP+"],
            "selectedFS": ["FDX+"]
        }
        """.utf8)

        let decoded = try JSONDecoder().decode(BestTableFilterSettings.self, from: data)

        #expect(decoded == BestTableFilterSettings(
            selectedDifficulties: ["master"],
            selectedCategories: ["maimai"],
            selectedTypes: ["dx"],
            selectedRanks: ["SSS+"],
            selectedFC: ["AP+"],
            selectedFS: ["FDX+"]
        ))
    }

    @Test("Saved settings preserve zero and multiple selected stars")
    func roundTrip() throws {
        let settings = BestTableFilterSettings(selectedRanks: ["SSS+"], selectedDXStars: [0, 1, 5])
        let data = try JSONEncoder().encode(settings)
        let decoded = try JSONDecoder().decode(BestTableFilterSettings.self, from: data)

        #expect(decoded == settings)
    }

    private func entry(
        dxScore: Int,
        maxDxScore: Int = 3_000,
        achievement: Double = 100.5
    ) -> RatingUtils.RatingEntry {
        RatingUtils.RatingEntry(
            songId: 1,
            songIdentifier: "song",
            songTitle: "Song",
            imageName: nil,
            achievement: achievement,
            rating: 300,
            level: 14,
            diff: "master",
            type: "dx",
            dxScore: dxScore,
            maxDxScore: maxDxScore,
            fc: "app",
            fs: "fsdp"
        )
    }
}
