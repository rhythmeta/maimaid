import Foundation
import Testing
@testable import maimaid

@MainActor
struct RatingUtilsTests {
    private let versions = [
        "BUDDiES",
        "PRiSM",
        "PRiSM PLUS",
        "CiRCLE",
        "CiRCLE PLUS",
        "MAGiCAL"
    ]

    /// 魔法仕掛けのファンタジア: MAGiCAL in JP, synced to the CN server while CN is
    /// still on PRiSM PLUS.
    private let cnSyncedSongId = 12025

    init() {
        // `determineSongCategory` reads the synced catalog order from user defaults.
        UserDefaults.app.maimaiVersionSequence = versions
    }

    @Test("CN current-version songs are new songs")
    func cnCurrentVersionSongIsNew() {
        #expect(category(songVersion: "PRiSM PLUS", latest: "PRiSM PLUS", server: .cn) == .b15)
    }

    @Test("CN older songs stay in the old-song bucket")
    func cnOlderSongIsOld() {
        #expect(category(songVersion: "PRiSM", latest: "PRiSM PLUS", server: .cn) == .b35)
        #expect(category(songVersion: "BUDDiES", latest: "PRiSM PLUS", server: .cn) == .b35)
    }

    @Test("CN preview charts stay old unless the CN server synced them")
    func cnPreviewChartIsOld() {
        // A CiRCLE chart playable in CN is not in the synced list, so it keeps the
        // previous behaviour: old-song bucket until CiRCLE becomes the CN version.
        #expect(category(songVersion: "CiRCLE", latest: "PRiSM PLUS", server: .cn, songId: 12_345) == .b35)
    }

    @Test("CN synced song is a new song despite its newer JP version")
    func cnSyncedSongIsNew() {
        #expect(
            category(songVersion: "MAGiCAL", latest: "PRiSM PLUS", server: .cn, songId: cnSyncedSongId) == .b15
        )
        // STD charts of the same song carry the base id.
        #expect(
            category(songVersion: "MAGiCAL", latest: "PRiSM PLUS", server: .cn, songId: 2_025) == .b15
        )
    }

    @Test("CN synced songs still need an active CN region")
    func cnSyncedSongRequiresRegion() {
        #expect(
            category(
                songVersion: "MAGiCAL",
                latest: "PRiSM PLUS",
                server: .cn,
                isRegionActive: false,
                songId: cnSyncedSongId
            ) == .excluded
        )
    }

    @Test("CN synced songs are only special-cased on the CN server")
    func cnSyncedSongKeepsJpRules() {
        #expect(
            category(songVersion: "MAGiCAL", latest: "PRiSM PLUS", server: .jp, songId: cnSyncedSongId) == .excluded
        )
    }

    @Test("JP and INTL include the current and previous version from CiRCLE onward")
    func jpIntlVersionWindow() {
        #expect(category(songVersion: "CiRCLE", latest: "CiRCLE", server: .jp) == .b15)
        #expect(category(songVersion: "PRiSM PLUS", latest: "CiRCLE", server: .jp) == .b15)
        #expect(category(songVersion: "PRiSM", latest: "CiRCLE", server: .jp) == .b35)
        #expect(category(songVersion: "CiRCLE PLUS", latest: "CiRCLE", server: .jp) == .excluded)
    }

    @Test("CN synced charts reach B15 through the Best 50 calculation")
    func cnSyncedChartCalculatesAsB15() async {
        let sheetId = "magical_dx_master"
        let song = RatingUtils.SongCalculationData(
            songId: cnSyncedSongId,
            songIdentifier: "magical",
            title: "魔法仕掛けのファンタジア",
            artist: "igel",
            imageName: "",
            version: "MAGiCAL",
            category: "maimai",
            isLocked: true,
            sheets: [
                RatingUtils.SheetCalculationData(
                    songIdentifier: "magical",
                    type: "dx",
                    difficulty: "master",
                    version: "MAGiCAL",
                    total: 1_000,
                    calculationLevel: 14.8,
                    isPlayable: true
                )
            ]
        )
        let input = RatingUtils.CalculationInput(
            songs: [song],
            userProfileId: nil,
            server: .cn,
            scoreMap: [
                sheetId: RatingUtils.ScoreCalculationData(
                    sheetId: sheetId,
                    rate: 100.0,
                    rank: "SSS",
                    dxScore: 3_000,
                    fc: "ap",
                    fs: nil
                )
            ]
        )

        let result = await RatingUtils.calculateB50(
            input: input,
            b35Count: 35,
            b15Count: 15,
            latestVersion: "PRiSM PLUS"
        )

        #expect(result.b15.map(\.songId) == [cnSyncedSongId])
        #expect(result.b35.isEmpty)
    }

    private func category(
        songVersion: String,
        latest: String,
        server: GameServer,
        isRegionActive: Bool = true,
        songId: Int? = nil
    ) -> RatingUtils.SongCategory {
        RatingUtils.determineSongCategory(
            songVersion: songVersion,
            latestServerVersion: latest,
            server: server,
            isRegionActive: isRegionActive,
            songId: songId
        )
    }
}
