import CoreGraphics
import Foundation
import ImageIO
import Testing
@testable import maimaid

/// Locks the pure chart-preview helpers: timing, geometry tables, prepared-chart bookkeeping and the
/// export package. These mirror the Android `ChartKeyDensityTest`, `PreparedChartTest`,
/// `ChartAssetIdResolverTest` and `BpmChangeFlashTest` contracts.
struct ChartPreviewCoreTests {

    // MARK: - TimingTimeline

    @Test
    func timingTimelineIntegratesBpmChanges() {
        let timeline = TimingTimeline(
            defaultBpm: 120,
            bpmEvents: [BpmEvent(timing: 4, bpm: 240)],
            divisorEvents: [DivisorEvent(timing: 2, divisor: 8)]
        )
        // 4 beats at 120 BPM = 2000 ms; the next beat runs at 240 BPM = 250 ms.
        #expect(abs(timeline.msFromBeat(4) - 2_000) < 0.001)
        #expect(abs(timeline.msFromBeat(5) - 2_250) < 0.001)
        #expect(abs(timeline.beatFromMs(2_250) - 5) < 0.001)
        #expect(timeline.bpmAtBeat(3) == 120)
        #expect(timeline.bpmAtBeat(4) == 240)
        #expect(timeline.divisorAtBeat(1) == 4)
        #expect(timeline.divisorAtBeat(2) == 8)
    }

    // MARK: - Key density

    @Test
    func keyDensityCountsHeadsOnly() {
        let notes = [
            makeNote(kind: .tap, timingMs: 100),
            makeNote(kind: .holdStart, timingMs: 200),
            makeNote(kind: .holdEnd, timingMs: 900),
            makeNote(kind: .slide, timingMs: 300),
            makeNote(kind: .touch, timingMs: 1_100),
            makeNote(kind: .touchHoldStart, timingMs: 1_200),
            makeNote(kind: .touchHoldEnd, timingMs: 1_900),
        ]
        let times = ChartKeyDensity.pressTimes(notes)
        #expect(times == [100, 200, 300, 1_100, 1_200])
        // Window is (150, 1150]: 200, 300 and 1100 count.
        #expect(ChartKeyDensity.keysPerSecond(times, currentTimeMs: 1_150) == 3)
        #expect(ChartKeyDensity.keysPerSecond(times, currentTimeMs: 50) == 0)
        #expect(ChartKeyDensity.keysPerSecond(times, currentTimeMs: 1_250, windowMs: 100) == 1)
    }

    // MARK: - Slide geometry

    @Test
    func slideGeometryReportsMirroringAndShapes() {
        #expect(SlideGeometry.relativeEnd(startPos: 3, endPos: 3) == 1)
        #expect(SlideGeometry.relativeEnd(startPos: 8, endPos: 1) == 2)
        #expect(SlideGeometry.mirrorKey(2) == 8)
        #expect(SlideGeometry.mirrorKey(5) == 5)

        let clockwise = SlideGeometry.detectSlideShape(type: .clockwise, startPos: 1, endPos: 3)
        #expect(clockwise?.shape == "circle3")
        #expect(clockwise?.mirror == false)

        let counterFromUpperHalf = SlideGeometry.detectSlideShape(
            type: .counterClockwise, startPos: 1, endPos: 3
        )
        #expect(counterFromUpperHalf?.shape == "circle7")
        #expect(counterFromUpperHalf?.mirror == true)

        #expect(SlideGeometry.detectSlideShape(type: .line, startPos: 1, endPos: 2) == nil)
        let line = SlideGeometry.detectSlideShape(type: .line, startPos: 1, endPos: 7)
        #expect(line?.shape == "line3")
        #expect(line?.mirror == true)

        #expect(SlideGeometry.detectSlideShape(type: .zCurve, startPos: 1, endPos: 5)?.mirror == true)
        // The via corner must be the left corner of the start button (1 + 5 steps ⇒ 7), not any
        // intermediate sensor.
        #expect(SlideGeometry.detectSlideShape(type: .via, startPos: 1, endPos: 3, midPos: 7)?.shape == "L3")
        #expect(SlideGeometry.detectSlideShape(type: .via, startPos: 1, endPos: 3, midPos: 6) == nil)
        #expect(SlideGeometry.detectSlideShape(type: .wifi, startPos: 2, endPos: 2)?.shape == "wifi")
    }

