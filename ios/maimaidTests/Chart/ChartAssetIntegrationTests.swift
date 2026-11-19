import Foundation
import Testing
@testable import maimaid

/// End-to-end checks against the published chart-asset bundle: index → maidata → parse → track
/// download/verify → embedded cover art.
///
/// These tests need the network and a multi-megabyte download, so they only run when
/// `MAIMAID_NETWORK_TESTS=1` is set in the test environment.
@MainActor
struct ChartAssetIntegrationTests {
    private nonisolated static let networkEnabled =
        ProcessInfo.processInfo.environment["MAIMAID_NETWORK_TESTS"] == "1"

    /// Provider song id of the chart used by the Android reference build
    /// (君の知らない物語, master: 312 notes, BPM 165, 14 breaks).
    private static let providerSongID = 10_181
    private static let difficultySlot = 5

    @Test(.enabled(if: networkEnabled, "Set MAIMAID_NETWORK_TESTS=1 to run network integration tests"))
    func downloadsParsesAndCachesARealChart() async throws {
        let index = try await ChartAssetClient.fetchIndex()
        let entry = try #require(index.entry(for: Self.providerSongID),
                                 "chart asset index has no entry for the reference song")
        let maidataAsset = try #require(entry.files.maidata)
        let maidata = try await ChartAssetClient.downloadMaidata(maidataAsset)

        // The published maidata is CRLF; parsing it must reproduce the reference note count.
        let chart = try SimaiParser.parse(maidata, difficulty: Self.difficultySlot)
        #expect(chart.notes.count == 312)
        #expect(chart.bpm == 165)
        #expect(chart.availableDifficulties == [2, 3, 4, 5])

        let prepared = PreparedChart.build(chart: chart)
        #expect(prepared.breakCompletionTimes.count == 14)
        #expect(prepared.breakNoExCompletionTimes.count == 14)
        let scene = ChartSceneGraph(chart: chart)
        #expect(scene.playbackDurationMs > chart.endTimeMs)
        #expect(ChartAudioEvents.prepare(chart.notes).count > 0)

        // The track is downloaded, digest-verified and cached; its ID3 tag carries the cover art.
        let audioAsset = try #require(entry.files.audio)
        let cache = ChartTrackCache.shared
        let cached = try await cache.trackFile(for: audioAsset) { _ in }
        #expect(cache.cachedFile(for: audioAsset) == cached)
        #expect(try ChartAssetClient.sha256Hex(ofFile: cached) == audioAsset.sha256.lowercased())
        #expect(try FileManager.default.attributesOfItem(atPath: cached.path)[.size] as? Int64
            == audioAsset.size)

        let cover = try #require(Mp3CoverArt.read(fileURL: cached), "track has no embedded cover")
        #expect(cover.count > 1_000)
        #expect(ChartImageLoader.image(from: cover) != nil)

        // A second request must reuse the cached file instead of transferring it again.
        let again = try await cache.trackFile(for: audioAsset) { _ in }
        #expect(again == cached)

        // The PCM engine decodes the cached track and advances the chart clock while it plays.
        let engine = ChartAudioEngine()
        let ready = await engine.prepare(fileURL: cached, key: audioAsset.sha256)
        #expect(ready)
        #expect(engine.isReady)
        #expect(engine.durationMs > 60_000)
        engine.applyConfiguration(
            events: ChartAudioEvents.prepare(chart.notes),
            leadInMs: chart.leadInMs,
            durationMs: scene.playbackDurationMs,
            musicOffsetMs: 0,
            sfxOffsetMs: 0
        )
        engine.setMusicVolume(0.2)
        engine.setAnswerVolume(0)
        engine.setJudgeVolume(0)
        engine.seek(toChartTimeMs: 5_000, playAfterSeek: false)
        engine.play()
        try await Task.sleep(for: .milliseconds(800))
        let position = engine.chartPositionMs
        #expect(position > 5_100, "engine clock did not advance: \(position)")
        engine.pause()
        engine.release()
    }
}

/// Checks that the bundled runtime assets the audio engine needs are present in the app bundle.
@MainActor
struct ChartSfxBundleTests {
    @Test
    func loadsEveryJudgeAndAnswerSample() {
        let bank = ChartSfxSampleBank.load(sampleRate: 48_000, bundle: .main)
        for kind in ChartSfxKind.allCases {
            let sample = bank.sample(kind)
            #expect(sample?.isEmpty == false, "missing bundled sample \(kind.assetName)")
        }
        // Every sample carries audible frames, and the touch-hold loop is the longest one (it is
        // looped for the duration of the hold).
        var lengths: [ChartSfxKind: Int] = [:]
        for kind in ChartSfxKind.allCases {
            guard let sample = bank.sample(kind) else { continue }
            #expect(sample.count > 1_000, "\(kind.assetName) is unexpectedly short")
            lengths[kind] = sample.count
        }
        if let hold = lengths[.touchHold] {
            #expect(hold == lengths.values.max())
        }
    }

    @Test
    func resolvesTheBundledSensorLayout() {
        #expect(ChartImageLoader.sensorImage() != nil)
    }
}
