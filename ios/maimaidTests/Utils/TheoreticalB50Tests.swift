import Foundation
import Testing
@testable import maimaid

@MainActor
struct TheoreticalB50Tests {
    // Keep the sequence identical to RatingUtilsTests so the shared defaults
    // never flip between different values while suites run in parallel.
    private let versions = ["BUDDiES", "PRiSM", "PRiSM PLUS", "CiRCLE", "CiRCLE PLUS", "MAGiCAL"]

    init() {
        UserDefaults.app.maimaiVersionSequence = versions
    }

    @Test("Theoretical entries use 101% AP+ and version constants")
    func perfectPlayEntry() {
        UserDefaults.app.maimaiVersionSequence = versions
        let result = RatingUtils.calculateTheoreticalB50(
            input: input([
                song(identifier: "new", version: "CiRCLE", level: 13.7)
            ]),
            b35Count: 35,
            b15Count: 15,
            latestVersion: "CiRCLE"
        )

        #expect(result.b15.count == 1)
        #expect(result.b35.isEmpty)
        let entry = result.b15[0]
        #expect(entry.achievement == 101.0)
        #expect(entry.fc == "app")
        #expect(entry.fs == "fsdp")
        #expect(entry.dxScore == 3_000)
        #expect(entry.level == 13.7)
        #expect(
            entry.rating == RatingUtils.calculateRating(
                internalLevel: 13.7,
                achievement: 101.0,
                fc: "app",
                afterCircle: true
            )
        )
        #expect(result.total == entry.rating)
    }

    @Test("Buckets are split and capped by the requested counts")
    func bucketsAndCounts() {
        UserDefaults.app.maimaiVersionSequence = versions
        let result = RatingUtils.calculateTheoreticalB50(
            input: input([
                song(identifier: "old0", version: "BUDDiES", level: 10.0),
                song(identifier: "old1", version: "BUDDiES", level: 11.0),
                song(identifier: "old2", version: "BUDDiES", level: 12.0),
                song(identifier: "new0", version: "CiRCLE", level: 13.0),
                song(identifier: "new1", version: "CiRCLE", level: 14.0)
            ]),
            b35Count: 2,
            b15Count: 1,
            latestVersion: "CiRCLE"
        )

        #expect(result.b35.map(\.songIdentifier) == ["old2", "old1"])
        #expect(result.b15.map(\.songIdentifier) == ["new1"])
        #expect(result.b35.map(\.level) == [12.0, 11.0])
        #expect(result.b15.map(\.level) == [14.0])
    }

    @Test("Songs newer than the selected version are excluded")
    func futureSongsExcluded() {
        UserDefaults.app.maimaiVersionSequence = versions
        let result = RatingUtils.calculateTheoreticalB50(
            input: input([
                song(identifier: "current", version: "CiRCLE", level: 13.0),
                song(identifier: "future", version: "MAGiCAL", level: 14.5)
            ]),
            b35Count: 35,
            b15Count: 15,
            latestVersion: "CiRCLE"
        )

        #expect(result.b15.map(\.songIdentifier) == ["current"])
        #expect(result.b35.isEmpty)
    }

    private func input(_ songs: [RatingUtils.SongCalculationData]) -> RatingUtils.CalculationInput {
        RatingUtils.CalculationInput(
            songs: songs,
            userProfileId: nil,
            server: .jp,
            scoreMap: [:]
        )
    }

    private func song(
        identifier: String,
        version: String,
        level: Double
    ) -> RatingUtils.SongCalculationData {
        RatingUtils.SongCalculationData(
            songId: 1,
            songIdentifier: identifier,
            title: identifier,
            artist: "",
            imageName: "\(identifier).png",
            version: version,
            category: "maimai",
            isLocked: false,
            sheets: [
                RatingUtils.SheetCalculationData(
                    songIdentifier: identifier,
                    type: "dx",
                    difficulty: "master",
                    version: version,
                    total: 1_000,
                    calculationLevel: level,
                    isPlayable: true
                )
            ]
        )
    }
}
