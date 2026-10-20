import Foundation
import Testing
@testable import maimaid

struct ServerVersionCalculatorTests {
    @Test("CN version follows game release date despite rereleases and preview songs")
    func cnVersionUsesVersionReleaseDates() {
        let versions = ["ORANGE PLUS", "PRiSM PLUS", "CiRCLE", "CiRCLE PLUS", "MAGiCAL"]
        let songs = [
            song(version: "ORANGE PLUS", releaseDate: "2026-10-16"),
            song(version: "PRiSM PLUS", releaseDate: "2025-06-01", cn: true),
            song(version: "PRiSM PLUS", releaseDate: "2025-08-01"),
            song(version: "CiRCLE", releaseDate: "2025-09-26", cn: true),
            song(version: "CiRCLE", releaseDate: "2025-09-26", cn: true),
            song(version: "CiRCLE", releaseDate: "2026-01-23", cn: true),
            song(version: "CiRCLE", releaseDate: "2026-01-23", cn: true),
            song(version: "CiRCLE", releaseDate: "2026-01-23", cn: true)
        ]
        let releaseDates = [
            "ORANGE PLUS": "2015-03-19",
            "PRiSM PLUS": "2025-03-13",
            "CiRCLE": "2025-09-18",
            "CiRCLE PLUS": "2026-03-19",
            "MAGiCAL": "2026-09-17"
        ]

        let version = ServerVersionCalculator.latestVersion(
            for: "cn",
            songs: songs,
            sequence: versions,
            cutoff: "2025-07-20",
            versionReleaseDates: releaseDates,
            fallback: "MAGiCAL"
        )

        #expect(version == "PRiSM PLUS")
    }

    @Test("CN falls back to song release dates when version metadata is incomplete")
    func cnVersionFallsBackWithoutCompleteVersionDates() {
        let songs = [
            song(version: "ORANGE PLUS", releaseDate: "2015-03-19", cn: true),
            song(version: "ORANGE PLUS", releaseDate: "2026-10-16"),
            song(version: "PRiSM PLUS", releaseDate: "2025-06-01", cn: true)
        ]

        let version = ServerVersionCalculator.latestVersion(
            for: "cn",
            songs: songs,
            sequence: ["ORANGE PLUS", "PRiSM PLUS"],
            cutoff: "2025-07-20",
            versionReleaseDates: ["ORANGE PLUS": "2015-03-19"],
            fallback: "PRiSM PLUS"
        )

        #expect(version == "ORANGE PLUS")
    }

    @Test("Version metadata accepts valid ISO dates and rejects malformed dates")
    func parsesVersionReleaseDates() {
        let data = Data(
            #"[{"version":"PRiSM PLUS","releaseDate":"2025-03-13"},{"version":"CiRCLE","releaseDate":"2025-02-30"}]"#.utf8
        )

        #expect(ServerVersionCalculator.releaseDates(from: data) == ["PRiSM PLUS": "2025-03-13"])
    }

    private func song(
        version: String,
        releaseDate: String,
        cn: Bool = false
    ) -> ServerVersionSongSnapshot {
        ServerVersionSongSnapshot(
            version: version,
            category: "maimai",
            releaseDate: releaseDate,
            hasAnyRegion: true,
            hasJpRegion: true,
            hasIntlRegion: false,
            hasCnRegion: cn
        )
    }
}