    @Test
    func slideStepTablesMatchArrowTotals() {
        // Every table starts at zero and is strictly increasing; the renderer indexes it by arrow.
        for (shape, steps) in SlideGeometry.stepMap {
            #expect(steps.first == 0, "\(shape) must start at 0")
            #expect(steps == steps.sorted(), "\(shape) must be ascending")
        }
        #expect(SlideGeometry.stepMap["wifi"] == [0, 1, 4, 6, 11])
    }

    // MARK: - Touch geometry

    @Test
    func touchGeometryMirrorsSensorsAroundTheCentre() {
        #expect(TouchGeometry.mirrorTouchSensor("A1", .none) == "A1")
        #expect(TouchGeometry.mirrorTouchSensor("C", .horizontal) == "C")
        #expect(TouchGeometry.mirrorTouchSensor("A1", .horizontal) == "A8")
        #expect(TouchGeometry.mirrorTouchSensor("E2", .vertical) == "E4")

        let centre = TouchGeometry.sensorPoint("C", centerX: 100, centerY: 100, radius: 50, mirrorMode: .none)
        #expect(centre.x == 100 && centre.y == 100)
    }

    // MARK: - Slide appearance

    @Test
    func slideTrackFadesInAroundTheAppearanceStart() {
        let before = SlideAppearance.trackAppearance(
            noteTimeMs: 2_000, approachTimeMs: 1_000, slideDelay: 0, currentTimeMs: 500, isWifi: false
        )
        #expect(before.alpha == 0)
        // Appearance starts at `note - approach + approach × 10 / 21` = 1476 ms, then fades in over
        // the 200 ms ramp.
        let midway = SlideAppearance.trackAppearance(
            noteTimeMs: 2_000, approachTimeMs: 1_000, slideDelay: 0, currentTimeMs: 1_600, isWifi: false
        )
        #expect(midway.alpha > 0 && midway.alpha <= 0.5)
        let judged = SlideAppearance.trackAppearance(
            noteTimeMs: 2_000, approachTimeMs: 1_000, slideDelay: 0, currentTimeMs: 1_980, isWifi: false
        )
        #expect(judged.alpha == 1)
    }

    // MARK: - Prepared chart

    @Test
    func preparedChartBuildsChordMetadataAndCounters() {
        var tapA = makeNote(kind: .tap, timingMs: 1_000)
        tapA.position = 1
        var tapB = makeNote(kind: .tap, timingMs: 1_000)
        tapB.position = 2
        var breakTap = makeNote(kind: .tap, timingMs: 2_000)
        breakTap.position = 3
        breakTap.isBreak = true
        var hold = makeNote(kind: .holdStart, timingMs: 3_000)
        hold.position = 4
        hold.duration = 1.0
        var holdEnd = makeNote(kind: .holdEnd, timingMs: 3_500)
        holdEnd.position = 4
        // The renderer looks the tail up by `"<position>:<hold head beat>"`.
        holdEnd.holdStartTiming = hold.timing
        var slide = makeNote(kind: .slide, timingMs: 4_000)
        slide.position = 5
        slide.durationMs = 500
        slide.allDurationMs = [500]
        slide.allDelayMs = [0]
        slide.allSlideBreaks = [false]

        let notes = [tapA, tapB, breakTap, hold, holdEnd, slide].enumerated().map { index, note in
            var copy = note
            copy.id = index
            return copy
        }
        let chart = Chart(bpm: 120, difficulty: 5, measures: 2, notes: notes)
        let prepared = PreparedChart.build(chart: chart)

        #expect(prepared.metaOf(notes[0]).simultaneousNoteCount == 2)
        #expect(prepared.metaOf(notes[1]).simultaneousNoteCount == 2)
        #expect(prepared.metaOf(notes[2]).noExBreakIndex == 1)
        #expect(prepared.holdEndMap["4:6.0"]?.timingMs == 3_500)
        // Tap, tap, break tap, hold end, the slide head and the slide's single path end complete.
        #expect(prepared.noteCompletionTimes.count == 6)
        #expect(prepared.breakCompletionTimes == [2_000])
        #expect(prepared.breakNoExCompletionTimes == [2_000])
        // Three tap heads (including the break tap), one hold head and one slide head.
        #expect(prepared.layeredHeads.count == 5)
        #expect(prepared.minHiSpeed == 1)
    }

    // MARK: - Scene graph

    @Test
    func sceneGraphKeepsAFloorAfterTheFinalNote() {
        let shortChart = Chart(bpm: 120, measures: 1, notes: [makeNote(kind: .tap, timingMs: 500)])
        let scene = ChartSceneGraph(chart: shortChart)
        // measures × 4 beats at 120 BPM = 2 s, plus 500 ms; the 1.5 s floor is not reached.
        #expect(abs(scene.playbackDurationMs - 2_500) < 0.001)

        let longChart = Chart(bpm: 120, measures: 1, notes: [makeNote(kind: .tap, timingMs: 9_000)])
        #expect(ChartSceneGraph(chart: longChart).playbackDurationMs == 10_500)
    }

    // MARK: - Asset ids

    @Test
    func assetIdResolverPrefersTheChartTypeEntry() {
        #expect(ChartAssetIdResolver.resolve(providerSongId: 8, chartType: "dx") == 10_008)
        #expect(ChartAssetIdResolver.resolve(providerSongId: 10_008, chartType: "std") == 10_008)
        #expect(ChartAssetIdResolver.resolve(providerSongId: 0, chartType: "dx") == nil)
        #expect(ChartAssetIdResolver.candidates(providerSongId: 8, chartType: "std") == [8, 10_008])
        #expect(ChartAssetIdResolver.candidates(providerSongId: 10_008, chartType: "dx") == [10_008, 8])
        #expect(ChartAssetIdResolver.candidates(providerSongId: 100_123, chartType: "utage")
            == [100_123, 123, 10_123])
    }

    // MARK: - Export package

    @Test
    func zipArchiveUsesStoreEntriesAndAValidCentralDirectory() throws {
        let entries: [(name: String, data: Data)] = [
            ("maidata.txt", Data("&title=Test".utf8)),
            ("bg.png", Data(repeating: 0xAB, count: 32)),
        ]
        let archive = ChartZipArchive.archive(entries: entries, date: Date(timeIntervalSince1970: 1_700_000_000))
        let bytes = [UInt8](archive)
        // Local file header signature.
        #expect(Array(bytes.prefix(4)) == [0x50, 0x4B, 0x03, 0x04])
        // End of central directory signature.
        #expect(Array(bytes.suffix(from: bytes.count - 22).prefix(4)) == [0x50, 0x4B, 0x05, 0x06])
        // Both entry names are present verbatim.
        let text = String(decoding: bytes, as: UTF8.self)
        #expect(text.contains("maidata.txt"))
        #expect(text.contains("bg.png"))
        // The payload survives after the 30-byte local header + name length.
        let payloadOffset = 30 + "maidata.txt".utf8.count
        #expect(Array(bytes[payloadOffset..<(payloadOffset + 11)]) == Array("&title=Test".utf8))
    }

    // MARK: - Export

    @Test
    func exportPackageContainsMaidataTrackAndCover() throws {
        // A 2x2 opaque PNG stands in for the cover; the exporter must re-encode it as `bg.png`.
        let cover = try #require(makeSolidPNG())
        let audioURL = FileManager.default.temporaryDirectory.appending(path: "chart-export-test.mp3")
        try Data([0xFF, 0xFB, 0x90, 0x00, 0x01, 0x02]).write(to: audioURL)
        defer { try? FileManager.default.removeItem(at: audioURL) }

        let url = try ChartZipExporter.export(
            maidata: "&title=Export Test",
            title: "Export / Test",
            difficulty: "master",
            audioURL: audioURL,
            coverData: cover,
            noteDesigner: "Tester"
        )
        defer { try? FileManager.default.removeItem(at: url) }

        #expect(url.lastPathComponent == "Export _ Test - master.zip")
        let archive = try Data(contentsOf: url)
        let text = String(decoding: [UInt8](archive), as: UTF8.self)
        #expect(text.contains("maidata.txt"))
        #expect(text.contains("track.mp3"))
        #expect(text.contains("bg.png"))
        #expect(!text.contains("Export / Test"), "file names must be sanitized")
    }

    private func makeSolidPNG() -> Data? {
        let width = 2
        let height = 2
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        for index in stride(from: 0, to: pixels.count, by: 4) {
            pixels[index] = 0x20
            pixels[index + 1] = 0x80
            pixels[index + 2] = 0xFF
            pixels[index + 3] = 0xFF
        }
        guard let provider = CGDataProvider(data: Data(pixels) as CFData),
              let image = CGImage(
                width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
              )
        else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data, "public.png" as CFString, 1, nil
        ) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    // MARK: - Helpers

    /// Minimal note used by the synthetic charts above.
    private func makeNote(kind: ChartNoteKind, timingMs: Double) -> ChartNote {
        var note = ChartNote(
            kind: kind,
            timing: timingMs / 500.0,
            timingMs: timingMs,
            measure: Int(timingMs / 2_000),
            positionInMeasure: 0,
            scale: 1,
            bpm: 120,
            hasDelayMarker: false
        )
        note.hiSpeed = nil
        return note
    }
}
